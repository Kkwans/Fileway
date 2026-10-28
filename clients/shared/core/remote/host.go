package remote

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/coder/websocket"
)

var ErrClosed = errors.New("remote host closed")

type Config struct {
	Identity Identity `json:"identity"`
	Name     string   `json:"name"`
	// ListenAddress is one concrete local interface address, never 0.0.0.0.
	ListenAddress string       `json:"listenAddress"`
	Controllers   []Controller `json:"controllers"`
}

// Invitation is private: Android renders it only as a short-lived pairing QR.
// It must never appear in DNS-SD TXT or in the public identity endpoint.
type Invitation struct {
	Descriptor Descriptor `json:"device"`
	ID         string     `json:"id"`
	Secret     string     `json:"secret"`
	ExpiresIn  int        `json:"expiresIn"`
}

type knownController struct {
	value Controller
	key   *ecdsa.PublicKey
}
type challenge struct {
	controller string
	expires    time.Time
}
type pairPending struct {
	controller Controller
	until      time.Time
	done       chan bool
}
type commandEntry struct {
	digest     string
	controller string
	connection string
	result     Result
	expires    time.Time
	timer      *time.Timer
}
type peer struct {
	id, controller string
	socket         *websocket.Conn
}

// Host owns only control messages. The loopback media Broker is independent.
type Host struct {
	mu              sync.Mutex
	ctx             context.Context
	cancel          context.CancelFunc
	server          *http.Server
	listener        net.Listener
	descriptor      Descriptor
	controllers     map[string]knownController
	challenges      map[string]challenge
	pairs           map[string]*pairPending
	commands        map[string]*commandEntry
	events          chan Event
	state           State
	active          *peer
	inviteID        string
	inviteSecret    []byte
	inviteUntil     time.Time
	inviteUsed      bool
	requestTimes    map[string][]time.Time
	lastPairDisplay time.Time
}

func Start(config Config) (*Host, error) {
	ip := net.ParseIP(config.ListenAddress)
	if ip == nil || !(ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLoopback()) {
		return nil, errors.New("a concrete local interface is required")
	}
	if len(config.Name) == 0 || len(config.Name) > 160 || len(config.Controllers) > 16 {
		return nil, errors.New("invalid remote host configuration")
	}
	certificate, fingerprint, err := config.Identity.certificate()
	if err != nil {
		return nil, err
	}
	controllers := make(map[string]knownController, len(config.Controllers))
	for _, controller := range config.Controllers {
		key, err := parseController(controller)
		if err != nil {
			return nil, err
		}
		if _, exists := controllers[controller.ID]; exists {
			return nil, errors.New("duplicate controller")
		}
		controllers[controller.ID] = knownController{controller, key}
	}
	listener, err := net.Listen("tcp", net.JoinHostPort(ip.String(), "0"))
	if err != nil {
		return nil, errors.New("cannot start local control listener")
	}
	ctx, cancel := context.WithCancel(context.Background())
	host := &Host{ctx: ctx, cancel: cancel, listener: listener, controllers: controllers,
		challenges: make(map[string]challenge), pairs: make(map[string]*pairPending), commands: make(map[string]*commandEntry),
		requestTimes: make(map[string][]time.Time), events: make(chan Event, 32),
		state: State{Scene: "LOGIN", Capabilities: json.RawMessage(`{}`), Content: json.RawMessage(`{}`)},
		descriptor: Descriptor{Protocol: Protocol, DeviceID: config.Identity.DeviceID, Name: config.Name,
			ServerName: config.Identity.ServerName, Address: ip.String(), Port: listener.Addr().(*net.TCPAddr).Port, SPKISHA256: fingerprint},
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{certificate}}
	host.server = &http.Server{Handler: host, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 20 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8 << 10,
		ErrorLog: log.New(io.Discard, "", 0)}
	limited := newLimitedListener(listener, 8)
	go func() { _ = host.server.Serve(tls.NewListener(limited, tlsConfig)) }()
	return host, nil
}

func (h *Host) Descriptor() Descriptor { return h.descriptor }

func (h *Host) Connected(connectionID, controllerID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	_, trusted := h.controllers[controllerID]
	return trusted && h.active != nil && h.active.id == connectionID && h.active.controller == controllerID && h.ctx.Err() == nil
}

func (h *Host) Invite() (Invitation, error) {
	id, err := randomID()
	if err != nil {
		return Invitation{}, err
	}
	secret, err := randomID()
	if err != nil {
		return Invitation{}, err
	}
	decoded, _ := base64.RawURLEncoding.DecodeString(secret)
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.ctx.Err() != nil {
		return Invitation{}, ErrClosed
	}
	if len(h.controllers) >= 16 {
		return Invitation{}, errors.New("controller limit reached")
	}
	clear(h.inviteSecret)
	h.inviteID, h.inviteSecret, h.inviteUntil, h.inviteUsed = id, decoded, time.Now().Add(2*time.Minute), false
	return Invitation{h.descriptor, id, secret, 120}, nil
}

func (h *Host) Wait(ctx context.Context) (Event, error) {
	select {
	case event := <-h.events:
		return event, nil
	case <-h.ctx.Done():
		return Event{}, ErrClosed
	case <-ctx.Done():
		return Event{}, ctx.Err()
	}
}

// CompletePair is called only after Android durably saved the controller key.
func (h *Host) CompletePair(requestID string, saved bool) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	pending := h.pairs[requestID]
	if pending == nil || time.Now().After(pending.until) || h.ctx.Err() != nil {
		return errors.New("pairing request expired")
	}
	delete(h.pairs, requestID)
	if saved {
		key, err := parseController(pending.controller)
		if err != nil {
			return err
		}
		h.controllers[pending.controller.ID] = knownController{pending.controller, key}
		clear(h.inviteSecret)
	} else {
		h.inviteUsed = false
	}
	pending.done <- saved
	return nil
}

func (h *Host) Revoke(controllerID string) {
	h.mu.Lock()
	delete(h.controllers, controllerID)
	var active *peer
	if h.active != nil && h.active.controller == controllerID {
		active = h.active
		h.active = nil
	}
	for nonce, value := range h.challenges {
		if value.controller == controllerID {
			delete(h.challenges, nonce)
		}
	}
	h.mu.Unlock()
	if active != nil {
		_ = active.socket.CloseNow()
	}
}

func (h *Host) Publish(state State) error {
	if !state.valid() {
		return errors.New("invalid projected TV state")
	}
	state.Capabilities, state.Content = bytes.Clone(state.Capabilities), bytes.Clone(state.Content)
	h.mu.Lock()
	if h.ctx.Err() != nil {
		h.mu.Unlock()
		return ErrClosed
	}
	if state.Revision < h.state.Revision || state.SessionEpoch < h.state.SessionEpoch {
		h.mu.Unlock()
		return errors.New("stale TV state")
	}
	h.state = state
	active := h.active
	h.mu.Unlock()
	if active != nil {
		if err := h.sendState(active); err != nil {
			h.disconnectPeer(active)
		}
	}
	return nil
}

func (h *Host) Reply(connectionID, controllerID string, result Result) error {
	if result.Status != "COMPLETED" && result.Status != "REJECTED" && result.Status != "UNKNOWN" {
		return errors.New("invalid command result")
	}
	if len(result.Code) > 96 || len(result.Payload) > 32<<10 || (len(result.Payload) > 0 && !jsonObject(result.Payload)) {
		return errors.New("invalid command result payload")
	}
	h.mu.Lock()
	entry := h.commands[controllerID+"/"+result.CommandID]
	if entry == nil || entry.connection != connectionID || (entry.result.Status != "ACCEPTED" && entry.result.Status != "UNKNOWN") {
		h.mu.Unlock()
		return errors.New("command is no longer pending")
	}
	result.Type = "command.result"
	entry.result, entry.expires = result, time.Now().Add(5*time.Minute)
	if entry.timer != nil {
		entry.timer.Stop()
	}
	active := h.active
	h.mu.Unlock()
	if active != nil && active.id == connectionID {
		if err := h.send(active, result); err != nil {
			h.disconnectPeer(active)
		}
	}
	return nil
}

func (h *Host) disconnectPeer(active *peer) {
	h.mu.Lock()
	if h.active == active {
		h.active = nil
	}
	h.mu.Unlock()
	_ = active.socket.CloseNow()
}

func (h *Host) Close() error {
	h.cancel()
	h.mu.Lock()
	active := h.active
	h.active = nil
	clear(h.inviteSecret)
	for _, entry := range h.commands {
		if entry.timer != nil {
			entry.timer.Stop()
		}
	}
	h.mu.Unlock()
	if active != nil {
		_ = active.socket.CloseNow()
	}
	return h.server.Close()
}

func (h *Host) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	expectedHost := net.JoinHostPort(h.descriptor.ServerName, strconv.Itoa(h.descriptor.Port))
	if r.Host != expectedHost || r.URL.RawQuery != "" || r.Header.Get("Origin") != "" {
		writeError(w, "INVALID_ORIGIN", http.StatusForbidden)
		return
	}
	if !h.allowRequest(r.RemoteAddr) {
		writeError(w, "RATE_LIMITED", http.StatusTooManyRequests)
		return
	}
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/v1/identity":
		writeJSON(w, h.descriptor)
	case r.Method == http.MethodPost && r.URL.Path == "/v1/pair":
		h.pair(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/v1/pairing/request":
		h.mu.Lock()
		allowed := (len(h.controllers) == 0 || (!h.inviteUsed && time.Now().Before(h.inviteUntil))) && time.Since(h.lastPairDisplay) >= 30*time.Second
		if allowed {
			h.lastPairDisplay = time.Now()
		}
		h.mu.Unlock()
		if !allowed {
			writeError(w, "PAIR_WINDOW_CLOSED", http.StatusForbidden)
			return
		}
		h.emit(Event{Type: "pairing.requested"})
		// This can only ask an unpaired foreground TV to display its QR.
		// It grants no control and returns no invitation or private state.
		writeJSON(w, map[string]string{"status": "DISPLAY_REQUESTED"})
	case r.Method == http.MethodPost && r.URL.Path == "/v1/challenge":
		h.challenge(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/v1/control":
		h.connect(w, r)
	default:
		writeError(w, "NOT_FOUND", http.StatusNotFound)
	}
}

func (h *Host) allowRequest(address string) bool {
	ip, _, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	now := time.Now()
	h.mu.Lock()
	defer h.mu.Unlock()
	for key, times := range h.requestTimes {
		if len(times) == 0 || now.Sub(times[len(times)-1]) > time.Minute {
			delete(h.requestTimes, key)
		}
	}
	if _, found := h.requestTimes[ip]; !found && len(h.requestTimes) >= 64 {
		return false
	}
	times := h.requestTimes[ip]
	keep := times[:0]
	for _, at := range times {
		if now.Sub(at) < 10*time.Second {
			keep = append(keep, at)
		}
	}
	if len(keep) >= 20 {
		h.requestTimes[ip] = keep
		return false
	}
	h.requestTimes[ip] = append(keep, now)
	return h.ctx.Err() == nil
}

func readRequest(w http.ResponseWriter, r *http.Request, target any) bool {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4096))
	if err != nil || decodeStrict(data, target) != nil {
		writeError(w, "INVALID_REQUEST", http.StatusBadRequest)
		return false
	}
	return true
}

func (h *Host) pair(w http.ResponseWriter, r *http.Request) {
	var input struct {
		InvitationID string     `json:"invitationId"`
		Nonce        string     `json:"nonce"`
		Proof        string     `json:"proof"`
		Controller   Controller `json:"controller"`
	}
	if !readRequest(w, r, &input) {
		return
	}
	if _, err := parseController(input.Controller); err != nil || !opaqueID.MatchString(input.Nonce) {
		writeError(w, "INVALID_CONTROLLER", http.StatusBadRequest)
		return
	}
	requestID, err := randomID()
	if err != nil {
		writeError(w, "UNAVAILABLE", http.StatusServiceUnavailable)
		return
	}
	now := time.Now()
	h.mu.Lock()
	for id, pending := range h.pairs {
		if now.After(pending.until) {
			delete(h.pairs, id)
		}
	}
	if input.InvitationID != h.inviteID || h.inviteUsed || now.After(h.inviteUntil) || len(h.pairs) != 0 ||
		!verifyPairProof(h.inviteSecret, input.Proof, h.descriptor.DeviceID, input.Controller.ID, input.Nonce) {
		h.mu.Unlock()
		writeError(w, "PAIR_REQUIRED", http.StatusUnauthorized)
		return
	}
	pending := &pairPending{controller: input.Controller, until: now.Add(15 * time.Second), done: make(chan bool, 1)}
	event := Event{Type: "pair.requested", PairRequestID: requestID, Controller: &input.Controller}
	select {
	case h.events <- event:
		h.pairs[requestID] = pending
		h.inviteUsed = true
	default:
		h.mu.Unlock()
		writeError(w, "DEVICE_BUSY", http.StatusServiceUnavailable)
		return
	}
	h.mu.Unlock()
	timer := time.NewTimer(15 * time.Second)
	defer timer.Stop()
	select {
	case saved := <-pending.done:
		if saved {
			writeJSON(w, map[string]any{"status": "PAIRED", "deviceId": h.descriptor.DeviceID, "controllerId": input.Controller.ID})
		} else {
			writeError(w, "PERSISTENCE_FAILED", http.StatusServiceUnavailable)
		}
	case <-timer.C:
		writeError(w, "PAIR_TIMEOUT", http.StatusGatewayTimeout)
	case <-h.ctx.Done():
		writeError(w, "DEVICE_OFFLINE", http.StatusServiceUnavailable)
	case <-r.Context().Done():
		// A lost HTTP response does not undo a durable pairing. The phone can
		// prove its key against /challenge to resolve an uncertain result.
	}
}

func (h *Host) challenge(w http.ResponseWriter, r *http.Request) {
	var input struct {
		ControllerID string `json:"controllerId"`
	}
	if !readRequest(w, r, &input) {
		return
	}
	nonce, err := randomID()
	if err != nil {
		writeError(w, "UNAVAILABLE", http.StatusServiceUnavailable)
		return
	}
	h.mu.Lock()
	now := time.Now()
	for key, value := range h.challenges {
		if now.After(value.expires) {
			delete(h.challenges, key)
		}
	}
	_, known := h.controllers[input.ControllerID]
	if !known || len(h.challenges) >= 32 {
		h.mu.Unlock()
		writeError(w, "NOT_PAIRED", http.StatusUnauthorized)
		return
	}
	h.challenges[nonce] = challenge{input.ControllerID, now.Add(30 * time.Second)}
	h.mu.Unlock()
	writeJSON(w, map[string]any{"nonce": nonce, "expiresIn": 30})
}

func (h *Host) connect(w http.ResponseWriter, r *http.Request) {
	id, nonce, signature := r.Header.Get("X-Fileway-Controller"), r.Header.Get("X-Fileway-Challenge"), r.Header.Get("X-Fileway-Proof")
	h.mu.Lock()
	known, exists := h.controllers[id]
	value, found := h.challenges[nonce]
	if found {
		delete(h.challenges, nonce)
	}
	valid := exists && found && value.controller == id && time.Now().Before(value.expires) && verifyControllerProof(known.key, signature, h.descriptor.DeviceID, id, nonce)
	busy := h.active != nil
	h.mu.Unlock()
	if !valid {
		writeError(w, "UNAUTHORIZED", http.StatusUnauthorized)
		return
	}
	if busy {
		writeError(w, "DEVICE_BUSY", http.StatusConflict)
		return
	}
	connectionID, err := randomID()
	if err != nil {
		writeError(w, "UNAVAILABLE", http.StatusServiceUnavailable)
		return
	}
	socket, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{Subprotocol}, CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		return
	}
	defer func() { _ = socket.CloseNow() }()
	if socket.Subprotocol() != Subprotocol {
		_ = socket.Close(websocket.StatusPolicyViolation, "PROTOCOL_UNSUPPORTED")
		return
	}
	socket.SetReadLimit(MaxMessageBytes)
	active := &peer{connectionID, id, socket}
	h.mu.Lock()
	if h.active != nil || h.ctx.Err() != nil {
		h.mu.Unlock()
		_ = socket.Close(websocket.StatusTryAgainLater, "DEVICE_BUSY")
		return
	}
	if _, stillKnown := h.controllers[id]; !stillKnown {
		h.mu.Unlock()
		_ = socket.Close(websocket.StatusPolicyViolation, "UNAUTHORIZED")
		return
	}
	h.active = active
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		if h.active == active {
			h.active = nil
		}
		h.mu.Unlock()
		h.emit(Event{Type: "connection.closed", ConnectionID: connectionID, ControllerID: id})
	}()
	h.emit(Event{Type: "connection.opened", ConnectionID: connectionID, ControllerID: id})
	if h.send(active, map[string]any{"type": "hello", "protocol": Protocol, "deviceId": h.descriptor.DeviceID, "connectionId": connectionID}) != nil || h.sendState(active) != nil {
		return
	}
	ctx, cancel := context.WithCancel(h.ctx)
	defer cancel()
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				pingCtx, stop := context.WithTimeout(ctx, 5*time.Second)
				err := socket.Ping(pingCtx)
				stop()
				if err != nil {
					_ = socket.CloseNow()
					return
				}
			}
		}
	}()
	window, received := time.Now(), 0
	for {
		typ, data, err := socket.Read(ctx)
		if err != nil {
			return
		}
		if time.Since(window) >= time.Second {
			window, received = time.Now(), 0
		}
		received++
		if typ != websocket.MessageText || received > 24 {
			_ = socket.Close(websocket.StatusPolicyViolation, "RATE_LIMITED")
			return
		}
		command, err := DecodeCommand(data)
		if err != nil || command.ConnectionID != connectionID {
			_ = socket.Close(websocket.StatusPolicyViolation, "INVALID_COMMAND")
			return
		}
		if err := h.acceptCommand(active, command); err != nil {
			return
		}
	}
}

func (h *Host) acceptCommand(active *peer, command Command) error {
	if command.Operation == "device.snapshot" {
		if err := h.sendState(active); err != nil {
			return err
		}
		return h.send(active, Result{Type: "command.result", CommandID: command.CommandID, Status: "COMPLETED"})
	}
	key := active.controller + "/" + command.CommandID
	h.mu.Lock()
	if h.active != active {
		h.mu.Unlock()
		return ErrClosed
	}
	now, pendingCount := time.Now(), 0
	for id, entry := range h.commands {
		if now.After(entry.expires) {
			if entry.timer != nil {
				entry.timer.Stop()
			}
			delete(h.commands, id)
		} else if entry.result.Status == "ACCEPTED" {
			pendingCount++
		}
	}
	if command.Operation == "device.commandStatus" {
		var payload struct {
			CommandID string `json:"commandId"`
		}
		if decodeStrict(command.Payload, &payload) != nil {
			h.mu.Unlock()
			return h.send(active, Result{Type: "command.result", CommandID: command.CommandID, Status: "REJECTED", Code: "INVALID_REQUEST"})
		}
		entry := h.commands[active.controller+"/"+payload.CommandID]
		result := Result{Type: "command.result", CommandID: payload.CommandID, Status: "REJECTED", Code: "RESULT_UNKNOWN"}
		if entry != nil {
			result = entry.result
		}
		h.mu.Unlock()
		resultPayload, _ := json.Marshal(struct {
			Result Result `json:"result"`
		}{result})
		return h.send(active, Result{Type: "command.result", CommandID: command.CommandID, Status: "COMPLETED", Payload: resultPayload})
	}
	if existing := h.commands[key]; existing != nil {
		result := existing.result
		if existing.digest != commandDigest(command) {
			result = Result{Type: "command.result", CommandID: command.CommandID, Status: "REJECTED", Code: "COMMAND_CONFLICT"}
		}
		h.mu.Unlock()
		return h.send(active, result)
	}
	if pendingCount >= MaxPendingCommands || len(h.commands) >= 160 {
		h.mu.Unlock()
		return h.send(active, Result{Type: "command.result", CommandID: command.CommandID, Status: "REJECTED", Code: "DEVICE_BUSY"})
	}
	result := Result{Type: "command.result", CommandID: command.CommandID, Status: "ACCEPTED"}
	entry := &commandEntry{digest: commandDigest(command), controller: active.controller, connection: active.id, result: result, expires: now.Add(5 * time.Minute)}
	h.commands[key] = entry
	entry.timer = time.AfterFunc(30*time.Second, func() {
		// A timed-out acknowledgement does not prove a mutation failed. Keep it
		// queryable and allow a later actual Android result to resolve UNKNOWN.
		_ = h.Reply(active.id, active.controller, Result{CommandID: command.CommandID, Status: "UNKNOWN", Code: "RESULT_PENDING_TIMEOUT"})
	})
	h.mu.Unlock()
	// Write ACCEPTED before dispatching to Android; a fast Android completion
	// must never arrive before ACCEPTED or be overwritten by a later receipt.
	if err := h.send(active, result); err != nil {
		_ = h.Reply(active.id, active.controller, Result{CommandID: command.CommandID, Status: "REJECTED", Code: "CONNECTION_CLOSED"})
		return err
	}
	h.mu.Lock()
	current := h.active == active && entry.result.Status == "ACCEPTED"
	if current {
		select {
		case h.events <- Event{Type: "command", ConnectionID: active.id, ControllerID: active.controller, Command: &command}:
			h.mu.Unlock()
			return nil
		default:
		}
	}
	h.mu.Unlock()
	return h.Reply(active.id, active.controller, Result{CommandID: command.CommandID, Status: "REJECTED", Code: "DEVICE_BUSY"})
}

func (h *Host) emit(event Event) {
	select {
	case h.events <- event:
	default:
	}
}

func (h *Host) send(active *peer, value any) error {
	data, err := json.Marshal(value)
	if err != nil || len(data) > MaxMessageBytes {
		return errors.New("invalid remote response")
	}
	ctx, cancel := context.WithTimeout(h.ctx, 5*time.Second)
	defer cancel()
	return active.socket.Write(ctx, websocket.MessageText, data)
}

func (h *Host) sendState(active *peer) error {
	h.mu.Lock()
	state := h.state
	h.mu.Unlock()
	return h.send(active, struct {
		Type         string `json:"type"`
		Protocol     int    `json:"protocol"`
		ConnectionID string `json:"connectionId"`
		State
	}{"snapshot", Protocol, active.id, state})
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}
func writeError(w http.ResponseWriter, code string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": code})
}
