package remote

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"github.com/coder/websocket"
	"io"
	"net"
	"net/http"
	"strconv"
	"testing"
	"time"
)

// Real TLS + WSS, rather than a mocked command handler: validates pairing ACK,
// scoped certificate trust, proof authentication, ordering and deduplication.
func TestCompanionPairControlAndReconnect(t *testing.T) {
	identity, err := GenerateIdentity(time.Now())
	if err != nil {
		t.Fatal(err)
	}
	host, err := Start(Config{Identity: identity, Name: "Owned TV", ListenAddress: "127.0.0.1"})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = host.Close() }()
	d := host.Descriptor()
	cert, _, _ := identity.certificate()
	pool := x509.NewCertPool()
	pool.AddCert(cert.Leaf)
	transport := &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool}, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, net.JoinHostPort(d.Address, strconv.Itoa(d.Port)))
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 4 * time.Second}
	origin := "https://" + net.JoinHostPort(d.ServerName, strconv.Itoa(d.Port))
	post := func(path string, value any) (int, map[string]any) {
		t.Helper()
		data, _ := json.Marshal(value)
		response, e := client.Post(origin+path, "application/json", bytes.NewReader(data))
		if e != nil {
			t.Fatal(e)
		}
		defer func() { _ = response.Body.Close() }()
		var result map[string]any
		if e = json.NewDecoder(io.LimitReader(response.Body, 8192)).Decode(&result); e != nil {
			t.Fatal(e)
		}
		return response.StatusCode, result
	}
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	spki, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	sum := sha256.Sum256(spki)
	controller := Controller{ID: hex.EncodeToString(sum[:]), Name: "Owned Phone", PublicKey: base64.RawURLEncoding.EncodeToString(spki)}
	if status, _ := post("/v1/challenge", map[string]string{"controllerId": controller.ID}); status != 401 {
		t.Fatal("unpaired controller challenged", status)
	}
	invite, _ := host.Invite()
	nonce, _ := randomID()
	secret, _ := base64.RawURLEncoding.DecodeString(invite.Secret)
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write(ProofMessage("pair", d.DeviceID, controller.ID, nonce))
	proof := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	paired := make(chan error, 1)
	go func() {
		event, e := host.Wait(ctx)
		if e == nil && (event.Type != "pair.requested" || event.Controller.ID != controller.ID) {
			e = ErrClosed
		}
		if e == nil {
			e = host.CompletePair(event.PairRequestID, true)
		}
		paired <- e
	}()
	if status, _ := post("/v1/pair", map[string]any{"invitationId": invite.ID, "nonce": nonce, "proof": proof, "controller": controller}); status != 200 {
		t.Fatal("pair failed", status)
	}
	if err = <-paired; err != nil {
		t.Fatal(err)
	}
	connect := func() *websocket.Conn {
		t.Helper()
		status, value := post("/v1/challenge", map[string]string{"controllerId": controller.ID})
		if status != 200 {
			t.Fatal("challenge", status)
		}
		challenge := value["nonce"].(string)
		digest := sha256.Sum256(ProofMessage("connect", d.DeviceID, controller.ID, challenge))
		signature, _ := ecdsa.SignASN1(rand.Reader, key, digest[:])
		headers := make(http.Header)
		headers.Set("X-Fileway-Controller", controller.ID)
		headers.Set("X-Fileway-Challenge", challenge)
		headers.Set("X-Fileway-Proof", base64.RawURLEncoding.EncodeToString(signature))
		socket, _, e := websocket.Dial(ctx, "wss://"+net.JoinHostPort(d.ServerName, strconv.Itoa(d.Port))+"/v1/control", &websocket.DialOptions{HTTPClient: client, HTTPHeader: headers, Subprotocols: []string{Subprotocol}})
		if e != nil {
			t.Fatal(e)
		}
		return socket
	}
	socket := connect()
	defer func() { _ = socket.CloseNow() }()
	read := func(connection *websocket.Conn) map[string]any {
		t.Helper()
		_, data, e := connection.Read(ctx)
		if e != nil {
			t.Fatal(e)
		}
		var value map[string]any
		if e = json.Unmarshal(data, &value); e != nil {
			t.Fatal(e)
		}
		return value
	}
	hello := read(socket)
	if hello["type"] != "hello" {
		t.Fatal("no hello")
	}
	snapshot := read(socket)
	if snapshot["type"] != "snapshot" {
		t.Fatal("no authoritative snapshot")
	}
	connectionID := hello["connectionId"].(string)
	id, _ := randomID()
	command := Command{Type: "command", Protocol: Protocol, CommandID: id, ConnectionID: connectionID, Operation: "app.key", Payload: json.RawMessage(`{"key":"down"}`)}
	write := func(value any) {
		t.Helper()
		data, _ := json.Marshal(value)
		if e := socket.Write(ctx, websocket.MessageText, data); e != nil {
			t.Fatal(e)
		}
	}
	write(command)
	if value := read(socket); value["status"] != "ACCEPTED" {
		t.Fatal("Android received no accepted acknowledgement", value)
	}
	for {
		event, e := host.Wait(ctx)
		if e != nil {
			t.Fatal(e)
		}
		if event.Type == "command" {
			if event.Command.CommandID != id || !host.Connected(event.ConnectionID, event.ControllerID) {
				t.Fatal("event identity mismatch")
			}
			break
		}
	}
	if err = host.Reply(connectionID, controller.ID, Result{CommandID: id, Status: "COMPLETED"}); err != nil {
		t.Fatal(err)
	}
	if value := read(socket); value["status"] != "COMPLETED" {
		t.Fatal("completion lost", value)
	}
	write(command)
	if value := read(socket); value["status"] != "COMPLETED" {
		t.Fatal("duplicate mutation not deduplicated", value)
	}
	command.Payload = json.RawMessage(`{"key":"up"}`)
	write(command)
	if value := read(socket); value["code"] != "COMMAND_CONFLICT" {
		t.Fatal("conflicting duplicate accepted", value)
	}

	// A browsing response is visible to its requesting socket once, but its
	// filenames must not survive in the controller-scoped receipt cache.
	var directoryCommands []string
	for _, example := range []struct{ operation, status, payload string }{
		{"subtitle.browse.open", "COMPLETED", `{"items":[{"name":"owned-caption.srt"}]}`},
		{"subtitle.browse.page", "COMPLETED", `{"items":[{"name":"owned-caption.srt"}]}`},
		{"subtitle.browse.page", "REJECTED", `{"message":"owned-caption.srt is unavailable"}`},
		{"browse.list", "COMPLETED", `{"files":[{"name":"owned-video.mp4"}]}`},
		{"preview.open", "COMPLETED", `{"previewId":"owned-preview","data":"AA=="}`},
		{"preview.read", "COMPLETED", `{"data":"AA=="}`},
	} {
		browseID, _ := randomID()
		directoryCommands = append(directoryCommands, browseID)
		browse := Command{Type: "command", Protocol: Protocol, CommandID: browseID, ConnectionID: connectionID,
			Operation: example.operation, Payload: json.RawMessage(`{}`)}
		write(browse)
		if value := read(socket); value["status"] != "ACCEPTED" {
			t.Fatal("directory read was not accepted", value)
		}
		for {
			event, e := host.Wait(ctx)
			if e != nil {
				t.Fatal(e)
			}
			if event.Type == "command" {
				if event.Command.CommandID != browseID {
					t.Fatal("wrong directory command", event)
				}
				break
			}
		}
		if err = host.Reply(connectionID, controller.ID, Result{CommandID: browseID, Status: example.status,
			Payload: json.RawMessage(example.payload)}); err != nil {
			t.Fatal(err)
		}
		first := read(socket)
		if first["status"] != example.status || first["payload"] == nil {
			t.Fatal("requesting connection did not receive the directory", first)
		}
		write(browse)
		duplicate := read(socket)
		if duplicate["status"] != "REJECTED" || duplicate["code"] != "RESULT_NOT_RETAINED" || duplicate["payload"] != nil {
			t.Fatal("directory payload was replayed", duplicate)
		}
		host.mu.Lock()
		stored := host.commands[controller.ID+"/"+browseID].result
		host.mu.Unlock()
		if len(stored.Payload) != 0 {
			t.Fatal("directory payload remained in receipt storage")
		}
	}
	staleID, _ := randomID()
	stale := Command{Type: "command", Protocol: Protocol, CommandID: staleID, ConnectionID: connectionID,
		Operation: "subtitle.browse.page", Payload: json.RawMessage(`{}`)}
	write(stale)
	if value := read(socket); value["status"] != "ACCEPTED" {
		t.Fatal("stale-scope test was not accepted", value)
	}
	for {
		event, e := host.Wait(ctx)
		if e != nil {
			t.Fatal(e)
		}
		if event.Type == "command" {
			if event.Command.CommandID != staleID {
				t.Fatal("wrong stale-scope command", event)
			}
			break
		}
	}
	if err = host.Publish(State{SessionEpoch: 1, MediaEpoch: 1, Revision: 1, Scene: "BROWSE",
		Capabilities: json.RawMessage(`{}`), Content: json.RawMessage(`{}`)}); err != nil {
		t.Fatal(err)
	}
	if value := read(socket); value["type"] != "snapshot" {
		t.Fatal("source change was not published", value)
	}
	if err = host.Reply(connectionID, controller.ID, Result{CommandID: staleID, Status: "COMPLETED",
		Payload: json.RawMessage(`{"items":[{"name":"old-account.srt"}]}`)}); err != nil {
		t.Fatal(err)
	}
	if value := read(socket); value["status"] != "REJECTED" || value["code"] != "STALE_SCOPE" || value["payload"] != nil {
		t.Fatal("stale directory response was delivered", value)
	}
	write(stale)
	if value := read(socket); value["status"] != "REJECTED" || value["code"] != "STALE_SCOPE" || value["payload"] != nil {
		t.Fatal("stale directory receipt changed on retry", value)
	}
	storageID, _ := randomID()
	storage := Command{Type: "command", Protocol: Protocol, CommandID: storageID, ConnectionID: connectionID,
		SessionEpoch: 1, MediaEpoch: 1, Operation: "subtitle.browse.page", Payload: json.RawMessage(`{}`)}
	write(storage)
	if value := read(socket); value["status"] != "ACCEPTED" {
		t.Fatal("storage-scope test was not accepted", value)
	}
	for {
		event, e := host.Wait(ctx)
		if e != nil {
			t.Fatal(e)
		}
		if event.Type == "command" {
			if event.Command.CommandID != storageID {
				t.Fatal("wrong storage-scope command", event)
			}
			break
		}
	}
	if err = host.Publish(State{SessionEpoch: 1, MediaEpoch: 1, Revision: 2, Scene: "BROWSE",
		Capabilities: json.RawMessage(`{}`), Content: json.RawMessage(`{"subtitleStorageRevision":1}`)}); err != nil {
		t.Fatal(err)
	}
	if value := read(socket); value["type"] != "snapshot" {
		t.Fatal("storage revocation was not published", value)
	}
	if err = host.Reply(connectionID, controller.ID, Result{CommandID: storageID, Status: "COMPLETED",
		Payload: json.RawMessage(`{"items":[{"name":"revoked.srt"}]}`)}); err != nil {
		t.Fatal(err)
	}
	if value := read(socket); value["status"] != "REJECTED" || value["code"] != "STALE_SCOPE" || value["payload"] != nil {
		t.Fatal("revoked directory response was delivered", value)
	}
	_ = socket.CloseNow()
	for {
		event, e := host.Wait(ctx)
		if e != nil {
			t.Fatal(e)
		}
		if event.Type == "connection.closed" {
			break
		}
	}
	if host.Connected(connectionID, controller.ID) {
		t.Fatal("disconnected controller remains active")
	}
	// No new invitation is required on reconnect and state remains owned by TV.
	second := connect()
	defer func() { _ = second.CloseNow() }()
	secondHello := read(second)
	_ = read(second)
	for _, browseID := range directoryCommands {
		queryID, _ := randomID()
		payload, _ := json.Marshal(map[string]string{"commandId": browseID})
		query := Command{Type: "command", Protocol: Protocol, CommandID: queryID,
			ConnectionID: secondHello["connectionId"].(string), Operation: "device.commandStatus", Payload: payload}
		data, _ := json.Marshal(query)
		if err = second.Write(ctx, websocket.MessageText, data); err != nil {
			t.Fatal(err)
		}
		value := read(second)
		result := value["payload"].(map[string]any)["result"].(map[string]any)
		if result["status"] != "REJECTED" || result["code"] != "RESULT_NOT_RETAINED" || result["payload"] != nil {
			t.Fatal("reconnected controller recovered a directory payload", result)
		}
	}
	host.Revoke(controller.ID)
	if status, _ := post("/v1/challenge", map[string]string{"controllerId": controller.ID}); status != 401 {
		t.Fatal("revoked controller authenticated", status)
	}
}
