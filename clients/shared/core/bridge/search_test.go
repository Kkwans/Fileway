package bridge

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser-client/core/transport"
)

func TestSearchControlSurvivesCallReturnAndCancelsBeforeWorker(t *testing.T) {
	const expectedWire = "/%E7%94%B5%E5%BD%B1%F0%9F%8E%AC.mkv"
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/x-ndjson")
		_, _ = io.WriteString(w, `{"type":"result","item":{"path":"电影🎬.mkv","name":"电影🎬.mkv","wirePath":"`+expectedWire+`","pathVerified":true}}`+"\n"+`{"type":"summary","reason":"completed","count":1}`)
	}))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	opened := command(t, e, Command{Op: "open", BaseURL: server.URL})
	var owner string
	json.Unmarshal(opened["result"], &owner)
	started := command(t, e, Command{Op: "search_start", Session: owner, Path: "/", Query: "电影", Scope: "current"})
	var id string
	if json.Unmarshal(started["result"], &id) != nil || id == "" {
		t.Fatal(started)
	}
	deadline := time.Now().Add(3 * time.Second)
	received := 0
	for {
		out := command(t, e, Command{Op: "search_poll", Session: owner, Search: id})
		var batch transport.SearchBatch
		if string(out["ok"]) != "true" || json.Unmarshal(out["result"], &batch) != nil {
			t.Fatal(out)
		}
		received += len(batch.Items)
		for _, item := range batch.Items {
			if item.WirePath != expectedWire || !item.PathVerified {
				t.Fatal("bridge lost additive verified wire metadata", item)
			}
		}
		if batch.Done {
			if received != 1 || batch.State != "finished" {
				t.Fatal("JNI call completion canceled the stream", batch, received)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("stream did not complete")
		}
		time.Sleep(time.Millisecond)
	}
	command(t, e, Command{Op: "cancel", RequestID: "queued-search"})
	blocked := command(t, e, Command{Op: "search_start", Session: owner, RequestID: "queued-search", Path: "/", Query: "film", Scope: "current"})
	if string(blocked["ok"]) != "false" || calls.Load() != 1 {
		t.Fatal("pre-canceled search contacted source", blocked, calls.Load())
	}
}
