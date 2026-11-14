package bridge

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser-client/core/transport"
)

func TestCachedPreviewR022PreservesContainRouteAndSharesRawBlockBudget(t *testing.T) {
	const budget int64 = 1500
	thumbnail := bytes.Repeat([]byte("t"), 1024)
	original := bytes.Repeat([]byte("o"), 1024)
	wire := "/%D6%D0/pic%252F.png"
	var previewChunks atomic.Int64
	var denied atomic.Bool
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Auth") != "r022-image-fixture" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if denied.Load() {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		payload := original
		switch r.URL.EscapedPath() {
		case "/nas/api/preview/thumb" + wire:
			if r.URL.RawQuery != "fit=contain" {
				t.Error("contained thumbnail route changed")
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			payload = thumbnail
			if r.Header.Get("Range") == "bytes=0-1023" {
				previewChunks.Add(1)
			}
		case "/nas/api/raw" + wire:
			if r.URL.RawQuery != "" {
				t.Error("raw original acquired preview parameters")
			}
		default:
			t.Error("opaque image endpoint changed")
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		http.ServeContent(w, r, "fixture.png", time.Unix(1000, 0), bytes.NewReader(payload))
	}))
	t.Cleanup(source.Close)
	e := &Engine{}
	t.Cleanup(func() { _ = e.Call([]byte(`{"op":"shutdown"}`)) })
	root := t.TempDir()
	configured := command(t, e, Command{Op: "cache_configure", CacheConfig: transport.CacheConfig{
		Directory: root, MaxBytes: budget, Hours: 24, Managed: true,
	}})
	if string(configured["ok"]) != "true" {
		t.Fatal(configured)
	}
	opened := command(t, e, Command{Op: "open", BaseURL: source.URL + "/nas", Token: "r022-image-fixture"})
	var session string
	if json.Unmarshal(opened["result"], &session) != nil || session == "" {
		t.Fatal(opened)
	}
	preview := previewR022Lease(t, e, Command{Op: "preview", Session: session, WirePath: wire,
		Scope: "contain", CacheKey: "metadata-thumb-contain512", CacheOwner: "owner-a"})
	raw := previewR022Lease(t, e, Command{Op: "lease", Session: session, WirePath: wire,
		CacheKey: "metadata-original", CacheOwner: "owner-a"})
	previewR022Read(t, preview, http.StatusOK, thumbnail)
	previewR022Read(t, preview, http.StatusOK, thumbnail)
	if previewChunks.Load() != 1 {
		t.Fatalf("thumbnail block was not reused: %d fetches", previewChunks.Load())
	}
	denied.Store(true)
	previewR022Read(t, preview, http.StatusForbidden, nil)
	denied.Store(false)
	previewR022Read(t, raw, http.StatusOK, original)
	usage := command(t, e, Command{Op: "cache_stats"})
	var stats transport.CacheUsageStats
	if string(usage["ok"]) != "true" || json.Unmarshal(usage["result"], &stats) != nil ||
		stats.Bytes != 1024 || stats.Files != 1 || stats.Protected != 1 || stats.ProtectedBytes != 1024 || !stats.ActivePool {
		t.Fatalf("images acquired an independent budget or inaccurate usage: %+v, %v", stats, usage)
	}
	previewR022Read(t, preview, http.StatusOK, thumbnail)
	if previewChunks.Load() != 2 {
		t.Fatalf("raw and thumbnail did not share capacity eviction: %d fetches", previewChunks.Load())
	}
	command(t, e, Command{Op: "close_session", Session: session})
	cleaned := command(t, e, Command{Op: "cache_cleanup_owner", CacheOwner: "owner-a", CacheRoot: root})
	if string(cleaned["ok"]) != "true" {
		t.Fatal(cleaned)
	}
	usage = command(t, e, Command{Op: "cache_stats", CacheOwner: "owner-a", CacheRoot: root})
	if json.Unmarshal(usage["result"], &stats) != nil || stats.Bytes != 0 || stats.Files != 0 {
		t.Fatal("closed image owner retained cache data", usage)
	}
}

func previewR022Lease(t *testing.T, engine *Engine, request Command) string {
	t.Helper()
	response := command(t, engine, request)
	var value string
	if string(response["ok"]) != "true" || json.Unmarshal(response["result"], &value) != nil || value == "" {
		t.Fatal("image lease was not created", response)
	}
	return value
}

func previewR022Read(t *testing.T, address string, status int, expected []byte) {
	t.Helper()
	response, err := (&http.Client{Timeout: 5 * time.Second}).Get(address)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != status || expected != nil && !bytes.Equal(data, expected) {
		t.Fatalf("image stream changed: status=%d, bytes=%d, error=%v", response.StatusCode, len(data), err)
	}
}
