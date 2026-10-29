package fbhttp

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Cache hits bypass extraction admission. Cold requests share work by identity;
// fonts have a separate lane so an entire-file subtitle scan cannot block them.
// Both lanes reserve disk capacity under mu before creating an output file.
type mediaAssetService struct {
	directory string
	lane      chan struct{}
	fontLane  chan struct{}
	mu        sync.Mutex
	pending   map[string]*mediaAssetFlight
	reserved  int64
}

type mediaAssetFlight struct {
	done      chan struct{}
	cancel    context.CancelFunc
	waiters   int
	finished  bool
	abandoned bool
	err       error
}

func newMediaAssetService(directory string) *mediaAssetService {
	return &mediaAssetService{
		directory: directory,
		lane:      make(chan struct{}, 1),
		fontLane:  make(chan struct{}, 1),
		pending:   make(map[string]*mediaAssetFlight),
	}
}

func (service *mediaAssetService) cached(key string, limit int64) (*os.File, error) {
	name := filepath.Join(service.directory, key)
	info, err := os.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > limit {
		return nil, nil
	}
	file, err := os.Open(name)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil // Eviction won the race; prepare a fresh resource.
	}
	if err == nil {
		_ = os.Chtimes(name, time.Now(), time.Now())
	}
	return file, err
}

func (service *mediaAssetService) prepare(ctx context.Context, key string, limit int64, run func(context.Context, io.Writer) error) (*os.File, error) {
	return service.prepareInLane(ctx, key, limit, service.lane, run)
}

func (service *mediaAssetService) prepareFont(ctx context.Context, key string, limit int64, run func(context.Context, io.Writer) error) (*os.File, error) {
	return service.prepareInLane(ctx, key, limit, service.fontLane, run)
}

func (service *mediaAssetService) prepareInLane(ctx context.Context, key string, limit int64, lane chan struct{}, run func(context.Context, io.Writer) error) (*os.File, error) {
	if !hlsAssetKey(key) || limit <= 0 || limit > sourceAssetCacheLimit {
		return nil, errors.New("invalid source asset request")
	}
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if file, err := service.cached(key, limit); file != nil || err != nil {
			return file, err
		}
		service.mu.Lock()
		flight := service.pending[key]
		if flight == nil {
			// One canceled HTTP reader must not cancel other readers' extraction.
			// The last reader does cancel it, including time waiting for a lane.
			work, cancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Minute)
			flight = &mediaAssetFlight{done: make(chan struct{}), cancel: cancel}
			service.pending[key] = flight
			go service.extract(work, key, limit, lane, flight, run)
		}
		flight.waiters++
		service.mu.Unlock()
		select {
		case <-ctx.Done():
			service.leave(flight)
			return nil, ctx.Err()
		case <-flight.done:
			service.leave(flight)
			if flight.err != nil && !flight.abandoned {
				return nil, flight.err
			}
		}
		// Open our own handle. A canceled previous cohort or concurrent eviction
		// may require another iteration, always bounded by this reader's context.
	}
}

func (service *mediaAssetService) leave(flight *mediaAssetFlight) {
	service.mu.Lock()
	defer service.mu.Unlock()
	flight.waiters--
	if flight.waiters == 0 && !flight.finished {
		flight.abandoned = true
		flight.cancel()
	}
}

func (service *mediaAssetService) extract(ctx context.Context, key string, limit int64, lane chan struct{}, flight *mediaAssetFlight, run func(context.Context, io.Writer) error) {
	defer flight.cancel()
	var err error
	select {
	case lane <- struct{}{}:
		err = service.build(ctx, key, limit, run)
		<-lane
	case <-ctx.Done():
		err = ctx.Err()
	}
	service.mu.Lock()
	flight.err = err
	flight.finished = true
	delete(service.pending, key)
	close(flight.done)
	service.mu.Unlock()
}

func (service *mediaAssetService) build(ctx context.Context, key string, limit int64, run func(context.Context, io.Writer) error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := os.MkdirAll(service.directory, 0o700); err != nil {
		return err
	}
	if file, err := service.cached(key, limit); file != nil || err != nil {
		if file != nil {
			return file.Close()
		}
		return err
	}
	service.mu.Lock()
	err := service.reserve(limit)
	service.mu.Unlock()
	if err != nil {
		return err
	}
	reserved := true
	defer func() {
		if reserved {
			service.mu.Lock()
			service.reserved -= limit
			service.mu.Unlock()
		}
	}()
	file, err := os.CreateTemp(service.directory, ".extract-")
	if err != nil {
		return err
	}
	defer func() { _ = os.Remove(file.Name()) }()
	defer func() { _ = file.Close() }()
	work, cancel := context.WithCancel(ctx)
	defer cancel()
	writer := &boundedAssetWriter{file: file, remaining: limit, cancel: cancel}
	if err := run(work, writer); err != nil {
		return err
	}
	if writer.remaining == limit {
		return errors.New("source asset is empty")
	}
	if err := file.Sync(); err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	// Publish the final file and release its reservation atomically; otherwise
	// the next builder could count both the file and its reservation as used.
	service.reserved -= limit
	reserved = false
	if err := work.Err(); err != nil {
		return err
	}
	return os.Rename(file.Name(), filepath.Join(service.directory, key))
}

// reserve is called under mu. It accounts for both complete files and bounded
// temporary outputs, so allowing fonts alongside subtitles cannot overfill disk.
func (service *mediaAssetService) reserve(limit int64) error {
	entries, err := os.ReadDir(service.directory)
	if err != nil {
		return err
	}
	var cached []os.FileInfo
	var total int64
	for _, entry := range entries {
		if !hlsAssetKey(entry.Name()) {
			continue
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		cached = append(cached, info)
		total += info.Size()
	}
	sort.Slice(cached, func(i, j int) bool { return cached[i].ModTime().Before(cached[j].ModTime()) })
	for _, info := range cached {
		if total+service.reserved+limit <= sourceAssetCacheLimit && time.Since(info.ModTime()) < 24*time.Hour {
			break
		}
		if err := os.Remove(filepath.Join(service.directory, info.Name())); err == nil {
			total -= info.Size()
		}
	}
	if total+service.reserved+limit > sourceAssetCacheLimit {
		return errors.New("source asset cache is busy")
	}
	service.reserved += limit
	return nil
}
