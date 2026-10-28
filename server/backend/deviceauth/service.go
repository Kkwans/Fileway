// Package deviceauth owns independent, revocable TV credentials. Passwords and
// the approving phone's bearer token never become a device credential.
package deviceauth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"sync"
	"time"
)

var ErrDenied = errors.New("device authorization unavailable, expired or revoked")

type Session struct {
	ID          string `json:"id"`
	UserID      uint   `json:"userId"`
	DeviceID    string `json:"deviceId"`
	Name        string `json:"name"`
	PublicKey   string `json:"publicKey"`
	RefreshHash string `json:"refreshHash"`
	CreatedAt   int64  `json:"createdAt"`
	ExpiresAt   int64  `json:"expiresAt"`
	Revoked     bool   `json:"revoked"`
	// Retrying an acknowledged refresh with the same signed nonce returns the
	// same secret. This closes the commit/response-loss window without replay.
	LastNonce           string `json:"lastNonce"`
	PreviousRefreshHash string `json:"previousRefreshHash"`
}
type Request struct {
	ID            string `json:"id"`
	DeviceID      string `json:"deviceId"`
	Name          string `json:"name"`
	PublicKey     string `json:"publicKey"`
	Challenge     string `json:"challenge"`
	ExpiresAt     int64  `json:"expiresAt"`
	ApprovedBy    *uint  `json:"approvedBy,omitempty"`
	SessionID     string `json:"sessionId,omitempty"`
	ExchangeNonce string `json:"exchangeNonce,omitempty"`
}
type State struct {
	ServerID   string
	SigningKey []byte
	ProofKey   []byte
	Requests   map[string]Request
	Sessions   map[string]Session
}

// Update must commit the whole mutation atomically or preserve the old value.
// Persistent implementations serialize concurrent exchanges and refreshes.
type Store interface {
	Update(func(*State) error) error
}
type Service struct {
	store     Store
	admission sync.Mutex
	arrivals  map[string][]time.Time
}
type Identity struct {
	ID        string `json:"id"`
	PublicKey string `json:"publicKey"`
}
type Credential struct {
	Session Session
	Refresh string
}

func New(store Store) (*Service, error) {
	s := &Service{store: store, arrivals: make(map[string][]time.Time)}
	err := store.Update(func(state *State) error {
		if state.ServerID == "" {
			state.ServerID = random()
			state.SigningKey = make([]byte, 32)
			if _, err := rand.Read(state.SigningKey); err != nil {
				return err
			}
			key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
			if err != nil {
				return err
			}
			state.ProofKey, err = x509.MarshalECPrivateKey(key)
			if err != nil {
				return err
			}
		}
		if len(state.SigningKey) != 32 {
			return errors.New("invalid device signing key")
		}
		if state.Requests == nil {
			state.Requests = make(map[string]Request)
		}
		if state.Sessions == nil {
			state.Sessions = make(map[string]Session)
		}
		return nil
	})
	return s, err
}
func random() string {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b[:])
}
func digest(value string) string { d := sha256.Sum256([]byte(value)); return hex.EncodeToString(d[:]) }
func refreshSecret(st *State, id, nonce string) string {
	mac := hmac.New(sha256.New, st.SigningKey)
	_, _ = mac.Write(Message("refresh-secret", id, nonce))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
func equal(a, b string) bool { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }
func Message(purpose string, fields ...string) []byte {
	b, _ := json.Marshal(append([]string{"fileway.device-auth.v1", purpose}, fields...))
	return b
}
func ParseKey(encoded, deviceID string) (*ecdsa.PublicKey, error) {
	b, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil || len(b) > 256 {
		return nil, ErrDenied
	}
	v, err := x509.ParsePKIXPublicKey(b)
	if err != nil {
		return nil, ErrDenied
	}
	k, ok := v.(*ecdsa.PublicKey)
	if !ok || k.Curve != elliptic.P256() {
		return nil, ErrDenied
	}
	d := sha256.Sum256(b)
	if hex.EncodeToString(d[:16]) != deviceID {
		return nil, ErrDenied
	}
	return k, nil
}
func verify(encoded, deviceID, signature string, message []byte) bool {
	k, err := ParseKey(encoded, deviceID)
	if err != nil {
		return false
	}
	sig, err := base64.RawURLEncoding.DecodeString(signature)
	if err != nil || len(sig) > 80 {
		return false
	}
	hash := sha256.Sum256(message)
	return ecdsa.VerifyASN1(k, hash[:], sig)
}
func (s *Service) Identity() (result Identity, err error) {
	err = s.store.Update(func(st *State) error {
		k, e := x509.ParseECPrivateKey(st.ProofKey)
		if e != nil {
			return e
		}
		b, e := x509.MarshalPKIXPublicKey(&k.PublicKey)
		if e != nil {
			return e
		}
		result = Identity{st.ServerID, base64.RawURLEncoding.EncodeToString(b)}
		return nil
	})
	return
}
func (s *Service) Proof(nonce string) (identity Identity, signature string, err error) {
	if len(nonce) < 32 || len(nonce) > 96 {
		return identity, "", ErrDenied
	}
	err = s.store.Update(func(st *State) error {
		k, e := x509.ParseECPrivateKey(st.ProofKey)
		if e != nil {
			return e
		}
		b, e := x509.MarshalPKIXPublicKey(&k.PublicKey)
		if e != nil {
			return e
		}
		identity = Identity{st.ServerID, base64.RawURLEncoding.EncodeToString(b)}
		h := sha256.Sum256(Message("instance", st.ServerID, nonce))
		sig, e := ecdsa.SignASN1(rand.Reader, k, h[:])
		signature = base64.RawURLEncoding.EncodeToString(sig)
		return e
	})
	return
}
func (s *Service) SigningKey() ([]byte, error) {
	var key []byte
	err := s.store.Update(func(st *State) error { key = append([]byte(nil), st.SigningKey...); return nil })
	return key, err
}

// Bound unauthenticated request/proof/signature traffic using the socket peer,
// not caller-controlled forwarding headers. Trusted proxy policy is separate.
func (s *Service) Allow(remoteAddress string) bool {
	host, _, err := net.SplitHostPort(remoteAddress)
	if err != nil {
		return false
	}
	now := time.Now()
	s.admission.Lock()
	defer s.admission.Unlock()
	for address, times := range s.arrivals {
		if len(times) == 0 || now.Sub(times[len(times)-1]) > time.Minute {
			delete(s.arrivals, address)
		}
	}
	times := s.arrivals[host]
	kept := times[:0]
	for _, t := range times {
		if now.Sub(t) < time.Minute {
			kept = append(kept, t)
		}
	}
	if len(kept) >= 30 || len(s.arrivals) >= 256 && len(kept) == 0 {
		return false
	}
	s.arrivals[host] = append(kept, now)
	return true
}
func clean(st *State) {
	now := time.Now().Unix()
	for id, r := range st.Requests {
		if r.ExpiresAt < now {
			delete(st.Requests, id)
		}
	}
	for id, v := range st.Sessions {
		if v.ExpiresAt < now {
			delete(st.Sessions, id)
		}
	}
}
func (s *Service) Create(deviceID, name, publicKey string) (result Request, err error) {
	if _, err = ParseKey(publicKey, deviceID); err != nil {
		return
	}
	if len(name) == 0 || len(name) > 160 {
		return result, ErrDenied
	}
	err = s.store.Update(func(st *State) error {
		clean(st)
		if len(st.Requests) >= 128 {
			return ErrDenied
		}
		result = Request{ID: random(), DeviceID: deviceID, Name: name, PublicKey: publicKey, Challenge: random(), ExpiresAt: time.Now().Add(2 * time.Minute).Unix()}
		st.Requests[result.ID] = result
		return nil
	})
	return
}

// Approval is idempotent for the same account; it cannot silently replace a
// previous approver or alter the TV key shown to the approving phone.
func (s *Service) Approve(id, deviceID, publicKey string, user uint) error {
	return s.store.Update(func(st *State) error {
		r, ok := st.Requests[id]
		if !ok || r.ExpiresAt < time.Now().Unix() || r.DeviceID != deviceID || r.PublicKey != publicKey {
			return ErrDenied
		}
		if r.ApprovedBy != nil && *r.ApprovedBy != user {
			return ErrDenied
		}
		r.ApprovedBy = &user
		st.Requests[id] = r
		return nil
	})
}
func (s *Service) Exchange(id, nonce, proof string) (result Credential, err error) {
	err = s.store.Update(func(st *State) error {
		r, ok := st.Requests[id]
		if !ok || r.ExpiresAt < time.Now().Unix() || r.ApprovedBy == nil || len(nonce) < 32 || len(nonce) > 96 || !verify(r.PublicKey, r.DeviceID, proof, Message("exchange", st.ServerID, id, r.Challenge, nonce)) {
			return ErrDenied
		}
		if r.SessionID != "" {
			v, ok := st.Sessions[r.SessionID]
			if !ok || v.Revoked || r.ExchangeNonce != nonce || v.LastNonce != nonce {
				return ErrDenied
			}
			result = Credential{v, refreshSecret(st, v.ID, nonce)}
			return nil
		}
		clean(st)
		count := 0
		for _, v := range st.Sessions {
			if v.UserID == *r.ApprovedBy && !v.Revoked {
				count++
			}
		}
		if count >= 32 || len(st.Sessions) >= 4096 {
			return ErrDenied
		}
		sid := random()
		secret := refreshSecret(st, sid, nonce)
		v := Session{ID: sid, UserID: *r.ApprovedBy, DeviceID: r.DeviceID, Name: r.Name, PublicKey: r.PublicKey, RefreshHash: digest(secret), CreatedAt: time.Now().Unix(), ExpiresAt: time.Now().Add(365 * 24 * time.Hour).Unix(), LastNonce: nonce}
		st.Sessions[v.ID] = v
		r.SessionID = v.ID
		r.ExchangeNonce = nonce
		st.Requests[id] = r
		result = Credential{v, secret}
		return nil
	})
	return
}
func (s *Service) Refresh(id, refresh, nonce, proof string) (result Credential, err error) {
	err = s.store.Update(func(st *State) error {
		v, ok := st.Sessions[id]
		if !ok || v.Revoked || v.ExpiresAt < time.Now().Unix() || len(nonce) < 32 || len(nonce) > 96 || !verify(v.PublicKey, v.DeviceID, proof, Message("refresh", st.ServerID, id, refresh, nonce)) {
			return ErrDenied
		}
		// Lost responses can retry the identical signed nonce and previous secret.
		if v.LastNonce == nonce {
			if !equal(v.PreviousRefreshHash, digest(refresh)) {
				return ErrDenied
			}
			result = Credential{v, refreshSecret(st, id, nonce)}
			return nil
		}
		if !equal(v.RefreshHash, digest(refresh)) {
			return ErrDenied
		}
		secret := refreshSecret(st, id, nonce)
		v.PreviousRefreshHash = v.RefreshHash
		v.RefreshHash = digest(secret)
		v.LastNonce = nonce
		v.ExpiresAt = time.Now().Add(365 * 24 * time.Hour).Unix()
		st.Sessions[id] = v
		result = Credential{v, secret}
		return nil
	})
	return
}
func (s *Service) Validate(id string, user uint) (result Session, err error) {
	err = s.store.Update(func(st *State) error {
		v, ok := st.Sessions[id]
		if !ok || v.UserID != user || v.Revoked || v.ExpiresAt < time.Now().Unix() {
			return ErrDenied
		}
		result = v
		return nil
	})
	return
}
func (s *Service) List(user uint) (result []Session, err error) {
	result = []Session{}
	err = s.store.Update(func(st *State) error {
		for _, v := range st.Sessions {
			if v.UserID == user && !v.Revoked && v.ExpiresAt >= time.Now().Unix() {
				result = append(result, v)
			}
		}
		return nil
	})
	return
}
func (s *Service) Revoke(id string, user uint) error {
	return s.store.Update(func(st *State) error {
		v, ok := st.Sessions[id]
		if !ok || v.UserID != user {
			return ErrDenied
		}
		v.Revoked = true
		v.RefreshHash = ""
		v.PreviousRefreshHash = ""
		st.Sessions[id] = v
		return nil
	})
}
