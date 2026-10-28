//go:build fileway_direct

package bridge

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDirectBuildRejectsEmbeddedAndKeepsDirectSession(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("{}")) }))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	for _, c := range []Command{
		{Op: "open", BaseURL: server.URL, Network: "tailnet"},
		{Op: "network_configure", StorageKey: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="},
		{Op: "network_start"},
	} {
		if out := command(t, e, c); string(out["ok"]) != "false" {
			t.Fatalf("embedded request accepted: %s", out)
		}
	}
	out := command(t, e, Command{Op: "open", BaseURL: server.URL, Network: "direct"})
	var session string
	if string(out["ok"]) != "true" || json.Unmarshal(out["result"], &session) != nil || session == "" {
		t.Fatal(out)
	}
	if out := command(t, e, Command{Op: "request", Session: session, Method: "GET", Endpoint: "/api/resources/"}); string(out["ok"]) != "true" {
		t.Fatal(out)
	}
}
