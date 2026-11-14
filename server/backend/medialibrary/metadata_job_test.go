package medialibrary

import (
	"errors"
	"path/filepath"
	"testing"
)

func TestWatchSchemaUpgradeKeepsExistingUserState(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "Films")
	item := catalogItem(t, store, 7, library.ID, "Fixture")
	watched := false
	if _, err := store.SetUserState(ctx, 7, library.ID, item.ID, 0, "legacy-unwatched", UserStatePatch{ManualWatched: &watched}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.ExecContext(ctx, `DROP TABLE user_watch_progress; PRAGMA user_version=15`); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	state, err := reopened.GetUserState(ctx, 7, library.ID, item.ID)
	if err != nil || state.ManualWatched == nil || *state.ManualWatched || state.WatchResetRevision != 1 || tableCount(t, reopened, "user_watch_progress") != 0 {
		t.Fatalf("watch upgrade lost explicit state: %+v %v", state, err)
	}
}

func TestMetadataJobResumesAfterDatabaseReopen(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	connection := fixtureProvider(t, store, 7, false, "user", "metadata-reopen-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "metadata-reopen-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID}); err != nil {
		t.Fatal(err)
	}
	scan, lease := testScan(t, store, binding, "metadata-reopen-scan")
	for i, name := range []string{"one", "two"} {
		input := testObservation(name, "/videos/"+name+".mkv")
		if _, err := store.CommitScanObservation(ctx, 7, *lease, input, testCheckpoint(input, int64(i+1), int64(i+1))); err != nil {
			t.Fatal(err)
		}
	}
	finishEmptyReconcile(t, store, 7, *lease, 2)
	initial := testCoordinator(t, store)
	job, err := initial.ClaimNextMetadataJob(ctx)
	if err != nil || job.ScanID != scan.ID {
		t.Fatal(job, err)
	}
	first, err := initial.NextMetadataJobTarget(ctx, job)
	if err != nil {
		t.Fatal(err)
	}
	if err := initial.ResolveMetadataJobTarget(ctx, job, first.ItemID, "needs_review", "typed_identifier_required"); err != nil {
		t.Fatal(err)
	}
	if err := initial.Close(); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	coordinator := testCoordinator(t, reopened)
	if err := coordinator.RecoverMetadataJobs(ctx); err != nil {
		t.Fatal(err)
	}
	resumed, err := coordinator.ClaimNextMetadataJob(ctx)
	if err != nil || resumed.ID != job.ID || resumed.Generation <= job.Generation {
		t.Fatal(resumed, err)
	}
	next, err := coordinator.NextMetadataJobTarget(ctx, resumed)
	if err != nil || next.ItemID == first.ItemID {
		t.Fatal(next, err)
	}
	if err := coordinator.ResolveMetadataJobTarget(ctx, job, next.ItemID, "no_match", "identifier_not_found"); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("old generation acknowledged a target: %v", err)
	}
	if err := coordinator.CancelMetadataJob(ctx, 7, library.ID, resumed.ID, resumed.Generation); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.ResolveMetadataJobTarget(ctx, resumed, next.ItemID, "no_match", "identifier_not_found"); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("canceled generation acknowledged a target: %v", err)
	}
	progress, err := reopened.MetadataJobForScan(ctx, 7, library.ID, scan.ID)
	if err != nil || progress.Status != "canceled" || progress.Review != 1 || progress.Pending != 1 {
		t.Fatalf("retained progress: %+v, %v", progress, err)
	}
	if err := coordinator.RetryMetadataJob(ctx, 7, progress, progress.Generation); err != nil {
		t.Fatal(err)
	}
	retried, err := coordinator.ClaimNextMetadataJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	pending, err := coordinator.NextMetadataJobTarget(ctx, retried)
	if err != nil || pending.ItemID != next.ItemID {
		t.Fatal(pending, err)
	}
}
