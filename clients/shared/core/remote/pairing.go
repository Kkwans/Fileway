package remote

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"net/http"
	"time"
)

const approvalLifetime = 2 * time.Minute

// ApprovalStart proves possession of an installation key. It does not grant
// trust: only an explicit TV decision followed by durable storage can do that.
type ApprovalStart struct {
	Controller Controller `json:"controller"`
	Nonce      string     `json:"nonce"`
	Signature  string     `json:"signature"`
}

type ApprovalAuth struct {
	ID           string `json:"id"`
	ControllerID string `json:"controllerId"`
	Nonce        string `json:"nonce"`
	Signature    string `json:"signature"`
}

type ApprovalState struct {
	ID               string `json:"id"`
	DeviceID         string `json:"deviceId"`
	ControllerID     string `json:"controllerId"`
	Status           string `json:"status"`
	VerificationCode string `json:"verificationCode"`
	ExpiresIn        int    `json:"expiresIn"`
}

type approvalPending struct {
	controller Controller
	key        *ecdsa.PublicKey
	nonce      string
	status     string
	until      time.Time
}

// ApprovalCode is a comparison code, not an authentication secret or a PIN.
// Both displays bind it to the actual TV key, phone key and request nonce.
func ApprovalCode(deviceID, controllerID, nonce string) string {
	sum := sha256.Sum256(ProofMessage("pair-request", deviceID, controllerID, nonce))
	return fmt.Sprintf("%06d", binary.BigEndian.Uint32(sum[:4])%1_000_000)
}

func (h *Host) expireApprovalsLocked(now time.Time) {
	for id, pending := range h.approvals {
		if now.After(pending.until.Add(approvalLifetime)) {
			delete(h.approvals, id)
		} else if pending.status == "PENDING" && !now.Before(pending.until) {
			pending.status = "EXPIRED"
		}
	}
}

func (h *Host) approvalStateLocked(id string, pending *approvalPending) ApprovalState {
	remaining := max(0, int(time.Until(pending.until).Seconds()))
	return ApprovalState{id, h.descriptor.DeviceID, pending.controller.ID, pending.status,
		ApprovalCode(h.descriptor.DeviceID, pending.controller.ID, pending.nonce), remaining}
}

func (h *Host) startApproval(w http.ResponseWriter, r *http.Request) {
	var input ApprovalStart
	if !readRequest(w, r, &input) {
		return
	}
	key, err := parseController(input.Controller)
	if err != nil || !opaqueID.MatchString(input.Nonce) ||
		!verifyKeyProof(key, input.Signature, "pair-request", h.descriptor.DeviceID, input.Controller.ID, input.Nonce) {
		writeError(w, "INVALID_CONTROLLER", http.StatusBadRequest)
		return
	}
	id, err := randomID()
	if err != nil {
		writeError(w, "UNAVAILABLE", http.StatusServiceUnavailable)
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	now := time.Now()
	h.expireApprovalsLocked(now)
	for requestID, pending := range h.pairs {
		if !now.Before(pending.until) {
			delete(h.pairs, requestID)
		}
	}
	// Lost responses are resolved using the original signed request, without
	// another TV prompt or another saved key. Name changes cannot rewrite it.
	for requestID, pending := range h.approvals {
		if pending.controller.ID == input.Controller.ID && pending.nonce == input.Nonce {
			writeJSON(w, h.approvalStateLocked(requestID, pending))
			return
		}
	}
	for _, pending := range h.approvals {
		if pending.status == "PENDING" {
			writeError(w, "PAIRING_BUSY", http.StatusConflict)
			return
		}
	}
	if len(h.controllers) >= 16 || len(h.approvals) >= 16 || len(h.pairs) != 0 || h.ctx.Err() != nil {
		writeError(w, "PAIRING_UNAVAILABLE", http.StatusConflict)
		return
	}
	if now.Sub(h.lastApproval) < 5*time.Second {
		writeError(w, "RATE_LIMITED", http.StatusTooManyRequests)
		return
	}
	pending := &approvalPending{input.Controller, key, input.Nonce, "PENDING", now.Add(approvalLifetime)}
	state := h.approvalStateLocked(id, pending)
	select {
	case h.events <- Event{Type: "pair.approval", PairRequestID: id, Controller: &input.Controller, Approval: &state}:
		h.approvals[id] = pending
		h.lastApproval = now
		writeJSON(w, state)
	default:
		writeError(w, "DEVICE_BUSY", http.StatusServiceUnavailable)
	}
}

func (h *Host) approvalPendingLocked(now time.Time) bool {
	h.expireApprovalsLocked(now)
	for _, pending := range h.approvals {
		if pending.status == "PENDING" {
			return true
		}
	}
	return false
}

func (h *Host) approvalStatus(w http.ResponseWriter, r *http.Request, cancel bool) {
	var input ApprovalAuth
	if !readRequest(w, r, &input) {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.expireApprovalsLocked(time.Now())
	pending := h.approvals[input.ID]
	if pending == nil || pending.controller.ID != input.ControllerID || pending.nonce != input.Nonce ||
		!verifyKeyProof(pending.key, input.Signature, "pair-request", h.descriptor.DeviceID, input.ControllerID, input.Nonce) {
		writeError(w, "PAIR_REQUEST_NOT_FOUND", http.StatusNotFound)
		return
	}
	if cancel && pending.status == "PENDING" {
		pending.status = "CANCELLED"
	}
	writeJSON(w, h.approvalStateLocked(input.ID, pending))
}

// completeApprovalLocked shares CompletePair's durable-storage boundary. False
// denotes a persistence failure; an explicit local rejection uses RejectPair.
func (h *Host) completeApprovalLocked(id string, saved bool) error {
	h.expireApprovalsLocked(time.Now())
	pending := h.approvals[id]
	if pending == nil || pending.status != "PENDING" || h.ctx.Err() != nil {
		return errors.New("pairing request expired or already resolved")
	}
	if saved {
		if len(h.controllers) >= 16 {
			pending.status = "FAILED"
			return errors.New("controller limit reached")
		}
		h.controllers[pending.controller.ID] = knownController{pending.controller, pending.key}
		pending.status = "PAIRED"
	} else {
		pending.status = "FAILED"
	}
	return nil
}

// RejectPair is a local TV action, never an unauthenticated network operation.
func (h *Host) RejectPair(id string) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.expireApprovalsLocked(time.Now())
	pending := h.approvals[id]
	if pending == nil || pending.status != "PENDING" || h.ctx.Err() != nil {
		return errors.New("pairing request expired or already resolved")
	}
	pending.status = "DENIED"
	return nil
}

// ApprovalStatus is local-only. Android uses it to retire a visible request
// after phone cancellation/expiry without relying on a lossy UI event.
func (h *Host) ApprovalStatus(id string) (ApprovalState, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.expireApprovalsLocked(time.Now())
	pending := h.approvals[id]
	if pending == nil || h.ctx.Err() != nil {
		return ApprovalState{}, errors.New("pairing request unavailable")
	}
	return h.approvalStateLocked(id, pending), nil
}

func verifyKeyProof(key *ecdsa.PublicKey, signature, purpose, deviceID, controllerID, nonce string) bool {
	if key == nil {
		return false
	}
	data, err := base64.RawURLEncoding.DecodeString(signature)
	if err != nil || len(data) > 80 {
		return false
	}
	sum := sha256.Sum256(ProofMessage(purpose, deviceID, controllerID, nonce))
	return ecdsa.VerifyASN1(key, sum[:], data)
}
