//go:build !fileway_direct

package bridge

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"testing"
)

func TestEmbeddedSelectionNeverFallsBackToDirect(t *testing.T) {
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	open := Command{Op: "open", BaseURL: "http://example.test", Network: "tailnet"}
	out := command(t, e, open)
	if string(out["ok"]) != "false" {
		t.Fatal("unconfigured embedded mode accepted", out)
	}
	key := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{1}, 32))
	out = command(t, e, Command{Op: "network_configure", StateDir: t.TempDir(), Hostname: "nfb-test", StorageKey: key})
	if string(out["ok"]) != "true" {
		t.Fatal(out)
	}
	out = command(t, e, Command{Op: "network_status"})
	var status map[string]any
	json.Unmarshal(out["result"], &status)
	if status["state"] != "Configured" {
		t.Fatal(status)
	}
	out = command(t, e, open)
	if string(out["ok"]) != "false" {
		t.Fatal("configured but unauthenticated mode silently used direct HTTP", out)
	}
}
