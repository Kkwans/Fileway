package fbhttp

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"sync/atomic"
	"time"
)

// join shares the same producer with ordinary prepared-file readers. A canceled
// cohort must drain before another producer can own its cache key.
func (service *mediaAssetService) join(ctx context.Context, key string, limit int64, lane chan struct{}, run func(context.Context, io.Writer) error, pin bool) (*mediaAssetFlight, error) {
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		service.mu.Lock()
		flight := service.pending[key]
		if flight != nil && flight.abandoned {
			done := flight.done
			service.mu.Unlock()
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-done:
				continue
			}
		}
		if flight == nil {
			work, cancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Minute)
			flight = &mediaAssetFlight{done: make(chan struct{}), changed: make(chan struct{}), cancel: cancel}
			service.pending[key] = flight
			go service.extract(work, key, limit, lane, flight, run)
		}
		flight.waiters++
		if pin {
			service.pins[key]++
		}
		service.mu.Unlock()
		return flight, nil
	}
}

// Called only under mu. Readers retain the current channel before unlocking.
func (service *mediaAssetService) changed(flight *mediaAssetFlight) {
	close(flight.changed)
	flight.changed = make(chan struct{})
}

type mediaAssetStream struct {
	service *mediaAssetService
	flight  *mediaAssetFlight
	key     string
	ctx     context.Context
	cancel  context.CancelFunc
	cached  *os.File
	offset  int64
	closed  atomic.Bool
}

func (service *mediaAssetService) openStream(ctx context.Context, key string, limit int64, run func(context.Context, io.Writer) error) (*mediaAssetStream, error) {
	if !hlsAssetKey(key) || limit <= 0 || limit > sourceAssetCacheLimit {
		return nil, errors.New("invalid source asset request")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	work, cancel := context.WithCancel(ctx)
	reader := &mediaAssetStream{service: service, key: key, ctx: work, cancel: cancel}
	if cached, err := service.cached(key, limit); cached != nil || err != nil {
		if err != nil {
			cancel()
			return nil, err
		}
		reader.cached = cached
		return reader, nil
	}
	flight, err := service.join(work, key, limit, service.lane, run, true)
	if err != nil {
		cancel()
		return nil, err
	}
	reader.flight = flight
	return reader, nil
}

func (reader *mediaAssetStream) Read(value []byte) (int, error) {
	if len(value) == 0 {
		return 0, nil
	}
	for {
		if err := reader.ctx.Err(); err != nil {
			return 0, err
		}
		if reader.cached != nil {
			return reader.cached.Read(value)
		}
		service, flight := reader.service, reader.flight
		service.mu.Lock()
		// Failed temporary output is never a successful resource, even if an
		// earlier prefix was usable. Consumers must require a complete frame.
		if flight.finished && flight.err != nil {
			err := flight.err
			service.mu.Unlock()
			return 0, err
		}
		if reader.offset < flight.written && flight.name != "" {
			count := min(int64(len(value)), flight.written-reader.offset)
			// Do not hold a temporary-file handle between reads: on Windows it
			// can prevent the producer's atomic publish/rename. The same mutex
			// protects both the selected path and final-file pinning.
			file, err := os.Open(flight.name)
			if err != nil {
				service.mu.Unlock()
				return 0, err
			}
			n, readErr := file.ReadAt(value[:count], reader.offset)
			closeErr := file.Close()
			service.mu.Unlock()
			reader.offset += int64(n)
			if readErr == nil {
				readErr = closeErr
			}
			return n, readErr
		}
		if flight.finished {
			service.mu.Unlock()
			return 0, io.EOF
		}
		changed := flight.changed
		service.mu.Unlock()
		select {
		case <-reader.ctx.Done():
			return 0, reader.ctx.Err()
		case <-changed:
		}
	}
}

func (reader *mediaAssetStream) Close() error {
	if !reader.closed.CompareAndSwap(false, true) {
		return nil
	}
	reader.cancel()
	if reader.cached != nil {
		return reader.cached.Close()
	}
	reader.service.mu.Lock()
	reader.service.pins[reader.key]--
	if reader.service.pins[reader.key] == 0 {
		delete(reader.service.pins, reader.key)
	}
	reader.service.mu.Unlock()
	reader.service.leave(reader.flight)
	return nil
}

type sourceAssetFrame struct {
	Type     string `json:"type"`
	Identity string `json:"identity,omitempty"`
	Format   string `json:"format,omitempty"`
	Data     []byte `json:"data,omitempty"`
	Bytes    int64  `json:"bytes,omitempty"`
	SHA256   string `json:"sha256,omitempty"`
	Code     string `json:"code,omitempty"`
}

// Heartbeats keep bounded HTTP reads alive during legitimate lane waits and
// gaps between subtitle events. Completion and content integrity are explicit;
// a dropped connection or failed producer cannot masquerade as a complete SUP.
func writeSourceAssetStream(w http.ResponseWriter, r *http.Request, reader io.Reader, identity string, valid func() bool) (int, error) {
	if !valid() {
		return http.StatusConflict, errors.New("媒体版本或读取权限已变化")
	}
	w.Header().Set("Content-Type", "application/x-fileway-media-asset+ndjson; version=1")
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	controller := http.NewResponseController(w)
	encoder := json.NewEncoder(w)
	write := func(frame sourceAssetFrame) error {
		if err := encoder.Encode(frame); err != nil {
			return err
		}
		if err := controller.Flush(); err != nil && !errors.Is(err, http.ErrNotSupported) {
			return err
		}
		return nil
	}
	if err := write(sourceAssetFrame{Type: "start", Identity: identity, Format: "sup"}); err != nil {
		return 0, err
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	type chunk struct {
		data []byte
		err  error
	}
	chunks := make(chan chunk)
	go func() {
		for {
			data := make([]byte, 32<<10)
			n, err := reader.Read(data)
			select {
			case chunks <- chunk{data[:n], err}:
			case <-ctx.Done():
				return
			}
			if err != nil {
				return
			}
		}
	}()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	digest := sha256.New()
	var total int64
	for {
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case <-ticker.C:
			if err := write(sourceAssetFrame{Type: "pending"}); err != nil {
				return 0, err
			}
		case next := <-chunks:
			if len(next.data) > 0 {
				_, _ = digest.Write(next.data)
				total += int64(len(next.data))
				if err := write(sourceAssetFrame{Type: "data", Data: next.data}); err != nil {
					return 0, err
				}
			}
			if next.err != nil {
				if !errors.Is(next.err, io.EOF) || total == 0 {
					return 0, write(sourceAssetFrame{Type: "error", Code: "SOURCE_ASSET_FAILED"})
				}
				if !valid() {
					return 0, write(sourceAssetFrame{Type: "error", Code: "SOURCE_CHANGED"})
				}
				return 0, write(sourceAssetFrame{Type: "complete", Bytes: total, SHA256: hex.EncodeToString(digest.Sum(nil))})
			}
		}
	}
}
