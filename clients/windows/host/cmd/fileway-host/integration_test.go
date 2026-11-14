package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/Fileway/clients/windows/host/internal/ipc"
)

var integrationExecutable string

func TestMain(m *testing.M) {
	root, err := os.MkdirTemp("", "fileway-host-integration-")
	if err != nil {
		fmt.Fprintln(os.Stderr, "cannot create isolated integration directory")
		os.Exit(1)
	}
	integrationExecutable = filepath.Join(root, "Fileway.Host.exe")
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	command := exec.CommandContext(ctx, "go", "build", "-trimpath", "-buildvcs=false", "-ldflags", "-X main.hostVersion=integration-test", "-o", integrationExecutable, ".")
	output, buildErr := command.CombinedOutput()
	cancel()
	if buildErr != nil {
		fmt.Fprintf(os.Stderr, "integration Host build failed: %v\n%s", buildErr, output)
		_ = os.RemoveAll(root)
		os.Exit(1)
	}
	result := m.Run()
	_ = os.RemoveAll(root)
	os.Exit(result)
}

type subprocess struct {
	command   *exec.Cmd
	input     io.WriteCloser
	output    io.ReadCloser
	responses chan ipc.Response
	saved     []ipc.Response
	stderr    bytes.Buffer
	done      chan struct{}
	waitErr   error
	cancel    context.CancelFunc
}

func startSubprocess(t *testing.T) *subprocess {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	process := &subprocess{command: exec.CommandContext(ctx, integrationExecutable), responses: make(chan ipc.Response, 256), done: make(chan struct{}), cancel: cancel}
	var err error
	process.input, err = process.command.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	process.output, err = process.command.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	process.command.Stderr = &process.stderr
	if err := process.command.Start(); err != nil {
		t.Fatal(err)
	}
	readDone := make(chan struct{})
	go func() {
		// StdoutPipe must reach EOF before Wait closes the inherited pipe.
		<-readDone
		process.waitErr = process.command.Wait()
		close(process.done)
	}()
	go func() {
		defer close(readDone)
		defer close(process.responses)
		for {
			data, err := ipc.ReadFrame(process.output)
			if err != nil {
				return
			}
			var response ipc.Response
			if json.Unmarshal(data, &response) != nil {
				return
			}
			process.responses <- response
		}
	}()
	t.Cleanup(func() {
		_ = process.input.Close()
		select {
		case <-process.done:
		case <-time.After(5 * time.Second):
			_ = process.command.Process.Kill()
			<-process.done
		}
		cancel()
		_ = process.output.Close()
	})
	return process
}

func (process *subprocess) send(t *testing.T, id, op, session string, parameters map[string]any) {
	t.Helper()
	if parameters == nil {
		parameters = map[string]any{}
	}
	data, err := json.Marshal(map[string]any{"protocolMajor": 1, "protocolMinor": 0, "requestId": id, "generation": 123, "op": op, "parameters": parameters, "session": session})
	if err != nil {
		t.Fatal(err)
	}
	if session == "" {
		var fields map[string]json.RawMessage
		_ = json.Unmarshal(data, &fields)
		delete(fields, "session")
		data, _ = json.Marshal(fields)
	}
	process.write(t, func() error { return ipc.WriteFrame(process.input, data) })
}

func (process *subprocess) write(t *testing.T, write func() error) {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- write() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("subprocess reader blocked")
	}
}

func (process *subprocess) response(t *testing.T, id string) ipc.Response {
	t.Helper()
	for i, response := range process.saved {
		if response.RequestID == id {
			process.saved = append(process.saved[:i], process.saved[i+1:]...)
			return response
		}
	}
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for {
		select {
		case response, ok := <-process.responses:
			if !ok {
				t.Fatalf("Host output closed before response %s", id)
			}
			if response.RequestID == id {
				return response
			}
			process.saved = append(process.saved, response)
		case <-timer.C:
			t.Fatalf("Host response deadline for %s", id)
		}
	}
}

func checkCode(t *testing.T, response ipc.Response, code string) {
	t.Helper()
	if response.OK || response.ErrorInfo == nil || response.ErrorInfo.Code != code || response.Generation != 123 {
		t.Fatalf("expected %s, got %+v", code, response)
	}
}

func (process *subprocess) init(t *testing.T) ipc.Manifest {
	t.Helper()
	process.send(t, "init", "init", "", nil)
	response := process.response(t, "init")
	var manifest ipc.Manifest
	if !response.OK || json.Unmarshal(response.Result, &manifest) != nil || manifest.CoreProtocol != 1 || manifest.HostVersion != "integration-test" || manifest.BuildCommit != "unknown" || manifest.Module.Path == "" || len(manifest.Dependencies) == 0 {
		t.Fatalf("real build manifest is incomplete: %s", response.Result)
	}
	if _, leaked := manifest.BuildSettings["-ldflags"]; leaked {
		t.Fatal("linker flags leaked into build metadata")
	}
	return manifest
}

func (process *subprocess) open(t *testing.T, base string) string {
	t.Helper()
	process.send(t, "open", "open", "", map[string]any{"baseUrl": base, "network": "direct"})
	response := process.response(t, "open")
	var session string
	if !response.OK || json.Unmarshal(response.Result, &session) != nil || session == "" {
		t.Fatal("shared Engine did not open a real session")
	}
	return session
}

func (process *subprocess) exit(t *testing.T, clean bool) {
	t.Helper()
	select {
	case <-process.done:
		if clean != (process.waitErr == nil) {
			t.Fatalf("unexpected process exit: %v", process.waitErr)
		}
		if process.stderr.Len() > 1024 || bytes.Contains(process.stderr.Bytes(), []byte("fixture.password")) {
			t.Fatal("unbounded or private subprocess diagnostics")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Host process did not terminate within its bound")
	}
}

func TestRealEngineHTTPStatusUnicodeAndLeaseLifetime(t *testing.T) {
	fixture := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path == "/nas/api/login" {
			var login map[string]string
			if json.NewDecoder(request.Body).Decode(&login) != nil || login["username"] != "fixture.user" || login["password"] != "fixture.password" {
				t.Error("fixture login fields changed")
			}
			fmt.Fprint(writer, "fixture.owned.token")
			return
		}
		if request.Header.Get("X-Auth") != "fixture.owned.token" {
			t.Error("session authentication was not preserved")
		}
		if request.URL.Path == "/nas/api/forbidden" {
			writer.WriteHeader(http.StatusForbidden)
			fmt.Fprint(writer, "permission denied")
			return
		}
		if request.URL.EscapedPath() != "/nas/api/resources/%E7%94%B5%E5%BD%B1" {
			t.Error("opaque encoded route changed")
		}
		writer.Header().Set("Content-Type", "application/json")
		fmt.Fprint(writer, `{"name":"电影🎬"}`)
	}))
	t.Cleanup(fixture.Close)
	process := startSubprocess(t)
	process.init(t)
	session := process.open(t, fixture.URL+"/nas")
	process.send(t, "login", "login", session, map[string]any{"username": "fixture.user", "password": "fixture.password"})
	if !process.response(t, "login").OK {
		t.Fatal("real login failed")
	}
	for id, endpoint := range map[string]string{"unicode": "/api/resources/%E7%94%B5%E5%BD%B1", "forbidden": "/api/forbidden"} {
		process.send(t, id, "request", session, map[string]any{"method": "GET", "endpoint": endpoint})
		response := process.response(t, id)
		var result struct {
			Status int    `json:"status"`
			Body   string `json:"body"`
		}
		if !response.OK || json.Unmarshal(response.Result, &result) != nil {
			t.Fatal("HTTP response was reclassified as a Host failure")
		}
		if id == "unicode" && (result.Status != 200 || !strings.Contains(result.Body, "电影🎬")) || id == "forbidden" && (result.Status != 403 || result.Body != "permission denied") {
			t.Fatal("HTTP status or body changed")
		}
	}
	process.send(t, "asset", "asset", session, map[string]any{"endpoint": "/api/resources/%E7%94%B5%E5%BD%B1"})
	asset := process.response(t, "asset")
	var lease string
	if !asset.OK || json.Unmarshal(asset.Result, &lease) != nil || !strings.HasPrefix(lease, "http://127.0.0.1:") {
		t.Fatal("shared resource lease was not retained")
	}
	process.send(t, "close", "close_session", session, nil)
	if !process.response(t, "close").OK {
		t.Fatal("real session close failed")
	}
	client := &http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusGone {
		t.Fatal("session closure did not retire its resource lease")
	}
	process.send(t, "shutdown", "shutdown", "", nil)
	if response := process.response(t, "shutdown"); !response.OK || string(response.Result) != "null" {
		t.Fatal("terminal shutdown response changed")
	}
	process.exit(t, true)
}

func TestRealSaturationCancellationAndSessionClose(t *testing.T) {
	entered := make(chan struct{}, 128)
	var upstream atomic.Int32
	fixture := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		upstream.Add(1)
		entered <- struct{}{}
		<-request.Context().Done()
	}))
	t.Cleanup(fixture.Close)
	process := startSubprocess(t)
	process.init(t)
	session := process.open(t, fixture.URL)
	for i := range 8 {
		process.send(t, fmt.Sprintf("active-%d", i), "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/block"})
	}
	for range 8 {
		select {
		case <-entered:
		case <-time.After(3 * time.Second):
			t.Fatal("eight actual HTTP calls did not start")
		}
	}
	for i := range 64 {
		process.send(t, fmt.Sprintf("queued-%d", i), "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/queued"})
	}
	process.send(t, "overflow", "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/overflow"})
	checkCode(t, process.response(t, "overflow"), "Busy")
	process.send(t, "health", "health", "", nil)
	var health struct{ ActiveBusiness, QueuedBusiness int }
	response := process.response(t, "health")
	if !response.OK || json.Unmarshal(response.Result, &health) != nil || health.ActiveBusiness != 8 || health.QueuedBusiness != 64 {
		t.Fatal("control health did not bypass saturated business workers")
	}
	process.send(t, "active-0", "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/duplicate"})
	checkCode(t, process.response(t, "active-0"), "DuplicateRequest")
	process.send(t, "cancel", "cancel", "", map[string]any{"targetRequestId": "queued-0"})
	if !process.response(t, "cancel").OK {
		t.Fatal("cancel was blocked by saturation")
	}
	checkCode(t, process.response(t, "queued-0"), "Canceled")
	process.send(t, "close", "close_session", session, nil)
	if !process.response(t, "close").OK {
		t.Fatal("session close was blocked by saturation")
	}
	for i := range 8 {
		checkCode(t, process.response(t, fmt.Sprintf("active-%d", i)), "Canceled")
	}
	for i := 1; i < 64; i++ {
		checkCode(t, process.response(t, fmt.Sprintf("queued-%d", i)), "Canceled")
	}
	if upstream.Load() != 8 {
		t.Fatal("queued cancellation or session close contacted the HTTP fixture")
	}
	_ = process.input.Close()
	process.exit(t, true)
}

func TestRealResponseBoundAndOrdinaryCommandLimit(t *testing.T) {
	var upstream atomic.Int32
	fixture := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		upstream.Add(1)
		_, _ = writer.Write(make([]byte, 3<<20)) // JSON escapes expand beyond 16 MiB.
	}))
	t.Cleanup(fixture.Close)
	process := startSubprocess(t)
	process.init(t)
	session := process.open(t, fixture.URL)
	process.send(t, "response-bound", "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/large"})
	checkCode(t, process.response(t, "response-bound"), "ResponseTooLarge")
	process.send(t, "command-bound", "request", session, map[string]any{"method": "POST", "endpoint": "/api/resources/large", "body": strings.Repeat("x", 1<<20)})
	checkCode(t, process.response(t, "command-bound"), "InvalidRequest")
	for _, key := range []string{"bodyBase64", "Op", "SESSION", "RequestID"} {
		process.send(t, "reserved-"+key, "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/large", key: "injected"})
		checkCode(t, process.response(t, "reserved-"+key), "InvalidRequest")
	}
	if upstream.Load() != 1 {
		t.Fatal("rejected parameters were dispatched to the real Engine")
	}
	process.send(t, "health", "health", "", nil)
	if !process.response(t, "health").OK {
		t.Fatal("oversized response corrupted the connection")
	}
	_ = process.input.Close()
	process.exit(t, true)
}

func TestRealEOFAndShutdownWhileHTTPIsInFlight(t *testing.T) {
	for _, terminal := range []string{"eof", "shutdown"} {
		t.Run(terminal, func(t *testing.T) {
			entered := make(chan struct{}, 8)
			fixture := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
				entered <- struct{}{}
				<-request.Context().Done()
			}))
			t.Cleanup(fixture.Close)
			process := startSubprocess(t)
			process.init(t)
			session := process.open(t, fixture.URL)
			for i := range 8 {
				process.send(t, fmt.Sprintf("active-%d", i), "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/block"})
			}
			for range 8 {
				select {
				case <-entered:
				case <-time.After(3 * time.Second):
					t.Fatal("real HTTP call start deadline")
				}
			}
			for i := range 64 {
				process.send(t, fmt.Sprintf("queued-%d", i), "request", session, map[string]any{"method": "GET", "endpoint": "/api/resources/queued"})
			}
			if terminal == "eof" {
				_ = process.input.Close()
			} else {
				process.send(t, "shutdown", "shutdown", "", nil)
				if !process.response(t, "shutdown").OK {
					t.Fatal("shutdown control failed while saturated")
				}
			}
			process.exit(t, true)
		})
	}
}

func TestRealMalformedFramesExitWithBoundedDiagnostics(t *testing.T) {
	var oversized [4]byte
	binary.LittleEndian.PutUint32(oversized[:], ipc.MaxFrameBytes+1)
	for name, data := range map[string][]byte{
		"zero": {0, 0, 0, 0}, "oversized": oversized[:], "truncated header": {1, 0},
		"truncated payload": {2, 0, 0, 0, '{'}, "invalid UTF8": {1, 0, 0, 0, 0xff},
	} {
		t.Run(name, func(t *testing.T) {
			process := startSubprocess(t)
			process.write(t, func() error { _, err := process.input.Write(data); return err })
			_ = process.input.Close()
			process.exit(t, false)
		})
	}
	t.Run("duplicate properties", func(t *testing.T) {
		process := startSubprocess(t)
		data := []byte(`{"protocolMajor":1,"protocolMinor":0,"requestId":"duplicate","generation":0,"op":"init","op":"open","parameters":{"password":"fixture.password"}}`)
		process.write(t, func() error { return ipc.WriteFrame(process.input, data) })
		process.exit(t, false)
	})
}
