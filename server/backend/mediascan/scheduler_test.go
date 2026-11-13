package mediascan

import (
	"context"
	"errors"
	"io/fs"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

func createSchedulerSource(t *testing.T, fixture *scanFixture, store *medialibrary.Store, libraryID string, revision int64, configuration, root, operation string) *medialibrary.LibrarySource {
	t.Helper()
	authority, err := fixture.resolve(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	proof, err := CaptureLocalBindingProof(t.Context(), 7, configuration, []byte(root), authority)
	if err != nil {
		t.Fatal(err)
	}
	ack, err := store.CreateAttestedLocalSourceWithOperation(t.Context(), 7, libraryID, revision, operation, configuration, []byte(root), *proof)
	if err != nil {
		t.Fatal(err)
	}
	binding, err := store.GetLibrarySourceByID(t.Context(), 7, ack.BindingID)
	if err != nil {
		t.Fatal(err)
	}
	return binding
}

func TestSchedulerExclusiveLeaseShutdownAndRecovery(t *testing.T) {
	fixture := newScanFixture(t)
	fixture.write(t, "film.mp4", "fixture video")
	database := filepath.Join(t.TempDir(), "catalog.db")
	store, err := medialibrary.Open(t.Context(), database)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	library, err := store.CreateLibrary(t.Context(), 7, "Fixture")
	if err != nil {
		t.Fatal(err)
	}
	binding := createSchedulerSource(t, fixture, store, library.ID, library.Revision, "fixture", "/media", "source-fixture")
	otherStore, err := medialibrary.Open(t.Context(), database)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = otherStore.Close() })
	job, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "scheduler-fixture", LocalRulesRevision)
	if err != nil {
		t.Fatal(err)
	}
	jobID := job.ID
	entered := make(chan struct{})
	var enteredOnce sync.Once
	resolve := func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		owner, ref := binding.Source.OwnerUserID, binding.Source.ConfigurationRef
		if owner != 7 || ref != "fixture" {
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		if jobID != "" {
			job, err := store.GetScan(ctx, owner, jobID)
			if err != nil {
				return mediasource.LocalAuthority{}, err
			}
			if job.Status == "running" {
				enteredOnce.Do(func() { close(entered) })
				<-ctx.Done()
				return mediasource.LocalAuthority{}, ctx.Err()
			}
		}
		return fixture.resolve(ctx)
	}
	scheduler, err := NewScheduler(t.Context(), store, resolve)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = scheduler.Close() })
	if second, err := NewScheduler(t.Context(), otherStore, resolve); err == nil {
		_ = second.Close()
		t.Fatal("two schedulers acquired the same catalog")
	}
	scheduler.Wake()
	select {
	case <-entered:
	case <-time.After(10 * time.Second):
		t.Fatal("worker did not claim and open the source")
	}
	if err := scheduler.Close(); err != nil {
		t.Fatal(err)
	}
	interrupted, err := store.GetScan(t.Context(), 7, job.ID)
	if err != nil || interrupted.Status != "interrupted" {
		t.Fatalf("shutdown status=%+v err=%v", interrupted, err)
	}
	resume, err := NewScheduler(t.Context(), otherStore, func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		if binding.Source.OwnerUserID != 7 || binding.Source.ConfigurationRef != "fixture" {
			return mediasource.LocalAuthority{}, fs.ErrPermission
		}
		return fixture.resolve(ctx)
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = resume.Close() })
	completed := waitSchedulerJob(t, otherStore, 7, job.ID, "completed")
	if completed.Generation <= interrupted.Generation || completed.Checkpoint.Processed != 1 {
		t.Fatalf("resume did not use a new generation: %+v", completed)
	}
	page, err := otherStore.ListCatalogItems(t.Context(), 7, library.ID, medialibrary.CatalogListOptions{})
	if err != nil || len(page.Items) != 1 {
		t.Fatalf("recovered catalog: %+v %v", page, err)
	}
}

func TestSchedulerFailsPendingJobWhenAuthorityIsRevoked(t *testing.T) {
	fixture := newScanFixture(t)
	store, err := medialibrary.Open(t.Context(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	library, err := store.CreateLibrary(t.Context(), 7, "Fixture")
	if err != nil {
		t.Fatal(err)
	}
	binding := createSchedulerSource(t, fixture, store, library.ID, library.Revision, "fixture", "/media", "source-fixture")
	job, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "revoked-fixture", LocalRulesRevision)
	if err != nil {
		t.Fatal(err)
	}
	scheduler, err := NewScheduler(t.Context(), store, func(context.Context, *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		return mediasource.LocalAuthority{}, fs.ErrPermission
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = scheduler.Close() })
	failed := waitSchedulerJob(t, store, 7, job.ID, "failed")
	if failed.ErrorCode != medialibrary.ScanErrorPermissionDenied {
		t.Fatalf("safe failure: %+v", failed)
	}
}

func TestSchedulerCancelOneJobKeepsOtherWorkersRunning(t *testing.T) {
	fixture := newScanFixture(t)
	fixture.write(t, "slow.mp4", "fixture")
	fixture.write(t, "fast/fast.mp4", "fixture")
	store, err := medialibrary.Open(t.Context(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	library, err := store.CreateLibrary(t.Context(), 7, "Fixture")
	if err != nil {
		t.Fatal(err)
	}
	slow := createSchedulerSource(t, fixture, store, library.ID, library.Revision, "slow", "/media", "source-slow")
	fast := createSchedulerSource(t, fixture, store, library.ID, slow.LibraryRevision, "fast", "/media/fast", "source-fast")
	slowJob, err := store.EnqueueScan(t.Context(), 7, slow.Ref(), "slow-operation", LocalRulesRevision)
	if err != nil {
		t.Fatal(err)
	}
	fastJob, err := store.EnqueueScan(t.Context(), 7, fast.Ref(), "fast-operation", LocalRulesRevision)
	if err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{})
	var once sync.Once
	scheduler, err := NewScheduler(t.Context(), store, func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		owner, ref := binding.Source.OwnerUserID, binding.Source.ConfigurationRef
		if ref == "slow" {
			job, err := store.GetScan(ctx, owner, slowJob.ID)
			if err != nil {
				return mediasource.LocalAuthority{}, err
			}
			if job.Status == "running" {
				once.Do(func() { close(entered) })
				<-ctx.Done()
				return mediasource.LocalAuthority{}, ctx.Err()
			}
		}
		return fixture.resolve(ctx)
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = scheduler.Close() })
	select {
	case <-entered:
	case <-time.After(10 * time.Second):
		t.Fatal("slow worker did not start")
	}
	current, err := store.GetScan(t.Context(), 7, slowJob.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.CancelScan(t.Context(), 7, slowJob.ID, current.Generation); err != nil {
		t.Fatal(err)
	}
	scheduler.CancelWorker(slowJob.ID)
	_ = waitSchedulerJob(t, store, 7, fastJob.ID, "completed")
	if err := scheduler.Ready(t.Context()); err != nil {
		t.Fatalf("canceling one worker stopped the scheduler: %v", err)
	}
}

func waitSchedulerJob(t *testing.T, store *medialibrary.Store, actor uint, jobID, status string) *medialibrary.ScanJob {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		job, err := store.GetScan(ctx, actor, jobID)
		if err != nil && !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		if err == nil && job.Status == status {
			return job
		}
		select {
		case <-ctx.Done():
			t.Fatalf("wanted %s, last job %+v: %v", status, job, ctx.Err())
		case <-ticker.C:
		}
	}
}
