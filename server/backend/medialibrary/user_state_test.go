package medialibrary

import (
	"errors"
	"path/filepath"
	"sync"
	"testing"
)

func boolPointer(value bool) *bool { return &value }

func TestUserStateIsolationReceiptsAndResetFence(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "private")
	item := catalogItem(t, store, 7, library.ID, "film")
	grant, err := store.GrantMember(ctx, 7, library.ID, library.Revision, 8, RoleViewer)
	if err != nil {
		t.Fatal(err)
	}
	initial, err := store.GetUserState(ctx, 8, library.ID, item.ID)
	if err != nil || initial.Revision != 0 || initial.Favorite || initial.ManualWatched != nil || initial.WatchResetRevision != 0 {
		t.Fatalf("initial state: %+v %v", initial, err)
	}
	if _, err = store.GetUserState(ctx, 9, library.ID, item.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("outsider read", err)
	}
	first, err := store.SetUserState(ctx, 8, library.ID, item.ID, 0, "favorite-1", UserStatePatch{Favorite: boolPointer(true)})
	if err != nil || first.Revision != 1 || !first.Favorite || first.ManualWatched != nil {
		t.Fatalf("favorite: %+v %v", first, err)
	}
	owner, err := store.GetUserState(ctx, 7, library.ID, item.ID)
	if err != nil || owner.Favorite || owner.Revision != 0 {
		t.Fatalf("owner state leaked: %+v %v", owner, err)
	}
	if _, err = store.SetUserState(ctx, 8, library.ID, item.ID, 0, "stale", UserStatePatch{ManualWatched: boolPointer(true)}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("stale revision", err)
	}
	watched, err := store.SetUserState(ctx, 8, library.ID, item.ID, 1, "watched", UserStatePatch{ManualWatched: boolPointer(true)})
	if err != nil || watched.Revision != 2 || watched.ManualWatched == nil || !*watched.ManualWatched || watched.WatchResetRevision != 0 {
		t.Fatalf("watched: %+v %v", watched, err)
	}
	reset, err := store.SetUserState(ctx, 8, library.ID, item.ID, 2, "reset-1", UserStatePatch{ManualWatched: boolPointer(false)})
	if err != nil || reset.Revision != 3 || reset.ManualWatched == nil || *reset.ManualWatched || reset.WatchResetRevision != 1 || !reset.Favorite {
		t.Fatalf("reset: %+v %v", reset, err)
	}
	replay, err := store.SetUserState(ctx, 8, library.ID, item.ID, 2, "reset-1", UserStatePatch{ManualWatched: boolPointer(false)})
	if err != nil || replay.Revision != 3 || replay.WatchResetRevision != 1 {
		t.Fatalf("replay: %+v %v", replay, err)
	}
	if _, err = store.SetUserState(ctx, 8, library.ID, item.ID, 2, "reset-1", UserStatePatch{ManualWatched: boolPointer(true)}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("operation mismatch", err)
	}
	resetAgain, err := store.SetUserState(ctx, 8, library.ID, item.ID, 3, "reset-2", UserStatePatch{ManualWatched: boolPointer(false)})
	if err != nil || resetAgain.Revision != 4 || resetAgain.WatchResetRevision != 2 {
		t.Fatalf("second reset: %+v %v", resetAgain, err)
	}
	revoked, err := store.RevokeMember(ctx, 7, library.ID, grant.LibraryRevision, 8)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.GetUserState(ctx, 8, library.ID, item.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("revoked read", err)
	}
	if _, err = store.SetUserState(ctx, 8, library.ID, item.ID, 2, "reset-1", UserStatePatch{ManualWatched: boolPointer(false)}); !errors.Is(err, ErrNotFound) {
		t.Fatal("revoked receipt replay", err)
	}
	if _, err = store.GrantMember(ctx, 7, library.ID, revoked.LibraryRevision, 8, RoleViewer); err != nil {
		t.Fatal(err)
	}
	restored, err := store.GetUserState(ctx, 8, library.ID, item.ID)
	if err != nil || restored.Revision != 4 || restored.WatchResetRevision != 2 {
		t.Fatalf("regrant lost state: %+v %v", restored, err)
	}
	current, err := store.GetLibrary(ctx, 7, library.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.ArchiveLibrary(ctx, 7, library.ID, current.Revision); err != nil {
		t.Fatal(err)
	}
	if _, err = store.GetUserState(ctx, 7, library.ID, item.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("archived read", err)
	}
}

func TestUserStateConcurrentRevisionConflict(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "private")
	item := catalogItem(t, store, 7, library.ID, "film")
	results := make(chan error, 2)
	var workers sync.WaitGroup
	for _, operation := range []string{"one", "two"} {
		workers.Add(1)
		go func(id string) {
			defer workers.Done()
			_, err := store.SetUserState(t.Context(), 7, library.ID, item.ID, 0, id, UserStatePatch{Favorite: boolPointer(true)})
			results <- err
		}(operation)
	}
	workers.Wait()
	close(results)
	var ok, conflicts int
	for err := range results {
		if err == nil {
			ok++
		} else if errors.Is(err, ErrRevisionConflict) {
			conflicts++
		} else {
			t.Fatal(err)
		}
	}
	if ok != 1 || conflicts != 1 {
		t.Fatalf("success=%d conflicts=%d", ok, conflicts)
	}
}

func TestUserStateSchemaNineMigrationPreservesCatalog(t *testing.T) {
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "preserved")
	item := catalogItem(t, store, 7, library.ID, "film")
	if _, err = store.db.Exec(`DROP TABLE user_watch_progress; DROP TABLE provider_match_manual_review; DROP TABLE provider_match_revision; DROP INDEX library_file_scan_observation; DROP TABLE metadata_job_target; DROP TABLE metadata_job; DROP TABLE item_provider_match; DROP TABLE library_metadata_policy; DROP TABLE provider_grant; DROP TABLE provider_connection; DROP TABLE provider_credential; DROP TABLE user_item_state; PRAGMA user_version=9`); err != nil {
		t.Fatal(err)
	}
	if err = store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	state, err := store.GetUserState(t.Context(), 7, library.ID, item.ID)
	if err != nil || state.Revision != 0 || state.ManualWatched != nil {
		t.Fatalf("migrated state: %+v %v", state, err)
	}
	if _, err = store.GetCatalogItem(t.Context(), 7, library.ID, item.ID); err != nil {
		t.Fatal("catalog not preserved", err)
	}
}
