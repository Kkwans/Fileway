// Package ipc implements the private, framed Windows Host protocol.
package ipc

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser-client/core/bridge"
)

const (
	ProtocolMajor       = 1
	ProtocolMinor       = 0
	MaxFrameBytes       = 16 << 20
	MaxCoreCommandBytes = 1 << 20
)

var (
	ErrFrame    = errors.New("invalid protocol frame")
	ErrEnvelope = errors.New("invalid protocol envelope")
)

type Request struct {
	ProtocolMajor int                        `json:"protocolMajor"`
	ProtocolMinor int                        `json:"protocolMinor"`
	RequestID     string                     `json:"requestId"`
	Generation    int64                      `json:"generation"`
	Op            string                     `json:"op"`
	Session       string                     `json:"session,omitempty"`
	Parameters    map[string]json.RawMessage `json:"parameters"`
}

type ErrorInfo struct {
	Code       string `json:"code"`
	Message    string `json:"message"`
	HTTPStatus *int   `json:"httpStatus,omitempty"`
	Retryable  bool   `json:"retryable"`
}

type Response struct {
	ProtocolMajor int             `json:"protocolMajor"`
	ProtocolMinor int             `json:"protocolMinor"`
	RequestID     string          `json:"requestId"`
	Generation    int64           `json:"generation"`
	OK            bool            `json:"ok"`
	Result        json.RawMessage `json:"result,omitempty"`
	Error         string          `json:"error,omitempty"`
	ErrorInfo     *ErrorInfo      `json:"errorInfo,omitempty"`
}

// ReadFrame checks the length before allocating the payload buffer.
func ReadFrame(r io.Reader) ([]byte, error) {
	var header [4]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		if err == io.EOF {
			return nil, io.EOF
		}
		return nil, ErrFrame
	}
	n := binary.LittleEndian.Uint32(header[:])
	if n == 0 || n > MaxFrameBytes {
		return nil, ErrFrame
	}
	data := make([]byte, int(n))
	if _, err := io.ReadFull(r, data); err != nil {
		return nil, ErrFrame
	}
	if !utf8.Valid(data) {
		return nil, ErrFrame
	}
	return data, nil
}

func WriteFrame(w io.Writer, data []byte) error {
	if len(data) == 0 || len(data) > MaxFrameBytes || !utf8.Valid(data) {
		return ErrFrame
	}
	var header [4]byte
	binary.LittleEndian.PutUint32(header[:], uint32(len(data)))
	if err := writeAll(w, header[:]); err != nil {
		return err
	}
	return writeAll(w, data)
}

func writeAll(w io.Writer, data []byte) error {
	for len(data) != 0 {
		n, err := w.Write(data)
		if err != nil {
			return err
		}
		if n <= 0 || n > len(data) {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}

// ParseRequest rejects missing fields, aliases, duplicate keys, and trailing JSON.
// A malformed envelope closes the connection; semantic failures retain identity.
func ParseRequest(data []byte) (Request, error) {
	var request Request
	if !utf8.Valid(data) || validateJSON(data) != nil {
		return request, ErrEnvelope
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil || fields == nil {
		return request, ErrEnvelope
	}
	for _, name := range []string{"protocolMajor", "protocolMinor", "requestId", "generation", "op", "parameters"} {
		if value, ok := fields[name]; !ok || bytes.Equal(value, []byte("null")) {
			return request, ErrEnvelope
		}
	}
	for key := range fields {
		switch key {
		case "protocolMajor", "protocolMinor", "requestId", "generation", "op", "parameters", "session":
		default:
			return request, ErrEnvelope
		}
	}
	if session, present := fields["session"]; present && bytes.Equal(session, []byte("null")) {
		return request, ErrEnvelope
	}
	if err := json.Unmarshal(data, &request); err != nil || request.Parameters == nil {
		return Request{}, ErrEnvelope
	}
	if request.ProtocolMajor < 0 || request.ProtocolMinor < 0 || request.Generation < 0 || !validID(request.RequestID) || request.Op == "" {
		return Request{}, ErrEnvelope
	}
	if _, present := fields["session"]; present && request.Session == "" {
		return Request{}, ErrEnvelope
	}
	return request, nil
}

func validID(value string) bool {
	if len(value) == 0 || len(value) > 128 {
		return false
	}
	for i := range value {
		c := value[i]
		if c < 0x21 || c > 0x7e {
			return false
		}
	}
	return true
}

// Duplicate JSON properties are ambiguous even when Go would accept them.
func validateJSON(data []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	if err := readValue(decoder, 0); err != nil {
		return err
	}
	if _, err := decoder.Token(); err != io.EOF {
		return ErrEnvelope
	}
	return nil
}

func readValue(decoder *json.Decoder, depth int) error {
	if depth > 128 {
		return ErrEnvelope
	}
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delim, isDelim := token.(json.Delim)
	if !isDelim {
		return nil
	}
	switch delim {
	case '{':
		keys := make(map[string]struct{})
		for decoder.More() {
			token, err := decoder.Token()
			if err != nil {
				return err
			}
			key, ok := token.(string)
			if !ok {
				return ErrEnvelope
			}
			if _, duplicate := keys[key]; duplicate {
				return ErrEnvelope
			}
			keys[key] = struct{}{}
			if err := readValue(decoder, depth+1); err != nil {
				return err
			}
		}
	case '[':
		for decoder.More() {
			if err := readValue(decoder, depth+1); err != nil {
				return err
			}
		}
	default:
		return ErrEnvelope
	}
	_, err = decoder.Token() // The decoder validates the matching closing delimiter.
	return err
}

type operation struct {
	parameters string
	session    bool
	control    bool
}

var operations = map[string]operation{
	"init":                {control: true},
	"health":              {control: true},
	"cancel":              {parameters: "targetRequestId", control: true},
	"shutdown":            {control: true},
	"close_session":       {session: true, control: true},
	"open":                {parameters: "baseUrl token network"},
	"login":               {parameters: "username password", session: true},
	"request":             {parameters: "method endpoint body", session: true},
	"token":               {session: true},
	"lease":               {parameters: "path wirePath cacheKey cacheOwner", session: true},
	"asset":               {parameters: "endpoint", session: true},
	"preview":             {parameters: "path wirePath scope", session: true},
	"revoke":              {parameters: "url"},
	"lease_stats":         {parameters: "url", session: true},
	"upload_lease":        {parameters: "path wirePath upload", session: true},
	"upload_stats":        {parameters: "url", session: true},
	"search_start":        {parameters: "path wirePath query scope", session: true},
	"search_poll":         {parameters: "search", session: true},
	"search_cancel":       {parameters: "search", session: true},
	"command_start":       {parameters: "path wirePath command", session: true},
	"command_poll":        {parameters: "commandHandle", session: true},
	"command_cancel":      {parameters: "commandHandle", session: true},
	"cache_configure":     {parameters: "cacheConfig"},
	"cache_cleanup":       {parameters: "clear"},
	"cache_cleanup_owner": {parameters: "cacheOwner"},
	"network_platform":    {parameters: "platformNetwork"},
	"network_configure":   {parameters: "stateDir hostname storageKey"},
	"network_start":       {},
	"network_status":      {},
	"network_logout":      {},
	"network_stop":        {},
}

func commandFor(request Request, coreID string) ([]byte, error) {
	operation, known := operations[request.Op]
	if !known || operation.session != (request.Session != "") {
		return nil, errors.New("invalid operation or session")
	}
	command := make(map[string]json.RawMessage, len(request.Parameters)+3)
	for key, value := range request.Parameters {
		allowed := false
		for _, name := range strings.Fields(operation.parameters) {
			if name == key {
				allowed = true
				break
			}
		}
		if !allowed {
			return nil, errors.New("invalid operation parameters")
		}
		command[key] = value
	}
	if request.Op == "cancel" {
		var target string
		if json.Unmarshal(request.Parameters["targetRequestId"], &target) != nil || !validID(target) || target == request.RequestID {
			return nil, errors.New("invalid cancellation target")
		}
		return nil, nil
	}
	command["op"], _ = json.Marshal(request.Op)
	command["requestId"], _ = json.Marshal(coreID)
	if request.Session != "" {
		command["session"], _ = json.Marshal(request.Session)
	}
	data, err := json.Marshal(command)
	if err != nil || len(data) > MaxCoreCommandBytes {
		return nil, errors.New("core command exceeds size limit")
	}
	var typed bridge.Command
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&typed) != nil {
		return nil, errors.New("invalid core parameters")
	}
	return data, nil
}

func success(request Request, result json.RawMessage) Response {
	if len(result) == 0 {
		result = json.RawMessage("null")
	}
	return Response{ProtocolMajor: ProtocolMajor, ProtocolMinor: ProtocolMinor, RequestID: request.RequestID, Generation: request.Generation, OK: true, Result: result}
}

func failure(request Request, code, message string, retryable bool) Response {
	return Response{ProtocolMajor: ProtocolMajor, ProtocolMinor: ProtocolMinor, RequestID: request.RequestID, Generation: request.Generation, Error: message,
		ErrorInfo: &ErrorInfo{Code: code, Message: message, Retryable: retryable}}
}

func marshalResponse(response Response) []byte {
	data, err := json.Marshal(response)
	if err != nil {
		response = failure(Request{RequestID: response.RequestID, Generation: response.Generation}, "HostFailure", "cannot encode host response", false)
		data, _ = json.Marshal(response)
	}
	if len(data) > MaxFrameBytes {
		response = failure(Request{RequestID: response.RequestID, Generation: response.Generation}, "ResponseTooLarge", "host response exceeds size limit", false)
		data, _ = json.Marshal(response)
	}
	return data
}
