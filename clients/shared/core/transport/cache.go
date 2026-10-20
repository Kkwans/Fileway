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
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const cacheChunkSize int64 = 2 << 20
const maxPlaybackCache int64 = 10 << 30

type CacheConfig struct {
	Directory string `json:"directory"`
	MaxBytes  int64  `json:"maxBytes"`
	Hours     int    `json:"hours"`
}
type CacheStats struct {
	Bytes     int64 `json:"bytes"`
	Files     int   `json:"files"`
	Protected int   `json:"protected"`
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
	return cacheHash(l.session.base.String() + "\x00" + l.endpoint + "\x00" + l.cacheKey)
}

func (b *Broker) ConfigureCache(config CacheConfig) error {
	if !filepath.IsAbs(config.Directory) || config.MaxBytes < 0 || config.MaxBytes > maxPlaybackCache || config.Hours < 1 || config.Hours > 720 {
		return errors.New("invalid playback cache settings")
	}
	if err := os.MkdirAll(config.Directory, 0700); err != nil {
		return errors.New("cannot prepare playback cache")
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cache == nil {
		b.cache = &mediaCache{}
	}
	b.cache.mu.Lock()
	defer b.cache.mu.Unlock()
	if b.cache.config.Directory != "" && b.cache.config.Directory != config.Directory {
		return errors.New("playback cache directory is immutable")
	}
	b.cache.config = config
	_, err := b.cache.clean(nil, false, true)
	return err
}
func (b *Broker) LeaseCached(id, endpoint, identity string) (string, error) {
	value, err := b.Lease(id, endpoint, true)
	if err != nil {
		return "", err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	key := strings.TrimPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	if l := b.leases[key]; l != nil {
		l.cacheKey = identity
		l.prefetch = make(chan struct{}, 1)
	}
	return value, nil
}
func (b *Broker) cacheState() (*mediaCache, map[string]bool) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	active := make(map[string]bool)
	for _, l := range b.leases {
		if l.media && l.cacheKey != "" && l.ctx.Err() == nil {
			active[l.cacheGroup()] = true
		}
	}
	return b.cache, active
}
func (b *Broker) CleanCache(clear bool) (CacheStats, error) {
	cache, active := b.cacheState()
	if cache == nil {
		return CacheStats{}, nil
	}
	cache.mu.Lock()
	defer cache.mu.Unlock()
	return cache.clean(active, clear, false)
}

type cacheEntry struct {
	path, group string
	size        int64
	used        time.Time
}

func (c *mediaCache) entries() ([]cacheEntry, error) {
	var files []cacheEntry
	err := filepath.WalkDir(c.config.Directory, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		files = append(files, cacheEntry{path: path, group: filepath.Base(filepath.Dir(path)), size: info.Size(), used: info.ModTime()})
		return nil
	})
	return files, err
}

// Capacity eviction uses least recently read chunks. Time/manual cleanup never
// removes a lease's active movie. Data being sent lives in a bounded memory chunk.
func (c *mediaCache) clean(active map[string]bool, clear, capacityOnly bool) (CacheStats, error) {
	files, err := c.entries()
	if err != nil {
		return CacheStats{}, errors.New("cannot read playback cache")
	}
	sort.Slice(files, func(i, j int) bool { return files[i].used.Before(files[j].used) })
	total := int64(0)
	for _, f := range files {
		total += f.size
	}
	stats := CacheStats{}
	for _, f := range files {
		expired := !capacityOnly && (clear || time.Since(f.used) >= time.Duration(c.config.Hours)*time.Hour)
		remove := (expired && !active[f.group]) || (capacityOnly && total > c.config.MaxBytes)
		if remove {
			if err := os.Remove(f.path); err != nil && !os.IsNotExist(err) {
				return stats, errors.New("cannot clear playback cache")
			}
			total -= f.size
			continue
		}
		stats.Bytes += f.size
		stats.Files++
		if active[f.group] {
			stats.Protected++
		}
	}
	c.bytes = stats.Bytes
	return stats, nil
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
	path := filepath.Join(c.config.Directory, group, key+"-"+strconv.FormatInt(n, 10))
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
	defer c.mu.Unlock()
	if int64(len(data)) > c.config.MaxBytes {
		return
	}
	dir := filepath.Join(c.config.Directory, group)
	if os.MkdirAll(dir, 0700) != nil {
		return
	}
	path := filepath.Join(dir, key+"-"+strconv.Itoa(len(data)))
	tmp, err := os.CreateTemp(dir, "partial-")
	if err != nil {
		return
	}
	name := tmp.Name()
	defer os.Remove(name)
	_, err = tmp.Write(data)
	closeErr := tmp.Close()
	if err != nil || closeErr != nil {
		return
	}
	oldSize := int64(0)
	if info, err := os.Stat(path); err == nil {
		oldSize = info.Size()
	}
	if os.Rename(name, path) != nil {
		return
	}
	c.bytes += int64(len(data)) - oldSize
	if c.bytes > c.config.MaxBytes {
		_, _ = c.clean(nil, false, true)
	}
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
	res, err := l.session.do(ctx, http.MethodGet, l.endpoint, nil, headers)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	gotStart, gotEnd, gotTotal, ok := responseRange(res)
	if !ok || res.StatusCode != http.StatusPartialContent || gotStart != start || gotEnd != end || gotTotal != total {
		return nil, errors.New("source range changed or unavailable")
	}
	actual := cacheHash(res.Header.Get("ETag") + "\x00" + res.Header.Get("Last-Modified") + "\x00" + strconv.FormatInt(total, 10))
	if actual != version {
		return nil, errors.New("source identity changed")
	}
	data, err := io.ReadAll(io.LimitReader(measuredBody{ReadCloser: res.Body, stats: &l.telemetry}, n+1))
	if err != nil || int64(len(data)) != n {
		return nil, errors.New("source interrupted")
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	c.write(l.cacheGroup(), key, data)
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
	version := cacheHash(res.Header.Get("ETag") + "\x00" + res.Header.Get("Last-Modified") + "\x00" + strconv.FormatInt(total, 10))
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
		http.Error(w, "media cache source unavailable", http.StatusBadGateway)
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
