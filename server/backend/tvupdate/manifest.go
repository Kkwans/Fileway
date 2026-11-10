// Package tvupdate brokers published, signed Fileway TV updates.
package tvupdate

import (
	"bytes"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"regexp"
	"strings"
	"unicode/utf8"
)

const (
	manifestName   = "tv-update-v1.json"
	signatureName  = "tv-update-v1.sig"
	manifestLimit  = 64 << 10
	signatureLimit = 1024
	apkLimit       = 128 << 20
	signingDomain  = "Fileway-TV-Update/v1\x00"
	originalSigner = "ad35201bcbbcba94754447da9f6e7f369c2a4264bfd5e179194a3cd0b0a0c4a9"
)

//go:embed release-cert.pem
var releaseCertificate []byte

var (
	tvTag           = regexp.MustCompile(`^android-tv-([0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6})$`)
	versionName     = regexp.MustCompile(`^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}(-[A-Za-z0-9][A-Za-z0-9.-]{0,31})?$`)
	digestPattern   = regexp.MustCompile(`^[0-9a-f]{64}$`)
	revisionPattern = regexp.MustCompile(`^[0-9a-f]{40}$`)
	manifestFields  = strings.Fields("schema product applicationId tag channel versionName versionCode minSdk abis apkName size sha256 signerSha256 sourceRevision notes")
)

type manifest struct {
	Schema         int      `json:"schema"`
	Product        string   `json:"product"`
	ApplicationID  string   `json:"applicationId"`
	Tag            string   `json:"tag"`
	Channel        string   `json:"channel"`
	VersionName    string   `json:"versionName"`
	VersionCode    int64    `json:"versionCode"`
	MinSDK         int      `json:"minSdk"`
	ABIs           []string `json:"abis"`
	APKName        string   `json:"apkName"`
	Size           int64    `json:"size"`
	SHA256         string   `json:"sha256"`
	SignerSHA256   string   `json:"signerSha256"`
	SourceRevision string   `json:"sourceRevision"`
	Notes          string   `json:"notes"`
}

type trustRoot struct {
	key         *rsa.PublicKey
	fingerprint string
}

func embeddedTrust() (trustRoot, error) {
	block, rest := pem.Decode(releaseCertificate)
	if block == nil || block.Type != "CERTIFICATE" || len(bytes.TrimSpace(rest)) != 0 {
		return trustRoot{}, errors.New("invalid TV release certificate")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return trustRoot{}, err
	}
	key, ok := cert.PublicKey.(*rsa.PublicKey)
	fingerprint := sha256.Sum256(cert.Raw)
	if !ok || hex.EncodeToString(fingerprint[:]) != originalSigner {
		return trustRoot{}, errors.New("unexpected TV release certificate")
	}
	return trustRoot{key, originalSigner}, nil
}

func supportedABI(value string) bool {
	switch value {
	case "armeabi-v7a", "arm64-v8a", "x86", "x86_64":
		return true
	default:
		return false
	}
}

func validABIs(abis []string) bool {
	if len(abis) < 1 || len(abis) > 4 {
		return false
	}
	for index, abi := range abis {
		if !supportedABI(abi) {
			return false
		}
		for _, earlier := range abis[:index] {
			if earlier == abi {
				return false
			}
		}
	}
	return true
}

func (root trustRoot) parse(payload, signature []byte) (manifest, bool) {
	if len(payload) > manifestLimit || len(signature) != root.key.Size() || !utf8.Valid(payload) {
		return manifest{}, false
	}
	hash := sha256.New()
	_, _ = io.WriteString(hash, signingDomain)
	_, _ = hash.Write(payload)
	if rsa.VerifyPKCS1v15(root.key, crypto.SHA256, hash.Sum(nil), signature) != nil || !exactManifestFields(payload) {
		return manifest{}, false
	}
	var m manifest
	if err := json.Unmarshal(payload, &m); err != nil {
		return manifest{}, false
	}
	parts := tvTag.FindStringSubmatch(m.Tag)
	if len(parts) != 2 || m.APKName != "Fileway-TV-"+parts[1]+".apk" ||
		m.Schema != 1 || m.Product != "android-tv" || m.ApplicationID != "io.github.kkwans.fileway.tv" ||
		(m.Channel != "stable" && m.Channel != "preview") || !versionName.MatchString(m.VersionName) ||
		strings.SplitN(m.VersionName, "-", 2)[0] != parts[1] || m.VersionCode <= 0 ||
		m.MinSDK < 28 || m.MinSDK > 1000 || !validABIs(m.ABIs) || m.Size <= 0 || m.Size > apkLimit ||
		!digestPattern.MatchString(m.SHA256) || m.SignerSHA256 != root.fingerprint ||
		!revisionPattern.MatchString(m.SourceRevision) || len(m.Notes) > 32<<10 {
		return manifest{}, false
	}
	return m, true
}

// Reject missing, null, unknown and repeated fields before decoding into a struct.
func exactManifestFields(payload []byte) bool {
	decoder := json.NewDecoder(bytes.NewReader(payload))
	if token, err := decoder.Token(); err != nil || token != json.Delim('{') {
		return false
	}
	fields := make(map[string]bool, len(manifestFields))
	for decoder.More() {
		token, err := decoder.Token()
		name, ok := token.(string)
		if err != nil || !ok || fields[name] || len(fields) == len(manifestFields) {
			return false
		}
		var value json.RawMessage
		if err := decoder.Decode(&value); err != nil || bytes.Equal(value, []byte("null")) {
			return false
		}
		fields[name] = true
	}
	for _, field := range manifestFields {
		if !fields[field] {
			return false
		}
	}
	if token, err := decoder.Token(); err != nil || token != json.Delim('}') {
		return false
	}
	_, err := decoder.Token()
	return err == io.EOF
}
