package transport

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// Bytes/files refer only to recognized regular files in Fileway's managed pool,
// including leftover owned partial files. They exclude unknown files and
// filesystem allocation/metadata overhead. ActivePool supports root-alias
// deduplication without exposing a private filesystem identity or path.
type CacheUsageStats struct {
	CacheStats
	ActivePool bool `json:"activePool"`
}

// CacheStatsForRoot is a read-only snapshot. It never configures a cache,
// creates directories, evicts a block, changes mtime, or updates LRU accounting.
// A specified root is an application cache root, not the managed child path,
// and requires an explicit owner supplied by the client's registered-root ledger.
func (b *Broker) CacheStatsForRoot(root, owner string) (CacheUsageStats, error) {
	return b.CacheStatsForRootContext(context.Background(), root, owner)
}

func (b *Broker) CacheStatsForRootContext(ctx context.Context, root, owner string) (CacheUsageStats, error) {
	operation := func() cacheControlResult {
		usage, err := b.cacheStatsForRoot(root, owner)
		return cacheControlResult{usage: usage, err: err}
	}
	if result, handled := b.currentCacheControl(ctx, root, operation); handled {
		return result.usage, result.err
	}
	result := b.runCacheControl(ctx, cacheControlKey{operation: "stats", root: root, owner: owner}, operation)
	return result.usage, result.err
}

func (b *Broker) cacheStatsForRoot(root, owner string) (CacheUsageStats, error) {
	cache, activePool, err := b.managedCacheForRoot(root, owner)
	if err != nil || cache == nil {
		return CacheUsageStats{}, err
	}
	active := make(map[string]bool)
	if activePool {
		b.mu.RLock()
		active = b.activeCacheGroupsLocked()
		b.mu.RUnlock()
	}
	namespace := ""
	if owner != "" {
		namespace = cacheHash(owner)
	}
	cache.mu.Lock()
	defer cache.mu.Unlock()
	files, err := cache.entriesForOwner(namespace)
	if err != nil {
		return CacheUsageStats{}, errors.New("cannot read managed cache usage")
	}
	stats := CacheUsageStats{ActivePool: activePool}
	for _, file := range files {
		stats.Bytes += file.size
		stats.Files++
		if active[file.group] {
			stats.Protected++
			stats.ProtectedBytes += file.size
		}
	}
	return stats, nil
}

// CleanCacheOwnerInRoot never changes the broker's configured pool. Strict
// logout cleanup rejects an active owner. Inactive-only cleanup preserves every
// active group of that owner. The result keeps the existing cleanup contract:
// remaining totals for the selected pool; callers obtain owner-only usage with
// CacheStatsForRoot after cleanup.
func (b *Broker) CleanCacheOwnerInRoot(owner, root string, inactiveOnly bool) (CacheStats, error) {
	return b.CleanCacheOwnerInRootContext(context.Background(), owner, root, inactiveOnly)
}

func (b *Broker) CleanCacheOwnerInRootContext(ctx context.Context, owner, root string, inactiveOnly bool) (CacheStats, error) {
	operation := func() cacheControlResult {
		cleanup, err := b.cleanCacheOwnerInRoot(owner, root, inactiveOnly)
		return cacheControlResult{cleanup: cleanup, err: err}
	}
	if result, handled := b.currentCacheControl(ctx, root, operation); handled {
		return result.cleanup, result.err
	}
	result := b.runCacheControl(ctx, cacheControlKey{operation: "cleanup", root: root, owner: owner, inactiveOnly: inactiveOnly}, operation)
	return result.cleanup, result.err
}

func (b *Broker) cleanCacheOwnerInRoot(owner, root string, inactiveOnly bool) (CacheStats, error) {
	if owner == "" {
		return CacheStats{}, errors.New("cache owner is required")
	}
	cache, activePool, err := b.managedCacheForRoot(root, owner)
	if err != nil {
		return CacheStats{}, err
	}
	if cache == nil {
		if root != "" {
			return CacheStats{}, nil // A missing historical pool is already empty.
		}
		return CacheStats{}, errors.New("playback cache is not configured")
	}
	active := make(map[string]bool)
	namespace := cacheHash(owner)
	if activePool {
		// Only the real current pool uses the existing complete active-lease
		// protection boundary. Historical pool filesystem calls hold no b.mu.
		b.mu.Lock()
		defer b.mu.Unlock()
		active = b.activeCacheGroupsLocked()
		if !inactiveOnly {
			for _, lease := range b.leases {
				if lease.cacheOwner == namespace && lease.cacheKey != "" && lease.ctx.Err() == nil {
					return CacheStats{}, errors.New("cache owner still has active media")
				}
			}
		}
	}
	cache.mu.Lock()
	defer cache.mu.Unlock()
	return cache.cleanToLimit(active, true, false, cache.config.MaxBytes, namespace)
}

// The caller holds cacheBinding for reading. Capture and recheck the configured
// pool under short Broker locks; filesystem identity checks happen outside them.
// Selecting an external root never installs it on b or creates a directory.
func (b *Broker) managedCacheForRoot(root, owner string) (*mediaCache, bool, error) {
	b.mu.RLock()
	current := b.cache
	closed := b.closed
	b.mu.RUnlock()
	if closed {
		return nil, false, errors.New("transport closed")
	}
	if root == "" {
		if current == nil {
			return nil, false, nil
		}
		if !current.config.Managed {
			return nil, false, errors.New("managed cache control is unavailable")
		}
		return current, true, nil
	}
	if owner == "" || !filepath.IsAbs(root) || strings.IndexByte(root, 0) >= 0 {
		return nil, false, errors.New("invalid registered cache root")
	}
	path := filepath.Join(filepath.Clean(root), managedCacheDirectory)
	if current != nil && sameCachePath(path, current.config.Directory) {
		if !current.config.Managed {
			return nil, false, errors.New("managed cache control is unavailable")
		}
		return current, true, nil
	}
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return nil, false, nil
	}
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, false, errors.New("registered managed cache root is unavailable")
	}
	if current != nil {
		currentInfo, currentErr := os.Lstat(current.config.Directory)
		if currentErr != nil && !os.IsNotExist(currentErr) || currentErr == nil && (!currentInfo.IsDir() || currentInfo.Mode()&os.ModeSymlink != 0) {
			return nil, false, errors.New("active managed cache identity is unavailable")
		}
		if currentErr == nil && os.SameFile(info, currentInfo) {
			if !current.config.Managed {
				return nil, false, errors.New("managed cache control is unavailable")
			}
			return current, true, nil
		}
	}
	b.mu.RLock()
	unchanged := b.cache == current && !b.closed
	b.mu.RUnlock()
	if !unchanged {
		return nil, false, errors.New("active managed cache identity changed")
	}
	return &mediaCache{config: CacheConfig{Directory: path, MaxBytes: maxPlaybackCache, Hours: 720, Managed: true}}, false, nil
}

func sameCachePath(left, right string) bool {
	left, right = filepath.Clean(left), filepath.Clean(right)
	if runtime.GOOS == "windows" {
		return strings.EqualFold(left, right)
	}
	return left == right
}
