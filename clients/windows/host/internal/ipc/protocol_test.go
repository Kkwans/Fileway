package ipc

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"testing"
)

func TestFramesValidateBeforePayloadRead(t *testing.T) {
	for _, length := range []uint32{0, MaxFrameBytes + 1, ^uint32(0)} {
		t.Run(strings.TrimSpace(string(rune(length%26+'a'))), func(t *testing.T) {
			var header [4]byte
			binary.LittleEndian.PutUint32(header[:], length)
			reader := &headerOnlyReader{header: header[:]}
			if _, err := ReadFrame(reader); !errors.Is(err, ErrFrame) || reader.payloadReads != 0 {
				t.Fatalf("length %d: error=%v payload reads=%d", length, err, reader.payloadReads)
			}
		})
	}
	for _, input := range [][]byte{nil, {1}, {1, 0, 0, 0}, {2, 0, 0, 0, 'x'}, {1, 0, 0, 0, 0xff}} {
		_, err := ReadFrame(bytes.NewReader(input))
		if len(input) == 0 {
			if err != io.EOF {
				t.Fatal(err)
			}
		} else if err != ErrFrame {
			t.Fatalf("invalid frame accepted: %v", err)
		}
	}
}

type headerOnlyReader struct {
	header       []byte
	payloadReads int
}

func (reader *headerOnlyReader) Read(data []byte) (int, error) {
	if len(reader.header) == 0 {
		reader.payloadReads++
		return 0, io.EOF
	}
	n := copy(data, reader.header)
	reader.header = reader.header[n:]
	return n, nil
}

func TestFrameLittleEndianAndShortWrites(t *testing.T) {
	payload := []byte(`{"name":"电影🎬"}`)
	var output byteWriter
	if err := WriteFrame(&output, payload); err != nil {
		t.Fatal(err)
	}
	data := output.Bytes()
	if binary.LittleEndian.Uint32(data[:4]) != uint32(len(payload)) {
		t.Fatal("frame length is not little endian")
	}
	actual, err := ReadFrame(bytes.NewReader(data))
	if err != nil || !bytes.Equal(actual, payload) {
		t.Fatal(err)
	}
	if err := WriteFrame(zeroWriter{}, payload); err != io.ErrShortWrite {
		t.Fatal(err)
	}
}

type byteWriter struct{ bytes.Buffer }

func (writer *byteWriter) Write(data []byte) (int, error) { return writer.Buffer.Write(data[:1]) }

type zeroWriter struct{}

func (zeroWriter) Write([]byte) (int, error) { return 0, nil }

func TestStrictEnvelopeAndCanonicalParameters(t *testing.T) {
	valid := `{"protocolMajor":1,"protocolMinor":0,"requestId":"id-1","generation":9223372036854775807,"op":"open","parameters":{"baseUrl":"https://example.invalid"}}`
	request, err := ParseRequest([]byte(valid))
	if err != nil || request.Generation != 9223372036854775807 {
		t.Fatal(err)
	}
	for name, input := range map[string]string{
		"duplicate envelope":  strings.Replace(valid, `"op":"open"`, `"op":"init","op":"open"`, 1),
		"duplicate parameter": strings.Replace(valid, `"baseUrl":"https://example.invalid"`, `"baseUrl":"a","baseUrl":"b"`, 1),
		"nested duplicate":    strings.Replace(valid, `"parameters":{"baseUrl":"https://example.invalid"}`, `"parameters":{"body":{"value":1,"value":2}}`, 1),
		"alias envelope":      strings.Replace(valid, `"requestId"`, `"RequestID"`, 1),
		"negative generation": strings.Replace(valid, "9223372036854775807", "-1", 1),
		"missing generation":  strings.Replace(valid, `"generation":9223372036854775807,`, "", 1),
		"null generation":     strings.Replace(valid, "9223372036854775807", "null", 1),
		"overflow generation": strings.Replace(valid, "9223372036854775807", "9223372036854775808", 1),
		"empty identity":      strings.Replace(valid, "id-1", "", 1),
		"non ascii identity":  strings.Replace(valid, "id-1", "电影", 1),
		"long identity":       strings.Replace(valid, "id-1", strings.Repeat("a", 129), 1),
		"parameters array":    strings.Replace(valid, `{"baseUrl":"https://example.invalid"}`, `[]`, 1),
		"null session":        strings.Replace(valid, `"op":"open"`, `"session":null,"op":"open"`, 1),
		"trailing json":       valid + `{}`,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := ParseRequest([]byte(input)); err != ErrEnvelope {
				t.Fatalf("malformed envelope accepted: %v", err)
			}
		})
	}
	for _, key := range []string{"op", "Op", "SESSION", "Session", "session", "requestId", "RequestID", "bodyBase64", "BodyBase64", "BaseUrl"} {
		t.Run(key, func(t *testing.T) {
			request.Parameters[key] = json.RawMessage(`"injected"`)
			if _, err := commandFor(request, "internal-id"); err == nil {
				t.Fatal("noncanonical or reserved parameter accepted")
			}
			delete(request.Parameters, key)
		})
	}
}

func TestCoreCommandLimitUsesEncodedBytesAndRejectsRawBody(t *testing.T) {
	request := Request{RequestID: "size", Op: "request", Session: "session", Parameters: map[string]json.RawMessage{
		"method": json.RawMessage(`"POST"`), "endpoint": json.RawMessage(`"/api/resources/"`),
	}}
	request.Parameters["body"], _ = json.Marshal(strings.Repeat("x", MaxCoreCommandBytes))
	if _, err := commandFor(request, "internal"); err == nil {
		t.Fatal("oversized encoded core command accepted")
	}
	request.Parameters["body"] = json.RawMessage(`{}`)
	request.Parameters["bodyBase64"] = json.RawMessage(`""`)
	if _, err := commandFor(request, "internal"); err == nil {
		t.Fatal("raw-body exception accepted")
	}
	delete(request.Parameters, "bodyBase64")
	request.Parameters["method"] = json.RawMessage(`123`)
	if _, err := commandFor(request, "internal"); err == nil {
		t.Fatal("core parameter type was not checked")
	}
}

func TestSuccessNullAndResponseFrameBound(t *testing.T) {
	request := Request{RequestID: "result", Generation: 17}
	data := marshalResponse(success(request, nil))
	var fields map[string]json.RawMessage
	if json.Unmarshal(data, &fields) != nil || string(fields["result"]) != "null" {
		t.Fatal("successful nil omitted result")
	}
	result, _ := json.Marshal(strings.Repeat("x", MaxFrameBytes))
	data = marshalResponse(success(request, result))
	var response Response
	if len(data) > MaxFrameBytes || json.Unmarshal(data, &response) != nil || response.ErrorInfo == nil || response.ErrorInfo.Code != "ResponseTooLarge" || response.Generation != 17 {
		t.Fatal("response bound lost identity or stable error")
	}
}
