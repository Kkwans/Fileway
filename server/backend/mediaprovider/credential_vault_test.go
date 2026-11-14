package mediaprovider

import (
	"bytes"
	"testing"
)

func TestCredentialVaultBindsScopeAndStableFingerprint(t *testing.T) {
	v, err := NewCredentialVault(bytes.Repeat([]byte{1}, 32))
	if err != nil {
		t.Fatal(err)
	}
	const first = "fileway/provider/tmdb/first"
	const second = "fileway/provider/tmdb/second"
	data, fingerprint, err := v.Seal(first, "fixture-token")
	if err != nil {
		t.Fatal(err)
	}
	again, repeat, err := v.Seal(first, "fixture-token")
	if err != nil || bytes.Equal(data, again) || !bytes.Equal(fingerprint, repeat) || bytes.Contains(data, []byte("fixture-token")) {
		t.Fatal("expected random ciphertext and stable keyed fingerprint")
	}
	_, other, _ := v.Seal(second, "fixture-token")
	if bytes.Equal(fingerprint, other) {
		t.Fatal("fingerprints were shared across connections")
	}
	value, err := v.Open(first, data)
	if err != nil || value != "fixture-token" {
		t.Fatal("credential round trip failed")
	}
	if _, err := v.Open(second, data); err == nil {
		t.Fatal("copied credential decrypted in a different connection")
	}
	data[len(data)-1] ^= 1
	if _, err := v.Open(first, data); err == nil {
		t.Fatal("corrupt credential decrypted")
	}
}
