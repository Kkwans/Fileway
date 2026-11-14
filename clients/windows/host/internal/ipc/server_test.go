package ipc

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type functionCore func([]byte) []byte

func (core functionCore) Call(data []byte) []byte { return core(data) }

func coreOK(result any) []byte {
	data, _ := json.Marshal(map[string]any{"ok": true, "result": result})
	return data
}

func coreError(message string) []byte {
	data, _ := json.Marshal(map[string]any{"ok": false, "error": message})
	return data
}

type peer struct {
	input     *io.PipeWriter
	output    *io.PipeReader
	responses chan Response
	done      chan error
	saved     []Response
	server    *Server
}

func startPeer(t *testing.T, core Core, timeout time.Duration) *peer {
	t.Helper()
	return startServerPeer(t, NewServer(core, BuildManifest("test", "", timeout)))
}

func startServerPeer(t *testing.T, server *Server) *peer {
	t.Helper()
	input, parentInput := io.Pipe()
	parentOutput, output := io.Pipe()
	peer := &peer{input: parentInput, output: parentOutput, responses: make(chan Response, 256), done: make(chan error, 1), server: server}
	go func() { peer.done <- server.Run(input, output, io.Discard) }()
	go peer.read()
	t.Cleanup(func() {
		_ = peer.input.Close()
		_ = peer.output.Close()
		select {
		case <-peer.done:
		case <-time.After(2 * time.Second):
			t.Error("host did not terminate after cleanup")
		}
	})
	return peer
}

func TestCanceledQueuedInitReleasesHandshakeSlot(t *testing.T) {
	var initCalls atomic.Int32
	server := NewServer(functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		if command.Op == "init" {
			initCalls.Add(1)
			return coreOK(map[string]int{"protocol": 1})
		}
		return coreOK(nil)
	}), BuildManifest("test", "", 200*time.Millisecond))
	// Hold the queued init before starting control workers to deterministically
	// exercise cancel winning the race to the queued operation.
	server.accept(request("queued-init", "init", "", nil))
	queuedInit := <-server.controls
	server.accept(request("cancel-init", "cancel", "", map[string]json.RawMessage{"targetRequestId": json.RawMessage(`"queued-init"`)}))
	server.control(<-server.controls)
	server.controls <- queuedInit
	peer := startServerPeer(t, server)
	if !peer.response(t, "cancel-init").OK {
		t.Fatal("queued init cancellation failed")
	}
	requireCode(t, peer.response(t, "queued-init"), "Canceled")
	initPeer(t, peer)
	if initCalls.Load() != 1 {
		t.Fatal("canceled init invoked core or blocked the next handshake")
	}
}

func TestAcceptedSessionCloseCannotLoseCleanupToCancel(t *testing.T) {
	var closes, coreCancels atomic.Int32
	server := NewServer(functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		if command.Op == "close_session" {
			closes.Add(1)
		}
		if command.Op == "cancel" {
			coreCancels.Add(1)
		}
		return coreOK(nil)
	}), BuildManifest("test", "", 200*time.Millisecond))
	server.initialized = true
	server.accept(request("queued-close", "close_session", "account", nil))
	queuedClose := <-server.controls
	server.accept(request("cancel-close", "cancel", "", map[string]json.RawMessage{"targetRequestId": json.RawMessage(`"queued-close"`)}))
	server.control(<-server.controls)
	server.controls <- queuedClose
	peer := startServerPeer(t, server)
	if !peer.response(t, "cancel-close").OK || !peer.response(t, "queued-close").OK {
		t.Fatal("accepted session cleanup was discarded")
	}
	if closes.Load() != 1 || coreCancels.Load() != 0 {
		t.Fatal("core close was skipped or received a cancellation tombstone")
	}
	peer.send(t, request("closed-request", "request", "account", requestParameters()))
	requireCode(t, peer.response(t, "closed-request"), "InvalidRequest")
}

func TestInitRequiresActualSupportedCoreProtocol(t *testing.T) {
	for name, result := range map[string]any{"missing": nil, "wrong": map[string]int{"protocol": 2}, "wrong type": map[string]string{"protocol": "1"}} {
		t.Run(name, func(t *testing.T) {
			peer := startPeer(t, functionCore(func([]byte) []byte { return coreOK(result) }), 100*time.Millisecond)
			peer.send(t, request("init-1", "init", "", nil))
			requireCode(t, peer.response(t, "init-1"), "ProtocolMismatch")
			peer.send(t, request("init-2", "init", "", nil))
			requireCode(t, peer.response(t, "init-2"), "ProtocolMismatch")
			peer.send(t, request("business", "open", "", nil))
			requireCode(t, peer.response(t, "business"), "HandshakeRequired")
		})
	}
}

func (peer *peer) read() {
	defer close(peer.responses)
	for {
		data, err := ReadFrame(peer.output)
		if err != nil {
			return
		}
		var response Response
		if json.Unmarshal(data, &response) != nil {
			return
		}
		peer.responses <- response
	}
}

func (peer *peer) send(t *testing.T, request Request) {
	t.Helper()
	data, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	written := make(chan error, 1)
	go func() { written <- WriteFrame(peer.input, data) }()
	select {
	case err := <-written:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("request reader blocked")
	}
}

func request(id, op, session string, parameters map[string]json.RawMessage) Request {
	if parameters == nil {
		parameters = map[string]json.RawMessage{}
	}
	return Request{ProtocolMajor: 1, ProtocolMinor: 0, RequestID: id, Generation: 123, Op: op, Session: session, Parameters: parameters}
}

func (peer *peer) response(t *testing.T, id string) Response {
	t.Helper()
	for i, response := range peer.saved {
		if response.RequestID == id {
			peer.saved = append(peer.saved[:i], peer.saved[i+1:]...)
			return response
		}
	}
	timer := time.NewTimer(3 * time.Second)
	defer timer.Stop()
	for {
		select {
		case response, ok := <-peer.responses:
			if !ok {
				t.Fatalf("host output closed while waiting for %s", id)
			}
			if response.RequestID == id {
				return response
			}
			peer.saved = append(peer.saved, response)
		case <-timer.C:
			t.Fatalf("no response to %s", id)
		}
	}
}

func requireCode(t *testing.T, response Response, code string) {
	t.Helper()
	if response.OK || response.ErrorInfo == nil || response.ErrorInfo.Code != code || response.Generation != 123 {
		t.Fatalf("expected %s: %+v", code, response)
	}
}

func initPeer(t *testing.T, peer *peer) {
	t.Helper()
	peer.send(t, request("init", "init", "", nil))
	response := peer.response(t, "init")
	if !response.OK {
		t.Fatal(response)
	}
	var manifest Manifest
	if json.Unmarshal(response.Result, &manifest) != nil || manifest.CoreProtocol != 1 || manifest.BuildCommit == "" || manifest.Capabilities.BusinessWorkers != 8 || manifest.Capabilities.BusinessQueueCapacity != 64 {
		t.Fatal("incomplete build manifest")
	}
}

func TestHandshakeVersionCoreErrorsAndTerminalShutdown(t *testing.T) {
	var businessCalls atomic.Int32
	core := functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		if command.Op == "open" {
			businessCalls.Add(1)
			return coreError("context canceled timeout cannot connect")
		}
		if command.Op == "init" {
			return coreOK(map[string]int{"protocol": 1})
		}
		return []byte(`{"ok":true}`) // Existing core omits a nil result.
	})
	peer := startPeer(t, core, 200*time.Millisecond)
	peer.send(t, request("preinit", "open", "", nil))
	requireCode(t, peer.response(t, "preinit"), "HandshakeRequired")
	badVersion := request("major", "open", "", nil)
	badVersion.ProtocolMajor = 2
	peer.send(t, badVersion)
	requireCode(t, peer.response(t, "major"), "ProtocolMismatch")
	if businessCalls.Load() != 0 {
		t.Fatal("core invoked before successful handshake")
	}
	initPeer(t, peer)
	peer.send(t, request("core-failure", "open", "", nil))
	response := peer.response(t, "core-failure")
	requireCode(t, response, "CoreRejected")
	if response.Error != "context canceled timeout cannot connect" || response.ErrorInfo.Retryable {
		t.Fatal("untyped core error changed or was automatically retried")
	}
	peer.send(t, request("shutdown", "shutdown", "", nil))
	response = peer.response(t, "shutdown")
	if !response.OK || string(response.Result) != "null" {
		t.Fatal("shutdown did not acknowledge null")
	}
}

type controlledCore struct {
	mu              sync.Mutex
	pending         map[string]chan struct{}
	canceled        map[string]bool
	started         chan struct{ ID, Session string }
	release         chan struct{}
	active          int
	maximum         int
	total           int
	successOnCancel bool
}

func newControlledCore() *controlledCore {
	return &controlledCore{pending: map[string]chan struct{}{}, canceled: map[string]bool{}, started: make(chan struct{ ID, Session string }, 128), release: make(chan struct{})}
}

func (core *controlledCore) Call(data []byte) []byte {
	var command struct {
		Op        string `json:"op"`
		RequestID string `json:"requestId"`
		Session   string `json:"session"`
	}
	_ = json.Unmarshal(data, &command)
	core.mu.Lock()
	switch command.Op {
	case "cancel":
		core.canceled[command.RequestID] = true
		if stop, ok := core.pending[command.RequestID]; ok {
			delete(core.pending, command.RequestID)
			close(stop)
		}
		core.mu.Unlock()
		return coreOK(nil)
	case "close_session", "shutdown":
		for id, stop := range core.pending {
			delete(core.pending, id)
			close(stop)
		}
		core.mu.Unlock()
		return coreOK(nil)
	case "request":
		stop := make(chan struct{})
		core.pending[command.RequestID] = stop
		if core.canceled[command.RequestID] {
			delete(core.pending, command.RequestID)
			close(stop)
		}
		core.active++
		core.total++
		if core.active > core.maximum {
			core.maximum = core.active
		}
		core.mu.Unlock()
		core.started <- struct{ ID, Session string }{command.RequestID, command.Session}
		response := coreOK(map[string]bool{"confirmed": true})
		select {
		case <-stop:
			if !core.successOnCancel {
				response = coreError("deliberately untyped cancellation")
			}
		case <-core.release:
		}
		core.mu.Lock()
		delete(core.pending, command.RequestID)
		core.active--
		core.mu.Unlock()
		return response
	case "init":
		core.mu.Unlock()
		return coreOK(map[string]int{"protocol": 1})
	default:
		core.mu.Unlock()
		return coreOK(nil)
	}
}

func requestParameters() map[string]json.RawMessage {
	return map[string]json.RawMessage{"method": json.RawMessage(`"GET"`), "endpoint": json.RawMessage(`"/api/resources/"`)}
}

func TestEightWorkersSixtyFourQueuedAndResponsiveControls(t *testing.T) {
	core := newControlledCore()
	peer := startPeer(t, core, 250*time.Millisecond)
	initPeer(t, peer)
	for i := range BusinessWorkers {
		peer.send(t, request(fmt.Sprintf("active-%d", i), "request", "account", requestParameters()))
	}
	for range BusinessWorkers {
		select {
		case <-core.started:
		case <-time.After(time.Second):
			t.Fatal("business workers did not all start")
		}
	}
	for i := range BusinessQueueCapacity {
		peer.send(t, request(fmt.Sprintf("queued-%d", i), "request", "account", requestParameters()))
	}
	peer.send(t, request("overflow", "request", "account", requestParameters()))
	requireCode(t, peer.response(t, "overflow"), "Busy")
	peer.send(t, request("health", "health", "", nil))
	health := peer.response(t, "health")
	var load struct{ ActiveBusiness, QueuedBusiness int }
	if !health.OK || json.Unmarshal(health.Result, &load) != nil || load.ActiveBusiness != 8 || load.QueuedBusiness != 64 {
		t.Fatalf("control path blocked or capacity changed: %s", health.Result)
	}
	peer.send(t, request("active-0", "request", "account", requestParameters()))
	requireCode(t, peer.response(t, "active-0"), "DuplicateRequest")
	peer.send(t, request("cancel", "cancel", "", map[string]json.RawMessage{"targetRequestId": json.RawMessage(`"queued-0"`)}))
	if !peer.response(t, "cancel").OK {
		t.Fatal("cancel control failed while saturated")
	}
	requireCode(t, peer.response(t, "queued-0"), "Canceled")
	peer.send(t, request("close", "close_session", "account", nil))
	if !peer.response(t, "close").OK {
		t.Fatal("session close control failed while saturated")
	}
	for i := range BusinessWorkers {
		requireCode(t, peer.response(t, fmt.Sprintf("active-%d", i)), "Canceled")
	}
	for i := 1; i < BusinessQueueCapacity; i++ {
		requireCode(t, peer.response(t, fmt.Sprintf("queued-%d", i)), "Canceled")
	}
	peer.send(t, request("after-close", "request", "account", requestParameters()))
	requireCode(t, peer.response(t, "after-close"), "InvalidRequest")
	core.mu.Lock()
	defer core.mu.Unlock()
	if core.maximum != 8 || core.total != 8 {
		t.Fatalf("queued/canceled work reached core: maximum=%d total=%d", core.maximum, core.total)
	}
}

func TestConfirmedSuccessSurvivesCancelAndExternalIdentityReuse(t *testing.T) {
	core := newControlledCore()
	core.successOnCancel = true
	peer := startPeer(t, core, 200*time.Millisecond)
	initPeer(t, peer)
	peer.send(t, request("reused", "request", "account", requestParameters()))
	var first, second struct{ ID, Session string }
	select {
	case first = <-core.started:
	case <-time.After(time.Second):
		t.Fatal("first accepted request did not reach core")
	}
	peer.send(t, request("cancel", "cancel", "", map[string]json.RawMessage{"targetRequestId": json.RawMessage(`"reused"`)}))
	if !peer.response(t, "cancel").OK || !peer.response(t, "reused").OK {
		t.Fatal("confirmed core success was replaced by cancellation")
	}
	// The peer can observe the frame before the writer retires its owner.
	waitCondition(t, func() bool {
		peer.server.mu.Lock()
		defer peer.server.mu.Unlock()
		return peer.server.requests["reused"] == nil
	})
	peer.send(t, request("reused", "request", "account", requestParameters()))
	select {
	case second = <-core.started:
	case <-time.After(time.Second):
		t.Fatal("reused accepted request did not reach core")
	}
	if first.ID == second.ID {
		t.Fatal("external identity reuse reused a core tombstone ID")
	}
	close(core.release)
	if !peer.response(t, "reused").OK {
		t.Fatal("old cancellation contaminated a new accepted request")
	}
}

func TestOutstandingIdentityRetainedUntilFrameWritten(t *testing.T) {
	input, parentInput := io.Pipe()
	parentOutput, output := io.Pipe()
	called := make(chan struct{}, 1)
	server := NewServer(functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		if command.Op == "init" {
			called <- struct{}{}
			return coreOK(map[string]int{"protocol": 1})
		}
		return coreOK(nil)
	}), BuildManifest("test", "", 200*time.Millisecond))
	done := make(chan error, 1)
	go func() { done <- server.Run(input, output, io.Discard) }()
	peer := &peer{input: parentInput, output: parentOutput, responses: make(chan Response, 16), done: done, server: server}
	defer func() { _ = parentInput.Close(); _ = parentOutput.Close(); <-done }()
	peer.send(t, request("same", "init", "", nil))
	<-called
	peer.send(t, request("same", "init", "", nil))
	// The reader accepts each request before reading the next frame. Finishing
	// this independent frame proves the duplicate was accepted while stdout
	// remained blocked, regardless of whether the writer dequeued the first reply.
	peer.send(t, request("reader-barrier", "health", "", nil))
	go peer.read() // The first response was deliberately blocked until now.
	first, second := peer.response(t, "same"), peer.response(t, "same")
	if first.OK == second.OK {
		t.Fatal("duplicate was accepted while its first response write was blocked")
	}
	if first.OK {
		requireCode(t, second, "DuplicateRequest")
	} else {
		requireCode(t, first, "DuplicateRequest")
	}
	waitCondition(t, func() bool {
		server.mu.Lock()
		defer server.mu.Unlock()
		return server.requests["same"] == nil
	})
	peer.send(t, request("same", "init", "", nil))
	if !peer.response(t, "same").OK {
		t.Fatal("identity did not become reusable after frame write")
	}
}

func waitCondition(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatal("test synchronization deadline")
		}
		time.Sleep(time.Millisecond)
	}
}

func TestAcceptedShutdownBypassesTwoStuckControlWorkers(t *testing.T) {
	release := make(chan struct{})
	initStarted, cancelStarted := make(chan struct{}, 1), make(chan struct{}, 1)
	core := functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		switch command.Op {
		case "init":
			initStarted <- struct{}{}
			<-release
			return coreOK(map[string]int{"protocol": 1})
		case "cancel":
			cancelStarted <- struct{}{}
			<-release
		}
		return coreOK(nil)
	})
	peer := startPeer(t, core, 150*time.Millisecond)
	defer close(release)
	peer.send(t, request("stuck-init", "init", "", nil))
	<-initStarted
	peer.send(t, request("stuck-cancel", "cancel", "", map[string]json.RawMessage{"targetRequestId": json.RawMessage(`"stuck-init"`)}))
	<-cancelStarted
	peer.send(t, request("shutdown", "shutdown", "", nil))
	if !peer.response(t, "shutdown").OK {
		t.Fatal("independent shutdown acknowledgement failed")
	}
	select {
	case err := <-peer.done:
		peer.done <- err // Keep the shared cleanup's terminal observation intact.
	case <-time.After(500 * time.Millisecond):
		t.Fatal("accepted shutdown waited for stuck control workers")
	}
}

func TestControlCapacityIsTwoRunningAndSixteenQueued(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{}, 2)
	core := functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		switch command.Op {
		case "init":
			return coreOK(map[string]int{"protocol": 1})
		case "close_session":
			entered <- struct{}{}
			<-release
		}
		return coreOK(nil)
	})
	peer := startPeer(t, core, 200*time.Millisecond)
	defer close(release)
	initPeer(t, peer)
	peer.send(t, request("control-0", "close_session", "account-0", nil))
	peer.send(t, request("control-1", "close_session", "account-1", nil))
	for range 2 {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("two control workers did not start")
		}
	}
	for i := range 16 {
		peer.send(t, request(fmt.Sprintf("queued-control-%d", i), "health", "", nil))
	}
	peer.send(t, request("control-overflow", "health", "", nil))
	requireCode(t, peer.response(t, "control-overflow"), "Busy")
	peer.send(t, request("business", "open", "", nil))
	if !peer.response(t, "business").OK {
		t.Fatal("bounded control saturation blocked business reader dispatch")
	}
	peer.send(t, request("shutdown", "shutdown", "", nil))
	if !peer.response(t, "shutdown").OK {
		t.Fatal("terminal slot was consumed by the saturated control queue")
	}
}

func TestResponseByteBackpressureTerminatesWithStdinOpen(t *testing.T) {
	large := coreOK(strings.Repeat("x", 8<<20))
	core := functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		if command.Op == "open" {
			return large
		}
		return coreOK(nil)
	})
	input, parentInput := io.Pipe()
	output := &blockedOutput{closed: make(chan struct{}), start: make(chan struct{})}
	const shutdownTimeout = 150 * time.Millisecond
	server := NewServer(core, BuildManifest("test", "", shutdownTimeout))
	server.initialized = true
	done := make(chan error, 1)
	go func() { done <- server.Run(input, output, io.Discard) }()
	defer parentInput.Close()
	peer := &peer{input: parentInput}
	for i := range 4 {
		peer.send(t, request(fmt.Sprintf("large-%d", i), "open", "", nil))
	}
	// Large JSON validation/encoding under -race precedes the shutdown deadline.
	select {
	case <-server.stop:
	case <-time.After(10 * time.Second):
		t.Fatal("bounded response byte budget did not trigger backpressure")
	}
	select {
	case <-done:
	case <-time.After(shutdownTimeout + 500*time.Millisecond):
		t.Fatal("bounded response byte budget did not terminate backpressure")
	}
	server.outputMu.Lock()
	defer server.outputMu.Unlock()
	if server.responseSize > MaxQueuedResponseBytes || len(server.responses) > responseQueueCapacity {
		t.Fatal("response output exceeded its byte or frame budget")
	}
}

type blockedOutput struct {
	closed chan struct{}
	start  chan struct{}
	once   sync.Once
	close  sync.Once
}

func (output *blockedOutput) Write([]byte) (int, error) {
	output.once.Do(func() { close(output.start) })
	<-output.closed
	return 0, io.ErrClosedPipe
}

func (output *blockedOutput) Close() error {
	output.close.Do(func() { close(output.closed) })
	return nil
}

func TestEOFBoundIncludesIgnoredCancellationCoreShutdownAndOutputBackpressure(t *testing.T) {
	input, parentInput := io.Pipe()
	output := &blockedOutput{closed: make(chan struct{}), start: make(chan struct{})}
	release := make(chan struct{})
	started := make(chan struct{}, 1)
	shutdown := make(chan struct{}, 1)
	server := NewServer(functionCore(func(data []byte) []byte {
		var command struct{ Op string }
		_ = json.Unmarshal(data, &command)
		if command.Op == "request" {
			started <- struct{}{}
			<-release
		}
		if command.Op == "init" {
			return coreOK(map[string]int{"protocol": 1})
		}
		if command.Op == "cancel" {
			<-release
		}
		if command.Op == "shutdown" {
			shutdown <- struct{}{}
			<-release
		}
		return coreOK(nil)
	}), BuildManifest("test", "", 150*time.Millisecond))
	done := make(chan error, 1)
	go func() { done <- server.Run(input, output, io.Discard) }()
	peer := &peer{input: parentInput}
	peer.send(t, request("init", "init", "", nil))
	<-output.start
	peer.send(t, request("stuck", "request", "account", requestParameters()))
	<-started
	start := time.Now()
	_ = parentInput.Close()
	select {
	case <-done:
		if time.Since(start) > 500*time.Millisecond {
			t.Fatal("shutdown exceeded its total deadline")
		}
	case <-time.After(time.Second):
		t.Fatal("EOF failed to terminate a stalled Host")
	}
	select {
	case <-shutdown:
	default:
		t.Fatal("core shutdown was not attempted")
	}
	close(release)
}

func TestMalformedInputClosesConnectionWithoutEchoingPayload(t *testing.T) {
	input, parentInput := io.Pipe()
	parentOutput, output := io.Pipe()
	var diagnostics bytes.Buffer
	server := NewServer(functionCore(func([]byte) []byte { return coreOK(nil) }), BuildManifest("test", "", 100*time.Millisecond))
	done := make(chan error, 1)
	go func() { done <- server.Run(input, output, &diagnostics) }()
	go io.Copy(io.Discard, parentOutput)
	if err := WriteFrame(parentInput, []byte(`{"password":"private-fixture"}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != ErrEnvelope {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("malformed envelope did not close the connection")
	}
	if bytes.Contains(diagnostics.Bytes(), []byte("private-fixture")) || diagnostics.Len() > 1024 {
		t.Fatal("diagnostics leaked or were unbounded")
	}
}
