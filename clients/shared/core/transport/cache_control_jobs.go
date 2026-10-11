package transport

import (
	"context"
	"errors"
	"path/filepath"
	"time"
)

const cacheControlWait = 3 * time.Second

type cacheControlKey struct {
	operation    string
	root         string
	owner        string
	inactiveOnly bool
}

type cacheControlResult struct {
	usage   CacheUsageStats
	cleanup CacheStats
	err     error
}

type cacheControlJob struct {
	key    cacheControlKey
	done   chan struct{}
	result cacheControlResult
}

// There is at most one historical/unknown-root OS operation in flight per broker. A timed-out
// waiter does not forget or duplicate that operation: the same request reuses
// it, and other requests fail promptly. OS I/O itself may remain blocked; the
// bounded wait releases Host business workers without claiming cancellation.
func (b *Broker) runCacheControl(ctx context.Context, key cacheControlKey, operation func() cacheControlResult) cacheControlResult {
	if err := ctx.Err(); err != nil {
		return cacheControlResult{err: err}
	}
	b.mu.RLock()
	closed := b.closed
	b.mu.RUnlock()
	if closed {
		return cacheControlResult{err: errors.New("transport closed")}
	}
	b.cacheJobsMu.Lock()
	job := b.cacheJob
	if job != nil {
		select {
		case <-job.done:
			job = nil
		default:
		}
	}
	if job != nil && job.key != key {
		b.cacheJobsMu.Unlock()
		return cacheControlResult{err: errors.New("cache root control is busy")}
	}
	if job == nil {
		job = &cacheControlJob{key: key, done: make(chan struct{})}
		b.cacheJob = job
		go func() {
			// This guard only fences cache configuration. Network/session APIs do
			// not acquire it, and no Broker mutex is held while waiting on OS I/O
			// for a genuinely historical pool.
			b.cacheBinding.RLock()
			b.mu.RLock()
			current, closed := b.cache, b.closed
			b.mu.RUnlock()
			if closed {
				job.result.err = errors.New("transport closed")
			} else {
				job.result = operation()
				b.mu.RLock()
				unchanged := b.cache == current && !b.closed
				b.mu.RUnlock()
				if !unchanged && job.result.err == nil {
					job.result.err = errors.New("active managed cache identity changed")
				}
			}
			b.cacheBinding.RUnlock()
			close(job.done)
		}()
	}
	b.cacheJobsMu.Unlock()
	timer := time.NewTimer(cacheControlWait)
	defer timer.Stop()
	select {
	case <-job.done:
		return job.result
	case <-ctx.Done():
		return cacheControlResult{err: ctx.Err()}
	case <-timer.C:
		return cacheControlResult{err: errors.New("cache root control is temporarily unavailable")}
	}
}

// Known-current operations do not queue behind a blocked historical disk. They
// retain the current pool's existing OS-I/O boundary and locking semantics.
func (b *Broker) currentCacheControl(ctx context.Context, root string, operation func() cacheControlResult) (cacheControlResult, bool) {
	if err := ctx.Err(); err != nil {
		return cacheControlResult{err: err}, true
	}
	if !b.cacheBinding.TryRLock() {
		return cacheControlResult{err: errors.New("cache root control is busy")}, true
	}
	b.mu.RLock()
	current := b.cache
	knownCurrent := root == "" || current != nil && filepath.IsAbs(root) &&
		sameCachePath(filepath.Join(filepath.Clean(root), managedCacheDirectory), current.config.Directory)
	b.mu.RUnlock()
	if !knownCurrent {
		b.cacheBinding.RUnlock()
		return cacheControlResult{}, false
	}
	result := operation()
	b.cacheBinding.RUnlock()
	return result, true
}
