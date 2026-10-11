package transport

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const cacheChunkSize int64 = 2 << 20
const maxPlaybackCache int64 = 1 << 40
const managedCacheDirectory = "fileway-cache-v1"

type CacheConfig struct {
	Directory string `json:"directory"`
	MaxBytes  int64  `json:"maxBytes"`
	Hours     int    `json:"hours"`
	Managed   bool   `json:"managed,omitempty"`
}
type CacheStats struct {
	Bytes          int64 `json:"bytes"`
	Files          int   `json:"files"`
	Protected      int   `json:"protected"`
	ProtectedBytes int64 `json:"protectedBytes"`
}
type mediaCache struct {
	bytes  int64
	mu     sync.Mutex
	config CacheConfig
}

func cacheHash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
func (l *Lease) cacheGroup() string {
	group := cacheHash(l.session.base.String() + "\x00" + l.endpoint + "\x00" + l.cacheKey)
	if l.cacheManaged {
		return l.cacheOwner + "/" + group
	}
	return group
}

func (b *Broker) ConfigureCache(config CacheConfig) error {
	if !filepath.IsAbs(config.Directory) || config.MaxBytes < 0 || config.MaxBytes > maxPlaybackCache || config.Hours < 1 || config.Hours > 720 {
		return errors.New("invalid playback cache settings")
	}
	config.Directory = filepath.Clean(config.Directory)
	if config.Managed {
		config.Directory = filepath.Join(config.Directory, managedCacheDirectory)
	}
	// A historical-root operation can be waiting inside an uninterruptible OS
	// filesystem call. It must not make configuration wait while holding b.mu.
	if !b.cacheBinding.TryLock() {
		return errors.New("cache root control is busy")
	}
	defer b.cacheBinding.Unlock()
	b.mu.Lock()
	defer b.mu.Unlock()
	cache := b.cache
	if cache == nil {
		cache = &mediaCache{}
	}
	cache.mu.Lock()
	defer cache.mu.Unlock()
	if cache.config.Directory != "" && (cache.config.Directory != config.Directory || cache.config.Managed != config.Managed) {
		return errors.New("playback cache directory is immutable")
	}
	if err := os.MkdirAll(filepath.Dir(config.Directory), 0700); err != nil {
		return errors.New("cannot prepare playback cache")
	}
	if err := ownedCacheDirectory(config.Directory); err != nil {
		return errors.New("cannot prepare playback cache")
	}
	previous := cache.config
	cache.config = config
	if _, err := cache.clean(nil, false, true); err != nil {
		cache.config = previous
		return err
	}
	b.cache = cache
	return nil
}
func (b *Broker) LeaseCached(id, endpoint, identity string) (string, error) {
	return b.LeaseCachedForOwner(id, endpoint, identity, "")
}

// The optional owner is supplied only by a trusted account-aware control client.
// Legacy callers retain their existing identity key behavior in the legacy namespace.
func (b *Broker) LeaseCachedForOwner(id, endpoint, identity, owner string) (string, error) {
	b.mu.RLock()
	configured := b.cache != nil && b.cache.config.Directory != ""
	b.mu.RUnlock()
	if !configured && owner == "" {
		// Legacy callers could obtain a stream before configuring the cache.
		// Leave that stream uncached even if a managed pool is configured later.
		return b.Lease(id, endpoint, true)
	}
	if !configured || identity == "" {
		return "", errors.New("playback cache is not configured")
	}
	value, err := b.Lease(id, endpoint, true)
	if err != nil {
		return "", err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	key := strings.TrimPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	if l := b.leases[key]; l != nil {
		l.cacheKey = identity
		l.cacheOwner = cacheHash(owner)
		l.cacheManaged = b.cache.config.Managed
		l.prefetch = make(chan struct{}, 1)
	} else {
		return "", errors.New("session closed")
	}
	return value, nil
}
func (b *Broker) cacheState() (*mediaCache, map[string]bool) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.cache, b.activeCacheGroupsLocked()
}

// The caller holds b.mu for the complete snapshot/operation boundary.
func (b *Broker) activeCacheGroupsLocked() map[string]bool {
	active := make(map[string]bool)
	for _, l := range b.leases {
		if l.media && l.cacheKey != "" && l.ctx.Err() == nil {
			active[l.cacheGroup()] = true
		}
	}
	return active
}
func (b *Broker) CleanCache(clear bool) (CacheStats, error) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	cache, active := b.cache, b.activeCacheGroupsLocked()
	if cache == nil {
		return CacheStats{}, nil
	}
	cache.mu.Lock()
	defer cache.mu.Unlock()
	return cache.clean(active, clear, false)
}

func (c *mediaCache) chunkSize() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return min(cacheChunkSize, c.config.MaxBytes)
}
func (c *mediaCache) enabled() bool { c.mu.Lock(); defer c.mu.Unlock(); return c.config.MaxBytes > 0 }
func (c *mediaCache) read(group, key string, n int64) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.config.MaxBytes == 0 {
		return nil, false
	}
	if !c.safeGroup(group) {
		return nil, false
	}
	path := c.chunkPath(group, key, n)
	if !regularCacheFile(path) {
		return nil, false
	}
	data, err := os.ReadFile(path)
	if err != nil || int64(len(data)) != n {
		return nil, false
	}
	now := time.Now()
	_ = os.Chtimes(path, now, now)
	return data, true
}
func (c *mediaCache) write(group, key string, data []byte) {
	c.mu.Lock()
	group = c.cacheGroupName(group)
	c.mu.Unlock()
	_ = c.writeChunk(context.Background(), group, cacheChunkKey(key), data)
}

func (c *mediaCache) writeChunk(ctx context.Context, group, key string, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	n := int64(len(data))
	if n == 0 || n > c.config.MaxBytes || n > cacheChunkSize {
		return errors.New("cache write exceeds budget")
	}
	path := c.chunkPath(group, key, n)
	if info, err := os.Lstat(path); err == nil && !info.Mode().IsRegular() {
		return errors.New("cache block is unavailable")
	}
	if regularCacheFile(path) {
		if info, err := os.Stat(path); err == nil && info.Size() == n {
			return nil
		}
	}
	// All writes share this mutex and reserve space by evicting before creating
	// their temporary file. Committed chunks plus temporary bytes never need an
	// extra chunk beyond the configured total data budget.
	if c.bytes > c.config.MaxBytes-n {
		if _, err := c.cleanToLimit(nil, false, true, c.config.MaxBytes-n, ""); err != nil {
			return err
		}
	}
	dir := filepath.Join(c.config.Directory, group)
	if c.config.Managed {
		if err := ownedCacheDirectory(filepath.Dir(dir)); err != nil {
			return errors.New("cannot prepare cache owner")
		}
	}
	if err := ownedCacheDirectory(dir); err != nil {
		return errors.New("cannot prepare cache group")
	}
	prefix := "partial-"
	if c.config.Managed {
		prefix = "fileway-partial-"
	}
	tmp, err := os.CreateTemp(dir, prefix)
	if err != nil {
		return errors.New("cannot create cache block")
	}
	name := tmp.Name()
	defer func() {
		if err := os.Remove(name); err != nil && !os.IsNotExist(err) {
			if info, statErr := os.Lstat(name); statErr == nil && info.Mode().IsRegular() {
				c.bytes += info.Size()
			}
		}
	}()
	_, err = tmp.Write(data)
	closeErr := tmp.Close()
	if err != nil || closeErr != nil {
		return errors.New("cannot write cache block")
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	oldSize := int64(0)
	if info, err := os.Lstat(path); err == nil {
		if !info.Mode().IsRegular() {
			return errors.New("cache block is unavailable")
		}
		oldSize = info.Size()
	}
	if os.Rename(name, path) != nil {
		return errors.New("cannot commit cache block")
	}
	c.bytes += n - oldSize
	return nil
}
func responseRange(res *http.Response) (start, end, total int64, ok bool) {
	if res.StatusCode == http.StatusOK && res.ContentLength > 0 && strings.EqualFold(res.Header.Get("Accept-Ranges"), "bytes") {
		return 0, res.ContentLength - 1, res.ContentLength, true
	}
	if res.StatusCode != http.StatusPartialContent {
		return
	}
	_, err := fmt.Sscanf(res.Header.Get("Content-Range"), "bytes %d-%d/%d", &start, &end, &total)
	ok = err == nil && start >= 0 && end >= start && total > end && (res.ContentLength < 0 || res.ContentLength == end-start+1)
	return
}
func cachedChunk(ctx context.Context, l *Lease, c *mediaCache, version string, start, total int64, validator string, chunkSize int64) ([]byte, error) {
	end := min(start+chunkSize, total) - 1
	n := end - start + 1
	key := version + "-" + strconv.FormatInt(start, 10)
	if data, ok := c.read(l.cacheGroup(), key, n); ok {
		l.telemetry.add(0, int64(len(data)), 0, 0)
		return data, nil
	}
	headers := make(http.Header)
	headers.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
	if validator != "" {
		headers.Set("If-Range", validator)
	}
	previous := l.session.currentToken()
	res, err := l.session.do(ctx, http.MethodGet, l.endpoint, nil, headers)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusUnauthorized || res.StatusCode == http.StatusForbidden {
		return nil, cacheReadError{status: res.StatusCode}
	}
	if res.Header.Get("X-Renew-Token") == "true" && previous != "" {
		if err = l.session.renew(ctx, previous); err != nil {
			if errors.Is(err, errRenewalUnauthorized) {
				return nil, cacheReadError{status: http.StatusUnauthorized}
			}
			return nil, err
		}
	}
	gotStart, gotEnd, gotTotal, ok := responseRange(res)
	if !ok || res.StatusCode != http.StatusPartialContent || gotStart != start || gotEnd != end || gotTotal != total {
		return nil, cacheReadError{status: http.StatusConflict}
	}
	actual := cacheSourceVersion(l, res, total)
	if actual != version {
		return nil, cacheReadError{status: http.StatusConflict}
	}
	data, err := io.ReadAll(io.LimitReader(measuredBody{ReadCloser: res.Body, stats: &l.telemetry}, n+1))
	if err != nil || int64(len(data)) != n {
		return nil, errors.New("source interrupted")
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err := c.writeChunk(ctx, l.cacheGroup(), key, data); err != nil && ctx.Err() == nil {
		l.cacheWriteFailures.Add(1)
	}
	return data, nil
}
func (b *Broker) serveCached(ctx context.Context, w http.ResponseWriter, r *http.Request, res *http.Response, l *Lease) bool {
	c, _ := b.cacheState()
	if c == nil || l.cacheKey == "" || !c.enabled() || r.Method != http.MethodGet || r.Header.Get("If-Range") != "" {
		return false
	}
	start, end, total, ok := responseRange(res)
	if !ok {
		return false
	}
	version := cacheSourceVersion(l, res, total)
	validator := res.Header.Get("ETag")
	if validator == "" || strings.HasPrefix(validator, "W/") {
		validator = res.Header.Get("Last-Modified")
	}
	chunkSize := c.chunkSize()
	if chunkSize <= 0 {
		return false
	}
	first := start / chunkSize * chunkSize
	data, err := cachedChunk(ctx, l, c, version, first, total, validator, chunkSize)
	if err != nil {
		status := http.StatusBadGateway
		var sourceError cacheReadError
		if errors.As(err, &sourceError) {
			status = sourceError.status
		}
		http.Error(w, "media cache source unavailable", status)
		return true
	}
	_ = res.Body.Close()
	for _, k := range []string{"Content-Type", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
		if v := res.Header.Get(k); v != "" {
			w.Header().Set(k, v)
		}
	}
	w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(res.StatusCode)
	for offset := first; offset <= end; offset += chunkSize {
		if ctx.Err() != nil {
			return true
		}
		if offset != first {
			data, err = cachedChunk(ctx, l, c, version, offset, total, validator, chunkSize)
			if err != nil {
				return true
			}
		}
		// At most one extra chunk per lease; tied to lease cancellation, never a
		// whole-file download and never video bytes across JNI.
		next := offset + chunkSize
		if next < total && l.prefetch != nil {
			select {
			case l.prefetch <- struct{}{}:
				go func(pos int64) {
					defer func() { <-l.prefetch }()
					prefetchCtx, cancel := context.WithTimeout(l.ctx, 20*time.Second)
					defer cancel()
					_, _ = cachedChunk(prefetchCtx, l, c, version, pos, total, validator, chunkSize)
				}(next)
			default:
			}
		}
		lo := max(start-offset, 0)
		hi := min(end-offset+1, int64(len(data)))
		if hi > lo {
			if _, err = w.Write(data[lo:hi]); err != nil {
				return true
			}
		}
	}
	return true
}
