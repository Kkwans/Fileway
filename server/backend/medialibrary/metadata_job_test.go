package medialibrary

import (
	"bytes"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
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
	if _, err := store.db.ExecContext(ctx, `DROP TABLE manual_mapping_revision; DROP TABLE manual_mapping_preview; DROP TABLE user_watch_progress; PRAGMA user_version=15`); err != nil {
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

func TestMetadataDurationDecisionUsesKindThresholdsAndAbstainsOnUnknown(t *testing.T) {
	policy := (LibraryMetadataPolicy{Mode: MetadataLocalFirst}).withDefaults()
	cases := []struct {
		name, kind, state, reason string
		durations                 []int64
	}{
		{name: "movie below", kind: "movie", durations: []int64{59*60_000 + 59_999}, state: "skipped", reason: "movie_below_minimum_duration"},
		{name: "movie boundary", kind: "video", durations: []int64{60 * 60_000}, state: "allowed"},
		{name: "movie unknown", kind: "movie", durations: []int64{0}, state: "needs_review", reason: "duration_unknown"},
		{name: "movie partial evidence", kind: "movie", durations: []int64{30 * 60_000, 0}, state: "needs_review", reason: "duration_unknown"},
		{name: "movie eligible with unknown variant", kind: "movie", durations: []int64{120 * 60_000, 0}, state: "allowed"},
		{name: "series below", kind: "series", durations: []int64{9*60_000 + 59_999}, state: "skipped", reason: "series_below_minimum_duration"},
		{name: "series boundary", kind: "series", durations: []int64{10 * 60_000}, state: "allowed"},
		{name: "series one eligible episode", kind: "series", durations: []int64{5 * 60_000, 20 * 60_000}, state: "allowed"},
		{name: "series unknown", kind: "series", durations: []int64{0}, state: "needs_review", reason: "duration_unknown"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			state, reason := MetadataDurationDecision(test.kind, test.durations, policy)
			if state != test.state || reason != test.reason {
				t.Fatalf("decision = %q %q, want %q %q", state, reason, test.state, test.reason)
			}
		})
	}
	custom := policy
	custom.MovieMinMinutes, custom.SeriesMinMinutes = 90, 15
	if state, reason := MetadataDurationDecision("movie", []int64{89 * 60_000}, custom); state != "skipped" || reason != "movie_below_minimum_duration" {
		t.Fatalf("custom movie threshold: %q %q", state, reason)
	}
	if state, reason := MetadataDurationDecision("series", []int64{14 * 60_000}, custom); state != "skipped" || reason != "series_below_minimum_duration" {
		t.Fatalf("custom series threshold: %q %q", state, reason)
	}
}

func TestMetadataJobCountsDurationFilteredTargetsAsSkipped(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Duration skip")
	binding := createBoundSource(t, store, library)
	connection := fixtureProvider(t, store, 7, false, "user", "duration-skip-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "duration-skip-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID}); err != nil {
		t.Fatal(err)
	}
	scan, lease := testScan(t, store, binding, "duration-skip-scan")
	input := testObservation("duration-skip-file", "/videos/short.mkv")
	parsed := mediametadata.ParseName("short.mkv")
	input.ParsedName = &parsed
	input.DurationMs = 59 * 60_000
	if _, err := store.CommitScanObservation(ctx, 7, *lease, input, testCheckpoint(input, 1, 1)); err != nil {
		t.Fatal(err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 1)
	coordinator := testCoordinator(t, store)
	job, err := coordinator.ClaimNextMetadataJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	target, err := coordinator.NextMetadataJobTarget(ctx, job)
	if err != nil {
		t.Fatal(err)
	}
	if err := coordinator.ResolveMetadataJobTarget(ctx, job, target.ItemID, "skipped", "movie_below_minimum_duration"); err != nil {
		t.Fatal(err)
	}
	progress, err := store.MetadataJobForScan(ctx, 7, library.ID, scan.ID)
	if err != nil || progress.Skipped != 1 || progress.Pending != 0 || progress.Review != 0 {
		t.Fatalf("duration-filtered progress: %+v %v", progress, err)
	}
}

func TestMetadataJobTargetsIgnoreStaleBindingEpochs(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Metadata epochs")
	binding := createBoundSource(t, store, library)
	connection := fixtureProvider(t, store, 7, false, "user", "metadata-epoch-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "metadata-epoch-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID}); err != nil {
		t.Fatal(err)
	}
	_, oldLease := testScan(t, store, binding, "metadata-old-epoch")
	oldInput := testObservation("metadata-old-file", "/videos/old.mkv")
	old, err := store.CommitScanObservation(ctx, 7, *oldLease, oldInput, testCheckpoint(oldInput, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	finishEmptyReconcile(t, store, 7, *oldLease, 1)
	proof := BindingProof{Version: 1, MappingDigest: bytes.Repeat([]byte{44}, 32), RootIdentityKind: "native", RootIdentity: []byte(binding.ID)}
	if _, err := store.AttestSourceBindingWithOperation(ctx, 7, binding.ID, binding.Revision, "metadata-new-epoch", proof); err != nil {
		t.Fatal(err)
	}
	binding, err = store.GetLibrarySourceByID(ctx, 7, binding.ID)
	if err != nil {
		t.Fatal(err)
	}
	currentScan, currentLease := testScan(t, store, binding, "metadata-current-epoch")
	currentInput := testObservation("metadata-current-file", "/videos/current.mkv")
	current, err := store.CommitScanObservation(ctx, 7, *currentLease, currentInput, testCheckpoint(currentInput, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.ExecContext(ctx, `UPDATE library_file SET last_seen_job_id=?,last_seen_generation=? WHERE source_file_id=?`,
		currentScan.ID, currentLease.Generation, old.SourceFileID); err != nil {
		t.Fatal(err)
	}
	finishEmptyReconcile(t, store, 7, *currentLease, 1)
	var jobID string
	if err := store.db.QueryRowContext(ctx, `SELECT job_id FROM metadata_job ORDER BY rowid DESC LIMIT 1`).Scan(&jobID); err != nil {
		t.Fatal(err)
	}
	rows, err := store.db.QueryContext(ctx, `SELECT item_id FROM metadata_job_target WHERE job_id=?`, jobID)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var targets []string
	for rows.Next() {
		var itemID string
		if err := rows.Scan(&itemID); err != nil {
			t.Fatal(err)
		}
		targets = append(targets, itemID)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if len(targets) != 1 || targets[0] != current.ItemID || targets[0] == old.ItemID {
		t.Fatalf("stale binding entered metadata targets: old=%s current=%s targets=%v", old.ItemID, current.ItemID, targets)
	}
}

func TestSchema22PreservesMetadataTargetsAndAddsDurationDefaults(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "schema22.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "schema22")
	binding := createBoundSource(t, store, library)
	connection := fixtureProvider(t, store, 7, false, "user", "schema22-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "schema22-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID}); err != nil {
		t.Fatal(err)
	}
	_, lease := testScan(t, store, binding, "schema22-scan")
	input := testObservation("schema22-file", "/videos/schema22.mkv")
	if _, err := store.CommitScanObservation(ctx, 7, *lease, input, testCheckpoint(input, 1, 1)); err != nil {
		t.Fatal(err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 1)
	if count := tableCount(t, store, "metadata_job_target"); count != 1 {
		t.Fatalf("metadata targets before migration: %d", count)
	}
	if err := testCoordinator(t, store).Release(); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.ExecContext(ctx, `DROP INDEX metadata_job_target_review; DROP INDEX metadata_job_target_pending;
ALTER TABLE metadata_job_target RENAME TO metadata_job_target_v22;
CREATE TABLE metadata_job_target (
 job_id TEXT NOT NULL REFERENCES metadata_job(job_id), library_id TEXT NOT NULL, item_id TEXT NOT NULL,
 outcome TEXT NOT NULL DEFAULT 'pending' CHECK(outcome IN ('pending','matched','already_matched','needs_review','no_match','source_changed','failed')),
 reason TEXT NOT NULL DEFAULT '', PRIMARY KEY(job_id,item_id), FOREIGN KEY(library_id,item_id) REFERENCES library_item(library_id,id)
) STRICT;
INSERT INTO metadata_job_target SELECT job_id,library_id,item_id,outcome,reason FROM metadata_job_target_v22;
DROP TABLE metadata_job_target_v22;
ALTER TABLE library_metadata_policy DROP COLUMN movie_min_minutes;
ALTER TABLE library_metadata_policy DROP COLUMN series_min_minutes;
PRAGMA user_version=21`)
	if err != nil {
		_ = db.Close()
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	if count := tableCount(t, store, "metadata_job_target"); count != 1 {
		t.Fatalf("metadata targets lost during migration: %d", count)
	}
	policy, err := store.GetLibraryMetadataPolicy(ctx, 7, library.ID)
	if err != nil || policy.MovieMinMinutes != 60 || policy.SeriesMinMinutes != 10 {
		t.Fatalf("duration defaults after migration: %+v %v", policy, err)
	}
	var version int
	if err := store.db.QueryRowContext(ctx, `PRAGMA user_version`).Scan(&version); err != nil || version != SchemaVersion {
		t.Fatalf("schema version: %d %v", version, err)
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
