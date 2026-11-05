package transport

import (
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Byte counters describe this lease only, not whole-file cache coverage.
// CurrentRead* describes the latest foreground HTTP response; prefetch contributes
// upstream bytes but cannot replace that response's progress or denominator.
type LeaseStatistics struct {
	UpstreamBytes    int64  `json:"upstreamBytes"`
	CacheReadBytes   int64  `json:"cacheReadBytes"`
	DeliveredBytes   int64  `json:"deliveredBytes"`
	RequestID        uint64 `json:"requestId"`
	CurrentReadBytes int64  `json:"currentReadBytes"`
	CurrentReadTotal int64  `json:"currentReadTotal"`
	Active           bool   `json:"active"`
	ElapsedMillis    int64  `json:"elapsedMillis"`
}

type leaseTelemetry struct {
	mu      sync.Mutex
	started time.Time
	value   LeaseStatistics
}

func (t *leaseTelemetry) begin() uint64 {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.started.IsZero() {
		t.started = time.Now()
	}
	t.value.RequestID++
	t.value.CurrentReadBytes = 0
	t.value.CurrentReadTotal = -1
	t.value.Active = true
	return t.value.RequestID
}
func (t *leaseTelemetry) total(id uint64, n int64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.value.RequestID == id {
		t.value.CurrentReadTotal = n
	}
}
func (t *leaseTelemetry) finish(id uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.value.RequestID == id {
		t.value.Active = false
	}
}
func (t *leaseTelemetry) add(upstream, cached, delivered int64, id uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.value.UpstreamBytes += upstream
	t.value.CacheReadBytes += cached
	t.value.DeliveredBytes += delivered
	if id != 0 && t.value.RequestID == id {
		t.value.CurrentReadBytes += delivered
	}
}
func (t *leaseTelemetry) snapshot() LeaseStatistics {
	t.mu.Lock()
	defer t.mu.Unlock()
	value := t.value
	if !t.started.IsZero() {
		value.ElapsedMillis = time.Since(t.started).Milliseconds()
	}
	return value
}

type measuredBody struct {
	io.ReadCloser
	stats *leaseTelemetry
}

func (r measuredBody) Read(p []byte) (int, error) {
	n, err := r.ReadCloser.Read(p)
	r.stats.add(int64(n), 0, 0, 0)
	return n, err
}

type measuredWriter struct {
	http.ResponseWriter
	stats   *leaseTelemetry
	request uint64
	status  int
}

func (w *measuredWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *measuredWriter) WriteHeader(status int) {
	if w.status == 0 {
		w.status = status
		if status != http.StatusOK && status != http.StatusPartialContent {
			w.stats.total(w.request, -1)
		}
	}
	w.ResponseWriter.WriteHeader(status)
}

func (w *measuredWriter) Write(p []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	n, err := w.ResponseWriter.Write(p)
	if w.status == http.StatusOK || w.status == http.StatusPartialContent {
		w.stats.add(0, 0, int64(n), w.request)
	}
	return n, err
}

func (b *Broker) LeaseStats(session, value string) (LeaseStatistics, error) {
	key, ok := strings.CutPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	if !ok {
		return LeaseStatistics{}, errors.New("unknown stream")
	}
	b.mu.RLock()
	l := b.leases[key]
	s := b.sessions[session]
	valid := l != nil && s != nil && l.session == s && l.ctx.Err() == nil
	b.mu.RUnlock()
	if !valid {
		return LeaseStatistics{}, errors.New("stream closed or belongs to another session")
	}
	return l.telemetry.snapshot(), nil
}
