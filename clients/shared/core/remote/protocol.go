// Package remote implements Fileway's device-scoped companion control channel.
// It never exposes a media lease, a NAS session, an arbitrary URL or a shell.
package remote

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"regexp"
)

const (
	Protocol           = 1
	Subprotocol        = "fileway.remote.v1"
	ServiceType        = "_fileway-tv._tcp."
	MaxMessageBytes    = 64 << 10
	MaxSnapshotBytes   = 48 << 10
	MaxPendingCommands = 32
)

var opaqueID = regexp.MustCompile(`^[A-Za-z0-9_-]{16,96}$`)

// Command is an intention. ACCEPTED never means that the player applied it.
// Epoch validation belongs to the Android gateway, which owns actual state.
type Command struct {
	Type         string          `json:"type"`
	Protocol     int             `json:"protocol"`
	CommandID    string          `json:"commandId"`
	ConnectionID string          `json:"connectionId"`
	SessionEpoch uint64          `json:"sessionEpoch"`
	MediaEpoch   uint64          `json:"mediaEpoch"`
	Operation    string          `json:"operation"`
	Payload      json.RawMessage `json:"payload"`
}

var operations = map[string]bool{
	"device.snapshot": true, "device.commandStatus": true,
	"device.enablePairing": true, "device.revokeController": true,
	"playback.play": true, "playback.pause": true, "playback.stop": true,
	"playback.seekTo": true, "playback.seekBy": true, "playback.setRate": true,
	"playback.setVolume": true, "playback.setHdrMode": true,
	"audio.select": true, "subtitle.select": true, "subtitle.disable": true,
	"subtitle.selectNasFile": true, "subtitle.setDelay": true, "subtitle.setAppearance": true,
	"media.open": true, "queue.set": true, "queue.select": true,
	"queue.next": true, "queue.previous": true,
	"image.open": true, "image.selectIndex": true, "image.next": true,
	"image.previous": true, "image.setTransform": true,
	"app.navigate": true, "app.back": true, "app.key": true, "app.list": true,
	"app.inputText": true, "app.setPreference": true,
	"session.requestGrant": true, "session.adopt": true, "session.cancelSwitch": true,
}

func DecodeCommand(data []byte) (Command, error) {
	var command Command
	if len(data) > MaxMessageBytes || decodeStrict(data, &command) != nil {
		return command, errors.New("INVALID_COMMAND")
	}
	if command.Type != "command" || command.Protocol != Protocol ||
		!opaqueID.MatchString(command.CommandID) || !opaqueID.MatchString(command.ConnectionID) ||
		!operations[command.Operation] || !jsonObject(command.Payload) {
		return command, errors.New("INVALID_COMMAND")
	}
	return command, nil
}

func decodeStrict(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return errors.New("unexpected trailing JSON")
	}
	return nil
}

func jsonObject(value []byte) bool {
	value = bytes.TrimSpace(value)
	return len(value) >= 2 && value[0] == '{' && json.Valid(value)
}

// State contains only the explicitly projected public state of the TV.
// Callers must not serialize a ProfileStore, JWT or native transport object.
type State struct {
	SessionEpoch uint64          `json:"sessionEpoch"`
	MediaEpoch   uint64          `json:"mediaEpoch"`
	Revision     uint64          `json:"revision"`
	Scene        string          `json:"scene"`
	Capabilities json.RawMessage `json:"capabilities"`
	Content      json.RawMessage `json:"content"`
}

func (s State) valid() bool {
	if s.Scene != "LOGIN" && s.Scene != "BROWSE" && s.Scene != "VIDEO" && s.Scene != "AUDIO" && s.Scene != "IMAGE" {
		return false
	}
	if !jsonObject(s.Capabilities) || !jsonObject(s.Content) {
		return false
	}
	data, err := json.Marshal(s)
	return err == nil && len(data) <= MaxSnapshotBytes
}

type Result struct {
	Type      string          `json:"type"`
	CommandID string          `json:"commandId"`
	Status    string          `json:"status"`
	Code      string          `json:"code,omitempty"`
	Revision  uint64          `json:"revision,omitempty"`
	Payload   json.RawMessage `json:"payload,omitempty"`
}

// Event crosses only the private JNI boundary. It is not a LAN response.
type Event struct {
	Type          string      `json:"type"`
	ConnectionID  string      `json:"connectionId,omitempty"`
	ControllerID  string      `json:"controllerId,omitempty"`
	PairRequestID string      `json:"pairRequestId,omitempty"`
	Controller    *Controller `json:"controller,omitempty"`
	Command       *Command    `json:"command,omitempty"`
}

func commandDigest(command Command) string {
	data, _ := json.Marshal(command)
	value := sha256.Sum256(data)
	return hex.EncodeToString(value[:])
}
