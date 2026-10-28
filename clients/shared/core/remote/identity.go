package remote

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"math/big"
	"strings"
	"time"
	"unicode"
)

// Identity is private installation state. Android encrypts it in CredentialVault.
// Only Descriptor may be returned by an unauthenticated network endpoint.
type Identity struct {
	DeviceID       string `json:"deviceId"`
	ServerName     string `json:"serverName"`
	CertificatePEM string `json:"certificatePem"`
	PrivateKeyPEM  string `json:"privateKeyPem"`
}

type Descriptor struct {
	Protocol   int    `json:"protocol"`
	DeviceID   string `json:"deviceId"`
	Name       string `json:"name"`
	ServerName string `json:"serverName"`
	Address    string `json:"address"`
	Port       int    `json:"port"`
	SPKISHA256 string `json:"spkiSha256"`
}

type Controller struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	PublicKey string `json:"publicKey"`
}

func GenerateIdentity(now time.Time) (Identity, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return Identity{}, err
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return Identity{}, err
	}
	sum := sha256.Sum256(spki)
	id := hex.EncodeToString(sum[:16])
	name := "tv-" + id + ".fileway.local"
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return Identity{}, err
	}
	certificate := &x509.Certificate{
		SerialNumber: serial, Subject: pkix.Name{CommonName: name},
		NotBefore: now.Add(-24 * time.Hour), NotAfter: now.AddDate(20, 0, 0),
		DNSNames: []string{name}, KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		return Identity{}, err
	}
	secret, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return Identity{}, err
	}
	return Identity{DeviceID: id, ServerName: name,
		CertificatePEM: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		PrivateKeyPEM:  string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: secret})),
	}, nil
}

func (i Identity) certificate() (tls.Certificate, string, error) {
	if len(i.CertificatePEM) > 8192 || len(i.PrivateKeyPEM) > 4096 {
		return tls.Certificate{}, "", errors.New("invalid device identity")
	}
	cert, err := tls.X509KeyPair([]byte(i.CertificatePEM), []byte(i.PrivateKeyPEM))
	if err != nil || len(cert.Certificate) != 1 {
		return tls.Certificate{}, "", errors.New("invalid device identity")
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return tls.Certificate{}, "", err
	}
	key, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return tls.Certificate{}, "", errors.New("invalid device identity key")
	}
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	id := hex.EncodeToString(sum[:16])
	if i.DeviceID != id || i.ServerName != "tv-"+id+".fileway.local" || leaf.VerifyHostname(i.ServerName) != nil ||
		leaf.CheckSignature(leaf.SignatureAlgorithm, leaf.RawTBSCertificate, leaf.Signature) != nil {
		return tls.Certificate{}, "", errors.New("device identity mismatch")
	}
	cert.Leaf = leaf
	return cert, base64.RawURLEncoding.EncodeToString(sum[:]), nil
}

func parseController(controller Controller) (*ecdsa.PublicKey, error) {
	if len(controller.Name) == 0 || len(controller.Name) > 160 || strings.IndexFunc(controller.Name, unicode.IsControl) >= 0 || len(controller.PublicKey) > 256 {
		return nil, errors.New("invalid controller")
	}
	data, err := base64.RawURLEncoding.DecodeString(controller.PublicKey)
	if err != nil {
		return nil, errors.New("invalid controller key")
	}
	value, err := x509.ParsePKIXPublicKey(data)
	if err != nil {
		return nil, errors.New("invalid controller key")
	}
	key, ok := value.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return nil, errors.New("invalid controller key")
	}
	sum := sha256.Sum256(data)
	if controller.ID != hex.EncodeToString(sum[:]) {
		return nil, errors.New("controller identity mismatch")
	}
	return key, nil
}

// ProofMessage has an explicit domain and purpose, with unambiguous JSON fields.
// It is shared by pairing HMAC and the paired phone's ECDSA challenge proof.
func ProofMessage(purpose, deviceID, controllerID, nonce string) []byte {
	data, _ := json.Marshal([]string{"fileway.remote.v1", purpose, deviceID, controllerID, nonce})
	return data
}

func verifyPairProof(secret []byte, proof, deviceID, controllerID, nonce string) bool {
	value, err := base64.RawURLEncoding.DecodeString(proof)
	if err != nil || len(value) != sha256.Size {
		return false
	}
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write(ProofMessage("pair", deviceID, controllerID, nonce))
	return hmac.Equal(value, mac.Sum(nil))
}

func verifyControllerProof(key *ecdsa.PublicKey, signature, deviceID, controllerID, nonce string) bool {
	data, err := base64.RawURLEncoding.DecodeString(signature)
	if err != nil || len(data) > 80 {
		return false
	}
	sum := sha256.Sum256(ProofMessage("connect", deviceID, controllerID, nonce))
	return ecdsa.VerifyASN1(key, sum[:], data)
}

func randomID() (string, error) {
	var value [32]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value[:]), nil
}
