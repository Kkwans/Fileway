package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"
)

// ScanCoordinator is an exclusive, process-independent scheduler capability.
// Stop and join every worker before Release. The persistent sidecar must not
// be unlinked or replaced while a catalog is in use.
type ScanCoordinator struct {
	mu       sync.RWMutex
	store    *Store
	lockFile *os.File
	lockPath string
	released bool
}

func (store *Store) AcquireScanCoordinator(ctx context.Context) (*ScanCoordinator, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.closed || store.identity == nil || store.coordinator != nil {
		return nil, ErrCoordinatorUnavailable
	}
	if err := validateCatalogIdentity(store.identity); err != nil {
		return nil, err
	}
	if err := rejectFileMountAlias(store.identity.canonical, store.identity.key); err != nil {
		return nil, err
	}
	path := store.identity.canonical + ".scan.lock"
	f, err := openCoordinatorFile(path)
	if err != nil {
		return nil, fmt.Errorf("open media scan lock: %w", err)
	}
	if err := verifyCoordinatorFile(path, f); err != nil {
		_ = f.Close()
		return nil, err
	}
	if err := tryLockCoordinatorFile(f); err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("%w: %v", ErrCoordinatorUnavailable, err)
	}
	c := &ScanCoordinator{store: store, lockFile: f, lockPath: path}
	if err := c.validateLocked(ctx); err != nil {
		_ = unlockCoordinatorFile(f)
		_ = f.Close()
		return nil, err
	}
	store.coordinator = c
	return c, nil
}

func verifyCoordinatorFile(path string, file *os.File) error {
	pathInfo, err := os.Lstat(path)
	if err != nil || !pathInfo.Mode().IsRegular() || pathInfo.Mode()&os.ModeSymlink != 0 {
		return ErrCoordinatorUnavailable
	}
	handleInfo, err := file.Stat()
	if err != nil || !os.SameFile(pathInfo, handleInfo) {
		return ErrCoordinatorUnavailable
	}
	_, links, err := inspectCatalogHandle(file)
	if err != nil || links != 1 {
		return ErrCoordinatorUnavailable
	}
	return nil
}

func (c *ScanCoordinator) validateLocked(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if c.released || c.store == nil || c.lockFile == nil {
		return ErrCoordinatorUnavailable
	}
	if err := validateCatalogIdentity(c.store.identity); err != nil {
		return err
	}
	return verifyCoordinatorFile(c.lockPath, c.lockFile)
}

// Validate must also be called by workers before each fresh live source
// resolution; a changed catalog or lock file invalidates the scheduler.
func (c *ScanCoordinator) Validate(ctx context.Context) error {
	if c == nil {
		return ErrCoordinatorUnavailable
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.validateLocked(ctx)
}

// ValidateFor additionally prevents accidentally using a different catalog's
// valid coordinator as authority for this store's workers.
func (c *ScanCoordinator) ValidateFor(ctx context.Context, store *Store) error {
	if c == nil || store == nil {
		return ErrCoordinatorUnavailable
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.store != store {
		return ErrCoordinatorUnavailable
	}
	return c.validateLocked(ctx)
}

type coordinatorContextKey struct{}

// The caller holds c.mu.RLock for the lifetime of a guarded context. This
// lets WithTx recheck the lock before committing without recursively locking.
func coordinatorContext(ctx context.Context, c *ScanCoordinator) context.Context {
	return context.WithValue(ctx, coordinatorContextKey{}, c)
}

func validateTransactionAuthority(ctx context.Context, store *Store) error {
	if c, ok := ctx.Value(coordinatorContextKey{}).(*ScanCoordinator); ok {
		if c.store != store {
			return ErrCoordinatorUnavailable
		}
		return c.validateLocked(ctx)
	}
	if store.identity != nil {
		return validateCatalogIdentity(store.identity)
	}
	return nil
}

func coordinatedCall[T any](ctx context.Context, c *ScanCoordinator, action func(context.Context, *Store) (T, error)) (T, error) {
	var zero T
	if c == nil {
		return zero, ErrCoordinatorUnavailable
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if err := c.validateLocked(ctx); err != nil {
		return zero, err
	}
	return action(coordinatorContext(ctx, c), c.store)
}

func (c *ScanCoordinator) ValidateScanLease(ctx context.Context, actor uint, lease ScanLease) (*LibrarySource, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*LibrarySource, error) {
		return store.ValidateScanLease(ctx, actor, lease)
	})
}

func (c *ScanCoordinator) GetScan(ctx context.Context, actor uint, jobID string) (*ScanJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ScanJob, error) { return store.GetScan(ctx, actor, jobID) })
}

func (c *ScanCoordinator) GetSourceFile(ctx context.Context, actor uint, sourceID string, objectKey []byte) (*SourceFile, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*SourceFile, error) {
		return store.GetSourceFile(ctx, actor, sourceID, objectKey)
	})
}

func (c *ScanCoordinator) GetObservationRevision(ctx context.Context, actor uint, binding SourceBindingRef, objectKey []byte) (FileObservationRevision, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (FileObservationRevision, error) {
		return store.GetObservationRevision(ctx, actor, binding, objectKey)
	})
}

func (c *ScanCoordinator) CommitScanObservation(ctx context.Context, actor uint, lease ScanLease, input ScanObservation, checkpoint ScanCheckpoint) (*ScanCommit, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ScanCommit, error) {
		return store.CommitScanObservation(ctx, actor, lease, input, checkpoint)
	})
}

func (c *ScanCoordinator) CommitScanContext(ctx context.Context, actor uint, lease ScanLease, input DirectoryContextObservation, checkpoint ScanCheckpoint) (*DirectoryContextRef, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*DirectoryContextRef, error) {
		return store.CommitScanContext(ctx, actor, lease, input, checkpoint)
	})
}

func (c *ScanCoordinator) CompleteScanEnumeration(ctx context.Context, actor uint, lease ScanLease, sequence int64) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.CompleteScanEnumeration(ctx, actor, lease, sequence)
	})
	return err
}

func (c *ScanCoordinator) ListScanMissingCandidates(ctx context.Context, actor uint, lease ScanLease, afterID string, limit int) (*ScanMissingPage, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ScanMissingPage, error) {
		return store.ListScanMissingCandidates(ctx, actor, lease, afterID, limit)
	})
}

func (c *ScanCoordinator) CommitScanMissing(ctx context.Context, actor uint, lease ScanLease, candidates []ScanMissingConfirmation, checkpoint ScanCheckpoint) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.CommitScanMissing(ctx, actor, lease, candidates, checkpoint)
	})
	return err
}

func (c *ScanCoordinator) CompleteScanReconciliation(ctx context.Context, actor uint, lease ScanLease, sequence int64) error {
	_, err := coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (struct{}, error) {
		return struct{}{}, store.CompleteScanReconciliation(ctx, actor, lease, sequence)
	})
	return err
}

func (c *ScanCoordinator) FinishScan(ctx context.Context, actor uint, lease ScanLease, finish ScanFinish) (*ScanJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ScanJob, error) {
		return store.FinishScan(ctx, actor, lease, finish)
	})
}

func (c *ScanCoordinator) EnqueueScanByBindingID(ctx context.Context, actor uint, libraryID, bindingID string, expectedBindingRevision int64, operationID string, rulesRevision int64) (*ScanJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ScanJob, error) {
		return store.EnqueueScanByBindingID(ctx, actor, libraryID, bindingID, expectedBindingRevision, operationID, rulesRevision)
	})
}

func (c *ScanCoordinator) EnqueueScanBatch(ctx context.Context, actor uint, libraryID, operationID string, rulesRevision int64, sources []ScanBatchSource) ([]ScanJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) ([]ScanJob, error) {
		return store.EnqueueScanBatch(ctx, actor, libraryID, operationID, rulesRevision, sources)
	})
}

func (c *ScanCoordinator) CancelScanWithOperation(ctx context.Context, actor uint, jobID string, expectedGeneration int64, operationID string) (*ScanJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ScanJob, error) {
		return store.CancelScanWithOperation(ctx, actor, jobID, expectedGeneration, operationID)
	})
}

func (c *ScanCoordinator) RetryScanWithOperation(ctx context.Context, actor uint, jobID string, expectedGeneration int64, operationID string) (*ScanJob, error) {
	return coordinatedCall(ctx, c, func(ctx context.Context, store *Store) (*ScanJob, error) {
		return store.RetryScanWithOperation(ctx, actor, jobID, expectedGeneration, operationID)
	})
}

func (c *ScanCoordinator) Release() error {
	if c == nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.released {
		return nil
	}
	c.released = true
	err := errors.Join(unlockCoordinatorFile(c.lockFile), c.lockFile.Close())
	c.store.mu.Lock()
	if c.store.coordinator == c {
		c.store.coordinator = nil
	}
	c.store.mu.Unlock()
	return err
}

func (c *ScanCoordinator) Close() error { return c.Release() }

func (c *ScanCoordinator) ClaimScan(ctx context.Context, actor uint, jobID string, expectedGeneration int64) (*ScanLease, error) {
	if c == nil {
		return nil, ErrCoordinatorUnavailable
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if err := c.validateLocked(ctx); err != nil {
		return nil, err
	}
	return c.store.claimScan(coordinatorContext(ctx, c), actor, jobID, expectedGeneration)
}

func (c *ScanCoordinator) InterruptRunningScans(ctx context.Context) (int, error) {
	if c == nil {
		return 0, ErrCoordinatorUnavailable
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if err := c.validateLocked(ctx); err != nil {
		return 0, err
	}
	return c.store.interruptRunningScans(coordinatorContext(ctx, c), c.validateLocked)
}

// ListPendingScans is an internal cross-owner scheduler view, never an HTTP
// authorization check. Each returned owner and source needs live resolution.
func (c *ScanCoordinator) ListPendingScans(ctx context.Context, afterID string, limit int) (*ScanPage, error) {
	if c == nil {
		return nil, ErrCoordinatorUnavailable
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if err := c.validateLocked(ctx); err != nil {
		return nil, err
	}
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: scan page limit", ErrInvalid)
	}
	rows, err := c.store.db.QueryContext(ctx, scanSelection+" WHERE sj.active = 1 AND j.status IN ('queued', 'interrupted') AND j.id > ? ORDER BY j.id LIMIT ?", afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &ScanPage{Items: make([]ScanJob, 0, limit)}
	for rows.Next() {
		r, err := readScan(rows)
		if err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		page.Items = append(page.Items, r.ScanJob)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

func validScanErrorCode(code string) bool {
	switch code {
	case ScanErrorOwnerUnavailable, ScanErrorPermissionDenied, ScanErrorRootUnavailable, ScanErrorUnsupportedRules, ScanErrorSchedulerUnavailable, ScanErrorSourceChanged, ScanErrorScanFailed:
		return true
	default:
		return false
	}
}

// FailPendingScan retires a queued/interrupted task only after the scheduler
// identifies a safe reason, without claiming it or bypassing source authority.
func (c *ScanCoordinator) FailPendingScan(ctx context.Context, jobID string, expectedGeneration int64, safeErrorCode string) (*ScanJob, error) {
	if c == nil {
		return nil, ErrCoordinatorUnavailable
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if err := c.validateLocked(ctx); err != nil {
		return nil, err
	}
	if !validScanErrorCode(safeErrorCode) {
		return nil, fmt.Errorf("%w: scan error code", ErrInvalid)
	}
	var result *ScanJob
	err := c.store.WithTx(coordinatorContext(ctx, c), func(tx *sql.Tx) error {
		r, err := readScan(tx.QueryRowContext(ctx, scanSelection+" WHERE j.id = ?", jobID))
		if err != nil {
			return err
		}
		if !r.active || r.Generation != expectedGeneration || (r.Status != "queued" && r.Status != "interrupted") {
			return ErrScanState
		}
		r.Status, r.Generation, r.ErrorCode, r.active = "failed", r.Generation+1, safeErrorCode, false
		r.UpdatedAt = max(time.Now().UnixMilli(), r.UpdatedAt)
		if _, err := tx.ExecContext(ctx, "UPDATE job SET status = ?, generation = ?, updated_at = ? WHERE id = ?", r.Status, r.Generation, r.UpdatedAt, r.ID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE scan_job SET active = 0, error_code = ? WHERE job_id = ?", safeErrorCode, r.ID); err != nil {
			return err
		}
		if err := publishScanChange(ctx, tx, r.OwnerUserID, r, "media.scan.failed"); err != nil {
			return err
		}
		result = &r.ScanJob
		return nil
	})
	if err != nil {
		return nil, err
	}
	return result, nil
}
