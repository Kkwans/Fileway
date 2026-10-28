package deviceauth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"sync"
	"testing"
)

type memoryStore struct {
	mu   sync.Mutex
	data []byte
}

func (m *memoryStore) Update(fn func(*State) error) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := new(State)
	if len(m.data) > 0 {
		if e := json.Unmarshal(m.data, st); e != nil {
			return e
		}
	}
	if e := fn(st); e != nil {
		return e
	}
	var e error
	m.data, e = json.Marshal(st)
	return e
}
func TestGrantIndependentKeyRotationAndRevocation(t *testing.T) {
	store := new(memoryStore)
	service, err := New(store)
	if err != nil {
		t.Fatal(err)
	}
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	spki, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	hash := sha256.Sum256(spki)
	id := hex.EncodeToString(hash[:16])
	public := base64.RawURLEncoding.EncodeToString(spki)
	identity, _ := service.Identity()
	r, err := service.Create(id, "TV", public)
	if err != nil {
		t.Fatal(err)
	}
	sign := func(purpose string, fields ...string) string {
		digest := sha256.Sum256(Message(purpose, fields...))
		signature, e := ecdsa.SignASN1(rand.Reader, key, digest[:])
		if e != nil {
			t.Fatal(e)
		}
		return base64.RawURLEncoding.EncodeToString(signature)
	}
	nonce := random()
	proof := sign("exchange", identity.ID, r.ID, r.Challenge, nonce)
	if _, err = service.Exchange(r.ID, nonce, proof); err == nil {
		t.Fatal("unapproved device signed in")
	}
	if err = service.Approve(r.ID, id, public, 7); err != nil {
		t.Fatal(err)
	}
	if err = service.Approve(r.ID, id, public, 8); err == nil {
		t.Fatal("approval account overwritten")
	}
	credential, err := service.Exchange(r.ID, nonce, proof)
	if err != nil {
		t.Fatal(err)
	}
	retry, err := service.Exchange(r.ID, nonce, proof)
	if err != nil || retry.Refresh != credential.Refresh || retry.Session.ID != credential.Session.ID {
		t.Fatal("lost exchange response cannot recover", err)
	}
	if _, err = service.Validate(credential.Session.ID, 8); err == nil {
		t.Fatal("cross-account device session accepted")
	}
	// Reopen from serialized durable state before rotating, then repeat a lost
	// refresh reply. Refresh secrets must never appear in database plaintext.
	reopened, err := New(store)
	if err != nil {
		t.Fatal(err)
	}
	nextNonce := random()
	nextProof := sign("refresh", identity.ID, credential.Session.ID, credential.Refresh, nextNonce)
	next, err := reopened.Refresh(credential.Session.ID, credential.Refresh, nextNonce, nextProof)
	if err != nil {
		t.Fatal(err)
	}
	if next.Refresh == credential.Refresh {
		t.Fatal("refresh did not rotate")
	}
	replayed, err := reopened.Refresh(credential.Session.ID, credential.Refresh, nextNonce, nextProof)
	if err != nil || replayed.Refresh != next.Refresh {
		t.Fatal("lost refresh reply cannot recover", err)
	}
	otherNonce := random()
	if _, err = reopened.Refresh(credential.Session.ID, credential.Refresh, otherNonce, sign("refresh", identity.ID, credential.Session.ID, credential.Refresh, otherNonce)); err == nil {
		t.Fatal("old refresh reused for new request")
	}
	var persisted map[string]any
	if err = json.Unmarshal(store.data, &persisted); err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{credential.Refresh, next.Refresh} {
		if containsJSONValue(persisted, secret) {
			t.Fatal("plaintext refresh persisted")
		}
	}
	if err = reopened.Revoke(credential.Session.ID, 8); err == nil {
		t.Fatal("other account revoked device")
	}
	if err = reopened.Revoke(credential.Session.ID, 7); err != nil {
		t.Fatal(err)
	}
	if _, err = reopened.Validate(credential.Session.ID, 7); err == nil {
		t.Fatal("revoked device accepted")
	}
}
func containsJSONValue(value any, wanted string) bool {
	switch v := value.(type) {
	case string:
		return v == wanted
	case map[string]any:
		for _, item := range v {
			if containsJSONValue(item, wanted) {
				return true
			}
		}
	case []any:
		for _, item := range v {
			if containsJSONValue(item, wanted) {
				return true
			}
		}
	}
	return false
}
