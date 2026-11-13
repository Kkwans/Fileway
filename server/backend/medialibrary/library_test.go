package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func createLibrary(t *testing.T, store *Store, owner uint, name string) *Library {
	t.Helper()
	library, err := store.CreateLibrary(t.Context(), owner, name)
	if err != nil {
		t.Fatal(err)
	}
	return library
}

func TestLibraryIsPrivateUntilExplicitGrantAndEditorCannotManage(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "  家庭电影  ")
	if library.Name != "家庭电影" || library.OwnerUserID != 7 || library.Role != RoleOwner {
		t.Fatal(library)
	}
	if _, err := store.GetLibrary(ctx, 8, library.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if page, err := store.ListLibraries(ctx, 8, LibraryListOptions{}); err != nil || len(page.Items) != 0 {
		t.Fatal(page, err)
	}
	grant, err := store.GrantMember(ctx, 7, library.ID, 1, 8, RoleEditor)
	if err != nil || grant.LibraryRevision != 2 || grant.Revision != 1 {
		t.Fatal(grant, err)
	}
	if visible, err := store.GetLibrary(ctx, 8, library.ID); err != nil || visible.Role != RoleEditor {
		t.Fatal(visible, err)
	}
	if page, err := store.ListLibraries(ctx, 8, LibraryListOptions{}); err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	for name, action := range map[string]func() error{
		"rename":  func() error { _, err := store.UpdateLibrary(ctx, 8, library.ID, "changed", 2); return err },
		"archive": func() error { _, err := store.ArchiveLibrary(ctx, 8, library.ID, 2); return err },
		"restore": func() error { _, err := store.RestoreLibrary(ctx, 8, library.ID, 2); return err },
		"grant":   func() error { _, err := store.GrantMember(ctx, 8, library.ID, 2, 9, RoleViewer); return err },
		"revoke":  func() error { _, err := store.RevokeMember(ctx, 8, library.ID, 2, 9); return err },
	} {
		if err := action(); !errors.Is(err, ErrForbidden) {
			t.Fatalf("%s: %v", name, err)
		}
	}
	if _, err := store.UpdateLibrary(ctx, 9, library.ID, "hidden", 2); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if unchanged, err := store.GetLibrary(ctx, 7, library.ID); err != nil || unchanged.Revision != 2 {
		t.Fatal(unchanged, err)
	}
}

func TestLibraryRevisionConflictsProtectNameAndGrants(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Original")
	updated, err := store.UpdateLibrary(ctx, 7, library.ID, "Renamed", 1)
	if err != nil || updated.Revision != 2 {
		t.Fatal(updated, err)
	}
	for _, action := range []func() error{
		func() error { _, err := store.UpdateLibrary(ctx, 7, library.ID, "Stale", 1); return err },
		func() error { _, err := store.GrantMember(ctx, 7, library.ID, 1, 8, RoleViewer); return err },
		func() error { _, err := store.ArchiveLibrary(ctx, 7, library.ID, 1); return err },
	} {
		if err := action(); !errors.Is(err, ErrRevisionConflict) {
			t.Fatal(err)
		}
	}
	grant, err := store.GrantMember(ctx, 7, library.ID, 2, 8, RoleViewer)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.RevokeMember(ctx, 7, library.ID, 2, 8); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	revoked, err := store.RevokeMember(ctx, 7, library.ID, grant.LibraryRevision, 8)
	if err != nil || revoked.RevokedAt == nil || revoked.Revision != 2 {
		t.Fatal(revoked, err)
	}
	if _, err := store.GetLibrary(ctx, 8, library.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	regranted, err := store.GrantMember(ctx, 7, library.ID, revoked.LibraryRevision, 8, RoleEditor)
	if err != nil || regranted.Revision != 3 || regranted.RevokedAt != nil || regranted.CreatedAt != grant.CreatedAt {
		t.Fatal(regranted, err)
	}
	if _, err := store.GrantMember(ctx, 7, library.ID, regranted.LibraryRevision, 7, RoleViewer); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	unchanged, err := store.GrantMember(ctx, 7, library.ID, regranted.LibraryRevision, 8, RoleEditor)
	if err != nil || unchanged.LibraryRevision != regranted.LibraryRevision {
		t.Fatal(unchanged, err)
	}
	var events int
	if err := store.db.QueryRow("SELECT count(*) FROM outbox WHERE library_id = ?", library.ID).Scan(&events); err != nil || events != 5 {
		t.Fatal(events, err)
	}
}

func TestArchiveRetainsCatalogAndOwnerCanRestoreWithoutRevivingRevokedMember(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Library")
	if _, err := store.GrantMember(ctx, 7, library.ID, 1, 8, RoleViewer); err != nil {
		t.Fatal(err)
	}
	if _, err := store.GrantMember(ctx, 7, library.ID, 2, 9, RoleViewer); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO library_item(id, library_id, kind, title, created_at) VALUES ('saved', ?, 'movie', 'Keep', 1)`, library.ID); err != nil {
		t.Fatal(err)
	}
	archived, err := store.ArchiveLibrary(ctx, 7, library.ID, 3)
	if err != nil || archived.ArchivedAt == nil || archived.Revision != 4 {
		t.Fatal(archived, err)
	}
	if _, err := store.GetLibrary(ctx, 8, library.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.GetLibrary(ctx, 7, library.ID); err != nil {
		t.Fatal(err)
	}
	if page, err := store.ListLibraries(ctx, 7, LibraryListOptions{}); err != nil || len(page.Items) != 0 {
		t.Fatal(page, err)
	}
	if page, err := store.ListLibraries(ctx, 7, LibraryListOptions{Archived: true}); err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	if page, err := store.ListLibraries(ctx, 8, LibraryListOptions{Archived: true}); err != nil || len(page.Items) != 0 {
		t.Fatal(page, err)
	}
	if _, err := store.UpdateLibrary(ctx, 7, library.ID, "Locked", 4); !errors.Is(err, ErrArchived) {
		t.Fatal(err)
	}
	if _, err := store.GrantMember(ctx, 7, library.ID, 4, 10, RoleViewer); !errors.Is(err, ErrArchived) {
		t.Fatal(err)
	}
	if _, err := store.RevokeMember(ctx, 7, library.ID, 4, 9); err != nil {
		t.Fatal(err)
	}
	restored, err := store.RestoreLibrary(ctx, 7, library.ID, 5)
	if err != nil || restored.ArchivedAt != nil || restored.Revision != 6 {
		t.Fatal(restored, err)
	}
	if _, err := store.GetLibrary(ctx, 8, library.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.GetLibrary(ctx, 9, library.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	var count int
	if err := store.db.QueryRow("SELECT count(*) FROM library_item WHERE library_id = ?", library.ID).Scan(&count); err != nil || count != 1 {
		t.Fatal(count, err)
	}
}

func TestLibraryPaginationFiltersBeforeLimitingAndReadsDoNotTakeWriterLock(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	first := createLibrary(t, store, 7, "One")
	second := createLibrary(t, store, 7, "Two")
	_ = createLibrary(t, store, 8, "Hidden")
	page, err := store.ListLibraries(ctx, 7, LibraryListOptions{Limit: 1})
	if err != nil || len(page.Items) != 1 || page.NextCursor == "" {
		t.Fatal(page, err)
	}
	last, err := store.ListLibraries(ctx, 7, LibraryListOptions{Limit: 1, AfterID: page.NextCursor})
	if err != nil || len(last.Items) != 1 || last.NextCursor != "" || last.Items[0].ID == page.Items[0].ID {
		t.Fatal(last, err)
	}
	// Keep a writer active. WAL readers must see the committed snapshot promptly.
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "UPDATE library SET name = 'Uncommitted' WHERE id = ?", first.ID); err != nil {
			return err
		}
		readCtx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
		defer cancel()
		visible, err := store.GetLibrary(readCtx, 7, first.ID)
		if err != nil {
			return err
		}
		if visible.Name != "One" {
			return fmt.Errorf("reader saw uncommitted name: %s", visible.Name)
		}
		page, err := store.ListLibraries(readCtx, 7, LibraryListOptions{})
		if err != nil {
			return err
		}
		if len(page.Items) != 2 {
			return fmt.Errorf("page has %d rows, want 2 including %s", len(page.Items), second.ID)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestOutboxFailureRollsBackLibraryAndMembershipMutations(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Original")
	if _, err := store.GrantMember(ctx, 7, library.ID, 1, 8, RoleViewer); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected outbox failure'); END`); err != nil {
		t.Fatal(err)
	}
	if result, err := store.CreateLibrary(ctx, 7, "Must roll back"); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.UpdateLibrary(ctx, 7, library.ID, "Must roll back", 2); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.GrantMember(ctx, 7, library.ID, 2, 9, RoleViewer); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.ArchiveLibrary(ctx, 7, library.ID, 2); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.RevokeMember(ctx, 7, library.ID, 2, 8); err == nil || result != nil {
		t.Fatal(result, err)
	}
	kept, err := store.GetLibrary(ctx, 7, library.ID)
	if err != nil || kept.Name != "Original" || kept.Revision != 2 || kept.ArchivedAt != nil {
		t.Fatal(kept, err)
	}
	if _, err := store.GetLibrary(ctx, 9, library.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.GetLibrary(ctx, 8, library.ID); err != nil {
		t.Fatal(err)
	}
	page, err := store.ListLibraries(ctx, 7, LibraryListOptions{})
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
}

func TestConcurrentLibraryUpdatesHaveOneRevisionWinner(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Original")
	start := make(chan struct{})
	results := make(chan error, 2)
	for _, name := range []string{"One", "Two"} {
		go func() {
			<-start
			_, err := store.UpdateLibrary(t.Context(), 7, library.ID, name, 1)
			results <- err
		}()
	}
	close(start)
	var successes, conflicts int
	for range 2 {
		err := <-results
		switch {
		case err == nil:
			successes++
		case errors.Is(err, ErrRevisionConflict):
			conflicts++
		default:
			t.Fatal(err)
		}
	}
	if successes != 1 || conflicts != 1 {
		t.Fatal(successes, conflicts)
	}
	var count int
	if err := store.db.QueryRow("SELECT count(*) FROM outbox WHERE library_id = ?", library.ID).Scan(&count); err != nil || count != 2 {
		t.Fatal(count, err)
	}
}

func TestVersionOneCatalogUpgradePreservesRecordsAndGrants(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v1.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	legacy := &Store{db: db}
	err = legacy.WithTx(t.Context(), func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(t.Context(), schemaV1); err != nil {
			return err
		}
		if err := addLibrary(t.Context(), tx, "old"); err != nil {
			return err
		}
		if _, err := tx.ExecContext(t.Context(), `INSERT INTO library_member(library_id, user_id, role, revision, created_at) VALUES ('old', 8, 'viewer', 3, 1)`); err != nil {
			return err
		}
		if _, err := tx.ExecContext(t.Context(), `INSERT INTO outbox(event_id, library_id, kind, payload_json, created_at) VALUES ('old-event', 'old', 'library.created', '{}', 1)`); err != nil {
			return err
		}
		_, err := tx.ExecContext(t.Context(), fmt.Sprintf("PRAGMA application_id = %d; PRAGMA user_version = 1", applicationID))
		return err
	})
	closeErr := legacy.Close()
	if err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	old, err := store.GetLibrary(t.Context(), 8, "old")
	if err != nil || old.Name != "Films" || old.Revision != 1 || old.ArchivedAt != nil {
		t.Fatal(old, err)
	}
	grant, err := store.RevokeMember(t.Context(), 7, "old", 1, 8)
	if err != nil || grant.Revision != 4 {
		t.Fatal(grant, err)
	}
	var count int
	if err := store.db.QueryRow("SELECT count(*) FROM outbox WHERE event_id = 'old-event'").Scan(&count); err != nil || count != 1 {
		t.Fatal(count, err)
	}
	version, err := inspectSchema(t.Context(), store.db)
	if err != nil || version != SchemaVersion {
		t.Fatal(version, err)
	}
}
