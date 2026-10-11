package transport

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"mime"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const searchQueueSize = 64
const searchBatchSize = 32
const searchRecordLimit = 64 << 10

// SearchItem is service metadata, not media bytes. NAS paths are relative to
// the search directory; they are not Android local filesystem paths.
type SearchItem struct {
	Directory    bool   `json:"dir"`
	Path         string `json:"path"`
	Name         string `json:"name"`
	Size         int64  `json:"size"`
	Modified     string `json:"modified"`
	RiskLevel    string `json:"riskLevel"`
	WirePath     string `json:"wirePath,omitempty"`
	PathVerified bool   `json:"pathVerified,omitempty"`
}

type SearchSummary struct {
	Reason string `json:"reason"`
	Count  int64  `json:"count"`
}

// Done is authoritative only after queued results have been drained. Count is
// the number actually received, which can differ from a timeout summary's
// count when the NAS stops while handing its final result to its writer.
type SearchBatch struct {
	Items      []SearchItem   `json:"items"`
	Count      int64          `json:"count"`
	Done       bool           `json:"done"`
	State      string         `json:"state"`
	Summary    *SearchSummary `json:"summary,omitempty"`
	HTTPStatus int            `json:"httpStatus,omitempty"`
	Failure    string         `json:"failure,omitempty"`
}

type searchStream struct {
	session *Session
	base    string // Original bytes, decoded once from the submitted base wire.
	scope   string
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.Mutex
	queue   []SearchItem
	room    chan struct{}
	state   string
	count   int64
	summary *SearchSummary
	status  int
	failure string
}

func SearchEndpoint(path, wirePath, query, scope string) (string, error) {
	if scope != "current" && scope != "recursive" {
		return "", errors.New("invalid search scope")
	}
	if strings.TrimSpace(query) == "" || len(query) > 8192 {
		return "", errors.New("search query is empty or too long")
	}
	raw, err := RawEndpoint(path, wirePath)
	if err != nil {
		return "", err
	}
	if _, err := searchWireIdentity(strings.TrimPrefix(raw, "/api/raw")); err != nil {
		return "", err
	}
	return "/api/search" + strings.TrimPrefix(raw, "/api/raw") + "?" + url.Values{"query": {query}, "scope": {scope}}.Encode(), nil
}

// StartSearch returns immediately. The stream belongs to the immutable source
// session rather than the short-lived JNI call context. The caller must cancel
// a replaced query; closing its session cancels every owned stream as well.
func (b *Broker) StartSearch(command context.Context, sessionID, path, wirePath, query, scope string) (string, error) {
	s, err := b.session(sessionID)
	if err != nil {
		return "", err
	}
	endpoint, err := SearchEndpoint(path, wirePath, query, scope)
	if err != nil {
		return "", err
	}
	if _, err := Endpoint(s.base, endpoint); err != nil {
		return "", err
	}
	raw, _ := RawEndpoint(path, wirePath) // SearchEndpoint already validated it.
	base, _ := searchWireIdentity(strings.TrimPrefix(raw, "/api/raw"))
	id, err := randomID()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(s.ctx, 45*time.Second)
	stream := &searchStream{session: s, base: base, scope: scope, ctx: ctx, cancel: cancel, room: make(chan struct{}, 1), state: "running"}
	b.mu.Lock()
	if err := command.Err(); err != nil {
		b.mu.Unlock()
		cancel()
		return "", err
	}
	if b.closed || b.sessions[sessionID] != s {
		b.mu.Unlock()
		cancel()
		return "", errors.New("session closed")
	}
	owned := 0
	for _, existing := range b.searches {
		if existing.session == s {
			owned++
		}
	}
	if owned >= 4 {
		b.mu.Unlock()
		cancel()
		return "", errors.New("cancel the previous searches before starting another")
	}
	b.searches[id] = stream
	b.mu.Unlock()
	go stream.read(endpoint)
	return id, nil
}

func (b *Broker) PollSearch(sessionID, id string) (SearchBatch, error) {
	b.mu.RLock()
	s := b.searches[id]
	owner := b.sessions[sessionID]
	b.mu.RUnlock()
	if s == nil || owner == nil || owner != s.session {
		return SearchBatch{}, errors.New("search closed or unknown")
	}
	s.mu.Lock()
	n := min(len(s.queue), searchBatchSize)
	items := make([]SearchItem, n)
	copy(items, s.queue[:n])
	clear(s.queue[:n])
	s.queue = s.queue[n:]
	batch := SearchBatch{Items: items, Count: s.count, Done: s.state != "running" && len(s.queue) == 0,
		State: s.state, HTTPStatus: s.status, Failure: s.failure}
	if batch.Done {
		batch.Summary = s.summary
	}
	s.mu.Unlock()
	s.signalRoom()
	if batch.Done {
		b.mu.Lock()
		if b.searches[id] == s {
			delete(b.searches, id)
		}
		b.mu.Unlock()
		s.cancel()
	}
	return batch, nil
}

// Cancel is idempotent for a retired handle, but cannot cancel another account's
// search. Removal precedes cancellation so no later poll can expose its queue.
func (b *Broker) CancelSearch(sessionID, id string) error {
	b.mu.Lock()
	s := b.searches[id]
	if s != nil && b.sessions[sessionID] != s.session {
		b.mu.Unlock()
		return errors.New("search closed or unknown")
	}
	delete(b.searches, id)
	b.mu.Unlock()
	if s != nil {
		s.stop()
	}
	return nil
}

func (s *searchStream) signalRoom() {
	select {
	case s.room <- struct{}{}:
	default:
	}
}

func (s *searchStream) stop() {
	s.mu.Lock()
	s.state, s.queue, s.summary = "canceled", nil, nil
	s.cancel()
	s.mu.Unlock()
	s.signalRoom()
}

func (s *searchStream) emit(item SearchItem) bool {
	for {
		s.mu.Lock()
		if s.state != "running" || s.ctx.Err() != nil {
			s.mu.Unlock()
			return false
		}
		if len(s.queue) < searchQueueSize {
			s.queue = append(s.queue, item)
			s.count++
			s.mu.Unlock()
			return true
		}
		s.mu.Unlock()
		select {
		case <-s.room:
		case <-s.ctx.Done():
			return false
		}
	}
}

func (s *searchStream) finish(state, failure string, status int, summary *SearchSummary) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state == "running" {
		s.state, s.failure, s.status, s.summary = state, failure, status, summary
	}
}

func (s *searchStream) read(endpoint string) {
	defer s.cancel()
	previous := s.session.currentToken()
	res, err := s.session.do(s.ctx, http.MethodGet, endpoint, nil, nil)
	if err != nil {
		s.finish("failed", "search connection interrupted", 0, nil)
		return
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		s.finish("failed", "search request rejected", res.StatusCode, nil)
		return
	}
	contentType, _, err := mime.ParseMediaType(res.Header.Get("Content-Type"))
	if err != nil || contentType != "application/x-ndjson" {
		s.finish("failed", "unsupported search response", res.StatusCode, nil)
		return
	}
	if res.Header.Get("X-Renew-Token") == "true" && previous != "" {
		if err := s.session.renew(s.ctx, previous); err != nil {
			s.finish("failed", "sign in again", http.StatusUnauthorized, nil)
			return
		}
	}
	lines := bufio.NewScanner(res.Body)
	lines.Buffer(make([]byte, 4096), searchRecordLimit)
	for lines.Scan() {
		line := bytes.TrimSpace(lines.Bytes())
		if len(line) == 0 {
			continue // NAS heartbeat, never a result or completion marker.
		}
		var event struct {
			Type   string      `json:"type"`
			Item   *SearchItem `json:"item"`
			Reason string      `json:"reason"`
			Count  int64       `json:"count"`
		}
		if json.Unmarshal(line, &event) != nil {
			s.finish("failed", "malformed search response", res.StatusCode, nil)
			return
		}
		switch event.Type {
		case "result":
			if event.Item == nil || !s.validItem(*event.Item) {
				s.finish("failed", "invalid search resource", res.StatusCode, nil)
				return
			}
			if !s.emit(*event.Item) {
				s.finish("failed", "search interrupted", res.StatusCode, nil)
				return
			}
		case "summary":
			s.mu.Lock()
			count := s.count
			s.mu.Unlock()
			valid := event.Count >= count && event.Count >= 0
			switch event.Reason {
			case "completed", "limit":
				valid = valid && event.Count == count
			case "timeout", "canceled", "error":
			default:
				valid = false
			}
			if !valid {
				s.finish("failed", "invalid search summary", res.StatusCode, nil)
				return
			}
			summary := &SearchSummary{Reason: event.Reason, Count: event.Count}
			if event.Reason == "error" {
				s.finish("failed", "search failed on the server", res.StatusCode, summary)
			} else {
				s.finish("finished", "", res.StatusCode, summary)
			}
			return
		default:
			s.finish("failed", "unexpected search event", res.StatusCode, nil)
			return
		}
	}
	if lines.Err() != nil {
		s.finish("failed", "search stream interrupted or record too large", res.StatusCode, nil)
	} else {
		s.finish("failed", "search ended without a summary", res.StatusCode, nil)
	}
}

func validSearchItem(item SearchItem) bool {
	if item.Path == "" || item.Name == "" || item.Size < 0 || strings.HasPrefix(item.Path, "/") || strings.ContainsRune(item.Path, 0) {
		return false
	}
	parts := strings.Split(item.Path, "/")
	for _, part := range parts {
		if part == "" || part == "." || part == ".." {
			return false
		}
	}
	return item.Name == parts[len(parts)-1]
}

func (s *searchStream) validItem(item SearchItem) bool {
	if !validSearchItem(item) || s.scope == "current" && strings.Contains(item.Path, "/") {
		return false
	}
	if item.WirePath == "" && !item.PathVerified {
		return true // Legacy relative display metadata remains source compatible.
	}
	if item.WirePath == "" || !item.PathVerified {
		return false
	}
	identity, err := searchWireIdentity(item.WirePath)
	prefix := strings.TrimSuffix(s.base, "/") + "/"
	if err != nil || identity == s.base || !strings.HasPrefix(identity, prefix) {
		return false
	}
	rawParts := strings.Split(strings.TrimPrefix(identity, prefix), "/")
	displayParts := strings.Split(item.Path, "/")
	if len(rawParts) != len(displayParts) || s.scope == "current" && len(rawParts) != 1 {
		return false
	}
	for index, part := range rawParts {
		// Opaque bytes may have a lossy display name; a valid UTF-8 segment
		// must still agree with its literal display metadata byte for byte.
		if utf8.ValidString(part) && part != displayParts[index] {
			return false
		}
	}
	return true
}

// A wire path is absolute within the user's scope. Decoding happens exactly
// once per segment; '+' and literal percent escapes retain their identity.
func searchWireIdentity(wire string) (string, error) {
	invalid := errors.New("invalid search wire path")
	if wire == "" || !strings.HasPrefix(wire, "/") || !utf8.ValidString(wire) || strings.ContainsAny(wire, "?#\\") {
		return "", invalid
	}
	for _, value := range wire {
		if value < 32 || value == 127 {
			return "", invalid
		}
	}
	if wire == "/" {
		return "/", nil
	}
	var identity strings.Builder
	for _, part := range strings.Split(wire[1:], "/") {
		decoded, err := url.PathUnescape(part)
		if err != nil || decoded == "" || decoded == "." || decoded == ".." || strings.ContainsAny(decoded, "/\x00") {
			return "", invalid
		}
		identity.WriteByte('/')
		identity.WriteString(decoded)
	}
	return identity.String(), nil
}
