package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestCoordinatorMutualExclusionAcrossStoresAndRelease(t *testing.T) {
	path := filepath.Join(t.TempDir(), "catalog.db")
	first, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := first.Close(); err != nil {
			t.Error(err)
		}
	}()
	second, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := second.Close(); err != nil {
			t.Error(err)
		}
	}()
	owner, err := first.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := second.AcquireScanCoordinator(t.Context()); !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatal(err)
	}
	if err := owner.ValidateFor(t.Context(), second); !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatal(err)
	}
	if err := first.Close(); !errors.Is(err, ErrCoordinatorActive) {
		t.Fatal(err)
	}
	if err := owner.Release(); err != nil {
		t.Fatal(err)
	}
	if err := owner.Release(); err != nil {
		t.Fatal(err)
	}
	if err := owner.Validate(t.Context()); !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatal(err)
	}
	next, err := second.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := next.Validate(t.Context()); err != nil {
		t.Fatal(err)
	}
	if err := next.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestCatalogIdentityChangeDuringWriteRollsBack(t *testing.T) {
	directory := t.TempDir()
	path, alias := filepath.Join(directory, "catalog.db"), filepath.Join(directory, "hardlink.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	err = store.WithTx(t.Context(), func(tx *sql.Tx) error {
		if err := addLibrary(t.Context(), tx, "not-committed"); err != nil {
			return err
		}
		return os.Link(path, alias)
	})
	if !errors.Is(err, ErrCatalogIdentityChanged) {
		t.Fatal(err)
	}
	if err := os.Remove(alias); err != nil {
		t.Fatal(err)
	}
	if got := tableCount(t, store, "library"); got != 0 {
		t.Fatalf("committed %d rows after identity change", got)
	}
}

func TestCoordinatorLockChangeDuringWriteRollsBack(t *testing.T) {
	store := openTestStore(t)
	coordinator := testCoordinator(t, store)
	alias := filepath.Join(t.TempDir(), "lock-hardlink")
	_, err := coordinatedCall(t.Context(), coordinator, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.WithTx(ctx, func(tx *sql.Tx) error {
			if err := addLibrary(ctx, tx, "not-committed"); err != nil {
				return err
			}
			return os.Link(coordinator.lockPath, alias)
		})
	})
	if !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatal(err)
	}
	if err := os.Remove(alias); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Validate(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := tableCount(t, store, "library"); got != 0 {
		t.Fatalf("committed %d rows after lock change", got)
	}
}

func TestCoordinatorSubprocess(t *testing.T) {
	if path := os.Getenv("FILEWAY_COORDINATOR_TEST_DB"); path != "" {
		store, err := Open(t.Context(), path)
		if err != nil {
			t.Fatal(err)
		}
		defer func() {
			if err := store.Close(); err != nil {
				t.Error(err)
			}
		}()
		coordinator, err := store.AcquireScanCoordinator(t.Context())
		if os.Getenv("FILEWAY_COORDINATOR_TEST_BUSY") == "1" {
			if !errors.Is(err, ErrCoordinatorUnavailable) {
				t.Fatalf("expected busy, got %v", err)
			}
			return
		}
		if err != nil {
			t.Fatal(err)
		}
		if err := coordinator.Release(); err != nil {
			t.Fatal(err)
		}
		return
	}
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	coordinator, err := store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	job, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "process-owner", 1)
	if err != nil {
		t.Fatal(err)
	}
	lease, err := coordinator.ClaimScan(t.Context(), 7, job.ID, job.Generation)
	if err != nil {
		t.Fatal(err)
	}
	runChild := func(childPath, busy string) {
		t.Helper()
		command := exec.Command(os.Args[0], "-test.run=^TestCoordinatorSubprocess$")
		command.Env = append(os.Environ(), "FILEWAY_COORDINATOR_TEST_DB="+childPath, "FILEWAY_COORDINATOR_TEST_BUSY="+busy)
		if output, err := command.CombinedOutput(); err != nil {
			t.Fatalf("child %s: %v\n%s", busy, err, output)
		}
	}
	runChild(store.identity.canonical, "1")
	input := testObservation("process-native", "/videos/one.mkv")
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1)); err != nil {
		t.Fatalf("contender interrupted the active scan: %v", err)
	}
	if _, err := store.FinishScan(t.Context(), 7, *lease, ScanFinish{Status: "failed", ExpectedSequence: 1, ErrorCode: ScanErrorScanFailed}); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Release(); err != nil {
		t.Fatal(err)
	}
	runChild(store.identity.canonical, "0")
}

func TestCoordinatorRejectsDatabaseReplacement(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	coordinator, err := store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := coordinator.Release(); err != nil {
			t.Error(err)
		}
	}()
	if err := os.Rename(path, filepath.Join(directory, "previous.db")); err != nil {
		t.Skipf("operating system prevents renaming the open catalog: %v", err)
	}
	if err := os.WriteFile(path, []byte("replacement"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Validate(t.Context()); !errors.Is(err, ErrCatalogIdentityChanged) {
		t.Fatal(err)
	}
}

func TestCoordinatorRejectsRenamedDatabaseEvenThroughSymlink(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	coordinator, err := store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := coordinator.Release(); err != nil {
			t.Error(err)
		}
	}()
	renamed := filepath.Join(directory, "renamed.db")
	if err := os.Rename(path, renamed); err != nil {
		t.Skipf("operating system prevents renaming the open catalog: %v", err)
	}
	if err := os.Symlink(renamed, path); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}
	if err := coordinator.Validate(t.Context()); !errors.Is(err, ErrCatalogIdentityChanged) {
		t.Fatal(err)
	}
}

func TestCatalogHardLinksRefusedBeforeAndDuringCoordination(t *testing.T) {
	directory := t.TempDir()
	path, alias := filepath.Join(directory, "catalog.db"), filepath.Join(directory, "hardlink.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	if err := os.Link(path, alias); err != nil {
		t.Skipf("hard links unavailable: %v", err)
	}
	if other, err := Open(t.Context(), alias); !errors.Is(err, ErrCatalogHardLinked) {
		if other != nil {
			_ = other.Close()
		}
		t.Fatalf("open hard link: %v", err)
	}
	if _, err := store.AcquireScanCoordinator(t.Context()); !errors.Is(err, ErrCatalogIdentityChanged) {
		t.Fatal(err)
	}
	if err := os.Remove(alias); err != nil {
		t.Fatal(err)
	}
	coordinator, err := store.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Link(path, alias); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Validate(t.Context()); !errors.Is(err, ErrCatalogIdentityChanged) {
		t.Fatal(err)
	}
	if err := coordinator.Release(); err != nil {
		t.Fatal(err)
	}
}

func TestCoordinatorRejectsSymlinkAliasCompetition(t *testing.T) {
	directory := t.TempDir()
	path, alias := filepath.Join(directory, "catalog.db"), filepath.Join(directory, "alias.db")
	first, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := first.Close(); err != nil {
			t.Error(err)
		}
	}()
	if err := os.Symlink(path, alias); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}
	second, err := Open(t.Context(), alias)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := second.Close(); err != nil {
			t.Error(err)
		}
	}()
	coordinator, err := first.AcquireScanCoordinator(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := second.AcquireScanCoordinator(t.Context()); !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatal(err)
	}
	if err := coordinator.Release(); err != nil {
		t.Fatal(err)
	}
}

func TestCoordinatorRefusesSymlinkLockFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	if err := os.Symlink(store.identity.canonical, store.identity.canonical+".scan.lock"); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}
	if coordinator, err := store.AcquireScanCoordinator(t.Context()); err == nil {
		_ = coordinator.Release()
		t.Fatal("symlink lock was accepted")
	}
	if _, err := store.CreateLibrary(t.Context(), 7, "Catalog remains usable"); err != nil {
		t.Fatal(err)
	}
}

func TestSchedulerQueriesAndFailPendingRequireCoordinator(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	job, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "pending", 1)
	if err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	page, err := coordinator.ListPendingScans(t.Context(), "", 10)
	if err != nil || len(page.Items) != 1 || page.Items[0].ID != job.ID {
		t.Fatal(page, err)
	}
	if _, err := coordinator.FailPendingScan(t.Context(), job.ID, job.Generation, "secret /raw/path"); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`CREATE TRIGGER fail_pending_outbox BEFORE INSERT ON outbox WHEN NEW.kind = 'media.scan.failed' BEGIN SELECT RAISE(ABORT,'injected'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := coordinator.FailPendingScan(t.Context(), job.ID, job.Generation, ScanErrorPermissionDenied); err == nil {
		t.Fatal("failed job and outbox were not atomic")
	}
	current, err := store.GetScan(t.Context(), 7, job.ID)
	if err != nil || current.Status != "queued" || current.Generation != job.Generation || current.ErrorCode != "" {
		t.Fatal(current, err)
	}
	if _, err := store.db.Exec("DROP TRIGGER fail_pending_outbox"); err != nil {
		t.Fatal(err)
	}
	failed, err := coordinator.FailPendingScan(t.Context(), job.ID, job.Generation, ScanErrorPermissionDenied)
	if err != nil || failed.Status != "failed" || failed.ErrorCode != ScanErrorPermissionDenied || failed.Generation != job.Generation+1 {
		t.Fatal(failed, err)
	}
	page, err = coordinator.ListPendingScans(t.Context(), "", 10)
	if err != nil || len(page.Items) != 0 {
		t.Fatal(page, err)
	}
	if _, err := store.EnqueueScan(t.Context(), 7, binding.Ref(), "fresh", 1); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Release(); err != nil {
		t.Fatal(err)
	}
	if _, err := coordinator.ListPendingScans(t.Context(), "", 10); !errors.Is(err, ErrCoordinatorUnavailable) {
		t.Fatal(err)
	}
}
