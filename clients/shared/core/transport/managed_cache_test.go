package transport

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestManagedCacheR021CleanupPreservesUnknownLayoutAndOtherOwners(t *testing.T) {
	base := t.TempDir()
	b := managedR021Broker(t, CacheConfig{Directory: base, MaxBytes: 64, Hours: 24, Managed: true})
	pool := filepath.Join(base, managedCacheDirectory)
	groupA := filepath.Join(pool, cacheHash("owner-a"), cacheHash("media-a"))
	groupB := filepath.Join(pool, cacheHash("owner-b"), cacheHash("media-b"))
	chunk := "fileway-chunk-" + cacheHash("version") + "-0-4"
	ownedA := filepath.Join(groupA, chunk)
	ownedB := filepath.Join(groupB, chunk)
	partial := filepath.Join(groupA, "fileway-partial-123456")
	managedR021File(t, ownedA, []byte("aaaa"))
	managedR021File(t, ownedB, []byte("bbbb"))
	managedR021File(t, partial, []byte("pp"))
	unknown := []string{
		filepath.Join(base, "downloads", "keep.mkv"),
		filepath.Join(base, cacheHash("legacy-group"), cacheHash("legacy-version")+"-0-4"),
		filepath.Join(pool, "keep.txt"),
		filepath.Join(pool, cacheHash("owner-a"), "keep.txt"),
		filepath.Join(groupA, "keep.txt"),
		filepath.Join(groupA, cacheHash("version")+"-0-4"),
		filepath.Join(groupA, "fileway-partial-"),
		filepath.Join(groupA, "fileway-partial-123x"),
		filepath.Join(groupA, "fileway-chunk-not-a-hash-0-4"),
		filepath.Join(groupA, "fileway-chunk-"+cacheHash("version")+"--1-4"),
		filepath.Join(groupA, "fileway-chunk-"+cacheHash("version")+"-0-"+strconv.FormatInt(cacheChunkSize+1, 10)),
		filepath.Join(groupA, "nested-user-directory", chunk),
		filepath.Join(pool, "non-owner-directory", cacheHash("media"), chunk),
		filepath.Join(pool, cacheHash("owner-a"), "non-group-directory", chunk),
	}
	for _, path := range unknown {
		managedR021File(t, path, []byte("private fixture"))
	}

	stats, err := b.CleanCacheOwner("owner-a")
	if err != nil || stats.Bytes != 4 || stats.Files != 1 {
		t.Fatalf("owner cleanup returned %+v, %v", stats, err)
	}
	managedR021Absent(t, ownedA)
	managedR021Absent(t, partial)
	managedR021Contents(t, ownedB, []byte("bbbb"))
	for _, path := range unknown {
		managedR021Contents(t, path, []byte("private fixture"))
	}
	if stats, err = b.CleanCache(true); err != nil || stats.Bytes != 0 || stats.Files != 0 {
		t.Fatalf("pool cleanup returned %+v, %v", stats, err)
	}
	for _, path := range unknown {
		managedR021Contents(t, path, []byte("private fixture"))
	}
}

func TestManagedCacheR021OwnerCleanupRejectsActiveOwnerAndNeedsNoSession(t *testing.T) {
	b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 64, Hours: 24, Managed: true})
	idA, valueA := managedR021Lease(t, b, "https://example.invalid", "owner-a")
	_, valueB := managedR021Lease(t, b, "https://example.invalid", "owner-b")
	leaseA := managedR021LookupLease(t, b, valueA)
	leaseB := managedR021LookupLease(t, b, valueB)
	key := cacheHash("version") + "-0"
	for _, lease := range []*Lease{leaseA, leaseB} {
		if err := b.cache.writeChunk(context.Background(), lease.cacheGroup(), key, []byte("data")); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := b.CleanCacheOwner(""); err == nil {
		t.Fatal("empty owner was accepted")
	}
	if _, err := b.CleanCacheOwner("owner-a"); err == nil {
		t.Fatal("owner cleanup accepted an active lease")
	}
	managedR021Contents(t, b.cache.chunkPath(leaseA.cacheGroup(), key, 4), []byte("data"))
	b.CloseSession(idA)
	stats, err := b.CleanCacheOwner("owner-a")
	if err != nil || stats.Bytes != 4 || stats.Files != 1 {
		t.Fatalf("cleanup after session close returned %+v, %v", stats, err)
	}
	managedR021Absent(t, b.cache.chunkPath(leaseA.cacheGroup(), key, 4))
	managedR021Contents(t, b.cache.chunkPath(leaseB.cacheGroup(), key, 4), []byte("data"))
	if stats, err = b.CleanCacheOwner("owner-a"); err != nil || stats.Bytes != 4 {
		t.Fatalf("repeated sessionless cleanup returned %+v, %v", stats, err)
	}
}

func TestManagedCacheR021ConcurrentOwnersShareSmallDataBudget(t *testing.T) {
	const budget int64 = 31
	b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: budget, Hours: 24, Managed: true})
	c := b.cache
	unknown := filepath.Join(c.config.Directory, cacheHash("owner-0"), cacheHash("unknown-group"), "private.bin")
	managedR021File(t, unknown, bytes.Repeat([]byte("u"), 128))
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := c.writeChunk(ctx, cacheHash("cancelled-owner")+"/"+cacheHash("cancelled-group"), cacheHash("version")+"-0", []byte("cancelled")); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled write returned %v", err)
	}

	const writers = 24
	start := make(chan struct{})
	results := make(chan error, writers)
	for i := 0; i < writers; i++ {
		go func(index int) {
			<-start
			group := cacheHash(fmt.Sprintf("owner-%d", index%3)) + "/" + cacheHash(fmt.Sprintf("media-%d", index))
			key := cacheHash("version") + "-" + strconv.Itoa(index)
			data := bytes.Repeat([]byte{byte(index)}, 7+index%5)
			if err := c.writeChunk(context.Background(), group, key, data); err != nil {
				results <- err
				return
			}
			// Inspect a stable filesystem snapshot while other writers wait. Count
			// every controlled data file, including any partial that survived a write.
			c.mu.Lock()
			entries, err := c.entries()
			var total int64
			for _, entry := range entries {
				total += entry.size
				if entry.partial && err == nil {
					err = errors.New("completed write left a partial file")
				}
			}
			if err == nil && (total > budget || c.bytes != total) {
				err = fmt.Errorf("controlled bytes=%d, tracked bytes=%d, pool budget=%d", total, c.bytes, budget)
			}
			c.mu.Unlock()
			results <- err
		}(i)
	}
	close(start)
	for i := 0; i < writers; i++ {
		if err := <-results; err != nil {
			t.Error(err)
		}
	}
	stats, err := b.CleanCache(false)
	if err != nil || stats.Bytes <= 0 || stats.Bytes > budget {
		t.Fatalf("concurrent pool returned %+v, %v", stats, err)
	}
	managedR021Contents(t, unknown, bytes.Repeat([]byte("u"), 128))
}

func TestManagedCacheR021StartupReclaimsPartialsAndCapacityIsPoolWide(t *testing.T) {
	base := t.TempDir()
	pool := filepath.Join(base, managedCacheDirectory)
	groupA := filepath.Join(pool, cacheHash("owner-a"), cacheHash("media-a"))
	groupB := filepath.Join(pool, cacheHash("owner-b"), cacheHash("media-b"))
	chunkA := filepath.Join(groupA, "fileway-chunk-"+cacheHash("version")+"-0-12")
	chunkB := filepath.Join(groupB, "fileway-chunk-"+cacheHash("version")+"-0-10")
	partial := filepath.Join(groupA, "fileway-partial-123")
	unknown := filepath.Join(groupB, "fileway-partial-user")
	managedR021File(t, chunkA, bytes.Repeat([]byte("a"), 12))
	managedR021File(t, chunkB, bytes.Repeat([]byte("b"), 10))
	managedR021File(t, partial, bytes.Repeat([]byte("p"), 8))
	managedR021File(t, unknown, bytes.Repeat([]byte("u"), 64))
	for path, age := range map[string]time.Duration{partial: 4 * time.Hour, chunkA: 3 * time.Hour, chunkB: 2 * time.Hour} {
		used := time.Now().Add(-age)
		if err := os.Chtimes(path, used, used); err != nil {
			t.Fatal(err)
		}
	}
	b := managedR021Broker(t, CacheConfig{Directory: base, MaxBytes: 22, Hours: 24, Managed: true})
	managedR021Absent(t, partial)
	stats, err := b.CleanCache(false)
	if err != nil || stats.Bytes != 22 || stats.Files != 2 {
		t.Fatalf("startup accounting returned %+v, %v", stats, err)
	}
	if err = b.ConfigureCache(CacheConfig{Directory: base, MaxBytes: 11, Hours: 24, Managed: true}); err != nil {
		t.Fatal(err)
	}
	stats, err = b.CleanCache(false)
	if err != nil || stats.Bytes != 10 || stats.Files != 1 {
		t.Fatalf("reduced pool budget returned %+v, %v", stats, err)
	}
	managedR021Absent(t, chunkA)
	managedR021Contents(t, chunkB, bytes.Repeat([]byte("b"), 10))
	managedR021Contents(t, unknown, bytes.Repeat([]byte("u"), 64))
	if err = b.ConfigureCache(CacheConfig{Directory: t.TempDir(), MaxBytes: 11, Hours: 24, Managed: true}); err == nil {
		t.Fatal("active pool silently switched to a different directory")
	}
	managedR021Contents(t, chunkB, bytes.Repeat([]byte("b"), 10))
}

func TestManagedCacheR021LegacyConfigurationAndUnconfiguredLeaseRemainCompatible(t *testing.T) {
	payload := []byte("0123456789abcdefghijklmnopqrstuv")
	t.Run("omitted managed retains legacy layout", func(t *testing.T) {
		source, _, chunks := managedR021Source(t, payload, "", time.Unix(1000, 0))
		directory := t.TempDir()
		encoded, err := json.Marshal(map[string]any{"directory": directory, "maxBytes": 64, "hours": 24})
		if err != nil {
			t.Fatal(err)
		}
		var config CacheConfig
		if err = json.Unmarshal(encoded, &config); err != nil || config.Managed {
			t.Fatalf("legacy configuration changed: %+v, %v", config, err)
		}
		b := managedR021Broker(t, config)
		id, err := b.Open(source.URL, "r021-fixture-token", nil)
		if err != nil {
			t.Fatal(err)
		}
		value, err := b.LeaseCached(id, "/api/raw/video.mkv", "same-file-identity")
		if err != nil {
			t.Fatal(err)
		}
		response := managedR021Request(t, value)
		managedR021Range(t, response, payload)
		entries, err := b.cache.entries()
		if err != nil || len(entries) != 1 || chunks.Load() != 1 {
			t.Fatalf("legacy cache entries=%v, chunks=%d, error=%v", entries, chunks.Load(), err)
		}
		entry := entries[0]
		if filepath.Dir(filepath.Dir(entry.path)) != directory || strings.HasPrefix(filepath.Base(entry.path), "fileway-") {
			t.Fatalf("legacy directory or block naming changed: %s", entry.path)
		}
		managedR021Absent(t, filepath.Join(directory, managedCacheDirectory))
		if _, err = b.CleanCacheOwner("owner-a"); err == nil {
			t.Fatal("managed owner cleanup was enabled for a legacy pool")
		}
	})
	t.Run("lease before configuration remains ordinary stream", func(t *testing.T) {
		source, attempts, chunks := managedR021Source(t, payload, "", time.Unix(1000, 0))
		b, err := New()
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = b.Close() })
		id, err := b.Open(source.URL, "r021-fixture-token", nil)
		if err != nil {
			t.Fatal(err)
		}
		value, err := b.LeaseCached(id, "/api/raw/video.mkv", "same-file-identity")
		if err != nil {
			t.Fatal(err)
		}
		if err = b.ConfigureCache(CacheConfig{Directory: t.TempDir(), MaxBytes: 64, Hours: 24, Managed: true}); err != nil {
			t.Fatal(err)
		}
		managedR021Range(t, managedR021Request(t, value), payload)
		stats, err := b.CleanCache(false)
		if err != nil || stats.Bytes != 0 || stats.Files != 0 || attempts.Load() != 1 || chunks.Load() != 0 {
			t.Fatalf("ordinary stream became cached: %+v, attempts=%d, chunks=%d, error=%v", stats, attempts.Load(), chunks.Load(), err)
		}
	})
}

func TestManagedCacheR021ReliableValidatorsPermitCrossSessionReuse(t *testing.T) {
	payload := []byte("0123456789abcdefghijklmnopqrstuv")
	for _, test := range []struct {
		name     string
		etag     string
		modified time.Time
	}{
		{name: "strong etag", etag: `"version-one"`},
		{name: "source mtime", modified: time.Unix(1000, 0)},
	} {
		t.Run(test.name, func(t *testing.T) {
			source, attempts, chunks := managedR021Source(t, payload, test.etag, test.modified)
			b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 64, Hours: 24, Managed: true})
			first, value := managedR021Lease(t, b, source.URL, "owner-a")
			managedR021Range(t, managedR021Request(t, value), payload)
			b.CloseSession(first)
			second, value := managedR021Lease(t, b, source.URL, "owner-a")
			managedR021Range(t, managedR021Request(t, value), payload)
			stats, err := b.CacheLeaseStats(second, value)
			if err != nil || stats.CacheReadBytes != int64(len(payload)) || chunks.Load() != 1 || attempts.Load() != 3 {
				t.Fatalf("cross-session reuse returned %+v, attempts=%d, chunks=%d, error=%v", stats, attempts.Load(), chunks.Load(), err)
			}
		})
	}
}

func TestManagedCacheR021UnvalidatedContentCannotCrossSessions(t *testing.T) {
	oldPayload := []byte("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	newPayload := []byte("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
	for _, etag := range []string{"", `W/"unchanged-weak-tag"`} {
		t.Run("etag="+etag, func(t *testing.T) {
			var payload atomic.Value
			payload.Store(oldPayload)
			var attempts, chunks atomic.Int64
			source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				attempts.Add(1)
				if r.Header.Get("X-Auth") != "r021-fixture-token" {
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
				if r.Header.Get("Range") == "bytes=0-31" {
					chunks.Add(1)
				}
				if etag != "" {
					w.Header().Set("ETag", etag)
				}
				http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(payload.Load().([]byte)))
			}))
			t.Cleanup(source.Close)
			b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 128, Hours: 24, Managed: true})
			first, value := managedR021Lease(t, b, source.URL, "owner-a")
			managedR021Range(t, managedR021Request(t, value), oldPayload)
			managedR021Range(t, managedR021Request(t, value), oldPayload)
			if chunks.Load() != 1 {
				t.Fatalf("same-session content was not reused: %d chunks", chunks.Load())
			}
			b.CloseSession(first)
			payload.Store(newPayload)
			second, value := managedR021Lease(t, b, source.URL, "owner-a")
			managedR021Range(t, managedR021Request(t, value), newPayload)
			stats, err := b.CacheLeaseStats(second, value)
			if err != nil || stats.CacheReadBytes != 0 || chunks.Load() != 2 || attempts.Load() != 5 {
				t.Fatalf("unvalidated content crossed sessions: %+v, attempts=%d, chunks=%d, error=%v", stats, attempts.Load(), chunks.Load(), err)
			}
		})
	}
}

func TestManagedCacheR021CacheHitsStillAuthorizeAndRevocationStopsRequests(t *testing.T) {
	payload := []byte("0123456789abcdefghijklmnopqrstuv")
	for _, deniedStatus := range []int{http.StatusUnauthorized, http.StatusForbidden, http.StatusAccepted} {
		t.Run(strconv.Itoa(deniedStatus), func(t *testing.T) {
			var denial atomic.Int64
			var attempts, chunks atomic.Int64
			source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				attempts.Add(1)
				if r.Header.Get("X-Auth") != "r021-fixture-token" {
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
				if status := int(denial.Load()); status != 0 {
					w.WriteHeader(status)
					return
				}
				if r.Header.Get("Range") == "bytes=0-31" {
					chunks.Add(1)
				}
				http.ServeContent(w, r, "video.mkv", time.Unix(1000, 0), bytes.NewReader(payload))
			}))
			t.Cleanup(source.Close)
			b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 64, Hours: 24, Managed: true})
			id, value := managedR021Lease(t, b, source.URL, "owner-a")
			managedR021Range(t, managedR021Request(t, value), payload)
			managedR021Range(t, managedR021Request(t, value), payload)
			before, err := b.CacheLeaseStats(id, value)
			if err != nil || chunks.Load() != 1 || attempts.Load() != 3 || before.CacheReadBytes != int64(len(payload)) {
				t.Fatalf("cache hit skipped upstream authorization: %+v, attempts=%d, chunks=%d, error=%v", before, attempts.Load(), chunks.Load(), err)
			}
			denial.Store(int64(deniedStatus))
			denied := managedR021Request(t, value)
			wantStatus := deniedStatus
			if deniedStatus == http.StatusAccepted {
				wantStatus = http.StatusForbidden
			}
			after, err := b.CacheLeaseStats(id, value)
			if denied.status != wantStatus || bytes.Equal(denied.body, payload[3:8]) || attempts.Load() != 4 || err != nil || after.CacheReadBytes != before.CacheReadBytes {
				t.Fatalf("denied source exposed cached media: status=%d, body=%q, attempts=%d, stats=%+v, error=%v", denied.status, denied.body, attempts.Load(), after, err)
			}
			b.Revoke(value)
			revoked := managedR021Request(t, value)
			if revoked.status != http.StatusGone || attempts.Load() != 4 {
				t.Fatalf("revoked lease reached source: status=%d, attempts=%d", revoked.status, attempts.Load())
			}
			if stats, err := b.CleanCacheOwner("owner-a"); err != nil || stats.Bytes != 0 {
				t.Fatalf("revoked owner cleanup returned %+v, %v", stats, err)
			}
		})
	}
}

func TestManagedCacheR021RevocationCancelsForegroundAndPrefetch(t *testing.T) {
	payload := bytes.Repeat([]byte("01234567"), 8)
	for _, mode := range []string{"foreground", "prefetch"} {
		t.Run(mode, func(t *testing.T) {
			entered := make(chan struct{}, 1)
			cancelled := make(chan struct{}, 1)
			unblock := make(chan struct{})
			blockedRange := "bytes=0-31"
			if mode == "prefetch" {
				blockedRange = "bytes=32-63"
			}
			source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Range") == blockedRange {
					select {
					case entered <- struct{}{}:
					default:
					}
					select {
					case <-r.Context().Done():
						select {
						case cancelled <- struct{}{}:
						default:
						}
					case <-unblock:
					}
					return
				}
				http.ServeContent(w, r, "video.mkv", time.Unix(1000, 0), bytes.NewReader(payload))
			}))
			t.Cleanup(source.Close)
			t.Cleanup(func() { close(unblock) })
			b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 32, Hours: 24, Managed: true})
			_, value := managedR021Lease(t, b, source.URL, "owner-a")
			finished := make(chan managedR021HTTPResponse, 1)
			go func() { finished <- managedR021Read(value) }()
			if mode == "prefetch" {
				select {
				case response := <-finished:
					managedR021Range(t, response, payload)
				case <-time.After(5 * time.Second):
					t.Fatal("foreground response did not finish before prefetch")
				}
			}
			managedR021Wait(t, entered, "source chunk request")
			b.Revoke(value)
			managedR021Wait(t, cancelled, "upstream request cancellation")
			if mode == "foreground" {
				select {
				case response := <-finished:
					if response.status == http.StatusPartialContent || bytes.Equal(response.body, payload[3:8]) {
						t.Fatal("cancelled foreground request delivered cached media")
					}
				case <-time.After(5 * time.Second):
					t.Fatal("cancelled foreground response did not finish")
				}
			}
			if stats, err := b.CleanCacheOwner("owner-a"); err != nil || stats.Bytes != 0 || stats.Files != 0 {
				t.Fatalf("cancelled owner retained cache data: %+v, %v", stats, err)
			}
			if response := managedR021Request(t, value); response.status != http.StatusGone {
				t.Fatalf("revoked lease returned %d", response.status)
			}
		})
	}
}

func TestManagedCacheR021WriteFailureKeepsStreamingAndReportsLeaseFailure(t *testing.T) {
	payload := []byte("0123456789abcdefghijklmnopqrstuv")
	source, _, chunks := managedR021Source(t, payload, "", time.Unix(1000, 0))
	b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 64, Hours: 24, Managed: true})
	blockedOwner := filepath.Join(b.cache.config.Directory, cacheHash("owner-a"))
	managedR021File(t, blockedOwner, []byte("unowned collision"))
	id, value := managedR021Lease(t, b, source.URL, "owner-a")
	for i := 0; i < 2; i++ {
		managedR021Range(t, managedR021Request(t, value), payload)
	}
	stats, err := b.CacheLeaseStats(id, value)
	if err != nil || stats.CacheWriteFailures != 2 || stats.CacheReadBytes != 0 || stats.DeliveredBytes != 10 || chunks.Load() != 2 {
		t.Fatalf("cache write failure interrupted or hid streaming: %+v, chunks=%d, error=%v", stats, chunks.Load(), err)
	}
	encoded, err := json.Marshal(stats)
	if err != nil || !bytes.Contains(encoded, []byte(`"cacheWriteFailures":2`)) {
		t.Fatalf("failure count missing from additive lease telemetry: %s, %v", encoded, err)
	}
	managedR021Contents(t, blockedOwner, []byte("unowned collision"))
	if _, err := b.CacheLeaseStats("another-session", value); err == nil {
		t.Fatal("cache telemetry crossed a session boundary")
	}
	b.Revoke(value)
	if _, err := b.CacheLeaseStats(id, value); err == nil {
		t.Fatal("revoked lease retained accessible telemetry")
	}
}

func managedR021Broker(t *testing.T, config CacheConfig) *Broker {
	t.Helper()
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = b.Close() })
	if err = b.ConfigureCache(config); err != nil {
		t.Fatal(err)
	}
	return b
}

func managedR021Lease(t *testing.T, b *Broker, base, owner string) (string, string) {
	t.Helper()
	id, err := b.Open(base, "r021-fixture-token", nil)
	if err != nil {
		t.Fatal(err)
	}
	value, err := b.LeaseCachedForOwner(id, "/api/raw/video.mkv", "same-file-identity", owner)
	if err != nil {
		t.Fatal(err)
	}
	return id, value
}

func managedR021LookupLease(t *testing.T, b *Broker, value string) *Lease {
	t.Helper()
	key := strings.TrimPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	b.mu.RLock()
	lease := b.leases[key]
	b.mu.RUnlock()
	if lease == nil {
		t.Fatal("test lease is unavailable")
	}
	return lease
}

func managedR021File(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
}

func managedR021Contents(t *testing.T, path string, want []byte) {
	t.Helper()
	got, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("fixture changed at %s: got %q, error=%v", path, got, err)
	}
}

func managedR021Absent(t *testing.T, path string) {
	t.Helper()
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatalf("path was retained or unreadable: %s, error=%v", path, err)
	}
}

func managedR021Source(t *testing.T, payload []byte, etag string, modified time.Time) (*httptest.Server, *atomic.Int64, *atomic.Int64) {
	t.Helper()
	attempts, chunks := &atomic.Int64{}, &atomic.Int64{}
	fullChunk := fmt.Sprintf("bytes=0-%d", len(payload)-1)
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts.Add(1)
		if r.Method != http.MethodGet || r.URL.Path != "/api/raw/video.mkv" || r.Header.Get("X-Auth") != "r021-fixture-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.Header.Get("Range") == fullChunk {
			chunks.Add(1)
		}
		if etag != "" {
			w.Header().Set("ETag", etag)
		}
		http.ServeContent(w, r, "video.mkv", modified, bytes.NewReader(payload))
	}))
	t.Cleanup(source.Close)
	return source, attempts, chunks
}

type managedR021HTTPResponse struct {
	status int
	body   []byte
	header http.Header
	err    error
}

func managedR021Read(value string) managedR021HTTPResponse {
	request, err := http.NewRequest(http.MethodGet, value, nil)
	if err != nil {
		return managedR021HTTPResponse{err: err}
	}
	request.Header.Set("Range", "bytes=3-7")
	response, err := (&http.Client{Timeout: 5 * time.Second}).Do(request)
	if err != nil {
		return managedR021HTTPResponse{err: err}
	}
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	return managedR021HTTPResponse{status: response.StatusCode, body: data, header: response.Header.Clone(), err: err}
}

func managedR021Request(t *testing.T, value string) managedR021HTTPResponse {
	t.Helper()
	response := managedR021Read(value)
	if response.err != nil {
		t.Fatal(response.err)
	}
	return response
}

func managedR021Range(t *testing.T, response managedR021HTTPResponse, payload []byte) {
	t.Helper()
	wantRange := fmt.Sprintf("bytes 3-7/%d", len(payload))
	if response.err != nil || response.status != http.StatusPartialContent || response.header.Get("Content-Range") != wantRange || !bytes.Equal(response.body, payload[3:8]) {
		t.Fatalf("range changed: status=%d, range=%q, body=%q, error=%v", response.status, response.header.Get("Content-Range"), response.body, response.err)
	}
}

func managedR021Wait(t *testing.T, event <-chan struct{}, name string) {
	t.Helper()
	select {
	case <-event:
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %s", name)
	}
}
