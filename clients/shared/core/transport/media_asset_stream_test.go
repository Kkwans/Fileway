package transport

import (
	"bufio"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSourceAssetStreamFlushesSmallFramesAndRevokes(t *testing.T) {
	stopped := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(stopped)
		if r.Header.Get("X-Auth") != "owned.stream.token" || r.URL.Path != "/api/subtitle/owned.mkv" || r.URL.Query().Get("stream") != "1" {
			t.Error("stream lost account or endpoint binding")
			w.WriteHeader(http.StatusForbidden)
			return
		}
		w.Header().Set("Content-Type", "application/x-fileway-media-asset+ndjson; version=1")
		for _, frame := range []string{"{\"type\":\"start\"}\n", "{\"type\":\"pending\"}\n", "{\"type\":\"data\",\"data\":\"UEc=\"}\n"} {
			_, _ = io.WriteString(w, frame)
			_ = http.NewResponseController(w).Flush()
		}
		<-r.Context().Done()
	}))
	defer upstream.Close()
	broker, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer broker.Close()
	session, err := broker.Open(upstream.URL, "owned.stream.token", nil)
	if err != nil {
		t.Fatal(err)
	}
	lease, err := broker.Lease(session, "/api/subtitle/owned.mkv?format=source&stream=1", false)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(lease, "owned.stream.token") {
		t.Fatal("token exposed in URL")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, lease, nil)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	reader := bufio.NewReader(response.Body)
	for _, wanted := range []string{"start", "pending", "data"} {
		line, err := reader.ReadString('\n')
		if err != nil || !strings.Contains(line, wanted) {
			t.Fatalf("small frame buffered or altered: %q / %v", line, err)
		}
	}
	stats, err := broker.LeaseStats(session, lease)
	if err != nil || stats.DeliveredBytes <= 0 {
		t.Fatalf("stream telemetry lost: %+v / %v", stats, err)
	}
	broker.Revoke(lease)
	select {
	case <-stopped:
	case <-ctx.Done():
		t.Fatal("revoked source stream remains active")
	}
}
