package medialibrary

import (
	"bytes"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
)

func testScan(t *testing.T, store *Store, binding *LibrarySource, operation string) (*ScanJob, *ScanLease) {
	t.Helper()
	job, err := store.EnqueueScan(t.Context(), binding.Source.OwnerUserID, binding.Ref(), operation, 1)
	if err != nil {
		t.Fatal(err)
	}
	lease, err := testCoordinator(t, store).ClaimScan(t.Context(), binding.Source.OwnerUserID, job.ID, job.Generation)
	if err != nil {
		t.Fatal(err)
	}
	return job, lease
}

func testCoordinator(t *testing.T, store *Store) *ScanCoordinator {
	t.Helper()
	store.mu.Lock()
	active := store.coordinator
	store.mu.Unlock()
	if active != nil {
		return active
	}
	c, err := store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := c.Release(); err != nil {
			t.Error(err)
		}
	})
	return c
}

func testObservation(key, locator string) ScanObservation {
	return ScanObservation{ObjectKey: []byte(key), Identity: "native", Locator: []byte(locator), SourceVersion: "v1:10:20", SizeBytes: 10, InitialTitle: "Example", NFOStatus: "invalid"}
}

func testCheckpoint(input ScanObservation, sequence, processed int64) ScanCheckpoint {
	return ScanCheckpoint{Version: 1, Sequence: sequence, Processed: processed, LastObjectKey: bytes.Clone(input.ObjectKey), Phase: "enumerate"}
}

func finishEmptyReconcile(t *testing.T, store *Store, actor uint, lease ScanLease, seq int64) {
	t.Helper()
	if err := store.CompleteScanEnumeration(t.Context(), actor, lease, seq); err != nil {
		t.Fatal(err)
	}
	if err := store.CompleteScanReconciliation(t.Context(), actor, lease, seq); err != nil {
		t.Fatal(err)
	}
	if _, err := store.FinishScan(t.Context(), actor, lease, ScanFinish{Status: "completed", ExpectedSequence: seq}); err != nil {
		t.Fatal(err)
	}
}

func tableCount(t *testing.T, store *Store, table string) int {
	t.Helper()
	var count int
	if err := store.db.QueryRow("SELECT count(*) FROM " + table).Scan(&count); err != nil {
		t.Fatal(err)
	}
	return count
}

func TestScanObservationRollbackReplayAndCancellationFence(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	job, lease := testScan(t, store, binding, "first")
	input := testObservation("native-key", "/videos/one.mkv")
	cp := testCheckpoint(input, 1, 1)
	before := tableCount(t, store, "outbox")
	if _, err := store.db.Exec(`CREATE TRIGGER fail_scan_progress BEFORE INSERT ON outbox WHEN NEW.kind = 'media.scan.progress' BEGIN SELECT RAISE(ABORT, 'injected failure'); END`); err != nil {
		t.Fatal(err)
	}
	if result, err := store.CommitScanObservation(t.Context(), 7, *lease, input, cp); err == nil || result != nil {
		t.Fatal(result, err)
	}
	for _, name := range []string{"source_file", "library_file", "library_item", "edition", "variant", "scan_nfo_status"} {
		if got := tableCount(t, store, name); got != 0 {
			t.Fatalf("%s retained %d rows", name, got)
		}
	}
	loaded, err := store.GetScan(t.Context(), 7, job.ID)
	if err != nil || loaded.Checkpoint.Sequence != 0 || tableCount(t, store, "outbox") != before {
		t.Fatal(loaded, err)
	}
	if _, err := store.db.Exec("DROP TRIGGER fail_scan_progress"); err != nil {
		t.Fatal(err)
	}
	first, err := store.CommitScanObservation(t.Context(), 7, *lease, input, cp)
	if err != nil {
		t.Fatal(err)
	}
	issues, err := store.ListScanNFOIssues(t.Context(), 7, job.ID, lease.Generation, "", 10)
	if err != nil || len(issues.Items) != 1 || issues.Items[0].SourceFileID != first.SourceFileID || issues.Items[0].Status != "invalid" {
		t.Fatal(issues, err)
	}
	if _, err := store.ListScanNFOIssues(t.Context(), 8, job.ID, lease.Generation, "", 10); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	events := tableCount(t, store, "outbox")
	again, err := store.CommitScanObservation(t.Context(), 7, *lease, input, cp)
	if err != nil || *again != *first || tableCount(t, store, "outbox") != events {
		t.Fatal(first, again, err)
	}
	changed := input
	changed.SourceVersion = "other"
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, changed, cp); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	if _, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "first", 2); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	if _, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "second", 1); !errors.Is(err, ErrScanActive) {
		t.Fatal(err)
	}
	canceled, err := store.CancelScan(t.Context(), 7, job.ID, lease.Generation)
	if err != nil || canceled.Generation != lease.Generation+1 || canceled.Status != "canceled" {
		t.Fatal(canceled, err)
	}
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, input, cp); !errors.Is(err, ErrScanState) {
		t.Fatal(err)
	}
	if _, err := store.GetScan(t.Context(), 8, job.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "second", 1); err != nil {
		t.Fatal(err)
	}
}

func TestScanRecoveryResetsTraversalAndPreservesCatalog(t *testing.T) {
	path := filepath.Join(t.TempDir(), "restart.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	job, oldLease := testScan(t, store, binding, "restart")
	input := testObservation("native-key", "/videos/one.mkv")
	first, err := store.CommitScanObservation(t.Context(), 7, *oldLease, input, testCheckpoint(input, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	if err := testCoordinator(t, store).Release(); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	})
	coordinator := testCoordinator(t, store)
	if n, err := coordinator.InterruptRunningScans(t.Context()); err != nil || n != 1 {
		t.Fatal(n, err)
	}
	interrupted, err := store.GetScan(t.Context(), 7, job.ID)
	if err != nil || interrupted.Status != "interrupted" || interrupted.Generation != oldLease.Generation+1 || interrupted.Checkpoint.Sequence != 1 {
		t.Fatal(interrupted, err)
	}
	if _, err := store.CommitScanObservation(t.Context(), 7, *oldLease, input, testCheckpoint(input, 1, 1)); !errors.Is(err, ErrScanState) {
		t.Fatal(err)
	}
	lease, err := coordinator.ClaimScan(t.Context(), 7, job.ID, interrupted.Generation)
	if err != nil || lease.Generation != interrupted.Generation+1 {
		t.Fatal(lease, err)
	}
	resumed, err := store.GetScan(t.Context(), 7, job.ID)
	if err != nil || resumed.Checkpoint.Sequence != 0 || resumed.Checkpoint.Processed != 0 || resumed.Checkpoint.Phase != "enumerate" {
		t.Fatal(resumed, err)
	}
	input.ExpectedRevision = first.FileRevision
	input.ExpectedLocatorRevision = first.LocatorRevision
	second, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil || second.SourceFileID != first.SourceFileID || second.ItemID != first.ItemID || tableCount(t, store, "library_item") != 1 {
		t.Fatal(first, second, err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 1)
}

func TestScanMissingIsStagedUntilAtomicCompletionAndBoundToRoot(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	root := createBoundSource(t, store, library)
	file := observeFile(t, store, root, "one.mkv")
	other, err := store.BindLocalSource(t.Context(), 7, library.ID, root.LibraryRevision, root.Source.ID, []byte("/videos/other"))
	if err != nil {
		t.Fatal(err)
	}
	other = testAttestBinding(t, store, other)
	if _, err := store.ObserveSourceFile(t.Context(), 7, other.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: []byte("/videos/other/one.mkv"), SourceVersion: file.SourceVersion, ExpectedRevision: file.Revision}); err != nil {
		t.Fatal(err)
	}
	prepare := func(operation string) (*ScanJob, *ScanLease, ScanMissingCandidate) {
		job, lease := testScan(t, store, root, operation)
		if err := store.CompleteScanEnumeration(t.Context(), 7, *lease, 0); err != nil {
			t.Fatal(err)
		}
		page, err := store.ListScanMissingCandidates(t.Context(), 7, *lease, "", 100)
		if err != nil || len(page.Items) != 1 || string(page.Items[0].Locator) != "/videos/one.mkv" {
			t.Fatal(page, err)
		}
		cp := ScanCheckpoint{Version: 1, Sequence: 1, Phase: "reconcile"}
		if err := store.CommitScanMissing(t.Context(), 7, *lease, page.Items, cp); err != nil {
			t.Fatal(err)
		}
		if err := store.CommitScanMissing(t.Context(), 7, *lease, page.Items, cp); err != nil {
			t.Fatal(err)
		}
		return job, lease, page.Items[0]
	}
	job, lease, _ := prepare("cancel-before-finish")
	if _, err := store.CancelScan(t.Context(), 7, job.ID, lease.Generation); err != nil {
		t.Fatal(err)
	}
	var missing int
	if err := store.db.QueryRow("SELECT count(*) FROM library_file WHERE missing_since IS NOT NULL").Scan(&missing); err != nil || missing != 0 {
		t.Fatal(missing, err)
	}
	job, lease, _ = prepare("complete")
	if err := store.CompleteScanReconciliation(t.Context(), 7, *lease, 1); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`CREATE TRIGGER fail_scan_finish BEFORE INSERT ON outbox WHEN NEW.kind = 'media.scan.completed' BEGIN SELECT RAISE(ABORT, 'injected'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := store.FinishScan(t.Context(), 7, *lease, ScanFinish{Status: "completed", ExpectedSequence: 1}); err == nil {
		t.Fatal("completion unexpectedly committed")
	}
	state, err := store.GetScan(t.Context(), 7, job.ID)
	if err != nil || state.Status != "running" {
		t.Fatal(state, err)
	}
	if err := store.db.QueryRow("SELECT count(*) FROM library_file WHERE missing_since IS NOT NULL").Scan(&missing); err != nil || missing != 0 {
		t.Fatal(missing, err)
	}
	if _, err := store.db.Exec("DROP TRIGGER fail_scan_finish"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.FinishScan(t.Context(), 7, *lease, ScanFinish{Status: "completed", ExpectedSequence: 1}); err != nil {
		t.Fatal(err)
	}
	if err := store.db.QueryRow("SELECT count(*) FROM library_file WHERE root_locator = ? AND missing_since IS NOT NULL", root.RootLocator).Scan(&missing); err != nil || missing != 1 {
		t.Fatal(missing, err)
	}
	if err := store.db.QueryRow("SELECT count(*) FROM library_file WHERE root_locator = ? AND missing_since IS NOT NULL", other.RootLocator).Scan(&missing); err != nil || missing != 0 {
		t.Fatal(missing, err)
	}
	if err := store.db.QueryRow("SELECT count(*) FROM source_file WHERE missing_since IS NOT NULL").Scan(&missing); err != nil || missing != 0 {
		t.Fatal(missing, err)
	}
}

func TestPathIdentityEpochAndExistingMapping(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	input := testObservation("unused", "/videos/one.mkv")
	input.Identity = "path_unverified"
	input.ObjectKey = UnverifiedPathObjectKey(binding.ID, binding.ProofEpoch, input.Locator)
	file, err := store.ObserveSourceFile(t.Context(), 7, binding.Ref(), FileObservation{ObjectKey: input.ObjectKey, Locator: input.Locator, SourceVersion: input.SourceVersion, SizeBytes: &input.SizeBytes})
	if err != nil {
		t.Fatal(err)
	}
	item := catalogItem(t, store, 7, library.ID, "Manually edited")
	if _, err := store.AttachCatalogFile(t.Context(), 7, library.ID, item.ID, item.Revision, file.ID); err != nil {
		t.Fatal(err)
	}
	input.ExpectedRevision = file.Revision
	input.ExpectedLocatorRevision = file.LocatorRevision
	_, lease := testScan(t, store, binding, "unverified-1")
	first, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil || first.ItemID != item.ID || first.IdentityEpoch != 2 || tableCount(t, store, "library_item") != 1 {
		t.Fatal(first, err)
	}
	input.ExpectedRevision = first.FileRevision
	input.ExpectedLocatorRevision = first.LocatorRevision
	second, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 2, 2))
	if err != nil || second.IdentityEpoch != first.IdentityEpoch {
		t.Fatal(second, err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 2)
	_, lease = testScan(t, store, binding, "unverified-2")
	input.ExpectedRevision = second.FileRevision
	input.ExpectedLocatorRevision = second.LocatorRevision
	third, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil || third.IdentityEpoch != first.IdentityEpoch+1 || third.ItemID != item.ID {
		t.Fatal(third, err)
	}
	wrong := input
	wrong.ObjectKey = []byte("one.mkv")
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, wrong, testCheckpoint(wrong, 2, 2)); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
}

func TestScanReconciliationRechecksStagedRevision(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	file := observeFile(t, store, binding, "old.mkv")
	_, lease := testScan(t, store, binding, "reconcile-conflict")
	if err := store.CompleteScanEnumeration(t.Context(), 7, *lease, 0); err != nil {
		t.Fatal(err)
	}
	page, err := store.ListScanMissingCandidates(t.Context(), 7, *lease, "", 10)
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	cp := ScanCheckpoint{Version: 1, Sequence: 1, Phase: "reconcile"}
	if err := store.CommitScanMissing(t.Context(), 7, *lease, page.Items, cp); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ObserveSourceFile(t.Context(), 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: "changed", ExpectedRevision: file.Revision}); err != nil {
		t.Fatal(err)
	}
	if err := store.CompleteScanReconciliation(t.Context(), 7, *lease, 1); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	page, err = store.ListScanMissingCandidates(t.Context(), 7, *lease, "", 10)
	if err != nil || len(page.Items) != 1 || page.Items[0].FileRevision != file.Revision+1 {
		t.Fatal(page, err)
	}
	cp.Sequence++
	if err := store.CommitScanMissing(t.Context(), 7, *lease, page.Items, cp); err != nil {
		t.Fatal(err)
	}
	if err := store.CompleteScanReconciliation(t.Context(), 7, *lease, 2); err != nil {
		t.Fatal(err)
	}
	if _, err := store.FinishScan(t.Context(), 7, *lease, ScanFinish{Status: "completed", ExpectedSequence: 2}); err != nil {
		t.Fatal(err)
	}
}

func TestMissingConfirmationRejectsBindingLocatorABA(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	file := observeFile(t, store, binding, "one.mkv")
	_, lease := testScan(t, store, binding, "missing-aba")
	ctx := t.Context()
	if err := store.CompleteScanEnumeration(ctx, 7, *lease, 0); err != nil {
		t.Fatal(err)
	}
	page, err := store.ListScanMissingCandidates(ctx, 7, *lease, "", 10)
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	stale := page.Items[0]
	checkpoint := ScanCheckpoint{Version: 1, Sequence: 1, Phase: "reconcile"}
	if err := store.CommitScanMissing(ctx, 7, *lease, page.Items, checkpoint); err != nil {
		t.Fatal(err)
	}
	version := stale.LocatorRevision
	moved, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: []byte("/videos/elsewhere.mkv"), SourceVersion: file.SourceVersion, ExpectedRevision: file.Revision, ExpectedLocatorRevision: &version})
	if err != nil {
		t.Fatal(err)
	}
	version = moved.LocatorRevision
	returned, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: file.SourceVersion, ExpectedRevision: file.Revision, ExpectedLocatorRevision: &version})
	if err != nil {
		t.Fatal(err)
	}
	if returned.Revision != file.Revision || returned.LocatorRevision != stale.LocatorRevision+2 {
		t.Fatal(returned)
	}
	if err := store.CompleteScanReconciliation(ctx, 7, *lease, 1); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("stale staged absence accepted", err)
	}
	checkpoint.Sequence = 2
	if err := store.CommitScanMissing(ctx, 7, *lease, []ScanMissingConfirmation{stale}, checkpoint); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("stale absence restaged", err)
	}
	fresh, err := store.ListScanMissingCandidates(ctx, 7, *lease, "", 10)
	if err != nil || len(fresh.Items) != 1 || fresh.Items[0].LocatorRevision != returned.LocatorRevision {
		t.Fatal(fresh, err)
	}
	if err := store.CommitScanMissing(ctx, 7, *lease, fresh.Items, checkpoint); err != nil {
		t.Fatal(err)
	}
	if err := store.CompleteScanReconciliation(ctx, 7, *lease, 2); err != nil {
		t.Fatal(err)
	}
	if _, err := store.FinishScan(ctx, 7, *lease, ScanFinish{Status: "completed", ExpectedSequence: 2}); err != nil {
		t.Fatal(err)
	}
}

func TestScanMissingStageRollsBackWithOutbox(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_ = observeFile(t, store, binding, "old.mkv")
	_, lease := testScan(t, store, binding, "stage-failure")
	if err := store.CompleteScanEnumeration(t.Context(), 7, *lease, 0); err != nil {
		t.Fatal(err)
	}
	page, err := store.ListScanMissingCandidates(t.Context(), 7, *lease, "", 10)
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	if _, err := store.db.Exec(`CREATE TRIGGER fail_stage BEFORE INSERT ON outbox WHEN NEW.kind = 'media.scan.reconcile.progress' BEGIN SELECT RAISE(ABORT, 'injected'); END`); err != nil {
		t.Fatal(err)
	}
	cp := ScanCheckpoint{Version: 1, Sequence: 1, Phase: "reconcile"}
	if err := store.CommitScanMissing(t.Context(), 7, *lease, page.Items, cp); err == nil {
		t.Fatal("stage unexpectedly committed")
	}
	job, err := store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || job.Checkpoint.Sequence != 0 || tableCount(t, store, "scan_missing_candidate") != 0 {
		t.Fatal(job, err)
	}
	var missing int
	if err := store.db.QueryRow("SELECT count(*) FROM library_file WHERE missing_since IS NOT NULL").Scan(&missing); err != nil || missing != 0 {
		t.Fatal(missing, err)
	}
}

func TestScanRejectsOlderObservationAfterConcurrentSourceUpdate(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "concurrent")
	input := testObservation("native-key", "/videos/one.mkv")
	first, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	input.ExpectedRevision = first.FileRevision
	if _, err := store.ObserveSourceFile(t.Context(), 7, binding.Ref(), FileObservation{ObjectKey: input.ObjectKey, Locator: input.Locator, SourceVersion: "newer", ExpectedRevision: first.FileRevision}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 2, 2)); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	current, err := store.GetSourceFile(t.Context(), 7, binding.Source.ID, input.ObjectKey)
	if err != nil || current.SourceVersion != "newer" {
		t.Fatal(current, err)
	}
	job, err := store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || job.Checkpoint.Sequence != 1 {
		t.Fatal(job, err)
	}
}

func TestScanKeepsAnNFOIssueWhenAHardlinkHasNoSidecar(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	job, lease := testScan(t, store, binding, "hardlink-issue")
	input := testObservation("same-native-file", "/videos/one.mkv")
	first, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	input.ExpectedRevision = first.FileRevision
	input.ExpectedLocatorRevision = first.LocatorRevision
	input.Locator, input.NFOStatus = []byte("/videos/other.mkv"), "missing"
	second, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 2, 2))
	if err != nil {
		t.Fatal(err)
	}
	issues, err := store.ListScanNFOIssues(t.Context(), 7, job.ID, lease.Generation, "", 10)
	if err != nil || len(issues.Items) != 1 || issues.Items[0].Status != "invalid" {
		t.Fatal(issues, err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 2)
	job, lease = testScan(t, store, binding, "hardlink-next")
	input.ExpectedRevision = second.FileRevision
	input.ExpectedLocatorRevision = second.LocatorRevision
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1)); err != nil {
		t.Fatal(err)
	}
	issues, err = store.ListScanNFOIssues(t.Context(), 7, job.ID, lease.Generation, "", 10)
	if err != nil || len(issues.Items) != 0 {
		t.Fatal(issues, err)
	}
}

func markTestBindingMissing(t *testing.T, store *Store, binding *LibrarySource, operation string) {
	t.Helper()
	_, lease := testScan(t, store, binding, operation)
	if err := store.CompleteScanEnumeration(t.Context(), 7, *lease, 0); err != nil {
		t.Fatal(err)
	}
	page, err := store.ListScanMissingCandidates(t.Context(), 7, *lease, "", 10)
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	cp := ScanCheckpoint{Version: 1, Sequence: 1, Phase: "reconcile"}
	if err := store.CommitScanMissing(t.Context(), 7, *lease, page.Items, cp); err != nil {
		t.Fatal(err)
	}
	if err := store.CompleteScanReconciliation(t.Context(), 7, *lease, cp.Sequence); err != nil {
		t.Fatal(err)
	}
	if _, err := store.FinishScan(t.Context(), 7, *lease, ScanFinish{Status: "completed", ExpectedSequence: cp.Sequence}); err != nil {
		t.Fatal(err)
	}
}

func TestNativeIdentityEpochAdvancesOnlyAfterEveryBindingWasMissing(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	root := createBoundSource(t, store, library)
	other, err := store.BindLocalSource(t.Context(), 7, library.ID, root.LibraryRevision, root.Source.ID, []byte("/videos/other"))
	if err != nil {
		t.Fatal(err)
	}
	other = testAttestBinding(t, store, other)
	input := testObservation("native-file-number", "/videos/one.mkv")
	_, lease := testScan(t, store, root, "native-initial-root")
	first, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil || first.IdentityEpoch != 1 {
		t.Fatal(first, err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 1)
	_, lease = testScan(t, store, other, "native-initial-other")
	input.ExpectedRevision, input.Locator = first.FileRevision, []byte("/videos/other/one.mkv")
	second, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil || second.IdentityEpoch != first.IdentityEpoch || second.SourceFileID != first.SourceFileID || second.ItemID != first.ItemID {
		t.Fatal(second, err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 1)

	// A missing root does not invalidate the shared native object while a
	// second binding still survives. Exercise the public observation helper.
	markTestBindingMissing(t, store, root, "one-root-missing")
	current, err := store.GetSourceFile(t.Context(), 7, root.Source.ID, input.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	currentTokens, err := store.GetObservationRevision(t.Context(), 7, root.Ref(), input.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	restored, err := store.ObserveSourceFile(t.Context(), 7, root.Ref(), FileObservation{
		ObjectKey: input.ObjectKey, Locator: []byte("/videos/one.mkv"), SourceVersion: input.SourceVersion,
		SizeBytes: &input.SizeBytes, ExpectedRevision: current.Revision, ExpectedLocatorRevision: &currentTokens.LocatorRevision,
	})
	if err != nil || restored.IdentityEpoch != first.IdentityEpoch {
		t.Fatal(restored, err)
	}

	// Once every binding has a completed missing confirmation, reappearance in
	// a later scan advances both the identity epoch and the file revision even
	// if the native number, locator, size and modification marker are unchanged.
	markTestBindingMissing(t, store, root, "all-missing-root")
	markTestBindingMissing(t, store, other, "all-missing-other")
	before, err := store.GetSourceFile(t.Context(), 7, root.Source.ID, input.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	_, lease = testScan(t, store, root, "native-reappeared")
	input.ExpectedRevision, input.Locator = before.Revision, []byte("/videos/one.mkv")
	currentTokens, err = store.GetObservationRevision(t.Context(), 7, root.Ref(), input.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	input.ExpectedLocatorRevision = currentTokens.LocatorRevision
	reappeared, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1))
	if err != nil || reappeared.IdentityEpoch != before.IdentityEpoch+1 || reappeared.FileRevision != before.Revision+1 || reappeared.ItemID != first.ItemID {
		t.Fatal(before, reappeared, err)
	}
	if tableCount(t, store, "source_file") != 1 || tableCount(t, store, "library_item") != 1 || tableCount(t, store, "variant") != 1 {
		t.Fatal("catalog associations changed")
	}
	var rootMissing, otherMissing int
	if err := store.db.QueryRow("SELECT count(*) FROM library_file WHERE root_locator = ? AND missing_since IS NOT NULL", root.RootLocator).Scan(&rootMissing); err != nil {
		t.Fatal(err)
	}
	if err := store.db.QueryRow("SELECT count(*) FROM library_file WHERE root_locator = ? AND missing_since IS NOT NULL", other.RootLocator).Scan(&otherMissing); err != nil {
		t.Fatal(err)
	}
	if rootMissing != 0 || otherMissing != 1 {
		t.Fatal(rootMissing, otherMissing)
	}
}

func TestVersionThreeMigrationPreservesBindingAndOpaqueLocator(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v3.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(t.Context(), func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(t.Context(), schemaV1+schemaV2+schemaV3); err != nil {
			return err
		}
		if err := addLibrary(t.Context(), tx, "old"); err != nil {
			return err
		}
		if _, err := tx.ExecContext(t.Context(), `INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('disk',7,'local','config',1);
INSERT INTO library_source(library_id,source_id,root_locator) VALUES ('old','disk',X'2FFF');
INSERT INTO source_file(id,source_id,object_key,locator,source_version,observed_at,missing_since) VALUES ('file','disk',X'FE',X'2FFF2FFE','old-version',1,2);
INSERT INTO library_file(library_id,source_file_id,source_id,root_locator,observed_at) VALUES ('old','file','disk',X'2FFF',1)`); err != nil {
			return err
		}
		_, err := tx.ExecContext(t.Context(), fmt.Sprintf("PRAGMA application_id = %d; PRAGMA user_version = 3", applicationID))
		return err
	})
	if closeErr := old.Close(); err != nil || closeErr != nil {
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
	file, err := store.GetSourceFile(t.Context(), 7, "disk", []byte{0xfe})
	if err != nil || file.ID != "file" || file.IdentityKind != "legacy_unverified" || file.IdentityEpoch != 1 || file.MissingSince == nil || *file.MissingSince != 2 {
		t.Fatal(file, err)
	}
	var locator []byte
	var seen sql.NullString
	if err := store.db.QueryRow("SELECT locator, last_seen_job_id FROM library_file WHERE source_file_id = 'file'").Scan(&locator, &seen); err != nil || !bytes.Equal(locator, []byte{'/', 0xff, '/', 0xfe}) || seen.Valid {
		t.Fatal(locator, seen, err)
	}
	var violations int
	if err := store.db.QueryRow("SELECT count(*) FROM pragma_foreign_key_check").Scan(&violations); err != nil || violations != 0 {
		t.Fatal(violations, err)
	}
}

func TestVersionThreeCorruptBindingFailsMigrationWithoutDroppingRows(t *testing.T) {
	path := filepath.Join(t.TempDir(), "corrupt-v3.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`PRAGMA foreign_keys = OFF;` + schemaV1 + schemaV2 + schemaV3 + `
INSERT INTO library(id,owner_user_id,name,created_at,updated_at) VALUES ('old',7,'Films',1,1);
INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('disk',7,'local','config',1);
INSERT INTO library_source(library_id,source_id,root_locator) VALUES ('old','disk',X'2F');
INSERT INTO library_file(library_id,source_file_id,source_id,root_locator,observed_at) VALUES ('old','missing','disk',X'2F',1);` + fmt.Sprintf("PRAGMA application_id = %d; PRAGMA user_version = 3", applicationID)); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	if store, err := Open(t.Context(), path); err == nil {
		_ = store.Close()
		t.Fatal("corrupt migration unexpectedly succeeded")
	}
	db, err = sql.Open("sqlite", databaseURL(path, "ro"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	}()
	var version, count int
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 3 {
		t.Fatal(version, err)
	}
	if err := db.QueryRow("SELECT count(*) FROM library_file").Scan(&count); err != nil || count != 1 {
		t.Fatal(count, err)
	}
	if err := db.QueryRow("SELECT count(*) FROM sqlite_schema WHERE name = 'scan_job'").Scan(&count); err != nil || count != 0 {
		t.Fatal(count, err)
	}
}
