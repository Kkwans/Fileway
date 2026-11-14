package mediaprovider

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode"
	"unicode/utf8"
)

const credentialVaultKeySize = 32
const credentialLimit = 4096
const credentialVersion byte = 1

// CredentialVault seals a provider credential for a stable connection context.
// The master key belongs to the service operator, outside the catalog and
// outside media roots. Fingerprints identify identical in-flight requests
// without exposing the credential to the database.
type CredentialVault struct {
	aead        cipher.AEAD
	fingerprint []byte
}

func NewCredentialVault(key []byte) (*CredentialVault, error) {
	if len(key) != credentialVaultKeySize {
		return nil, errors.New("provider vault requires a 32-byte key")
	}
	block, err := aes.NewCipher(deriveVaultKey(key, "credential/encryption/v1"))
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &CredentialVault{aead: aead, fingerprint: deriveVaultKey(key, "credential/receipt/v1")}, nil
}

// LoadCredentialVault validates the actual opened file before reading a key.
// No error contains the configured path or key material.
func LoadCredentialVault(name string) (*CredentialVault, error) {
	if !filepath.IsAbs(name) || strings.HasPrefix(name, "//") || strings.HasPrefix(name, `\\`) {
		return nil, errors.New("provider key requires an absolute local path")
	}
	before, err := os.Lstat(name)
	if err != nil || !before.Mode().IsRegular() {
		return nil, errors.New("provider key must be a regular file")
	}
	file, err := os.Open(name)
	if err != nil {
		return nil, errors.New("provider key could not be opened")
	}
	defer func() { _ = file.Close() }()
	after, err := file.Stat()
	if err != nil || !os.SameFile(before, after) || !privateKeyFile(file, after) {
		return nil, errors.New("provider key file permissions must restrict access")
	}
	key, err := io.ReadAll(io.LimitReader(file, credentialVaultKeySize+1))
	defer clear(key)
	if err != nil {
		return nil, errors.New("provider key could not be read")
	}
	return NewCredentialVault(key)
}

func deriveVaultKey(master []byte, purpose string) []byte {
	mac := hmac.New(sha256.New, master)
	_, _ = mac.Write([]byte("fileway/" + purpose))
	return mac.Sum(nil)
}

func (v *CredentialVault) Seal(scope, token string) (ciphertext, fingerprint []byte, err error) {
	if !validCredentialScope(scope) || !validCredential(token) {
		return nil, nil, errors.New("invalid provider credential")
	}
	prefix := make([]byte, 1+v.aead.NonceSize())
	prefix[0] = credentialVersion
	if _, err := rand.Read(prefix[1:]); err != nil {
		return nil, nil, err
	}
	plain := []byte(token)
	defer clear(plain)
	additional := []byte(scope)
	ciphertext = v.aead.Seal(prefix, prefix[1:], plain, additional)
	mac := hmac.New(sha256.New, v.fingerprint)
	_, _ = mac.Write(additional)
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write(plain)
	return ciphertext, mac.Sum(nil), nil
}

func (v *CredentialVault) Open(scope string, ciphertext []byte) (string, error) {
	start := 1 + v.aead.NonceSize()
	if !validCredentialScope(scope) || len(ciphertext) <= start+v.aead.Overhead() ||
		len(ciphertext) > start+v.aead.Overhead()+credentialLimit || ciphertext[0] != credentialVersion {
		return "", errors.New("invalid provider credential envelope")
	}
	plain, err := v.aead.Open(nil, ciphertext[1:start], ciphertext[start:], []byte(scope))
	if err != nil {
		return "", errors.New("provider credential could not be decrypted")
	}
	defer clear(plain)
	return string(plain), nil
}

func validCredentialScope(value string) bool {
	return len(value) > 0 && len(value) <= 256 && !strings.ContainsRune(value, 0)
}

func validCredential(value string) bool {
	return len(value) > 0 && len(value) <= credentialLimit && utf8.ValidString(value) &&
		strings.IndexFunc(value, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) }) < 0
}
