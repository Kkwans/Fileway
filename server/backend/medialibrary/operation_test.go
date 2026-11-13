package medialibrary

import (
	"bytes"
	"encoding/base64"
	"errors"
	"strings"
	"sync"
	"testing"
)

func TestOperationReceiptReplayConflictAndRollback(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	const workers = 6
	results := make([]*Library, workers)
	errorsSeen := make([]error, workers)
	var group sync.WaitGroup
	for i := range workers {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			results[i], errorsSeen[i] = store.CreateLibraryWithOperation(ctx, 7, "create-1", "Films")
		}(i)
	}
	group.Wait()
	for i := range workers {
		if errorsSeen[i] != nil || results[i] == nil || results[i].ID != results[0].ID {
			t.Fatal(i, results[i], errorsSeen[i])
		}
	}
	if tableCount(t, store, "library") != 1 || tableCount(t, store, "operation_receipt") != 1 {
		t.Fatal("duplicate creation")
	}
	if _, err := store.CreateLibraryWithOperation(ctx, 7, "create-1", "Different"); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	updated, err := store.UpdateLibraryWithOperation(ctx, 7, results[0].ID, 1, "rename-1", "Renamed")
	if err != nil || updated.Revision != 2 {
		t.Fatal(updated, err)
	}
	if replay, err := store.CreateLibraryWithOperation(ctx, 7, "create-1", "Films"); err != nil || replay.Name != "Films" || replay.Revision != 1 {
		t.Fatal(replay, err)
	}
	if replay, err := store.UpdateLibraryWithOperation(ctx, 7, results[0].ID, 1, "rename-1", "Renamed"); err != nil || *replay != *updated {
		t.Fatal(replay, err)
	}
	if _, err := store.UpdateLibraryWithOperation(ctx, 7, results[0].ID, 1, "rename-1", "Other"); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	beforeEvents := tableCount(t, store, "outbox")
	if _, err := store.db.Exec(`CREATE TRIGGER fail_receipt BEFORE INSERT ON operation_receipt BEGIN SELECT RAISE(ABORT,'injected'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := store.CreateLibraryWithOperation(ctx, 7, "rolled-back", "No partial"); err == nil {
		t.Fatal("receipt failure succeeded")
	}
	if tableCount(t, store, "library") != 1 || tableCount(t, store, "outbox") != beforeEvents {
		t.Fatal("domain write or outbox survived receipt failure")
	}
}

func TestSourceReceiptPreservesAckWithoutPersistingRootOrConfiguration(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	root := []byte("/private/sample-root")
	configuration := "configuration-reference-sensitive"
	first, err := store.CreateLocalSourceWithOperation(ctx, 7, library.ID, 1, "source-1", configuration, root)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.UpdateLibrary(ctx, 7, library.ID, "Updated", first.LibraryRevision); err != nil {
		t.Fatal(err)
	}
	again, err := store.CreateLocalSourceWithOperation(ctx, 7, library.ID, 1, "source-1", configuration, root)
	if err != nil || again.ID != first.ID || again.Source.ID != first.Source.ID || again.LibraryRevision != first.LibraryRevision || !bytes.Equal(again.RootLocator, root) {
		t.Fatal(first, again, err)
	}
	if _, err := store.CreateLocalSourceWithOperation(ctx, 7, library.ID, 1, "source-1", configuration, []byte("/different")); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	var payload string
	if err := store.db.QueryRow("SELECT result_json FROM operation_receipt WHERE kind = 'source.create.local'").Scan(&payload); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(payload, string(root)) || strings.Contains(payload, base64.StdEncoding.EncodeToString(root)) || strings.Contains(payload, configuration) {
		t.Fatalf("receipt leaked source input: %s", payload)
	}
}

func TestLibraryMutationReceiptsReplayOriginalRevisions(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	grant, err := store.GrantMemberWithOperation(ctx, 7, library.ID, 1, "grant", 8, RoleEditor)
	if err != nil {
		t.Fatal(err)
	}
	revoked, err := store.RevokeMemberWithOperation(ctx, 7, library.ID, grant.LibraryRevision, "revoke", 8)
	if err != nil {
		t.Fatal(err)
	}
	if again, err := store.GrantMemberWithOperation(ctx, 7, library.ID, 1, "grant", 8, RoleEditor); err != nil || again.Revision != grant.Revision || again.RevokedAt != nil {
		t.Fatal(again, err)
	}
	archived, err := store.ArchiveLibraryWithOperation(ctx, 7, library.ID, revoked.LibraryRevision, "archive")
	if err != nil {
		t.Fatal(err)
	}
	restored, err := store.RestoreLibraryWithOperation(ctx, 7, library.ID, archived.Revision, "restore")
	if err != nil || restored.ArchivedAt != nil {
		t.Fatal(restored, err)
	}
	if again, err := store.ArchiveLibraryWithOperation(ctx, 7, library.ID, revoked.LibraryRevision, "archive"); err != nil || again.ArchivedAt == nil || again.Revision != archived.Revision {
		t.Fatal(again, err)
	}
}

func TestScanOperationReceiptsAndRetryAreAtomic(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	job, err := store.EnqueueScanByBindingID(ctx, 7, library.ID, binding.ID, binding.Revision, "start", 1)
	if err != nil || job.Binding.RootLocator != nil {
		t.Fatal(job, err)
	}
	// Receipt replay does not depend on a later binding revision.
	if _, err := store.db.Exec("UPDATE library_source SET revision = revision + 1 WHERE binding_id = ?", binding.ID); err != nil {
		t.Fatal(err)
	}
	again, err := store.EnqueueScanByBindingID(ctx, 7, library.ID, binding.ID, binding.Revision, "start", 1)
	if err != nil || again.ID != job.ID || again.Status != "queued" {
		t.Fatal(again, err)
	}
	if _, err := store.EnqueueScanByBindingID(ctx, 7, library.ID, binding.ID, binding.Revision, "start", 2); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	canceled, err := store.CancelScanWithOperation(ctx, 7, job.ID, job.Generation, "cancel")
	if err != nil {
		t.Fatal(err)
	}
	if again, err := store.CancelScanWithOperation(ctx, 7, job.ID, job.Generation, "cancel"); err != nil || again.Generation != canceled.Generation {
		t.Fatal(again, err)
	}
	newJob, err := store.RetryScanWithOperation(ctx, 7, job.ID, canceled.Generation, "retry")
	if err != nil || newJob.ID == job.ID || newJob.Status != "queued" {
		t.Fatal(newJob, err)
	}
	if again, err := store.RetryScanWithOperation(ctx, 7, job.ID, canceled.Generation, "retry"); err != nil || again.ID != newJob.ID {
		t.Fatal(again, err)
	}
	if _, err := store.RetryScanWithOperation(ctx, 7, newJob.ID, canceled.Generation, "retry"); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	var leaked int
	if err := store.db.QueryRow(`SELECT count(*) FROM operation_receipt WHERE result_json LIKE '%/videos%' OR result_json LIKE '%configuration%' OR result_json LIKE '%LastObjectKey%'`).Scan(&leaked); err != nil || leaked != 0 {
		t.Fatal(leaked, err)
	}
}

func TestInterruptedRetryReceiptFailureRetainsOriginalActiveJob(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "old")
	interrupted, err := store.FinishScan(ctx, 7, *lease, ScanFinish{Status: "interrupted", ExpectedSequence: 0})
	if err != nil {
		t.Fatal(err)
	}
	beforeEvents := tableCount(t, store, "outbox")
	if _, err := store.db.Exec(`CREATE TRIGGER fail_retry_receipt BEFORE INSERT ON operation_receipt BEGIN SELECT RAISE(ABORT,'injected'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := store.RetryScanWithOperation(ctx, 7, interrupted.ID, interrupted.Generation, "retry"); err == nil {
		t.Fatal("receipt failure succeeded")
	}
	current, err := store.GetScan(ctx, 7, interrupted.ID)
	if err != nil || current.Status != "interrupted" || current.Generation != interrupted.Generation || tableCount(t, store, "job") != 1 || tableCount(t, store, "outbox") != beforeEvents {
		t.Fatal(current, err)
	}
	var active int
	if err := store.db.QueryRowContext(ctx, "SELECT active FROM scan_job WHERE job_id = ?", interrupted.ID).Scan(&active); err != nil || active != 1 {
		t.Fatal(active, err)
	}
}
