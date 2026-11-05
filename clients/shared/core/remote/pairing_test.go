package remote

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"strconv"
	"testing"
	"time"
)

type approvalFixture struct {
	host       *Host
	controller Controller
	key        *ecdsa.PrivateKey
	post       func(string, any) (int, map[string]any)
}

func newApprovalFixture(t *testing.T, enabled bool) approvalFixture {
	t.Helper()
	identity, err := GenerateIdentity(time.Now())
	if err != nil {
		t.Fatal(err)
	}
	host, err := Start(Config{Identity: identity, Name: "Owned approval TV", ListenAddress: "127.0.0.1", ApprovalPairing: enabled})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = host.Close() })
	descriptor := host.Descriptor()
	certificate, _, err := identity.certificate()
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(certificate.Leaf)
	transport := &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool},
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, network, net.JoinHostPort(descriptor.Address, strconv.Itoa(descriptor.Port)))
		}}
	t.Cleanup(transport.CloseIdleConnections)
	client := &http.Client{Transport: transport, Timeout: 3 * time.Second}
	origin := "https://" + net.JoinHostPort(descriptor.ServerName, strconv.Itoa(descriptor.Port))
	post := func(path string, input any) (int, map[string]any) {
		t.Helper()
		data, err := json.Marshal(input)
		if err != nil {
			t.Fatal(err)
		}
		response, err := client.Post(origin+path, "application/json", bytes.NewReader(data))
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = response.Body.Close() }()
		var value map[string]any
		if err := json.NewDecoder(response.Body).Decode(&value); err != nil {
			t.Fatal(err)
		}
		return response.StatusCode, value
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(spki)
	controller := Controller{hex.EncodeToString(sum[:]), "Owned phone", base64.RawURLEncoding.EncodeToString(spki)}
	return approvalFixture{host, controller, key, post}
}

func (f approvalFixture) request(t *testing.T) ApprovalStart {
	t.Helper()
	nonce, err := randomID()
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(ProofMessage("pair-request", f.host.Descriptor().DeviceID, f.controller.ID, nonce))
	signature, err := ecdsa.SignASN1(rand.Reader, f.key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return ApprovalStart{f.controller, nonce, base64.RawURLEncoding.EncodeToString(signature)}
}

func (f approvalFixture) begin(t *testing.T) (ApprovalStart, ApprovalAuth) {
	t.Helper()
	request := f.request(t)
	status, result := f.post("/v1/pairing/start", request)
	if status != 200 || result["status"] != "PENDING" {
		t.Fatal("start approval", status, result)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	event, err := f.host.Wait(ctx)
	if err != nil || event.Type != "pair.approval" || event.Controller.ID != f.controller.ID || event.Approval.ID != result["id"] {
		t.Fatal("missing exact local approval event", event, err)
	}
	if code := ApprovalCode(f.host.Descriptor().DeviceID, f.controller.ID, request.Nonce); len(code) != 6 || event.Approval.VerificationCode != code {
		t.Fatal("comparison code not bound to request")
	}
	return request, ApprovalAuth{event.PairRequestID, f.controller.ID, request.Nonce, request.Signature}
}

func TestApprovalRequiresLocalDecisionAndDurableStorage(t *testing.T) {
	f := newApprovalFixture(t, true)
	if f.host.Descriptor().PairingVersion != 2 {
		t.Fatal("missing opt-in capability")
	}
	request, auth := f.begin(t)
	if status, _ := f.post("/v1/challenge", map[string]string{"controllerId": f.controller.ID}); status != 401 {
		t.Fatal("pending request acquired control")
	}
	if status, result := f.post("/v1/pairing/start", request); status != 200 || result["id"] != auth.ID || len(f.host.events) != 0 {
		t.Fatal("retry created another prompt", status, result)
	}
	if status, result := f.post("/v1/pairing/status", auth); status != 200 || result["status"] != "PENDING" {
		t.Fatal(status, result)
	}
	if err := f.host.CompletePair(auth.ID, true); err != nil {
		t.Fatal(err)
	}
	// An uncertain response is reconciled without a new invitation or prompt.
	if status, result := f.post("/v1/pairing/start", request); status != 200 || result["id"] != auth.ID || result["status"] != "PAIRED" {
		t.Fatal("lost result not reconcilable", status, result)
	}
	if status, result := f.post("/v1/pairing/cancel", auth); status != 200 || result["status"] != "PAIRED" {
		t.Fatal("cancel rewrote acknowledged pairing", status, result)
	}
	if status, _ := f.post("/v1/challenge", map[string]string{"controllerId": f.controller.ID}); status != 200 {
		t.Fatal("approved phone cannot authenticate", status)
	}
}

func TestApprovalRejectCancelFailureAndExpiryNeverTrust(t *testing.T) {
	for _, outcome := range []string{"DENIED", "CANCELLED", "FAILED", "EXPIRED"} {
		t.Run(outcome, func(t *testing.T) {
			f := newApprovalFixture(t, true)
			_, auth := f.begin(t)
			switch outcome {
			case "DENIED":
				if err := f.host.RejectPair(auth.ID); err != nil {
					t.Fatal(err)
				}
			case "CANCELLED":
				if status, _ := f.post("/v1/pairing/cancel", auth); status != 200 {
					t.Fatal(status)
				}
			case "FAILED":
				if err := f.host.CompletePair(auth.ID, false); err != nil {
					t.Fatal(err)
				}
			case "EXPIRED":
				f.host.mu.Lock()
				f.host.approvals[auth.ID].until = time.Now().Add(-time.Second)
				f.host.mu.Unlock()
			}
			if err := f.host.CompletePair(auth.ID, true); err == nil {
				t.Fatal("stale approval completed")
			}
			if status, result := f.post("/v1/pairing/status", auth); status != 200 || result["status"] != outcome {
				t.Fatal("terminal result lost", status, result)
			}
			if status, _ := f.post("/v1/challenge", map[string]string{"controllerId": f.controller.ID}); status != 401 {
				t.Fatal("unapproved key became trusted")
			}
		})
	}
}

func TestApprovalProofScopeAndPendingBound(t *testing.T) {
	f := newApprovalFixture(t, true)
	request, auth := f.begin(t)
	wrong := auth
	wrong.Nonce, _ = randomID()
	if status, _ := f.post("/v1/pairing/cancel", wrong); status != 404 {
		t.Fatal("unauthorized cancel accepted", status)
	}
	if status, _ := f.post("/v1/pairing/start", f.request(t)); status != 409 || len(f.host.events) != 0 {
		t.Fatal("parallel prompts not bounded", status)
	}
	// A connect signature is not an approval-request signature.
	digest := sha256.Sum256(ProofMessage("connect", f.host.Descriptor().DeviceID, f.controller.ID, request.Nonce))
	signature, _ := ecdsa.SignASN1(rand.Reader, f.key, digest[:])
	request.Signature = base64.RawURLEncoding.EncodeToString(signature)
	if status, _ := f.post("/v1/pairing/start", request); status != 400 {
		t.Fatal("cross-purpose proof accepted", status)
	}
	if status, result := f.post("/v1/pairing/status", auth); status != 200 || result["status"] != "PENDING" {
		t.Fatal("unauthorized request changed decision", status, result)
	}
}

func TestApprovalRequiresHostUIOptIn(t *testing.T) {
	f := newApprovalFixture(t, false)
	if f.host.Descriptor().PairingVersion != 0 {
		t.Fatal("unsupported host advertises approval")
	}
	if status, _ := f.post("/v1/pairing/start", f.request(t)); status != 404 || len(f.host.events) != 0 {
		t.Fatal("host without approval UI accepts requests", status)
	}
}

func TestApprovalCancelAndDurableCompletionSerialize(t *testing.T) {
	f := newApprovalFixture(t, true)
	_, auth := f.begin(t)
	completed := make(chan error, 1)
	go func() { completed <- f.host.CompletePair(auth.ID, true) }()
	_, cancellation := f.post("/v1/pairing/cancel", auth)
	completion := <-completed
	state, err := f.host.ApprovalStatus(auth.ID)
	if err != nil {
		t.Fatal(err)
	}
	f.host.mu.Lock()
	_, trusted := f.host.controllers[f.controller.ID]
	f.host.mu.Unlock()
	if completion == nil {
		if !trusted || state.Status != "PAIRED" || cancellation["status"] != "PAIRED" {
			t.Fatal("acknowledged completion lost", state, cancellation)
		}
	} else if trusted || state.Status != "CANCELLED" || cancellation["status"] != "CANCELLED" {
		t.Fatal("cancelled request gained trust", state, cancellation)
	}
}
