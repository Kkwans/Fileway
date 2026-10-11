package transport

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestManagedCacheR022HistoricalControlRetainsSingleFlightAfterWaiterCancellation(t *testing.T) {
	b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 64, Hours: 24, Managed: true})
	key := cacheControlKey{operation: "stats", root: "blocked-fixture-root", owner: "owner-a"}
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	var calls atomic.Int64
	operation := func() cacheControlResult {
		calls.Add(1)
		entered <- struct{}{}
		<-release
		return cacheControlResult{usage: CacheUsageStats{CacheStats: CacheStats{Bytes: 7}}}
	}
	ctx, cancel := context.WithCancel(context.Background())
	first := make(chan cacheControlResult, 1)
	go func() { first <- b.runCacheControl(ctx, key, operation) }()
	managedR021Wait(t, entered, "historical filesystem worker")
	cancel()
	if result := <-first; !errors.Is(result.err, context.Canceled) {
		t.Fatalf("canceled waiter returned %v", result.err)
	}
	different := b.runCacheControl(context.Background(), cacheControlKey{operation: "cleanup", root: "other-root", owner: "owner-b"}, func() cacheControlResult {
		calls.Add(1)
		return cacheControlResult{}
	})
	if different.err == nil || calls.Load() != 1 {
		t.Fatal("different root dispatched another blocked filesystem worker")
	}
	secondContext, secondCancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer secondCancel()
	second := b.runCacheControl(secondContext, key, operation)
	if !errors.Is(second.err, context.DeadlineExceeded) || calls.Load() != 1 {
		t.Fatalf("same root dispatched duplicate pending work: %+v, calls=%d", second, calls.Load())
	}
	b.cacheJobsMu.Lock()
	job := b.cacheJob
	b.cacheJobsMu.Unlock()
	close(release)
	managedR021Wait(t, job.done, "retained historical work completion")
	if result := job.result; result.err != nil || result.usage.Bytes != 7 || calls.Load() != 1 {
		t.Fatalf("pending work result was lost: %+v, calls=%d", result, calls.Load())
	}
}

func TestManagedCacheR022HistoricalWorkerLeavesCurrentServicesResponsive(t *testing.T) {
	root := t.TempDir()
	b := managedR021Broker(t, CacheConfig{Directory: root, MaxBytes: 64, Hours: 24, Managed: true})
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	done := make(chan cacheControlResult, 1)
	go func() {
		done <- b.runCacheControl(context.Background(), cacheControlKey{operation: "stats", root: "blocked-fixture-root", owner: "owner-a"}, func() cacheControlResult {
			entered <- struct{}{}
			<-release
			return cacheControlResult{}
		})
	}()
	managedR021Wait(t, entered, "historical filesystem worker")
	defer func() { close(release); <-done }()
	opened := make(chan error, 1)
	go func() {
		_, err := b.Open("https://example.invalid", "r022-fixture", nil)
		opened <- err
	}()
	select {
	case err := <-opened:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("historical filesystem worker held the Broker mutex")
	}
	stats, err := b.CacheStatsForRoot("", "")
	if err != nil || !stats.ActivePool {
		t.Fatalf("historical filesystem worker blocked current usage: %+v, %v", stats, err)
	}
	if err := b.ConfigureCache(CacheConfig{Directory: root, MaxBytes: 32, Hours: 24, Managed: true}); err == nil {
		t.Fatal("configuration bypassed a pending historical identity fence")
	}
}
