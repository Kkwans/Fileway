package mediascan

import (
	"context"
	"errors"
	"io/fs"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

const (
	scanWorkers      = 2
	scanQueueSize    = 32
	scanRetryLimit   = 3
	scanPollInterval = 2 * time.Second
)

// Scheduler owns at most one catalog coordinator lease and a fixed number of
// workers. SQLite is the only persistent queue; the in-memory queue is merely
// a bounded set of wakeups and may be reconstructed after a process exit.
type Scheduler struct {
	store      *medialibrary.Store
	coord      *medialibrary.ScanCoordinator
	resolve    ResolveOwnerAuthority
	ctx        context.Context
	stop       context.CancelFunc
	queue      chan medialibrary.ScanJob
	wake       chan struct{}
	wg         sync.WaitGroup
	mu         sync.Mutex
	closing    bool
	queued     map[string]context.CancelFunc
	retries    map[string]int
	retryAfter map[string]time.Time
	closeOnce  sync.Once
	closeErr   error
}

// NewScheduler obtains the exclusive catalog coordinator before interrupting
// any old worker. A second process must fail closed and never fence or recover
// another scheduler's jobs. The caller must Close before closing the Store.
func NewScheduler(ctx context.Context, store *medialibrary.Store, resolve ResolveOwnerAuthority) (*Scheduler, error) {
	if store == nil || resolve == nil {
		return nil, fs.ErrInvalid
	}
	coord, err := store.AcquireScanCoordinator(ctx)
	if err != nil {
		return nil, err
	}
	if _, err := coord.InterruptRunningScans(ctx); err != nil {
		_ = coord.Close()
		return nil, err
	}
	workerCtx, cancel := context.WithCancel(context.Background())
	s := &Scheduler{store: store, coord: coord, resolve: resolve, ctx: workerCtx, stop: cancel,
		queue: make(chan medialibrary.ScanJob, scanQueueSize), wake: make(chan struct{}, 1),
		queued: make(map[string]context.CancelFunc), retries: make(map[string]int), retryAfter: make(map[string]time.Time)}
	s.wg.Add(1 + scanWorkers)
	go s.dispatch()
	for range scanWorkers {
		go s.worker()
	}
	s.Wake()
	return s, nil
}

// Ready checks both process state and the coordinator's held catalog identity.
// If the database or lock has changed, stop all work without releasing the
// guard until the workers have actually exited.
func (s *Scheduler) Ready(ctx context.Context) error {
	if s == nil {
		return fs.ErrClosed
	}
	s.mu.Lock()
	closed := s.closing
	s.mu.Unlock()
	if closed || s.ctx.Err() != nil {
		return fs.ErrClosed
	}
	if err := s.coord.Validate(ctx); err != nil {
		if ctx.Err() == nil {
			s.stop()
		}
		return err
	}
	return nil
}

// Wake is non-blocking. A full memory queue is safe: queued jobs are durable
// and the dispatcher will discover them again when a worker frees a slot.
func (s *Scheduler) Wake() {
	if s == nil {
		return
	}
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *Scheduler) CancelWorker(jobID string) {
	if s == nil {
		return
	}
	s.mu.Lock()
	cancel := s.queued[jobID]
	s.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// Public scan mutations are also coordinator-guarded: the same ownership
// token that protects recovery must remain valid through the SQLite commit.
func (s *Scheduler) EnqueueScanByBindingID(ctx context.Context, actor uint, libraryID, bindingID string, expectedRevision int64, operationID string, rulesRevision int64) (*medialibrary.ScanJob, error) {
	if err := s.Ready(ctx); err != nil {
		return nil, err
	}
	return s.coord.EnqueueScanByBindingID(ctx, actor, libraryID, bindingID, expectedRevision, operationID, rulesRevision)
}

func (s *Scheduler) CancelScanWithOperation(ctx context.Context, actor uint, jobID string, expectedGeneration int64, operationID string) (*medialibrary.ScanJob, error) {
	if err := s.Ready(ctx); err != nil {
		return nil, err
	}
	job, err := s.coord.CancelScanWithOperation(ctx, actor, jobID, expectedGeneration, operationID)
	if err == nil {
		s.CancelWorker(jobID)
	}
	return job, err
}

func (s *Scheduler) RetryScanWithOperation(ctx context.Context, actor uint, jobID string, expectedGeneration int64, operationID string) (*medialibrary.ScanJob, error) {
	if err := s.Ready(ctx); err != nil {
		return nil, err
	}
	return s.coord.RetryScanWithOperation(ctx, actor, jobID, expectedGeneration, operationID)
}

// Close first cancels and joins every worker, then releases the cross-process
// lease. It intentionally never releases the lease while a worker remains live.
func (s *Scheduler) Close() error {
	if s == nil {
		return nil
	}
	s.closeOnce.Do(func() {
		s.mu.Lock()
		s.closing = true
		s.mu.Unlock()
		s.stop()
		s.wg.Wait()
		s.closeErr = s.coord.Close()
	})
	return s.closeErr
}

func (s *Scheduler) dispatch() {
	defer s.wg.Done()
	ticker := time.NewTicker(scanPollInterval)
	defer ticker.Stop()
	failures := 0
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-s.wake:
		case <-ticker.C:
		}
		if err := s.Ready(s.ctx); err != nil {
			return
		}
		if err := s.findPending(); err != nil {
			failures++
			if failures >= scanRetryLimit {
				s.stop()
				return
			}
		} else {
			failures = 0
		}
	}
}

func (s *Scheduler) findPending() error {
	after := ""
	for s.ctx.Err() == nil {
		page, err := s.coord.ListPendingScans(s.ctx, after, 100)
		if err != nil {
			return err
		}
		for _, job := range page.Items {
			s.mu.Lock()
			if s.closing || s.ctx.Err() != nil {
				s.mu.Unlock()
				return nil
			}
			if _, exists := s.queued[job.ID]; exists {
				s.mu.Unlock()
				continue
			}
			if time.Now().Before(s.retryAfter[job.ID]) {
				s.mu.Unlock()
				continue
			}
			s.queued[job.ID] = nil
			select {
			case s.queue <- job:
				s.mu.Unlock()
			default:
				delete(s.queued, job.ID)
				s.mu.Unlock()
				return nil
			}
		}
		if page.NextCursor == "" || page.NextCursor == after {
			return nil
		}
		after = page.NextCursor
	}
	return s.ctx.Err()
}

func (s *Scheduler) worker() {
	defer s.wg.Done()
	for {
		select {
		case <-s.ctx.Done():
			return
		case job := <-s.queue:
			s.run(job)
		}
	}
}

func (s *Scheduler) run(candidate medialibrary.ScanJob) {
	ctx, cancel := context.WithCancel(s.ctx)
	s.mu.Lock()
	s.queued[candidate.ID] = cancel
	s.mu.Unlock()
	defer func() {
		cancel()
		s.mu.Lock()
		delete(s.queued, candidate.ID)
		s.mu.Unlock()
		s.Wake()
	}()
	if err := s.Ready(ctx); err != nil {
		return
	}
	job, err := s.store.GetScan(ctx, candidate.OwnerUserID, candidate.ID)
	if err != nil {
		s.retry(candidate, err)
		return
	}
	if job.Status != "queued" && job.Status != "interrupted" {
		s.clearRetries(job.ID)
		return
	}
	if job.RulesRevision != LocalRulesRevision {
		s.failPending(ctx, *job, medialibrary.ScanErrorUnsupportedRules)
		return
	}
	resolve := func(ctx context.Context, owner uint, configurationRef string) (mediasource.LocalAuthority, error) {
		if err := s.Ready(ctx); err != nil {
			return mediasource.LocalAuthority{}, err
		}
		return s.resolve(ctx, owner, configurationRef)
	}
	if _, err := CheckBinding(ctx, s.store, job.OwnerUserID, job.Binding, resolve); err != nil {
		if ctx.Err() != nil {
			return
		}
		switch {
		case errors.Is(err, fs.ErrPermission), errors.Is(err, medialibrary.ErrForbidden), errors.Is(err, medialibrary.ErrArchived), errors.Is(err, medialibrary.ErrRevisionConflict):
			s.failPending(ctx, *job, medialibrary.ScanErrorPermissionDenied)
		case errors.Is(err, fs.ErrNotExist), errors.Is(err, medialibrary.ErrNotFound), errors.Is(err, files.ErrSubtitleScopeUnavailable):
			s.failPending(ctx, *job, medialibrary.ScanErrorRootUnavailable)
		default:
			s.retry(*job, err)
		}
		return
	}
	lease, err := s.coord.ClaimScan(ctx, job.OwnerUserID, job.ID, job.Generation)
	if err != nil {
		if !errors.Is(err, medialibrary.ErrScanState) && !errors.Is(err, medialibrary.ErrRevisionConflict) {
			s.retry(*job, err)
		}
		return
	}
	s.clearRetries(job.ID)
	_, _ = Run(ctx, job.OwnerUserID, s.store, s.coord, *lease, resolve, Options{})
}

func (s *Scheduler) failPending(ctx context.Context, job medialibrary.ScanJob, code string) {
	if _, err := s.coord.FailPendingScan(ctx, job.ID, job.Generation, code); err != nil && ctx.Err() == nil && !errors.Is(err, medialibrary.ErrScanState) {
		s.retry(job, err)
		return
	}
	s.clearRetries(job.ID)
}

func (s *Scheduler) retry(job medialibrary.ScanJob, _ error) {
	if s.ctx.Err() != nil {
		return
	}
	s.mu.Lock()
	if len(s.retries) >= 256 && s.retries[job.ID] == 0 {
		s.mu.Unlock()
		s.stop()
		return
	}
	s.retries[job.ID]++
	count := s.retries[job.ID]
	s.retryAfter[job.ID] = time.Now().Add(time.Duration(1<<min(count-1, 3)) * time.Second)
	s.mu.Unlock()
	if count < scanRetryLimit {
		return
	}
	if _, err := s.coord.FailPendingScan(s.ctx, job.ID, job.Generation, medialibrary.ScanErrorSchedulerUnavailable); err != nil {
		// A database that cannot record its failure must not cause an unbounded
		// retry loop. Stop until the service is explicitly restarted.
		s.stop()
		return
	}
	s.clearRetries(job.ID)
}

func (s *Scheduler) clearRetries(jobID string) {
	s.mu.Lock()
	delete(s.retries, jobID)
	delete(s.retryAfter, jobID)
	s.mu.Unlock()
}
