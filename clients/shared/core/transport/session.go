package transport

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type renewalFlight struct {
	previous string
	done     chan struct{}
	err      error
}

// SessionState is local transport state; reading it never contacts the server.
// Session handles are immutable account/generation owners, including media leases.
type SessionState struct {
	Token            string `json:"token"`
	Health           string `json:"health,omitempty"`
	BusinessRevision uint64 `json:"businessRevision"`
}

func (b *Broker) SessionState(id string) (SessionState, error) {
	s, err := b.session(id)
	if err != nil {
		return SessionState{}, err
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return SessionState{Token: s.token, Health: s.health, BusinessRevision: s.businessRevision}, nil
}

type sessionClaims struct {
	DeviceSession string `json:"sid"`
	Scope         string `json:"scope"`
	IssuedAt      int64  `json:"iat"`
	ExpiresAt     int64  `json:"exp"`
	User          struct {
		ID json.Number `json:"id"`
	} `json:"user"`
}

// AdoptDeviceToken refreshes an already-bound device session without replacing
// its Range/media leases. A token is verified by the same origin before use;
// it can never change the account, session ID or media-only authority.
func (b *Broker) AdoptDeviceToken(ctx context.Context, id string, expectedUser int64, candidate string) (SessionState, error) {
	s, err := b.session(id)
	if err != nil {
		return SessionState{}, err
	}
	previous := s.currentToken()
	old, valid := tokenClaims(previous)
	fresh, newValid := tokenClaims(candidate)
	if !valid || !newValid || expectedUser < 0 || old.User.ID.String() != strconv.FormatInt(expectedUser, 10) || old.User.ID != fresh.User.ID || old.DeviceSession == "" || old.DeviceSession != fresh.DeviceSession || old.Scope != "media" || fresh.Scope != "media" {
		return SessionState{}, errors.New("device account or session changed")
	}
	call, done := combined(ctx, s.ctx)
	defer done()
	verifier := &Session{base: s.base, client: s.client, ctx: s.ctx, token: candidate}
	response, err := verifier.do(call, http.MethodGet, "/api/client-capabilities", nil, nil)
	if err != nil {
		return SessionState{}, err
	}
	result, err := readResponse(response)
	if err != nil {
		return SessionState{}, err
	}
	if result.Status != http.StatusOK {
		return SessionState{}, errors.New("device credential rejected")
	}
	next := verifier.currentToken()
	confirmed, ok := tokenClaims(next)
	if !ok || confirmed.User.ID != old.User.ID || confirmed.DeviceSession != old.DeviceSession || confirmed.Scope != "media" {
		return SessionState{}, errors.New("device credential identity changed")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err = call.Err(); err != nil {
		return SessionState{}, err
	}
	current, ok := tokenClaims(s.token)
	if !ok || current.DeviceSession != old.DeviceSession || current.User.ID != old.User.ID {
		return SessionState{}, errors.New("device session changed")
	}
	if s.token == previous || confirmed.IssuedAt >= current.IssuedAt {
		s.token = next
	}
	s.health = ""
	return SessionState{Token: s.token, Health: s.health, BusinessRevision: s.businessRevision}, nil
}

func tokenClaims(token string) (sessionClaims, bool) {
	var claims sessionClaims
	parts := strings.Split(token, ".")
	if len(parts) != 3 || len(parts[1]) > 262144 {
		return claims, false
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil || json.Unmarshal(raw, &claims) != nil || claims.User.ID == "" {
		return claims, false
	}
	return claims, true
}
func shouldRenew(res *http.Response, previous string) bool {
	if previous == "" || res.StatusCode == http.StatusUnauthorized {
		return false
	}
	switch res.Header.Get("X-Renew-Token") {
	case "true":
		return true
	case "false":
		return false
	}
	if (res.StatusCode < 200 && res.StatusCode != http.StatusSwitchingProtocols) || res.StatusCode >= 400 {
		return false
	}
	// Older servers only hint near expiry. Keep their endpoint and token contract,
	// while deriving activity from this actual authenticated request, never a timer.
	claims, ok := tokenClaims(previous)
	now := time.Now().Unix()
	return ok && claims.IssuedAt < now && claims.ExpiresAt > now
}
func (s *Session) afterHeaders(ctx context.Context, res *http.Response, previous string) {
	if res.StatusCode >= 200 && res.StatusCode < 400 {
		s.mu.Lock()
		if s.token == previous {
			s.businessRevision++
		}
		s.mu.Unlock()
	}
	if res.StatusCode == http.StatusUnauthorized {
		s.mu.Lock()
		if s.token == previous {
			s.health = "expired"
		}
		res.Header.Set("X-Fileway-Session-Health", s.health)
		s.mu.Unlock()
		return
	}
	if !shouldRenew(res, previous) {
		return
	}
	err := s.renew(ctx, previous)
	s.mu.Lock()
	defer s.mu.Unlock()
	if err != nil && s.token == previous {
		s.health = "renewal_failed"
		if errors.Is(err, errRenewalUnauthorized) {
			// Preserve legacy upload telemetry without describing a successful
			// business response as an expired original request.
			res.Header.Set("X-Fileway-Session-Expired", "true")
		}
	}
	res.Header.Set("X-Fileway-Session-Health", s.health)
	// A renewal failure describes session health, never the already received
	// business response. In particular retain successful Range bodies and writes.
}
func (s *Session) renew(ctx context.Context, previous string) error {
	s.renewMu.Lock()
	if s.currentToken() != previous {
		s.renewMu.Unlock()
		return nil
	}
	if flight := s.renewing; flight != nil && flight.previous == previous {
		s.renewMu.Unlock()
		select {
		case <-flight.done:
			return flight.err
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	flight := &renewalFlight{previous: previous, done: make(chan struct{})}
	s.renewing = flight
	s.renewMu.Unlock()
	err := s.performRenew(previous)
	s.renewMu.Lock()
	flight.err = err
	if s.renewing == flight {
		s.renewing = nil
	}
	close(flight.done)
	s.renewMu.Unlock()
	return err
}
func (s *Session) performRenew(previous string) error {
	// A canceled short request must not cancel renewal needed by other business
	// calls in this immutable session. Session closure still cancels immediately.
	ctx, cancel := context.WithTimeout(s.ctx, 15*time.Second)
	defer cancel()
	res, err := s.do(ctx, http.MethodPost, "/api/renew", nil, nil)
	if err != nil {
		return err
	}
	response, err := readResponse(res)
	if err != nil {
		return err
	}
	if response.Status == http.StatusUnauthorized {
		return errRenewalUnauthorized
	}
	next := strings.TrimSpace(response.Body)
	if response.Status != http.StatusOK || strings.Count(next, ".") != 2 {
		return errors.New("session renewal unavailable")
	}
	if old, ok := tokenClaims(previous); ok {
		fresh, valid := tokenClaims(next)
		if !valid || old.User.ID != fresh.User.ID {
			return errors.New("renewal account mismatch")
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil {
		return s.ctx.Err()
	}
	if s.token == previous {
		s.token = next
		s.health = ""
	}
	return nil
}

// RenewSession is explicit foreground restoration, not a timer/keepalive.
func (b *Broker) RenewSession(ctx context.Context, id string) (SessionState, error) {
	s, err := b.session(id)
	if err != nil {
		return SessionState{}, err
	}
	if err = s.renew(ctx, s.currentToken()); err != nil {
		s.mu.Lock()
		s.health = "renewal_failed"
		if errors.Is(err, errRenewalUnauthorized) {
			s.health = "expired"
		}
		s.mu.Unlock()
		return SessionState{}, err
	}
	return b.SessionState(id)
}

// RecoverSession keeps the existing handle/leases and only installs a candidate
// after validating the immutable account. It never replays a business request.
func (b *Broker) RecoverSession(ctx context.Context, id string, expectedUser int64, username, password string) (Response, error) {
	s, err := b.session(id)
	if err != nil {
		return Response{}, err
	}
	previous := s.currentToken()
	old, valid := tokenClaims(previous)
	if !valid || expectedUser < 0 || old.User.ID.String() != strconv.FormatInt(expectedUser, 10) {
		return Response{}, errors.New("cannot confirm saved account")
	}
	body, err := json.Marshal(map[string]string{"username": username, "password": password})
	if err != nil {
		return Response{}, err
	}
	call, done := combined(ctx, s.ctx)
	defer done()
	res, err := s.do(call, http.MethodPost, "/api/login", body, nil)
	if err != nil {
		return Response{}, err
	}
	result, err := readResponse(res)
	if err != nil || result.Status != http.StatusOK {
		return result, err
	}
	candidate := strings.TrimSpace(result.Body)
	fresh, valid := tokenClaims(candidate)
	if !valid || fresh.User.ID != old.User.ID {
		return Response{}, errors.New("saved account identity changed")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := call.Err(); err != nil {
		return Response{}, err
	}
	if s.token != previous {
		current, valid := tokenClaims(s.token)
		if !valid || current.User.ID != old.User.ID {
			return Response{}, errors.New("saved account identity changed")
		}
		result.Body = s.token
		return result, nil
	}
	s.token = candidate
	s.health = ""
	result.Body = candidate
	return result, nil
}
