package ipc

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	BusinessWorkers        = 8
	BusinessQueueCapacity  = 64
	ControlWorkers         = 2
	ControlQueueCapacity   = 16
	MaxQueuedResponseBytes = 32 << 20
	responseQueueCapacity  = 128
	ShutdownTimeout        = 3 * time.Second
)

// Core is the existing shared bridge.Engine boundary. It has no typed errors or
// caller context, so Host cancellation state is kept independently of its text.
type Core interface {
	Call([]byte) []byte
}

type work struct {
	request   Request
	command   []byte
	coreID    string
	started   bool
	canceled  bool
	completed bool // A core-confirmed response is never replaced by late cancel.
}

type packet struct {
	data  []byte
	owner *work
}

type Server struct {
	core     Core
	manifest Manifest
	timeout  time.Duration

	mu            sync.Mutex
	requests      map[string]*work
	queue         []*work
	closedSession map[string]bool
	active        int
	sequence      uint64
	initialized   bool
	initializing  bool
	closing       bool
	stop          chan struct{}
	stopOnce      sync.Once
	readErr       error
	controls      chan *work
	businessWG    sync.WaitGroup
	controlWG     sync.WaitGroup

	outputMu     sync.Mutex
	responses    chan packet
	responseSize int
	outputClosed bool
	writerDone   chan struct{}
	writerErr    error

	diagnosticMu    sync.Mutex
	diagnosticCount int
	diagnostics     io.Writer
}

func NewServer(core Core, manifest Manifest) *Server {
	timeout := time.Duration(manifest.Capabilities.ShutdownTimeoutMilliseconds) * time.Millisecond
	if timeout <= 0 {
		timeout = ShutdownTimeout
	}
	return &Server{core: core, manifest: manifest, timeout: timeout, requests: make(map[string]*work),
		closedSession: make(map[string]bool), stop: make(chan struct{}), controls: make(chan *work, ControlQueueCapacity),
		responses: make(chan packet, responseQueueCapacity), writerDone: make(chan struct{})}
}

// Run owns both pipes. EOF, protocol failure, shutdown, or output backpressure
// starts one terminal cleanup with a single overall deadline. A stuck core or
// unresponsive parent cannot hold process exit past that deadline.
func (server *Server) Run(input io.ReadCloser, output io.WriteCloser, diagnostics io.Writer) error {
	server.diagnostics = diagnostics
	for range ControlWorkers {
		server.controlWG.Add(1)
		go server.controlWorker()
	}
	go server.writer(output)
	go server.reader(input)
	<-server.stop
	deadline := time.Now().Add(server.timeout)
	_ = input.Close()
	server.cancelOutstanding()

	drained := make(chan struct{})
	go func() {
		server.businessWG.Wait()
		server.controlWG.Wait()
		close(drained)
	}()
	// Reserve time for core shutdown and for already queued responses to flush.
	_ = waitUntil(drained, deadline.Add(-2*server.timeout/3))
	coreStopped := make(chan struct{})
	go func() {
		_, _ = server.callCore([]byte(`{"op":"shutdown"}`))
		close(coreStopped)
	}()
	_ = waitUntil(coreStopped, deadline.Add(-server.timeout/3))
	server.outputMu.Lock()
	server.outputClosed = true
	close(server.responses)
	server.outputMu.Unlock()
	flushed := waitUntil(server.writerDone, deadline)
	_ = output.Close() // Interrupt an anonymous-pipe write if the parent stopped reading.
	if !flushed {
		server.diagnostic("output drain deadline reached")
	}
	server.mu.Lock()
	err := server.readErr
	server.mu.Unlock()
	server.outputMu.Lock()
	if server.writerErr != nil {
		err = errors.New("host output failed")
	}
	server.outputMu.Unlock()
	return err
}

func waitUntil(done <-chan struct{}, deadline time.Time) bool {
	remaining := time.Until(deadline)
	if remaining <= 0 {
		select {
		case <-done:
			return true
		default:
			return false
		}
	}
	timer := time.NewTimer(remaining)
	defer timer.Stop()
	select {
	case <-done:
		return true
	case <-timer.C:
		return false
	}
}

func (server *Server) reader(input io.Reader) {
	for {
		data, err := ReadFrame(input)
		if err != nil {
			if err != io.EOF {
				server.mu.Lock()
				if !server.closing {
					server.readErr = err
				}
				server.mu.Unlock()
				server.diagnostic("protocol connection closed")
			}
			server.beginClosing()
			return
		}
		request, err := ParseRequest(data)
		if err != nil {
			server.mu.Lock()
			server.readErr = ErrEnvelope
			server.mu.Unlock()
			server.diagnostic("invalid protocol envelope")
			server.beginClosing()
			return
		}
		server.accept(request)
	}
}

func (server *Server) accept(request Request) {
	if request.ProtocolMajor != ProtocolMajor {
		server.emit(nil, failure(request, "ProtocolMismatch", "unsupported protocol major", false))
		return
	}
	server.mu.Lock()
	if server.closing {
		server.mu.Unlock()
		server.emit(nil, failure(request, "HostClosing", "host is closing", false))
		return
	}
	if _, duplicate := server.requests[request.RequestID]; duplicate {
		server.mu.Unlock()
		server.emit(nil, failure(request, "DuplicateRequest", "request identity is already outstanding", false))
		return
	}
	if !server.initialized && request.Op != "init" && request.Op != "health" && request.Op != "cancel" && request.Op != "shutdown" {
		server.mu.Unlock()
		server.emit(nil, failure(request, "HandshakeRequired", "init is required before business operations", false))
		return
	}
	server.sequence++
	coreID := "windows-host-" + strconv.FormatUint(server.sequence, 10)
	command, err := commandFor(request, coreID)
	if err != nil {
		server.mu.Unlock()
		server.emit(nil, failure(request, "InvalidRequest", err.Error(), false))
		return
	}
	if request.Op == "init" && server.initializing {
		server.mu.Unlock()
		server.emit(nil, failure(request, "Busy", "host initialization is in progress", true))
		return
	}
	if request.Session != "" && server.closedSession[request.Session] && request.Op != "close_session" {
		server.mu.Unlock()
		server.emit(nil, failure(request, "InvalidRequest", "session is closed", false))
		return
	}
	job := &work{request: request, command: command, coreID: coreID}
	if request.Op == "shutdown" {
		// Terminal control has its own single slot. It must start the cleanup
		// deadline even when both ordinary control workers are stuck in core.
		server.requests[request.RequestID] = job
		server.closing = true
		server.mu.Unlock()
		server.finish(job, success(request, nil))
		server.beginClosing()
		return
	}
	if !operations[request.Op].control {
		if server.active == BusinessWorkers && len(server.queue) == BusinessQueueCapacity {
			server.mu.Unlock()
			server.emit(nil, failure(request, "Busy", "request queue is full", true))
			return
		}
		server.requests[request.RequestID] = job
		if server.active < BusinessWorkers {
			server.startBusinessLocked(job)
		} else {
			server.queue = append(server.queue, job)
		}
		server.mu.Unlock()
		return
	}
	select {
	case server.controls <- job:
		server.requests[request.RequestID] = job
	default:
		server.mu.Unlock()
		server.emit(nil, failure(request, "Busy", "control queue is full", true))
		return
	}
	if request.Op == "init" && !server.initialized {
		server.initializing = true
	}
	var canceled []*work
	if request.Op == "close_session" {
		server.closedSession[request.Session] = true
		for _, target := range server.requests {
			if target != job && target.request.Session == request.Session && !target.completed {
				target.canceled = true
				if !target.started && !operations[target.request.Op].control {
					server.removeQueuedLocked(target)
					canceled = append(canceled, target)
				}
			}
		}
	}
	server.mu.Unlock()
	for _, target := range canceled {
		server.finish(target, failure(target.request, "Canceled", "request was canceled", false))
	}
}

func (server *Server) startBusinessLocked(job *work) {
	job.started = true
	server.active++
	server.businessWG.Add(1)
	go server.business(job)
}

func (server *Server) business(job *work) {
	defer server.businessWG.Done()
	server.mu.Lock()
	skip := job.canceled || server.closing || server.closedSession[job.request.Session]
	server.mu.Unlock()
	if skip {
		server.finish(job, failure(job.request, "Canceled", "request was canceled", false))
	} else {
		server.finish(job, server.coreResponse(job))
	}
	server.mu.Lock()
	server.active--
	if !server.closing && len(server.queue) != 0 {
		next := server.queue[0]
		server.queue[0] = nil
		server.queue = server.queue[1:]
		server.startBusinessLocked(next)
	}
	server.mu.Unlock()
}

func (server *Server) controlWorker() {
	defer server.controlWG.Done()
	for {
		select {
		case <-server.stop:
			return
		case job := <-server.controls:
			server.mu.Lock()
			if job.completed {
				server.mu.Unlock()
				continue
			}
			job.started = true
			canceled := job.canceled && job.request.Op != "shutdown" && job.request.Op != "close_session"
			server.mu.Unlock()
			if canceled {
				server.finish(job, failure(job.request, "Canceled", "request was canceled", false))
				continue
			}
			server.control(job)
		}
	}
}

func (server *Server) control(job *work) {
	switch job.request.Op {
	case "health":
		server.mu.Lock()
		state := "starting"
		if server.initialized {
			state = "ready"
		}
		if server.closing {
			state = "closing"
		}
		result, _ := json.Marshal(map[string]any{"state": state, "activeBusiness": server.active, "queuedBusiness": len(server.queue)})
		server.mu.Unlock()
		server.finish(job, success(job.request, result))
	case "cancel":
		server.cancel(job)
	case "shutdown":
		server.finish(job, success(job.request, nil))
		server.beginClosing()
	case "init":
		server.mu.Lock()
		alreadyInitialized := server.initialized
		server.mu.Unlock()
		response := success(job.request, nil)
		if !alreadyInitialized {
			response = server.coreResponse(job)
		}
		if response.OK && !alreadyInitialized {
			var coreInit struct {
				Protocol *int `json:"protocol"`
			}
			if json.Unmarshal(response.Result, &coreInit) != nil || coreInit.Protocol == nil || *coreInit.Protocol != 1 {
				response = failure(job.request, "ProtocolMismatch", "unsupported shared core protocol", false)
			}
		}
		server.mu.Lock()
		if response.OK {
			server.initialized = true
		}
		server.mu.Unlock()
		if response.OK {
			result, _ := json.Marshal(server.manifest)
			response = success(job.request, result)
		}
		server.finish(job, response)
	default: // close_session is kept off the business workers.
		server.finish(job, server.coreResponse(job))
	}
}

func (server *Server) cancel(job *work) {
	var targetID string
	_ = json.Unmarshal(job.request.Parameters["targetRequestId"], &targetID)
	server.mu.Lock()
	target := server.requests[targetID]
	var callCancel, queued bool
	if target != nil && !target.completed && target.request.Op != "shutdown" && target.request.Op != "close_session" {
		target.canceled = true
		queued = !target.started && !operations[target.request.Op].control
		if queued {
			server.removeQueuedLocked(target)
		}
		callCancel = target.started && target.request.Op != "health" && target.request.Op != "cancel"
	}
	server.mu.Unlock()
	if queued {
		server.finish(target, failure(target.request, "Canceled", "request was canceled", false))
	}
	if callCancel {
		command, _ := json.Marshal(map[string]string{"op": "cancel", "requestId": target.coreID})
		if _, err := server.callCore(command); err != nil {
			server.finish(job, failure(job.request, "HostFailure", "shared core call failed", false))
			return
		}
	}
	server.finish(job, success(job.request, nil))
}

func (server *Server) removeQueuedLocked(target *work) {
	for i, job := range server.queue {
		if job == target {
			copy(server.queue[i:], server.queue[i+1:])
			server.queue[len(server.queue)-1] = nil
			server.queue = server.queue[:len(server.queue)-1]
			return
		}
	}
}

func (server *Server) coreResponse(job *work) Response {
	data, err := server.callCore(job.command)
	if err != nil {
		return failure(job.request, "HostFailure", "shared core call failed", false)
	}
	if len(data) > MaxFrameBytes {
		return failure(job.request, "ResponseTooLarge", "host response exceeds size limit", false)
	}
	var response struct {
		OK     *bool           `json:"ok"`
		Result json.RawMessage `json:"result"`
		Error  string          `json:"error"`
	}
	if !json.Valid(data) || !utf8.Valid(data) || json.Unmarshal(data, &response) != nil || response.OK == nil {
		return failure(job.request, "HostFailure", "shared core returned an invalid response", false)
	}
	if *response.OK {
		return success(job.request, response.Result)
	}
	server.mu.Lock()
	canceled := job.canceled
	server.mu.Unlock()
	if canceled {
		return failure(job.request, "Canceled", "request was canceled", false)
	}
	result := failure(job.request, "CoreRejected", "shared core rejected the request", false)
	result.Error = response.Error // Preserve the core contract, without classifying its English text.
	if result.Error == "" {
		return failure(job.request, "HostFailure", "shared core returned an invalid response", false)
	}
	return result
}

func (server *Server) callCore(command []byte) (data []byte, err error) {
	defer func() {
		if recover() != nil {
			server.diagnostic("shared core call failed")
			data = nil
			err = errors.New("shared core call failed")
		}
	}()
	return server.core.Call(command), nil
}

func (server *Server) finish(job *work, response Response) {
	server.mu.Lock()
	if job.completed {
		server.mu.Unlock()
		return
	}
	job.completed = true
	if job.request.Op == "init" {
		server.initializing = false
	}
	server.mu.Unlock()
	server.emit(job, response)
}

func (server *Server) emit(owner *work, response Response) {
	data := marshalResponse(response)
	server.outputMu.Lock()
	if server.outputClosed {
		server.outputMu.Unlock()
		return
	}
	accepted := false
	if server.responseSize+len(data) <= MaxQueuedResponseBytes {
		select {
		case server.responses <- packet{data: data, owner: owner}:
			server.responseSize += len(data)
			accepted = true
		default:
		}
	}
	server.outputMu.Unlock()
	if !accepted {
		server.diagnostic("output backpressure limit reached")
		server.beginClosing()
	}
}

func (server *Server) writer(output io.Writer) {
	defer close(server.writerDone)
	for packet := range server.responses {
		if err := WriteFrame(output, packet.data); err != nil {
			server.outputMu.Lock()
			server.writerErr = errors.New("host output failed")
			server.outputMu.Unlock()
			server.diagnostic("protocol output closed")
			server.beginClosing()
			return
		}
		// An identity stays outstanding until its complete frame was written.
		if packet.owner != nil {
			server.mu.Lock()
			if server.requests[packet.owner.request.RequestID] == packet.owner {
				delete(server.requests, packet.owner.request.RequestID)
			}
			server.mu.Unlock()
		}
		server.outputMu.Lock()
		server.responseSize -= len(packet.data)
		server.outputMu.Unlock()
	}
}

func (server *Server) beginClosing() {
	server.mu.Lock()
	server.closing = true
	server.mu.Unlock()
	server.stopOnce.Do(func() { close(server.stop) })
}

func (server *Server) cancelOutstanding() {
	server.mu.Lock()
	var queued []*work
	var active []string
	for _, job := range server.requests {
		if job.completed {
			continue
		}
		job.canceled = true
		if !job.started {
			queued = append(queued, job)
		} else if !operations[job.request.Op].control {
			active = append(active, job.coreID)
		}
	}
	server.queue = nil
	server.mu.Unlock()
	for _, job := range queued {
		server.finish(job, failure(job.request, "Canceled", "request was canceled", false))
	}
	// At most eight cancel calls can exist here, even if a faulty core ignores them.
	for _, id := range active {
		go func() {
			command, _ := json.Marshal(map[string]string{"op": "cancel", "requestId": id})
			_, _ = server.callCore(command)
		}()
	}
}

// Diagnostics have a fixed vocabulary, no exception text, identities, commands,
// URLs, or credentials, and at most eight short lines per process.
func (server *Server) diagnostic(message string) {
	server.diagnosticMu.Lock()
	defer server.diagnosticMu.Unlock()
	if server.diagnostics != nil && server.diagnosticCount < 8 {
		server.diagnosticCount++
		_, _ = fmt.Fprintln(server.diagnostics, "fileway-host:", message)
	}
}
