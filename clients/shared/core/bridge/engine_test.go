package bridge

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser-client/core/transport"
)

func TestNativeUploadControlDoesNotCarryBytesOrExposeCredentials(t *testing.T) {
	data := bytes.Repeat([]byte{7, 9, 13}, 600000)
	wire := "/%FF/owned%20%2B%25%23.bin"
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.EscapedPath() != "/nas/api/resources"+wire || r.Header.Get("X-Auth") != "owned.upload.token" || r.Method != "POST" {
			t.Error("bridge lost original upload context")
		}
		body, err := io.ReadAll(r.Body)
		if err != nil || !bytes.Equal(body, data) {
			t.Error("binary upload changed", err)
		}
		w.Write([]byte("200 OK"))
	}))
	defer source.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	opened := command(t, e, Command{Op: "open", BaseURL: source.URL + "/nas", Token: "owned.upload.token"})
	var session string
	if json.Unmarshal(opened["result"], &session) != nil {
		t.Fatal(opened)
	}
	out := command(t, e, Command{Op: "upload_lease", Session: session, WirePath: wire,
		Upload: transport.UploadOptions{Protocol: "resources", Size: int64(len(data)), TransferID: "owned-transfer"}})
	var lease string
	if string(out["ok"]) != "true" || json.Unmarshal(out["result"], &lease) != nil || !bytes.HasPrefix([]byte(lease), []byte("http://127.0.0.1:")) {
		t.Fatal("upload control did not issue a local capability", out)
	}
	response, err := http.Post(lease, "application/octet-stream", bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if response.StatusCode != 200 {
		t.Fatal(response.StatusCode)
	}
	out = command(t, e, Command{Op: "upload_stats", Session: session, URL: lease})
	var stats transport.UploadStatistics
	if json.Unmarshal(out["result"], &stats) != nil || stats.AcceptedOffset != int64(len(data)) || stats.SentBytes != int64(len(data)) {
		t.Fatal(out)
	}
	command(t, e, Command{Op: "close_session", Session: session})
	out = command(t, e, Command{Op: "upload_stats", Session: session, URL: lease})
	if string(out["ok"]) != "false" {
		t.Fatal("closed account still owns an upload", out)
	}
}

func TestPreviewLeaseStreamsAuthenticatedImageAndRevokesWithSession(t *testing.T) {
	var encoded bytes.Buffer
	owned := image.NewRGBA(image.Rect(0, 0, 4, 3))
	owned.Set(1, 1, color.RGBA{R: 240, G: 90, B: 140, A: 255})
	if err := png.Encode(&encoded, owned); err != nil {
		t.Fatal(err)
	}
	wire := "/%D6%D0/movie%252F.mkv"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.EscapedPath() != "/nas/api/preview/thumb"+wire || r.URL.RawQuery != "" || r.Header.Get("X-Auth") != "owned.preview.token" {
			t.Errorf("incorrect preview route or authentication")
			w.WriteHeader(http.StatusForbidden)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write(encoded.Bytes())
	}))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	out := command(t, e, Command{Op: "open", BaseURL: server.URL + "/nas", Token: "owned.preview.token"})
	var sid string
	if err := json.Unmarshal(out["result"], &sid); err != nil {
		t.Fatal(out)
	}
	out = command(t, e, Command{Op: "preview", Session: sid, WirePath: wire})
	var lease string
	if string(out["ok"]) != "true" || json.Unmarshal(out["result"], &lease) != nil {
		t.Fatal(out)
	}
	response, err := http.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || response.StatusCode != 200 || response.Header.Get("Content-Type") != "image/png" || !bytes.Equal(data, encoded.Bytes()) {
		t.Fatal("actual preview bytes not preserved", err)
	}
	command(t, e, Command{Op: "close_session", Session: sid})
	response, err = http.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusGone {
		t.Fatal("old account preview lease survived session closure")
	}
}

func TestConditionalLeaseCarriesSourceVersionInEndpoint(t *testing.T) {
	wire := "/movie.mkv"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.EscapedPath() != "/nas/api/raw/movie.mkv" || r.URL.Query().Get("sourceVersion") != "v1-fixture" {
			t.Fatalf("conditional source version was not carried: %s", r.URL.String())
		}
		http.ServeContent(w, r, "movie.mkv", time.Unix(42, 0), strings.NewReader("fixture"))
	}))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	opened := command(t, e, Command{Op: "open", BaseURL: server.URL + "/nas", Token: "owned.conditional.token"})
	var session string
	if err := json.Unmarshal(opened["result"], &session); err != nil || session == "" {
		t.Fatal(opened)
	}
	out := command(t, e, Command{Op: "lease", Session: session, WirePath: wire, SourceVersion: "v1-fixture"})
	var lease string
	if string(out["ok"]) != "true" || json.Unmarshal(out["result"], &lease) != nil {
		t.Fatal(out)
	}
	response, err := http.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("conditional lease status=%d", response.StatusCode)
	}
}

func command(t *testing.T, e *Engine, c Command) map[string]json.RawMessage {
	t.Helper()
	data, _ := json.Marshal(c)
	out := make(map[string]json.RawMessage)
	if err := json.Unmarshal(e.Call(data), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestControlBoundaryPreservesUTF8AndSessionLifecycle(t *testing.T) {
	var seen string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { seen = r.URL.Path; w.Write([]byte("原生🎬")) }))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	out := command(t, e, Command{Op: "open", BaseURL: server.URL + "/路径", Token: "a.b.c"})
	var sid string
	json.Unmarshal(out["result"], &sid)
	if sid == "" {
		t.Fatal(out)
	}
	out = command(t, e, Command{Op: "request", Session: sid, Method: "GET", Endpoint: "/api/resources/%E7%94%B5%E5%BD%B1"})
	if string(out["ok"]) != "true" || seen != "/路径/api/resources/电影" {
		t.Fatal(out, seen)
	}
	var res struct{ Body string }
	json.Unmarshal(out["result"], &res)
	if res.Body != "原生🎬" {
		t.Fatal(res)
	}
	command(t, e, Command{Op: "close_session", Session: sid})
	out = command(t, e, Command{Op: "lease", Session: sid, Path: "/电影.mkv"})
	if string(out["ok"]) != "false" {
		t.Fatal("closed session reopened", out)
	}
}

func TestInvalidCommandsAreBounded(t *testing.T) {
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	for _, data := range [][]byte{[]byte(`bad`), make([]byte, (1<<20)+1), []byte(`{"op":"unknown"}`)} {
		var out Envelope
		if err := json.Unmarshal(e.Call(data), &out); err != nil || out.OK || out.Error == "" {
			t.Fatal(err, out)
		}
	}
}

func TestCancellationBeforeWorkerDoesNotSendSourceRequest(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true; w.WriteHeader(200) }))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	out := command(t, e, Command{Op: "open", BaseURL: server.URL})
	var sid string
	json.Unmarshal(out["result"], &sid)
	command(t, e, Command{Op: "cancel", RequestID: "cancel-before-worker"})
	out = command(t, e, Command{Op: "request", Session: sid, RequestID: "cancel-before-worker", Method: "GET", Endpoint: "/api/resources/"})
	if string(out["ok"]) != "false" || called {
		t.Fatal("cancelled request contacted source", out)
	}
}
