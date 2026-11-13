package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	store, err := Open(t.Context(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	})
	return store
}

func addLibrary(ctx context.Context, tx *sql.Tx, id string) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO library(id, owner_user_id, name, created_at, updated_at) VALUES (?, 7, 'Films', 1, 1)`, id)
	return err
}

func TestOpenReopensCatalogAndEscapesFilesystemPath(t *testing.T) {
	// URI metacharacters are legal filename characters on both target systems.
	path := filepath.Join(t.TempDir(), "媒体 # 100% catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	err = store.WithTx(t.Context(), func(tx *sql.Tx) error { return addLibrary(t.Context(), tx, "private") })
	closeErr := store.Close()
	if err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err = Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	var owner, members, version int
	if err := store.db.QueryRow("SELECT owner_user_id FROM library WHERE id = 'private'").Scan(&owner); err != nil || owner != 7 {
		t.Fatal(owner, err)
	}
	if err := store.db.QueryRow("SELECT count(*) FROM library_member").Scan(&members); err != nil || members != 0 {
		t.Fatal(members, err)
	}
	if err := store.db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != SchemaVersion {
		t.Fatal(version, err)
	}
}

func TestFutureAndForeignCatalogsAreNotModified(t *testing.T) {
	for _, test := range []struct {
		name  string
		setup string
		want  error
	}{
		{"future", fmt.Sprintf("CREATE TABLE future_data (value TEXT); INSERT INTO future_data VALUES ('keep'); PRAGMA user_version = %d", SchemaVersion+1), ErrFutureSchema},
		{"unversioned foreign", "CREATE TABLE unrelated (value TEXT)", ErrForeignDatabase},
		{"foreign application", "PRAGMA application_id = 12345", ErrForeignDatabase},
	} {
		t.Run(test.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "existing.db")
			db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
			if err != nil {
				t.Fatal(err)
			}
			_, err = db.Exec(test.setup)
			closeErr := db.Close()
			if err != nil || closeErr != nil {
				t.Fatal(err, closeErr)
			}
			before, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			store, err := Open(t.Context(), path)
			if !errors.Is(err, test.want) {
				if store != nil {
					_ = store.Close()
				}
				t.Fatalf("Open = %v, want %v", err, test.want)
			}
			after, err := os.ReadFile(path)
			if err != nil || !bytes.Equal(before, after) {
				t.Fatalf("rejected database changed: %v", err)
			}
			if _, err := os.Stat(path + "-wal"); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("rejected database acquired WAL: %v", err)
			}
		})
	}
}

func TestConnectionsEnforceForeignKeysAndBoundedWALPolicy(t *testing.T) {
	store := openTestStore(t)
	// Hold every connection simultaneously so the check covers newly opened
	// connections, rather than repeatedly inspecting the first pooled handle.
	for range maxConnections {
		connection, err := store.db.Conn(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		defer func() {
			if err := connection.Close(); err != nil {
				t.Error(err)
			}
		}()
		var journal string
		if err := connection.QueryRowContext(t.Context(), "PRAGMA journal_mode").Scan(&journal); err != nil || journal != "wal" {
			t.Fatal(journal, err)
		}
		for pragma, want := range map[string]int{"foreign_keys": 1, "busy_timeout": busyTimeoutMS, "synchronous": 2} {
			var got int
			if err := connection.QueryRowContext(t.Context(), "PRAGMA "+pragma).Scan(&got); err != nil || got != want {
				t.Fatalf("%s = %d, want %d: %v", pragma, got, want, err)
			}
		}
		if _, err := connection.ExecContext(t.Context(), `INSERT INTO library_member(library_id, user_id, role, created_at) VALUES ('missing', 9, 'viewer', 1)`); err == nil {
			t.Fatal("orphan membership was accepted")
		}
	}
	if got := store.db.Stats().MaxOpenConnections; got != maxConnections {
		t.Fatalf("pool limit = %d", got)
	}
}

func TestCatalogCheckpointAndOutboxCommitOrRollbackTogether(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	sentinel := errors.New("interrupted before commit")
	write := func(tx *sql.Tx) error {
		if err := addLibrary(ctx, tx, "films"); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO job(id, library_id, owner_user_id, kind, status, checkpoint_json, created_at, updated_at) VALUES ('scan', 'films', 7, 'library.scan', 'running', '{"cursor":"page-2"}', 1, 1)`); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO outbox(event_id, library_id, kind, payload_json, created_at) VALUES ('event-1', 'films', 'library.changed', '{"revision":1}', 1)`)
		return err
	}
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		if err := write(tx); err != nil {
			return err
		}
		return sentinel
	})
	if !errors.Is(err, sentinel) {
		t.Fatal(err)
	}
	for _, table := range []string{"library", "job", "outbox"} {
		var count int
		if err := store.db.QueryRow("SELECT count(*) FROM " + table).Scan(&count); err != nil || count != 0 {
			t.Fatalf("rollback %s rows = %d: %v", table, count, err)
		}
	}
	if err := store.WithTx(ctx, write); err != nil {
		t.Fatal(err)
	}
	var checkpoint string
	if err := store.db.QueryRow("SELECT checkpoint_json FROM job WHERE id='scan'").Scan(&checkpoint); err != nil || checkpoint != `{"cursor":"page-2"}` {
		t.Fatal(checkpoint, err)
	}
	// A duplicate event must roll back the accompanying mutation, preventing a
	// producer retry from changing state without a new durable notification.
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "UPDATE library SET revision = 2 WHERE id = 'films'"); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO outbox(event_id, library_id, kind, payload_json, created_at) VALUES ('event-1', 'films', 'library.changed', '{}', 1)`)
		return err
	})
	if err == nil {
		t.Fatal("duplicate outbox event accepted")
	}
	var revision int
	if err := store.db.QueryRow("SELECT revision FROM library WHERE id = 'films'").Scan(&revision); err != nil || revision != 1 {
		t.Fatal(revision, err)
	}
}

func TestCatalogRejectsCrossLibraryReferencesAndKeepsOpaqueLocators(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	opaque := []byte{'/', 0xff, 0xfe, '.', 'm', 'k', 'v'}
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		for _, id := range []string{"one", "two"} {
			if err := addLibrary(ctx, tx, id); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO library_item(id, library_id, kind, title, created_at) VALUES ('movie', 'one', 'movie', 'Title', 1)`); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO source(id, owner_user_id, kind, configuration_ref, created_at) VALUES ('disk', 7, 'local', 'config-id', 1)`); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO source_file(id, source_id, object_key, locator, source_version, observed_at) VALUES ('file', 'disk', ?, ?, 'v1:100:200', 1)`, opaque, opaque)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{
		`INSERT INTO edition(id, library_id, item_id, name) VALUES ('wrong', 'two', 'movie', '')`,
		`INSERT INTO library_item(id, library_id, kind, parent_id, title, created_at) VALUES ('child', 'two', 'episode', 'movie', '', 1)`,
		`INSERT INTO metadata_revision(id, library_id, item_id, revision, origin, changes_json, created_at) VALUES ('change', 'two', 'movie', 1, 'manual', '{}', 1)`,
		`DELETE FROM library WHERE id = 'one'`,
	} {
		if _, err := store.db.ExecContext(ctx, statement); err == nil {
			t.Fatalf("invalid reference accepted: %s", statement)
		}
	}
	var locator []byte
	if err := store.db.QueryRow("SELECT locator FROM source_file WHERE id = 'file'").Scan(&locator); err != nil || !bytes.Equal(locator, opaque) {
		t.Fatal(locator, err)
	}
}

func TestMigrationFailureRollsBackDDLAndVersion(t *testing.T) {
	// Execute the actual v1 DDL then force a failure before version publication;
	// this models interruption of the transactional migration, without damage
	// injection against any user's catalog.
	db, err := sql.Open("sqlite", databaseURL(filepath.Join(t.TempDir(), "migration.db"), "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	}()
	store := &Store{db: db}
	err = store.WithTx(t.Context(), func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(t.Context(), schemaV1); err != nil {
			return err
		}
		_, err := tx.ExecContext(t.Context(), "INSERT INTO table_that_does_not_exist VALUES (1)")
		return err
	})
	if err == nil {
		t.Fatal("expected migration failure")
	}
	version, err := inspectSchema(t.Context(), db)
	if err != nil || version != 0 {
		t.Fatal(version, err)
	}
	if err := store.migrate(t.Context()); err != nil {
		t.Fatal(err)
	}
}

func TestCanceledAndPanickingTransactionsReleaseWriter(t *testing.T) {
	store := openTestStore(t)
	ctx, cancel := context.WithCancel(t.Context())
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		if err := addLibrary(ctx, tx, "canceled"); err != nil {
			return err
		}
		cancel()
		return nil
	})
	if err == nil {
		t.Fatal("canceled transaction committed")
	}
	func() {
		defer func() {
			if recovered := recover(); recovered != "stop" {
				t.Fatalf("panic = %v", recovered)
			}
		}()
		_ = store.WithTx(t.Context(), func(tx *sql.Tx) error {
			if err := addLibrary(t.Context(), tx, "panicked"); err != nil {
				t.Fatal(err)
			}
			panic("stop")
		})
	}()
	if err := store.WithTx(t.Context(), func(tx *sql.Tx) error {
		return addLibrary(t.Context(), tx, "survivor")
	}); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := store.db.QueryRow("SELECT count(*) FROM library").Scan(&count); err != nil || count != 1 {
		t.Fatal(count, err)
	}
}
