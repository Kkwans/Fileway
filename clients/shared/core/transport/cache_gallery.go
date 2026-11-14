package transport

import (
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const galleryChunkMarker = "preview-"
const galleryFilePrefix = "fileway-chunk-" + galleryChunkMarker
const galleryMaxEntries = 120

type CacheGalleryItem struct {
	ID    string    `json:"id"`
	Bytes int64     `json:"bytes"`
	Used  time.Time `json:"used"`
}

type CacheGallery struct {
	Items     []CacheGalleryItem `json:"items"`
	Truncated bool               `json:"truncated"`
}

func galleryChunkSize(name string) (int64, bool) {
	parts := strings.Split(name, "-")
	if len(parts) != 4 || parts[0] != "preview" || !cacheHex(parts[1]) || parts[2] != "0" {
		return 0, false
	}
	n, err := strconv.ParseInt(parts[3], 10, 64)
	return n, err == nil && n > 0 && n <= cacheChunkSize && strconv.FormatInt(n, 10) == parts[3]
}

func galleryFileSize(name string) (int64, bool) {
	chunk, ok := strings.CutPrefix(name, "fileway-chunk-")
	if !ok {
		return 0, false
	}
	return galleryChunkSize(chunk)
}

var errGalleryUnavailable = errors.New("cache gallery is unavailable")

func (b *Broker) galleryRoot(owner string) (string, error) {
	if owner == "" {
		return "", errGalleryUnavailable
	}
	b.mu.RLock()
	cache := b.cache
	closed := b.closed
	b.mu.RUnlock()
	if closed || cache == nil {
		return "", errGalleryUnavailable
	}
	cache.mu.Lock()
	config := cache.config
	cache.mu.Unlock()
	if !config.Managed || !galleryDirectory(config.Directory) {
		return "", errGalleryUnavailable
	}
	return filepath.Join(config.Directory, cacheHash(owner)), nil
}

func galleryDirectory(path string) bool {
	info, err := os.Lstat(path)
	return err == nil && info.IsDir() && info.Mode()&os.ModeSymlink == 0
}

// This snapshot never promotes entries or touches their last-used timestamps.
// A concurrent eviction may remove an entry before it is opened.
func (b *Broker) CacheGalleryList(ctx context.Context, owner string) (CacheGallery, error) {
	snapshot := CacheGallery{Items: make([]CacheGalleryItem, 0)}
	root, err := b.galleryRoot(owner)
	if err != nil {
		return snapshot, err
	}
	if _, err := os.Lstat(root); os.IsNotExist(err) {
		return snapshot, nil
	}
	if !galleryDirectory(root) {
		return snapshot, errGalleryUnavailable
	}
	groups, err := os.ReadDir(root)
	if err != nil {
		return snapshot, errGalleryUnavailable
	}
	for _, group := range groups {
		if err := ctx.Err(); err != nil {
			return snapshot, err
		}
		if !cacheHex(group.Name()) || !group.IsDir() || group.Type()&os.ModeSymlink != 0 {
			continue
		}
		children, err := os.ReadDir(filepath.Join(root, group.Name()))
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return snapshot, errGalleryUnavailable
		}
		for _, child := range children {
			if !strings.HasPrefix(child.Name(), galleryFilePrefix) || !child.Type().IsRegular() {
				continue
			}
			n, ok := galleryFileSize(child.Name())
			if !ok {
				continue
			}
			info, err := child.Info()
			if err == nil && info.Mode().IsRegular() && info.Size() == n {
				snapshot.Items = append(snapshot.Items, CacheGalleryItem{group.Name() + "/" + child.Name(), n, info.ModTime()})
			}
		}
	}
	sort.Slice(snapshot.Items, func(i, j int) bool {
		left, right := snapshot.Items[i], snapshot.Items[j]
		if left.Used.Equal(right.Used) {
			return left.ID < right.ID
		}
		return left.Used.After(right.Used)
	})
	if len(snapshot.Items) > galleryMaxEntries {
		snapshot.Items = snapshot.Items[:galleryMaxEntries]
		snapshot.Truncated = true
	}
	return snapshot, nil
}

func (b *Broker) CacheGalleryRead(ctx context.Context, owner, id string) ([]byte, error) {
	group, name, ok := strings.Cut(id, "/")
	n, valid := galleryFileSize(name)
	if !ok || !cacheHex(group) || !valid {
		return nil, errGalleryUnavailable
	}
	root, err := b.galleryRoot(owner)
	if err != nil || !galleryDirectory(root) {
		return nil, errGalleryUnavailable
	}
	dir := filepath.Join(root, group)
	if !galleryDirectory(dir) {
		return nil, errGalleryUnavailable
	}
	path := filepath.Join(dir, name)
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != n {
		return nil, errGalleryUnavailable
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, errGalleryUnavailable
	}
	defer f.Close()
	opened, err := f.Stat()
	if err != nil || !os.SameFile(opened, info) {
		return nil, errGalleryUnavailable
	}
	data, err := io.ReadAll(io.LimitReader(f, n+1))
	if err != nil || int64(len(data)) != n {
		return nil, errGalleryUnavailable
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if mediaType := http.DetectContentType(data); mediaType != "image/png" && mediaType != "image/jpeg" {
		return nil, errGalleryUnavailable
	}
	return data, nil
}
