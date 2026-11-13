package mediascan

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

type persistentFixture struct {
	*scanFixture
	store *medialibrary.Store
	coord *medialibrary.ScanCoordinator
	count int
}

type racingSink struct {
	*persistentSink
	afterLookup func(context.Context, []byte, int64) error
}

func (s *racingSink) LookupRevision(ctx context.Context, key []byte) (int64, error) {
	revision, err := s.persistentSink.LookupRevision(ctx, key)
	if err != nil {
		return 0, err
	}
	if err := s.afterLookup(ctx, key, revision); err != nil {
		return 0, err
	}
	return revision, nil
}

func newPersistentFixture(t *testing.T) *persistentFixture {
	t.Helper()
	f := &persistentFixture{scanFixture: newScanFixture(t)}
	store, err := medialibrary.Open(t.Context(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	f.store = store
	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	})
	f.coord, err = store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := f.coord.Close(); err != nil {
			t.Error(err)
		}
	})
	library, err := store.CreateLibrary(t.Context(), 7, "Fixture")
	if err != nil {
		t.Fatal(err)
	}
	authority, err := f.resolve(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	proof, err := CaptureLocalBindingProof(t.Context(), 7, "fixture-config", []byte("/media"), authority)
	if err != nil {
		t.Fatal(err)
	}
	ack, err := store.CreateAttestedLocalSourceWithOperation(t.Context(), 7, library.ID, library.Revision, "fixture-source", "fixture-config", []byte("/media"), *proof)
	if err != nil {
		t.Fatal(err)
	}
	f.binding, err = store.GetLibrarySourceByID(t.Context(), 7, ack.BindingID)
	if err != nil {
		t.Fatal(err)
	}
	f.calls = 0
	return f
}

func (f *persistentFixture) ownerResolve(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
	if binding.Source.OwnerUserID != 7 || binding.Source.ConfigurationRef != "fixture-config" {
		return mediasource.LocalAuthority{}, fs.ErrPermission
	}
	authority, err := f.resolve(ctx)
	if err != nil {
		return mediasource.LocalAuthority{}, err
	}
	if _, err := VerifyLocalBindingProof(ctx, 7, "fixture-config", binding.RootLocator, authority, binding.Proof); err != nil {
		return mediasource.LocalAuthority{}, err
	}
	return authority, nil
}

func (f *persistentFixture) claim(t *testing.T) medialibrary.ScanLease {
	t.Helper()
	f.count++
	job, err := f.store.EnqueueScan(t.Context(), 7, f.binding.Ref(), fmt.Sprintf("fixture-operation-%d", f.count), LocalRulesRevision)
	if err != nil {
		t.Fatal(err)
	}
	lease, err := f.coord.ClaimScan(t.Context(), 7, job.ID, job.Generation)
	if err != nil {
		t.Fatal(err)
	}
	return *lease
}

func (f *persistentFixture) catalog(t *testing.T) []medialibrary.CatalogItem {
	t.Helper()
	page, err := f.store.ListCatalogItems(t.Context(), 7, f.binding.LibraryID, medialibrary.CatalogListOptions{Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if page.NextCursor != "" {
		t.Fatal("unexpected fixture pagination")
	}
	return page.Items
}

func TestPersistentLocalScanJourneyIsRepeatable(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "a.mp4", "video A")
	f.write(t, "a.nfo", "<movie><title>A</title></movie>")
	f.write(t, "nested/b.mkv", "video B")
	f.write(t, "nested/b.nfo", "<movie><title>")
	lease := f.claim(t)
	result, err := Run(t.Context(), 7, f.store, f.coord, lease, f.ownerResolve, Options{ReadBatch: 1})
	if err != nil || result.Observed != 2 || result.NFOFailed != 1 {
		t.Fatalf("initial run: result=%+v err=%v", result, err)
	}
	job, err := f.store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || job.Status != "completed" || job.Checkpoint.Processed != 2 {
		t.Fatalf("job: %+v %v", job, err)
	}
	var invalidNFO int
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		return tx.QueryRowContext(t.Context(), "SELECT count(*) FROM scan_nfo_status WHERE job_id = ? AND status = 'invalid'", lease.JobID).Scan(&invalidNFO)
	}); err != nil || invalidNFO != 1 {
		t.Fatalf("bad NFO status not persisted: count=%d err=%v", invalidNFO, err)
	}
	items := f.catalog(t)
	if len(items) != 2 || items[0].FileCount != 1 || items[1].FileCount != 1 {
		t.Fatalf("catalog: %+v", items)
	}
	repeat := f.claim(t)
	result, err = Run(t.Context(), 7, f.store, f.coord, repeat, f.ownerResolve, Options{})
	if err != nil || result.Observed != 2 {
		t.Fatalf("repeat: result=%+v err=%v", result, err)
	}
	again := f.catalog(t)
	if len(again) != 2 || again[0].ID != items[0].ID || again[1].ID != items[1].ID || again[0].CreatedAt != items[0].CreatedAt || again[1].CreatedAt != items[1].CreatedAt {
		t.Fatalf("repeat created or reset catalog items: before=%+v after=%+v", items, again)
	}
	contents, err := os.ReadFile(filepath.Join(f.root, "media", "a.mp4"))
	if err != nil || string(contents) != "video A" {
		t.Fatalf("source media changed: %q %v", contents, err)
	}
}

func TestPersistentLocalScanRevocationAndCancellationDoNotComplete(t *testing.T) {
	for _, mode := range []string{"revoke", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			f := newPersistentFixture(t)
			f.write(t, "a.mp4", "A")
			f.write(t, "b.mp4", "B")
			lease := f.claim(t)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			resolve := func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
				owner := binding.Source.OwnerUserID
				job, err := f.store.GetScan(ctx, owner, lease.JobID)
				if err != nil {
					return mediasource.LocalAuthority{}, err
				}
				if job.Checkpoint.Processed > 0 {
					if mode == "cancel" {
						cancel()
						return mediasource.LocalAuthority{}, ctx.Err()
					}
					return mediasource.LocalAuthority{}, fs.ErrPermission
				}
				return f.ownerResolve(ctx, binding)
			}
			result, err := Run(ctx, 7, f.store, f.coord, lease, resolve, Options{ReadBatch: 1})
			wantErr, wantStatus := error(fs.ErrPermission), "failed"
			if mode == "cancel" {
				wantErr, wantStatus = context.Canceled, "interrupted"
			}
			if !errors.Is(err, wantErr) || result.Observed != 1 {
				t.Fatalf("result=%+v err=%v", result, err)
			}
			job, getErr := f.store.GetScan(t.Context(), 7, lease.JobID)
			before := f.catalog(t)
			if getErr != nil || job.Status != wantStatus || len(before) != 1 {
				t.Fatalf("job=%+v err=%v", job, getErr)
			}
			if mode == "cancel" {
				resumed, err := f.coord.ClaimScan(t.Context(), 7, job.ID, job.Generation)
				if err != nil {
					t.Fatal(err)
				}
				if _, err := Run(t.Context(), 7, f.store, f.coord, *resumed, f.ownerResolve, Options{}); err != nil {
					t.Fatal(err)
				}
				after := f.catalog(t)
				if len(after) != 2 || after[0].ID != before[0].ID && after[1].ID != before[0].ID {
					t.Fatalf("resume duplicated an observed item: before=%+v after=%+v", before, after)
				}
				job, err = f.store.GetScan(t.Context(), 7, lease.JobID)
				if err != nil || job.Status != "completed" || job.Checkpoint.Processed != 2 {
					t.Fatalf("resumed job: %+v %v", job, err)
				}
			}
		})
	}
}

func TestPersistentScanPublishesMissingOnlyAfterSuccessfulReconciliation(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "kept.mp4", "A")
	f.write(t, "removed.mp4", "B")
	initial := f.claim(t)
	if _, err := Run(t.Context(), 7, f.store, f.coord, initial, f.ownerResolve, Options{}); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(f.root, "media", "removed.mp4")); err != nil {
		t.Fatal(err)
	}
	lease := f.claim(t)
	// Revocation immediately before final publication occurs after the missing
	// candidate has been safely checked and staged, but must not publish it.
	resolve := func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		owner := binding.Source.OwnerUserID
		job, err := f.store.GetScan(ctx, owner, lease.JobID)
		if err != nil {
			return mediasource.LocalAuthority{}, err
		}
		if job.Checkpoint.Phase == "ready" {
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		return f.ownerResolve(ctx, binding)
	}
	if _, err := Run(t.Context(), 7, f.store, f.coord, lease, resolve, Options{}); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("final authority revocation: %v", err)
	}
	staged, missing := fixtureMissingCounts(t, f.store, lease.JobID)
	if staged != 1 || missing != 0 {
		t.Fatalf("failed scan staged=%d published missing=%d", staged, missing)
	}
	job, err := f.store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || job.Status != "failed" {
		t.Fatalf("failed job: %+v %v", job, err)
	}
	completed := f.claim(t)
	if _, err := Run(t.Context(), 7, f.store, f.coord, completed, f.ownerResolve, Options{}); err != nil {
		t.Fatal(err)
	}
	staged, missing = fixtureMissingCounts(t, f.store, completed.JobID)
	if staged != 1 || missing != 1 {
		t.Fatalf("completed scan staged=%d published missing=%d", staged, missing)
	}
}

func TestPersistentScanExternalCancellationFencesOldLease(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "a.mp4", "A")
	f.write(t, "b.mp4", "B")
	lease := f.claim(t)
	canceled := false
	resolve := func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		owner := binding.Source.OwnerUserID
		job, err := f.store.GetScan(ctx, owner, lease.JobID)
		if err != nil {
			return mediasource.LocalAuthority{}, err
		}
		if job.Checkpoint.Processed > 0 && !canceled {
			if _, err := f.store.CancelScan(ctx, owner, lease.JobID, lease.Generation); err != nil {
				return mediasource.LocalAuthority{}, err
			}
			canceled = true
		}
		return f.ownerResolve(ctx, binding)
	}
	result, err := Run(t.Context(), 7, f.store, f.coord, lease, resolve, Options{ReadBatch: 1})
	if !errors.Is(err, medialibrary.ErrScanState) || result.Observed != 1 || !canceled || len(f.catalog(t)) != 1 {
		t.Fatalf("cancellation fence: result=%+v canceled=%t err=%v", result, canceled, err)
	}
	job, err := f.store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || job.Status != "canceled" || job.Generation == lease.Generation {
		t.Fatalf("job: %+v %v", job, err)
	}
}

func TestPersistentScanEarlyCancellationOrArchivedBindingTerminatesLease(t *testing.T) {
	for _, mode := range []string{"cancel", "archive"} {
		t.Run(mode, func(t *testing.T) {
			f := newPersistentFixture(t)
			lease := f.claim(t)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			if mode == "cancel" {
				cancel()
			} else if _, err := f.store.ArchiveLibrary(ctx, 7, f.binding.LibraryID, f.binding.LibraryRevision); err != nil {
				t.Fatal(err)
			}
			_, err := Run(ctx, 7, f.store, f.coord, lease, f.ownerResolve, Options{})
			wantErr, wantStatus := error(context.Canceled), "interrupted"
			if mode == "archive" {
				wantErr, wantStatus = medialibrary.ErrArchived, "failed"
			}
			if !errors.Is(err, wantErr) {
				t.Fatalf("early failure: %v", err)
			}
			job, err := f.store.GetScan(t.Context(), 7, lease.JobID)
			if err != nil || job.Status != wantStatus {
				t.Fatalf("lease remained running: %+v %v", job, err)
			}
		})
	}
}

func TestPersistentScanRejectsUnavailableRulesRevision(t *testing.T) {
	f := newPersistentFixture(t)
	job, err := f.store.EnqueueScan(t.Context(), 7, f.binding.Ref(), "future-rules", LocalRulesRevision+1)
	if err != nil {
		t.Fatal(err)
	}
	lease, err := f.coord.ClaimScan(t.Context(), 7, job.ID, job.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Run(t.Context(), 7, f.store, f.coord, *lease, f.ownerResolve, Options{}); !errors.Is(err, ErrScanRulesUnavailable) {
		t.Fatalf("unexpected rules accepted: %v", err)
	}
	job, err = f.store.GetScan(t.Context(), 7, job.ID)
	if err != nil || job.Status != "failed" || f.calls != 0 {
		t.Fatalf("job=%+v source authority calls=%d err=%v", job, f.calls, err)
	}
}

func TestPersistentScanFinishesAfterDurableCheckpointAdvances(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "a.mp4", "A")
	f.write(t, "b.mp4", "B")
	lease := f.claim(t)
	injected := false
	resolve := func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		owner := binding.Source.OwnerUserID
		job, err := f.store.GetScan(ctx, owner, lease.JobID)
		if err != nil {
			return mediasource.LocalAuthority{}, err
		}
		if job.Checkpoint.Processed == 1 && !injected {
			file, err := f.store.GetSourceFile(ctx, owner, lease.Binding.SourceID, job.Checkpoint.LastObjectKey)
			if err != nil {
				return mediasource.LocalAuthority{}, err
			}
			if file.SizeBytes == nil {
				return mediasource.LocalAuthority{}, fs.ErrInvalid
			}
			cp := job.Checkpoint
			cp.Sequence++
			cp.Processed++
			_, err = f.coord.CommitScanObservation(ctx, owner, lease, medialibrary.ScanObservation{
				ObjectKey: file.ObjectKey, Identity: file.IdentityKind, Locator: file.Locator, ExpectedRevision: file.Revision,
				SourceVersion: file.SourceVersion, SizeBytes: *file.SizeBytes, InitialTitle: "A", NFOStatus: string(NFOMissing),
			}, cp)
			if err != nil {
				return mediasource.LocalAuthority{}, err
			}
			injected = true
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		return f.ownerResolve(ctx, binding)
	}
	if _, err := Run(t.Context(), 7, f.store, f.coord, lease, resolve, Options{ReadBatch: 1}); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("injected failure: %v", err)
	}
	job, err := f.store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || !injected || job.Status != "failed" || job.Checkpoint.Sequence != 2 {
		t.Fatalf("advanced checkpoint left lease running: injected=%t job=%+v err=%v", injected, job, err)
	}
}

func TestPersistentScanDoesNotWriteTerminalStateAfterCoordinatorLoss(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "a.mp4", "A")
	f.write(t, "b.mp4", "B")
	lease := f.claim(t)
	released := false
	resolve := func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		owner := binding.Source.OwnerUserID
		job, err := f.store.GetScan(ctx, owner, lease.JobID)
		if err != nil {
			return mediasource.LocalAuthority{}, err
		}
		if job.Checkpoint.Processed > 0 && !released {
			if err := f.coord.Release(); err != nil {
				return mediasource.LocalAuthority{}, err
			}
			released = true
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		return f.ownerResolve(ctx, binding)
	}
	_, err := Run(t.Context(), 7, f.store, f.coord, lease, resolve, Options{ReadBatch: 1})
	if !released || !errors.Is(err, medialibrary.ErrCoordinatorUnavailable) {
		t.Fatalf("coordinator loss: released=%t err=%v", released, err)
	}
	job, err := f.store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || job.Status != "running" || job.Generation != lease.Generation {
		t.Fatalf("lost coordinator wrote terminal state: %+v %v", job, err)
	}
	newCoordinator, err := f.store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = newCoordinator.Close() })
	if _, err := newCoordinator.InterruptRunningScans(t.Context()); err != nil {
		t.Fatal(err)
	}
	job, err = f.store.GetScan(t.Context(), 7, lease.JobID)
	if err != nil || job.Status != "interrupted" || job.Generation <= lease.Generation {
		t.Fatalf("new coordinator could not recover: %+v %v", job, err)
	}
}

func TestPersistentScanRejectsStaleCrossBindingObservation(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "z.mp4", "shared object")
	if err := os.Mkdir(filepath.Join(f.root, "media", "nested"), 0o700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"a.mp4", "b.mp4"} {
		if err := os.Link(filepath.Join(f.root, "media", "z.mp4"), filepath.Join(f.root, "media", "nested", name)); err != nil {
			t.Skipf("fixture filesystem cannot create hard links: %v", err)
		}
	}
	source, err := mediasource.NewLocal(string(f.binding.RootLocator), f.resolve)
	if err != nil {
		t.Fatal(err)
	}
	resource, err := source.Open(t.Context(), "z.mp4", false)
	if err != nil {
		t.Fatal(err)
	}
	info, _, err := resource.State()
	key, native := nativeObjectKey(resource.File)
	if closeErr := resource.Close(); err != nil || closeErr != nil {
		t.Fatal(errors.Join(err, closeErr))
	}
	if !native {
		t.Skip("fixture filesystem does not expose native object evidence")
	}
	initial := f.claim(t)
	if _, err := Run(t.Context(), 7, f.store, f.coord, initial, f.ownerResolve, Options{}); err != nil {
		t.Fatal(err)
	}
	secondBinding, err := f.store.BindLocalSource(t.Context(), 7, f.binding.LibraryID, f.binding.LibraryRevision, f.binding.Source.ID, []byte("/media/nested"))
	if err != nil {
		t.Fatal(err)
	}
	authority, err := f.resolve(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	proof, err := CaptureLocalBindingProof(t.Context(), 7, "fixture-config", secondBinding.RootLocator, authority)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.AttestSourceBindingWithOperation(t.Context(), 7, secondBinding.ID, secondBinding.Revision, "attest-second", *proof); err != nil {
		t.Fatal(err)
	}
	secondBinding, err = f.store.GetLibrarySourceByID(t.Context(), 7, secondBinding.ID)
	if err != nil {
		t.Fatal(err)
	}
	first := f.claim(t)
	otherJob, err := f.store.EnqueueScan(t.Context(), 7, secondBinding.Ref(), "other-binding-operation", 1)
	if err != nil {
		t.Fatal(err)
	}
	other, err := f.coord.ClaimScan(t.Context(), 7, otherJob.ID, otherJob.Generation)
	if err != nil {
		t.Fatal(err)
	}
	checkpoint := medialibrary.ScanCheckpoint{Version: 1, Phase: "enumerate"}
	sink := &racingSink{persistentSink: &persistentSink{coord: f.coord, actor: 7, lease: first, checkpoint: &checkpoint}}
	var competingLocator []byte
	sink.afterLookup = func(ctx context.Context, observedKey []byte, revision int64) error {
		file, err := f.store.GetSourceFile(ctx, 7, first.Binding.SourceID, key)
		if err != nil {
			return err
		}
		competingLocator = []byte("/media/nested/a.mp4")
		if string(file.Locator) == string(competingLocator) {
			competingLocator = []byte("/media/nested/b.mp4")
		}
		cp := medialibrary.ScanCheckpoint{Version: 1, Sequence: 1, Processed: 1, LastObjectKey: observedKey, Phase: "enumerate"}
		_, err = f.coord.CommitScanObservation(ctx, 7, *other, medialibrary.ScanObservation{
			ObjectKey: observedKey, Identity: string(IdentityNative), Locator: competingLocator, ExpectedRevision: revision,
			SourceVersion: fmt.Sprintf("v1:%d:%d", info.Size(), info.ModTime().UnixNano()), SizeBytes: info.Size(), InitialTitle: "competing", NFOStatus: string(NFOMissing),
		}, cp)
		return err
	}
	var result Result
	w := walker{source: source, binding: first.Binding, actor: 7, sink: sink, options: Options{ReadBatch: 64, MaxDepth: 64}, result: &result}
	if err := w.video(t.Context(), "z.mp4", "z.mp4"); !errors.Is(err, medialibrary.ErrRevisionConflict) {
		t.Fatalf("stale observation was not rejected: %v", err)
	}
	file, err := f.store.GetSourceFile(t.Context(), 7, first.Binding.SourceID, key)
	if err != nil || string(file.Locator) != string(competingLocator) {
		t.Fatalf("newer cross-binding locator was overwritten: %+v %v", file, err)
	}
}

func fixtureMissingCounts(t *testing.T, store *medialibrary.Store, jobID string) (staged, missing int) {
	t.Helper()
	if err := store.WithTx(t.Context(), func(tx *sql.Tx) error {
		if err := tx.QueryRowContext(t.Context(), "SELECT count(*) FROM scan_missing_candidate WHERE job_id = ?", jobID).Scan(&staged); err != nil {
			return err
		}
		return tx.QueryRowContext(t.Context(), "SELECT count(*) FROM library_file WHERE missing_since IS NOT NULL").Scan(&missing)
	}); err != nil {
		t.Fatal(err)
	}
	return staged, missing
}
