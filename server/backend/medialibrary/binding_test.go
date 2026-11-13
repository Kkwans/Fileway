package medialibrary

import (
	"bytes"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
)

func TestBindingIDsAreStableOwnerOnlyAndPaginated(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	first := createBoundSource(t, store, library)
	second, err := store.BindLocalSource(t.Context(), 7, library.ID, first.LibraryRevision, first.Source.ID, []byte("/videos/other"))
	if err != nil || first.ID == "" || second.ID == "" || first.ID == second.ID {
		t.Fatal(first, second, err)
	}
	loaded, err := store.GetLibrarySourceByID(t.Context(), 7, first.ID)
	if err != nil || loaded.ID != first.ID || !bytes.Equal(loaded.RootLocator, first.RootLocator) {
		t.Fatal(loaded, err)
	}
	if _, err := store.GetLibrarySourceByID(t.Context(), 8, first.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.ListLibrarySources(t.Context(), 8, library.ID, "", 10); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	page, err := store.ListLibrarySources(t.Context(), 7, library.ID, "", 1)
	if err != nil || len(page.Items) != 1 || page.NextCursor == "" {
		t.Fatal(page, err)
	}
	last, err := store.ListLibrarySources(t.Context(), 7, library.ID, page.NextCursor, 1)
	if err != nil || len(last.Items) != 1 || last.Items[0].ID == page.Items[0].ID || last.NextCursor != "" {
		t.Fatal(last, err)
	}
	if _, err := store.db.Exec("UPDATE library_source SET binding_id = 'different' WHERE binding_id = ?", first.ID); err == nil {
		t.Fatal("mutable binding ID accepted")
	}
	if _, err := store.db.Exec("INSERT INTO library_source(library_id,source_id,root_locator) VALUES (?, ?, ?)", library.ID, first.Source.ID, []byte("/invalid")); err == nil {
		t.Fatal("binding without ID accepted")
	}
}

func TestVersionFourMigrationBackfillsStableOpaqueBindingIDs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v4.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(t.Context(), func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(t.Context(), schemaV1+schemaV2+schemaV3+schemaV4); err != nil {
			return err
		}
		if err := addLibrary(t.Context(), tx, "old"); err != nil {
			return err
		}
		if _, err := tx.ExecContext(t.Context(), `INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('disk',7,'local','config',1);
INSERT INTO library_source(library_id,source_id,root_locator) VALUES ('old','disk',X'2FFF'), ('old','disk',X'2FFE')`); err != nil {
			return err
		}
		_, err := tx.ExecContext(t.Context(), fmt.Sprintf("PRAGMA application_id = %d; PRAGMA user_version = 4", applicationID))
		return err
	})
	if closeErr := old.Close(); err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	page, err := store.ListLibrarySources(t.Context(), 7, "old", "", 10)
	if err != nil || len(page.Items) != 2 || page.Items[0].ID == "" || page.Items[0].ID == page.Items[1].ID {
		t.Fatal(page, err)
	}
	firstID, firstRoot := page.Items[0].ID, bytes.Clone(page.Items[0].RootLocator)
	if err := store.Close(); err != nil {
		t.Fatal(err)
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
	first, err := store.GetLibrarySourceByID(t.Context(), 7, firstID)
	if err != nil || !bytes.Equal(first.RootLocator, firstRoot) {
		t.Fatal(first, err)
	}
	var violations int
	if err := store.db.QueryRow("SELECT count(*) FROM pragma_foreign_key_check").Scan(&violations); err != nil || violations != 0 {
		t.Fatal(violations, err)
	}
}
