package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func createBoundSource(t *testing.T, store *Store, library *Library) *LibrarySource {
	t.Helper()
	binding, err := store.CreateLocalSource(t.Context(), library.OwnerUserID, library.ID, library.Revision, "test-configuration", []byte("/videos"))
	if err != nil {
		t.Fatal(err)
	}
	return testAttestBinding(t, store, binding)
}

func testAttestBinding(t *testing.T, store *Store, binding *LibrarySource) *LibrarySource {
	t.Helper()
	proof := BindingProof{Version: 1, MappingDigest: bytes.Repeat([]byte{42}, 32), RootIdentityKind: "native", RootIdentity: []byte(binding.ID)}
	if _, err := store.AttestSourceBindingWithOperation(t.Context(), binding.Source.OwnerUserID, binding.ID, binding.Revision, "test-attest-"+binding.ID, proof); err != nil {
		t.Fatal(err)
	}
	current, err := store.GetLibrarySourceByID(t.Context(), binding.Source.OwnerUserID, binding.ID)
	if err != nil {
		t.Fatal(err)
	}
	return current
}

func observeFile(t *testing.T, store *Store, binding *LibrarySource, key string) *SourceFile {
	t.Helper()
	file, err := store.ObserveSourceFile(t.Context(), binding.Source.OwnerUserID, binding.Ref(), FileObservation{ObjectKey: []byte(key), Locator: []byte("/videos/" + key), SourceVersion: "version-1"})
	if err != nil {
		t.Fatal(err)
	}
	return file
}

func catalogItem(t *testing.T, store *Store, actor uint, libraryID, title string) *CatalogItem {
	t.Helper()
	item, err := store.CreateCatalogItem(t.Context(), actor, libraryID, NewCatalogItem{Kind: "movie", Title: title})
	if err != nil {
		t.Fatal(err)
	}
	return item
}

func TestSourceOwnershipAndCatalogPermissionsAreSeparate(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	file := observeFile(t, store, binding, "one.mkv")
	if _, err := store.GrantMember(ctx, 7, library.ID, binding.LibraryRevision, 8, RoleEditor); err != nil {
		t.Fatal(err)
	}
	if _, err := store.GrantMember(ctx, 7, library.ID, binding.LibraryRevision+1, 9, RoleViewer); err != nil {
		t.Fatal(err)
	}
	item := catalogItem(t, store, 8, library.ID, "A movie")
	link, err := store.AttachCatalogFile(ctx, 8, library.ID, item.ID, item.Revision, file.ID)
	if err != nil || link.ItemRevision != 2 {
		t.Fatal(link, err)
	}
	if _, err := store.CreateLocalSource(ctx, 8, library.ID, binding.LibraryRevision+2, "editor-config", []byte("/private")); !errors.Is(err, ErrForbidden) {
		t.Fatal(err)
	}
	if _, err := store.ObserveSourceFile(ctx, 8, binding.Ref(), FileObservation{ObjectKey: []byte("evil"), Locator: []byte("/private"), SourceVersion: "1"}); !errors.Is(err, ErrForbidden) {
		t.Fatal(err)
	}
	if _, err := store.GetLibrarySource(ctx, 8, library.ID, binding.Source.ID, binding.RootLocator); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.GetSourceFile(ctx, 9, binding.Source.ID, file.ObjectKey); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.CreateCatalogItem(ctx, 9, library.ID, NewCatalogItem{Kind: "movie", Title: "Viewer cannot edit"}); !errors.Is(err, ErrForbidden) {
		t.Fatal(err)
	}
	visible, err := store.GetCatalogItem(ctx, 9, library.ID, item.ID)
	if err != nil || visible.FileCount != 1 || visible.Title != "A movie" {
		t.Fatal(visible, err)
	}
	if _, err := store.GetCatalogItem(ctx, 10, library.ID, item.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.ListCatalogItems(ctx, 10, library.ID, CatalogListOptions{}); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	// Another user's source never becomes bindable merely because the actor
	// owns a library and can edit the shared library containing that source.
	other := createLibrary(t, store, 8, "Own")
	if _, err := store.BindLocalSource(ctx, 8, other.ID, 1, binding.Source.ID, binding.RootLocator); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestFileObservationKeepsOpaqueIdentityAndRejectsStaleUpdates(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	ctx := t.Context()
	key := []byte{'o', 0xff, 0xfe}
	locator := []byte{'/', 'v', '/', 0xfd, '.', 'm', 'k', 'v'}
	input := FileObservation{ObjectKey: key, Locator: locator, SourceVersion: "v1:10:20"}
	first, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), input)
	if err != nil {
		t.Fatal(err)
	}
	input.ExpectedRevision = first.Revision
	same, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), input)
	if err != nil || same.ID != first.ID || same.Revision != first.Revision {
		t.Fatal(same, err)
	}
	input.Locator = []byte{'/', 'v', '/', 0xfc, '.', 'm', 'k', 'v'}
	input.SourceVersion = "v1:10:21"
	changed, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), input)
	if err != nil || changed.ID != first.ID || changed.Revision != 2 || !bytes.Equal(changed.ObjectKey, key) {
		t.Fatal(changed, err)
	}
	if _, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), input); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	loaded, err := store.GetSourceFile(ctx, 7, binding.Source.ID, key)
	if err != nil || !bytes.Equal(loaded.Locator, input.Locator) || loaded.SourceVersion != input.SourceVersion {
		t.Fatal(loaded, err)
	}
	// Equal source version tokens do not equate distinct stable objects.
	other, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: []byte("other"), Locator: locator, SourceVersion: input.SourceVersion})
	if err != nil || other.ID == first.ID {
		t.Fatal(other, err)
	}
	ref := binding.Ref()
	ref.RootLocator = []byte("/not-bound")
	if _, err := store.ObserveSourceFile(ctx, 7, ref, FileObservation{ObjectKey: []byte("new"), Locator: locator, SourceVersion: "1"}); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	ref = binding.Ref()
	ref.AuthorizationRevision++
	if _, err := store.ObserveSourceFile(ctx, 7, ref, FileObservation{ObjectKey: []byte("new"), Locator: locator, SourceVersion: "1"}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	ref = binding.Ref()
	ref.BindingRevision++
	if _, err := store.ObserveSourceFile(ctx, 7, ref, FileObservation{ObjectKey: []byte("new"), Locator: locator, SourceVersion: "1"}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
}

func TestFileAssociationsRequireObservationInTheExactLibrary(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	one := createLibrary(t, store, 7, "One")
	two := createLibrary(t, store, 7, "Two")
	binding := createBoundSource(t, store, one)
	file := observeFile(t, store, binding, "one.mkv")
	item := catalogItem(t, store, 7, two.ID, "Target")
	if _, err := store.AttachCatalogFile(ctx, 7, two.ID, item.ID, 1, file.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	secondBinding, err := store.BindLocalSource(ctx, 7, two.ID, 1, binding.Source.ID, binding.RootLocator)
	if err != nil {
		t.Fatal(err)
	}
	secondBinding = testAttestBinding(t, store, secondBinding)
	if _, err := store.AttachCatalogFile(ctx, 7, two.ID, item.ID, 1, file.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	observed, err := store.ObserveSourceFile(ctx, 7, secondBinding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: file.SourceVersion, ExpectedRevision: file.Revision})
	if err != nil || observed.ID != file.ID {
		t.Fatal(observed, err)
	}
	link, err := store.AttachCatalogFile(ctx, 7, two.ID, item.ID, 1, file.ID)
	if err != nil {
		t.Fatal(err)
	}
	again, err := store.AttachCatalogFile(ctx, 7, two.ID, item.ID, link.ItemRevision, file.ID)
	if err != nil || again.VariantID != link.VariantID || again.ItemRevision != link.ItemRevision {
		t.Fatal(again, err)
	}
	if _, err := store.AttachCatalogFile(ctx, 7, one.ID, item.ID, link.ItemRevision, file.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.CreateCatalogItem(ctx, 7, one.ID, NewCatalogItem{Kind: "episode", ParentID: item.ID, Title: "Wrong parent"}); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	before := map[string]int{}
	for _, libraryID := range []string{one.ID, two.ID} {
		var count int
		if err := store.db.QueryRow("SELECT count(*) FROM outbox WHERE library_id = ? AND kind = 'catalog.file.observed'", libraryID).Scan(&count); err != nil {
			t.Fatal(err)
		}
		before[libraryID] = count
	}
	if _, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: "replacement", ExpectedRevision: file.Revision}); err != nil {
		t.Fatal(err)
	}
	for libraryID, count := range before {
		var got int
		if err := store.db.QueryRow("SELECT count(*) FROM outbox WHERE library_id = ? AND kind = 'catalog.file.observed'", libraryID).Scan(&got); err != nil || got != count+1 {
			t.Fatal(got, count, err)
		}
	}
}

func TestCatalogPaginationFileFilterAndReadDuringWriter(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	file := observeFile(t, store, binding, "one.mkv")
	item := catalogItem(t, store, 7, library.ID, "One")
	_ = catalogItem(t, store, 7, library.ID, "Two")
	if _, err := store.AttachCatalogFile(ctx, 7, library.ID, item.ID, 1, file.ID); err != nil {
		t.Fatal(err)
	}
	page, err := store.ListCatalogItems(ctx, 7, library.ID, CatalogListOptions{Limit: 1})
	if err != nil || len(page.Items) != 1 || page.NextCursor == "" {
		t.Fatal(page, err)
	}
	last, err := store.ListCatalogItems(ctx, 7, library.ID, CatalogListOptions{Limit: 1, AfterID: page.NextCursor})
	if err != nil || len(last.Items) != 1 || last.NextCursor != "" || last.Items[0].ID == page.Items[0].ID {
		t.Fatal(last, err)
	}
	filtered, err := store.ListCatalogItems(ctx, 7, library.ID, CatalogListOptions{SourceFileID: file.ID})
	if err != nil || len(filtered.Items) != 1 || filtered.Items[0].ID != item.ID {
		t.Fatal(filtered, err)
	}
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "UPDATE library_item SET title = 'Not committed' WHERE id = ?", item.ID); err != nil {
			return err
		}
		readCtx, cancel := context.WithTimeout(ctx, time.Second)
		defer cancel()
		loaded, err := store.GetCatalogItem(readCtx, 7, library.ID, item.ID)
		if err != nil {
			return err
		}
		if loaded.Title != "One" {
			return fmt.Errorf("uncommitted title: %s", loaded.Title)
		}
		_, err = store.ListCatalogItems(readCtx, 7, library.ID, CatalogListOptions{})
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestArchivedLibraryRejectsSourceObservationsAndCatalogWrites(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	file := observeFile(t, store, binding, "one.mkv")
	item := catalogItem(t, store, 7, library.ID, "One")
	archived, err := store.ArchiveLibrary(ctx, 7, library.ID, binding.LibraryRevision)
	if err != nil {
		t.Fatal(err)
	}
	for name, action := range map[string]func() error{
		"create source": func() error {
			_, err := store.CreateLocalSource(ctx, 7, library.ID, archived.Revision, "other-config", []byte("/other"))
			return err
		},
		"bind": func() error {
			_, err := store.BindLocalSource(ctx, 7, library.ID, archived.Revision, binding.Source.ID, []byte("/other"))
			return err
		},
		"observe": func() error {
			_, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: "new", ExpectedRevision: file.Revision})
			return err
		},
		"create item": func() error {
			_, err := store.CreateCatalogItem(ctx, 7, library.ID, NewCatalogItem{Kind: "movie", Title: "new"})
			return err
		},
		"attach": func() error { _, err := store.AttachCatalogFile(ctx, 7, library.ID, item.ID, 1, file.ID); return err },
	} {
		if err := action(); !errors.Is(err, ErrArchived) {
			t.Fatalf("%s: %v", name, err)
		}
	}
	if _, err := store.GetCatalogItem(ctx, 7, library.ID, item.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestSourceAndCatalogOutboxFailuresDoNotPublishPartialState(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	file := observeFile(t, store, binding, "one.mkv")
	item := catalogItem(t, store, 7, library.ID, "One")
	if _, err := store.db.Exec(`CREATE TRIGGER fail_catalog_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'injected failure'); END`); err != nil {
		t.Fatal(err)
	}
	if result, err := store.CreateLocalSource(ctx, 7, library.ID, binding.LibraryRevision, "new-config", []byte("/new")); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: []byte("new"), Locator: []byte("/videos/new"), SourceVersion: "new"}); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: "replacement", ExpectedRevision: file.Revision}); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.CreateCatalogItem(ctx, 7, library.ID, NewCatalogItem{Kind: "movie", Title: "new"}); err == nil || result != nil {
		t.Fatal(result, err)
	}
	if result, err := store.AttachCatalogFile(ctx, 7, library.ID, item.ID, 1, file.ID); err == nil || result != nil {
		t.Fatal(result, err)
	}
	for table, want := range map[string]int{"source": 1, "library_source": 1, "source_file": 1, "library_file": 1, "library_item": 1, "edition": 0, "variant": 0} {
		var got int
		if err := store.db.QueryRow("SELECT count(*) FROM " + table).Scan(&got); err != nil || got != want {
			t.Fatalf("%s=%d want %d: %v", table, got, want, err)
		}
	}
	kept, err := store.GetSourceFile(ctx, 7, binding.Source.ID, file.ObjectKey)
	if err != nil || kept.SourceVersion != file.SourceVersion || kept.Revision != file.Revision {
		t.Fatal(kept, err)
	}
}

func TestVersionTwoMigrationPreservesSourceIdentityWithoutInventingObservation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v2.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(t.Context(), func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(t.Context(), schemaV1+schemaV2); err != nil {
			return err
		}
		if err := addLibrary(t.Context(), tx, "old"); err != nil {
			return err
		}
		if _, err := tx.ExecContext(t.Context(), `INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('disk',7,'local','config',1);
INSERT INTO source_file(id,source_id,object_key,locator,source_version,observed_at) VALUES ('file','disk',X'FF',X'2FFF','old-version',1)`); err != nil {
			return err
		}
		_, err := tx.ExecContext(t.Context(), fmt.Sprintf("PRAGMA application_id = %d; PRAGMA user_version = 2", applicationID))
		return err
	})
	closeErr := old.Close()
	if err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	file, err := store.GetSourceFile(t.Context(), 7, "disk", []byte{0xff})
	if err != nil || file.ID != "file" || file.Revision != 1 || !bytes.Equal(file.Locator, []byte{'/', 0xff}) {
		t.Fatal(file, err)
	}
	var observations int
	if err := store.db.QueryRow("SELECT count(*) FROM library_file").Scan(&observations); err != nil || observations != 0 {
		t.Fatal(observations, err)
	}
}
