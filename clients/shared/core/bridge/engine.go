// Package bridge is the versioned, JSON control boundary shared by JNI and
// future desktop bindings. Video bytes never pass through this boundary.
package bridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser-client/core/tailnet"
	"github.com/Kkwans/nas-file-browser-client/core/transport"
)

type Command struct {
	CacheConfig     transport.CacheConfig   `json:"cacheConfig"`
	CacheKey        string                  `json:"cacheKey"`
	CacheOwner      string                  `json:"cacheOwner,omitempty"`
	CacheRoot       string                  `json:"cacheRoot,omitempty"`
	InactiveOnly    bool                    `json:"inactiveOnly,omitempty"`
	Clear           bool                    `json:"clear"`
	Op              string                  `json:"op"`
	RequestID       string                  `json:"requestId"`
	Session         string                  `json:"session"`
	BaseURL         string                  `json:"baseUrl"`
	Token           string                  `json:"token"`
	Username        string                  `json:"username"`
	Password        string                  `json:"password"`
	Method          string                  `json:"method"`
	Endpoint        string                  `json:"endpoint"`
	Body            json.RawMessage         `json:"body"`
	BodyBase64      *string                 `json:"bodyBase64,omitempty"`
	Path            string                  `json:"path"`
	WirePath        string                  `json:"wirePath"`
	URL             string                  `json:"url"`
	Network         string                  `json:"network"`
	StateDir        string                  `json:"stateDir"`
	Hostname        string                  `json:"hostname"`
	StorageKey      string                  `json:"storageKey"`
	Query           string                  `json:"query"`
	Scope           string                  `json:"scope"`
	Search          string                  `json:"search"`
	RawCommand      string                  `json:"command"`
	CommandHandle   string                  `json:"commandHandle"`
	PlatformNetwork tailnet.PlatformNetwork `json:"platformNetwork"`
	Upload          transport.UploadOptions `json:"upload"`
}

type Envelope struct {
	OK     bool   `json:"ok"`
	Result any    `json:"result,omitempty"`
	Error  string `json:"error,omitempty"`
}

type Engine struct {
	mu           sync.Mutex
	broker       *transport.Broker
	pending      map[string]context.CancelFunc
	cancelled    map[string]time.Time
	node         *tailnet.Node
	embedded     map[string]bool
	networkEpoch uint64
}

func (e *Engine) ready() (*transport.Broker, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.broker == nil {
		b, err := transport.New()
		if err != nil {
			return nil, errors.New("cannot start private media transport")
		}
		e.broker = b
	}
	if e.pending == nil {
		e.pending = make(map[string]context.CancelFunc)
	}
	if e.cancelled == nil {
		e.cancelled = make(map[string]time.Time)
	}
	if e.embedded == nil {
		e.embedded = make(map[string]bool)
	}
	for id, until := range e.cancelled {
		if time.Now().After(until) {
			delete(e.cancelled, id)
		}
	}
	return e.broker, nil
}

func (e *Engine) Call(data []byte) []byte {
	var c Command
	if len(data) > maxRawResourceEnvelope || json.Unmarshal(data, &c) != nil || (len(data) > 1<<20 && (c.Op != "request" || c.BodyBase64 == nil)) {
		return encode(Envelope{Error: "invalid native command"})
	}
	result, err := e.execute(c)
	if err != nil {
		return encode(Envelope{Error: err.Error()})
	}
	return encode(Envelope{OK: true, Result: result})
}

func encode(value Envelope) []byte {
	data, err := json.Marshal(value)
	if err != nil {
		return []byte(`{"ok":false,"error":"cannot encode native response"}`)
	}
	return data
}

func (e *Engine) execute(c Command) (any, error) {
	b, err := e.ready()
	if err != nil {
		return nil, err
	}
	if c.Op == "cancel" {
		e.mu.Lock()
		cancel := e.pending[c.RequestID]
		// Cancellation can arrive before the worker enters JNI. Retain a short
		// tombstone so that a cancelled queued request never reaches the server.
		if c.RequestID != "" {
			e.cancelled[c.RequestID] = time.Now().Add(time.Minute)
		}
		e.mu.Unlock()
		if cancel != nil {
			cancel()
		}
		return nil, nil
	}
	timeout := 30 * time.Second
	if c.Op == "request" {
		timeout = transport.RequestTimeout(c.Method, c.Endpoint)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	if c.RequestID != "" {
		e.mu.Lock()
		if until, found := e.cancelled[c.RequestID]; found && time.Now().Before(until) {
			e.mu.Unlock()
			return nil, context.Canceled
		}
		if _, found := e.pending[c.RequestID]; found {
			e.mu.Unlock()
			return nil, errors.New("request already running")
		}
		e.pending[c.RequestID] = cancel
		e.mu.Unlock()
		defer func() { e.mu.Lock(); delete(e.pending, c.RequestID); e.mu.Unlock() }()
	}
	switch c.Op {
	case "init":
		return map[string]int{"protocol": 1}, nil
	case "network_platform":
		if err := tailnet.UpdatePlatformNetwork(c.PlatformNetwork); err != nil {
			return nil, err
		}
		e.mu.Lock()
		n := e.node
		e.mu.Unlock()
		if n != nil {
			n.PlatformNetworkChanged()
		}
		return nil, nil
	case "open":
		if c.Network == "" || c.Network == "direct" {
			return b.Open(c.BaseURL, c.Token, nil)
		}
		if c.Network != "tailnet" {
			return nil, errors.New("unknown network mode")
		}
		e.mu.Lock()
		n := e.node
		epoch := e.networkEpoch
		e.mu.Unlock()
		if n == nil {
			return nil, errors.New("embedded network is not configured")
		}
		client, err := n.Client(ctx)
		if err != nil {
			return nil, err
		}
		e.mu.Lock()
		defer e.mu.Unlock()
		if e.node != n || e.networkEpoch != epoch {
			return nil, errors.New("embedded network changed")
		}
		id, err := b.Open(c.BaseURL, c.Token, client)
		if err == nil {
			e.embedded[id] = true
		}
		return id, err
	case "network_configure":
		key, err := base64.StdEncoding.DecodeString(c.StorageKey)
		if err != nil {
			return nil, errors.New("invalid wrapped node storage key")
		}
		defer clear(key)
		e.mu.Lock()
		defer e.mu.Unlock()
		if e.node != nil {
			return nil, errors.New("embedded network already configured")
		}
		n, err := tailnet.NewNode(c.StateDir, c.Hostname, key)
		if err != nil {
			return nil, err
		}
		e.node = n
		e.networkEpoch++
		return map[string]string{"state": "Configured"}, nil
	case "network_start", "network_status", "network_logout", "network_stop":
		e.mu.Lock()
		n := e.node
		e.mu.Unlock()
		if n == nil {
			if c.Op == "network_status" {
				return tailnet.Status{State: "Unconfigured"}, nil
			}
			return nil, errors.New("embedded network is not configured")
		}
		if c.Op == "network_start" {
			return n.Start(ctx)
		}
		if c.Op == "network_status" {
			return n.Status(ctx)
		}
		e.mu.Lock()
		e.networkEpoch++
		for id := range e.embedded {
			b.CloseSession(id)
			delete(e.embedded, id)
		}
		e.mu.Unlock()
		if c.Op == "network_stop" {
			return nil, n.Stop(ctx)
		}
		return nil, n.Logout(ctx)
	case "login":
		return b.Login(ctx, c.Session, c.Username, c.Password)
	case "request":
		if c.BodyBase64 != nil {
			body, err := rawResourceBody(c)
			if err != nil {
				return nil, err
			}
			return b.RequestRaw(ctx, c.Session, c.Method, c.Endpoint, body)
		}
		return b.Request(ctx, c.Session, c.Method, c.Endpoint, c.Body)
	case "search_start":
		id, err := b.StartSearch(ctx, c.Session, c.Path, c.WirePath, c.Query, c.Scope)
		if err == nil && ctx.Err() != nil {
			_ = b.CancelSearch(c.Session, id)
			return nil, ctx.Err()
		}
		return id, err
	case "search_poll":
		return b.PollSearch(c.Session, c.Search)
	case "search_cancel":
		return nil, b.CancelSearch(c.Session, c.Search)
	case "command_start", "command_poll", "command_cancel":
		return commandControl(ctx, b, c)
	case "token":
		return b.Token(c.Session)
	case "cache_configure":
		return nil, b.ConfigureCache(c.CacheConfig)
	case "cache_cleanup":
		return b.CleanCache(c.Clear)
	case "cache_stats":
		return b.CacheStatsForRootContext(ctx, c.CacheRoot, c.CacheOwner)
	case "cache_cleanup_owner":
		return b.CleanCacheOwnerInRootContext(ctx, c.CacheOwner, c.CacheRoot, c.InactiveOnly)
	case "lease_stats":
		return b.CacheLeaseStats(c.Session, c.URL)
	case "upload_lease":
		return b.UploadLease(c.Session, c.Path, c.WirePath, c.Upload)
	case "upload_stats":
		return b.UploadStats(c.Session, c.URL)
	case "lease":
		endpoint, err := transport.RawEndpoint(c.Path, c.WirePath)
		if err != nil {
			return nil, err
		}
		if c.CacheKey != "" {
			return b.LeaseCachedForOwner(c.Session, endpoint, c.CacheKey, c.CacheOwner)
		}
		return b.Lease(c.Session, endpoint, true)
	case "asset":
		return b.Lease(c.Session, c.Endpoint, false)
	case "preview":
		endpoint, err := transport.PreviewEndpoint(c.Path, c.WirePath)
		if err != nil {
			return nil, err
		}
		if c.Scope == "contain" {
			endpoint += "?fit=contain"
		}
		if c.CacheKey != "" {
			return b.LeaseCachedForOwner(c.Session, endpoint, c.CacheKey, c.CacheOwner)
		}
		return b.Lease(c.Session, endpoint, false)
	case "revoke":
		b.Revoke(c.URL)
		return nil, nil
	case "close_session":
		e.mu.Lock()
		delete(e.embedded, c.Session)
		e.mu.Unlock()
		b.CloseSession(c.Session)
		return nil, nil
	case "shutdown":
		e.mu.Lock()
		if e.broker == b {
			e.broker = nil
		}
		for _, stop := range e.pending {
			stop()
		}
		n := e.node
		e.node = nil
		e.networkEpoch++
		e.embedded = make(map[string]bool)
		e.mu.Unlock()
		if n != nil {
			_ = n.Close()
		}
		return nil, b.Close()
	default:
		return nil, errors.New("unknown native command")
	}
}
