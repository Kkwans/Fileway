package transport

import (
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type cacheEntry struct {
	path, group, owner string
	size               int64
	used               time.Time
	partial            bool
}

func cacheHex(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, ch := range value {
		if !(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f') {
			return false
		}
	}
	return true
}

func cacheChunkKey(value string) string {
	parts := strings.Split(value, "-")
	if len(parts) == 2 && cacheHex(parts[0]) {
		if offset, err := strconv.ParseInt(parts[1], 10, 64); err == nil && offset >= 0 {
			return value
		}
	}
	return cacheHash(value) + "-0"
}

func (c *mediaCache) cacheGroupName(value string) string {
	if !c.config.Managed {
		if cacheHex(value) {
			return value
		}
		return cacheHash(value)
	}
	parts := strings.Split(value, "/")
	if len(parts) == 2 && cacheHex(parts[0]) && cacheHex(parts[1]) {
		return value
	}
	return cacheHash("") + "/" + cacheHash(value)
}

func (c *mediaCache) chunkPath(group, key string, n int64) string {
	name := key + "-" + strconv.FormatInt(n, 10)
	if c.config.Managed {
		name = "fileway-chunk-" + name
	}
	return filepath.Join(c.config.Directory, group, name)
}

func ownedCacheDirectory(path string) error {
	if err := os.Mkdir(path, 0700); err != nil && !os.IsExist(err) {
		return err
	}
	info, err := os.Lstat(path)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("cache directory is unavailable")
	}
	return nil
}

func regularCacheFile(path string) bool {
	info, err := os.Lstat(path)
	return err == nil && info.Mode().IsRegular()
}

func (c *mediaCache) safeGroup(group string) bool {
	parts := strings.Split(group, "/")
	want := 1
	if c.config.Managed {
		want = 2
	}
	if len(parts) != want {
		return false
	}
	path := c.config.Directory
	for _, part := range parts {
		if !cacheHex(part) {
			return false
		}
		path = filepath.Join(path, part)
		info, err := os.Lstat(path)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return false
		}
	}
	return true
}

func ownedCacheFile(name string, managed bool) (owned, partial bool) {
	prefix := "partial-"
	if managed {
		prefix = "fileway-partial-"
	}
	if suffix, found := strings.CutPrefix(name, prefix); found {
		if suffix == "" {
			return false, false
		}
		for _, ch := range suffix {
			if ch < '0' || ch > '9' {
				return false, false
			}
		}
		return true, true
	}
	if managed {
		var found bool
		name, found = strings.CutPrefix(name, "fileway-chunk-")
		if !found {
			return false, false
		}
	}
	parts := strings.Split(name, "-")
	if len(parts) != 3 || !cacheHex(parts[0]) {
		return false, false
	}
	start, startErr := strconv.ParseInt(parts[1], 10, 64)
	n, sizeErr := strconv.ParseInt(parts[2], 10, 64)
	return startErr == nil && start >= 0 && sizeErr == nil && n > 0 && n <= cacheChunkSize, false
}

// Enumerate only the pool's known layout. Never recurse into arbitrary user
// directories, unknown filenames, or filesystem links.
func (c *mediaCache) entries() ([]cacheEntry, error) {
	return c.entriesForOwner("")
}

func (c *mediaCache) entriesForOwner(owner string) ([]cacheEntry, error) {
	var files []cacheEntry
	rootInfo, rootErr := os.Lstat(c.config.Directory)
	if rootErr != nil || !rootInfo.IsDir() || rootInfo.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("cache directory is unavailable")
	}
	appendGroup := func(path, group, owner string) error {
		children, err := os.ReadDir(path)
		if err != nil {
			return err
		}
		for _, child := range children {
			if !child.Type().IsRegular() {
				continue
			}
			owned, partial := ownedCacheFile(child.Name(), c.config.Managed)
			if !owned {
				continue
			}
			info, err := child.Info()
			if err != nil {
				return err
			}
			if info.Mode().IsRegular() {
				files = append(files, cacheEntry{path: filepath.Join(path, child.Name()), group: group,
					owner: owner, size: info.Size(), used: info.ModTime(), partial: partial})
			}
		}
		return nil
	}
	roots, err := os.ReadDir(c.config.Directory)
	if err != nil {
		return nil, err
	}
	for _, root := range roots {
		if !root.IsDir() || root.Type()&os.ModeSymlink != 0 || !cacheHex(root.Name()) {
			continue
		}
		if c.config.Managed && owner != "" && root.Name() != owner {
			continue
		}
		path := filepath.Join(c.config.Directory, root.Name())
		if !c.config.Managed {
			if err := appendGroup(path, root.Name(), ""); err != nil {
				return nil, err
			}
			continue
		}
		groups, err := os.ReadDir(path)
		if err != nil {
			return nil, err
		}
		for _, group := range groups {
			if group.IsDir() && group.Type()&os.ModeSymlink == 0 && cacheHex(group.Name()) {
				if err := appendGroup(filepath.Join(path, group.Name()), root.Name()+"/"+group.Name(), root.Name()); err != nil {
					return nil, err
				}
			}
		}
	}
	return files, nil
}

func (c *mediaCache) clean(active map[string]bool, clear, capacityOnly bool) (CacheStats, error) {
	return c.cleanToLimit(active, clear, capacityOnly, c.config.MaxBytes, "")
}

func (c *mediaCache) cleanToLimit(active map[string]bool, clear, capacityOnly bool, limit int64, owner string) (CacheStats, error) {
	files, err := c.entries()
	if err != nil {
		return CacheStats{}, errors.New("cannot read playback cache")
	}
	sort.Slice(files, func(i, j int) bool { return files[i].used.Before(files[j].used) })
	total := int64(0)
	for _, file := range files {
		total += file.size
	}
	stats := CacheStats{}
	for _, file := range files {
		expired := !capacityOnly && (clear || time.Since(file.used) >= time.Duration(c.config.Hours)*time.Hour)
		selected := owner == "" || file.owner == owner
		remove := selected && ((file.partial || expired) && !active[file.group] || capacityOnly && total > limit)
		if remove {
			if err := os.Remove(file.path); err != nil && !os.IsNotExist(err) {
				return stats, errors.New("cannot clear playback cache")
			}
			total -= file.size
			continue
		}
		stats.Bytes += file.size
		stats.Files++
		if active[file.group] {
			stats.Protected++
			stats.ProtectedBytes += file.size
		}
	}
	c.bytes = stats.Bytes
	return stats, nil
}

func (b *Broker) CleanCacheOwner(owner string) (CacheStats, error) {
	return b.CleanCacheOwnerInRoot(owner, "", false)
}

func cacheSourceVersion(lease *Lease, response *http.Response, total int64) string {
	etag := response.Header.Get("ETag")
	modified := response.Header.Get("Last-Modified")
	namespace := ""
	_, modifiedErr := http.ParseTime(modified)
	strongETag := len(etag) >= 2 && etag[0] == '"' && etag[len(etag)-1] == '"'
	if !strongETag && modifiedErr != nil {
		// Size alone is not a reliable cross-session version.
		namespace = lease.session.cacheNamespace
	}
	return cacheHash(etag + "\x00" + modified + "\x00" + strconv.FormatInt(total, 10) + "\x00" + namespace)
}

type CacheLeaseStatistics struct {
	LeaseStatistics
	CacheWriteFailures int64 `json:"cacheWriteFailures"`
}

type cacheReadError struct{ status int }

func (cacheReadError) Error() string { return "cache source authorization or identity changed" }

func (b *Broker) CacheLeaseStats(session, value string) (CacheLeaseStatistics, error) {
	statistics, err := b.LeaseStats(session, value)
	if err != nil {
		return CacheLeaseStatistics{}, err
	}
	key := strings.TrimPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	b.mu.RLock()
	defer b.mu.RUnlock()
	lease := b.leases[key]
	if lease == nil || lease.session != b.sessions[session] || lease.ctx.Err() != nil {
		return CacheLeaseStatistics{}, errors.New("stream closed or belongs to another session")
	}
	return CacheLeaseStatistics{LeaseStatistics: statistics, CacheWriteFailures: lease.cacheWriteFailures.Load()}, nil
}
