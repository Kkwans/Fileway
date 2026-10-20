package transport

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/coder/websocket"
)

const commandFrameLimit = 64 << 10
const commandQueueLimit = 256 << 10
const commandQueueLines = 256

type CommandBatch struct {
	Lines      []string `json:"lines"`
	Done       bool     `json:"done"`
	State      string   `json:"state"`
	Dropped    int64    `json:"dropped"`
	HTTPStatus int      `json:"httpStatus,omitempty"`
	Failure    string   `json:"failure,omitempty"`
}

type commandStream struct {
	session *Session
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.Mutex
	conn    *websocket.Conn
	queue   []string
	bytes   int
	dropped int64
	state   string
	status  int
	failure string
}

func CommandEndpoint(path, wire string) (string, error) {
	raw, err := RawEndpoint(path, wire)
	if err != nil {
		return "", err
	}
	return "/api/command" + strings.TrimPrefix(raw, "/api/raw"), nil
}

// The handle owns only one command's output connection. Disconnect does not
// promise process termination; the server has no cancel/exit-code protocol.
func (b *Broker) StartCommand(call context.Context, owner, path, wire, raw string) (string, error) {
	if strings.TrimSpace(raw) == "" || len(raw) > 8192 || strings.ContainsAny(raw, "\x00\r\n") || !utf8.ValidString(raw) {
		return "", errors.New("command must be one nonempty UTF-8 line of at most 8192 bytes")
	}
	s, err := b.session(owner)
	if err != nil {
		return "", err
	}
	endpoint, err := CommandEndpoint(path, wire)
	if err != nil {
		return "", err
	}
	if _, err = Endpoint(s.base, endpoint); err != nil {
		return "", err
	}
	id, err := randomID()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithCancel(s.ctx)
	stream := &commandStream{session: s, ctx: ctx, cancel: cancel, state: "running"}
	b.mu.Lock()
	if call.Err() != nil || b.closed || b.sessions[owner] != s {
		b.mu.Unlock()
		cancel()
		return "", errors.New("command source closed")
	}
	for _, existing := range b.commands {
		if existing.session == s {
			b.mu.Unlock()
			cancel()
			return "", errors.New("disconnect the previous output connection first")
		}
	}
	if b.commands == nil {
		b.commands = make(map[string]*commandStream)
	}
	b.commands[id] = stream
	b.mu.Unlock()
	go stream.read(endpoint, raw)
	return id, nil
}

func (b *Broker) PollCommand(owner, id string) (CommandBatch, error) {
	b.mu.RLock()
	stream := b.commands[id]
	session := b.sessions[owner]
	b.mu.RUnlock()
	if stream == nil || session == nil || session != stream.session {
		return CommandBatch{}, errors.New("command output closed or unknown")
	}
	stream.mu.Lock()
	n := min(len(stream.queue), 32)
	lines := make([]string, n)
	copy(lines, stream.queue[:n])
	for _, line := range lines {
		stream.bytes -= len(line)
	}
	clear(stream.queue[:n])
	stream.queue = stream.queue[n:]
	batch := CommandBatch{Lines: lines, Done: stream.state != "running" && len(stream.queue) == 0, State: stream.state,
		Dropped: stream.dropped, HTTPStatus: stream.status, Failure: stream.failure}
	stream.mu.Unlock()
	if batch.Done {
		b.mu.Lock()
		if b.commands[id] == stream {
			delete(b.commands, id)
		}
		b.mu.Unlock()
		stream.cancel()
	}
	return batch, nil
}

func (b *Broker) CancelCommand(owner, id string) error {
	b.mu.Lock()
	stream := b.commands[id]
	if stream != nil && b.sessions[owner] != stream.session {
		b.mu.Unlock()
		return errors.New("command output closed or unknown")
	}
	delete(b.commands, id)
	b.mu.Unlock()
	if stream != nil {
		stream.stop()
	}
	return nil
}

func (s *commandStream) stop() {
	s.mu.Lock()
	s.state = "closed"
	s.queue = nil
	s.bytes = 0
	s.cancel()
	conn := s.conn
	s.conn = nil
	s.mu.Unlock()
	if conn != nil {
		_ = conn.CloseNow()
	}
}

func (s *commandStream) finish(state, failure string, status int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state == "running" {
		s.state, s.failure, s.status = state, failure, status
	}
}

func (s *commandStream) emit(line string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state != "running" || s.ctx.Err() != nil {
		return false
	}
	for len(s.queue) > 0 && (len(s.queue) >= commandQueueLines || s.bytes+len(line) > commandQueueLimit) {
		s.bytes -= len(s.queue[0])
		s.queue[0] = ""
		s.queue = s.queue[1:]
		s.dropped++
	}
	s.queue = append(s.queue, line)
	s.bytes += len(line)
	return true
}

func (s *commandStream) read(endpoint, raw string) {
	defer s.cancel()
	target, err := Endpoint(s.session.base, endpoint)
	if err != nil {
		s.finish("failed", "invalid command directory", 0)
		return
	}
	headers := make(http.Header)
	previous := s.session.currentToken()
	if token := previous; token != "" {
		headers.Set("X-Auth", token)
	}
	conn, response, err := websocket.Dial(s.ctx, target.String(), &websocket.DialOptions{HTTPClient: s.session.client, HTTPHeader: headers})
	if err != nil {
		status := 0
		if response != nil {
			status = response.StatusCode
		}
		s.finish("failed", "command output connection rejected", status)
		return
	}
	defer conn.CloseNow()
	if response != nil {
		s.session.afterHeaders(s.ctx, response, previous)
	}
	s.mu.Lock()
	if s.ctx.Err() != nil || s.state != "running" {
		s.mu.Unlock()
		return
	}
	s.conn = conn
	s.mu.Unlock()
	conn.SetReadLimit(commandFrameLimit)
	if err = conn.Write(s.ctx, websocket.MessageText, []byte(raw)); err != nil {
		s.finish("failed", "command delivery was not confirmed; do not resend without checking", 0)
		return
	}
	for {
		kind, data, err := conn.Read(s.ctx)
		if err != nil {
			// The existing NAS closes the socket without an exit-status record.
			// A normal EOF and an interrupted command cannot be distinguished.
			s.finish("closed", "output connection closed; process result is unknown", 0)
			return
		}
		if kind != websocket.MessageText || !utf8.Valid(data) {
			s.finish("failed", "unsupported command output encoding", 0)
			return
		}
		if !s.emit(string(data)) {
			return
		}
	}
}
