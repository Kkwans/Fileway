package fbhttp

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestSourceAssetStreamReadsBeforeCompleteAndSharesProducer(t *testing.T) {
	service := newMediaAssetService(t.TempDir())
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	key := strings.Repeat("1", 64)
	release := make(chan struct{})
	var calls atomic.Int32
	run := func(ctx context.Context, out io.Writer) error {
		calls.Add(1)
		if _, err := io.WriteString(out, "early"); err != nil {
			return err
		}
		select {
		case <-release:
		case <-ctx.Done():
			return ctx.Err()
		}
		_, err := io.WriteString(out, "-complete")
		return err
	}
	first, err := service.openStream(ctx, key, 100, run)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := service.openStream(ctx, key, 100, run)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	value := make([]byte, 5)
	if _, err := io.ReadFull(first, value); err != nil || string(value) != "early" {
		t.Fatalf("early prefix: %q / %v", value, err)
	}
	if _, err := os.Stat(filepath.Join(service.directory, key)); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("partial output published")
	}
	_ = first.Close() // One canceled reader must not cancel the other's producer.
	close(release)
	all, err := io.ReadAll(second)
	if err != nil || string(all) != "early-complete" || calls.Load() != 1 {
		t.Fatalf("shared producer: %q / %v / %d", all, err, calls.Load())
	}
	file, err := service.cached(key, 100)
	if err != nil || file == nil {
		t.Fatalf("missing complete cache: %v", err)
	}
	_ = file.Close()
}

func TestSourceAssetStreamLastReaderCancelsAndFailureCannotPublish(t *testing.T) {
	for _, failure := range []bool{false, true} {
		t.Run(map[bool]string{false: "cancel", true: "failure"}[failure], func(t *testing.T) {
			service := newMediaAssetService(t.TempDir())
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			key := strings.Repeat("2", 64)
			started := make(chan struct{})
			reader, err := service.openStream(ctx, key, 100, func(ctx context.Context, out io.Writer) error {
				_, _ = io.WriteString(out, "partial")
				close(started)
				if failure {
					return errors.New("owned failure")
				}
				<-ctx.Done()
				return ctx.Err()
			})
			if err != nil {
				t.Fatal(err)
			}
			defer reader.Close()
			<-started
			flight := reader.flight
			if failure {
				<-flight.done
				if _, err := io.ReadAll(reader); err == nil {
					t.Fatal("failed output appeared complete")
				}
			} else {
				_ = reader.Close()
				select {
				case <-flight.done:
				case <-ctx.Done():
					t.Fatal("last reader did not cancel")
				}
			}
			if _, err := os.Stat(filepath.Join(service.directory, key)); !errors.Is(err, os.ErrNotExist) {
				t.Fatal("partial cache published")
			}
			entries, _ := os.ReadDir(service.directory)
			if len(entries) != 0 {
				t.Fatalf("orphan temporary output: %d", len(entries))
			}
		})
	}
}

func TestSourceAssetStreamPinsCompletedFileUntilReaderCloses(t *testing.T) {
	service := newMediaAssetService(t.TempDir())
	key := strings.Repeat("3", 64)
	reader, err := service.openStream(context.Background(), key, 100, func(_ context.Context, out io.Writer) error { _, err := io.WriteString(out, "owned"); return err })
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	<-reader.flight.done
	name := filepath.Join(service.directory, key)
	old := time.Now().Add(-48 * time.Hour)
	_ = os.Chtimes(name, old, old)
	service.mu.Lock()
	err = service.reserve(100)
	service.reserved -= 100
	service.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if value, err := io.ReadAll(reader); err != nil || string(value) != "owned" {
		t.Fatalf("active final file evicted: %q / %v", value, err)
	}
	_ = reader.Close()
	service.mu.Lock()
	err = service.reserve(100)
	service.reserved -= 100
	service.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(name); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("closed pin was retained")
	}
}

func TestSourceAssetStreamFramesHeartbeatIntegrityAndFailure(t *testing.T) {
	for _, badSource := range []bool{false, true} {
		t.Run(map[bool]string{false: "complete", true: "changed"}[badSource], func(t *testing.T) {
			input, output := io.Pipe()
			defer input.Close()
			done := make(chan struct{})
			var validations atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				defer close(done)
				_, _ = writeSourceAssetStream(w, r, input, "owned-identity", "sup", func() bool { return !badSource || validations.Add(1) == 1 })
			}))
			defer server.Close()
			response, err := server.Client().Get(server.URL)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			decoder := json.NewDecoder(response.Body)
			var frame sourceAssetFrame
			if err := decoder.Decode(&frame); err != nil || frame.Type != "start" || frame.Identity != "owned-identity" {
				t.Fatalf("start frame: %+v / %v", frame, err)
			}
			if err := decoder.Decode(&frame); err != nil || frame.Type != "pending" {
				t.Fatalf("heartbeat was buffered: %+v / %v", frame, err)
			}
			payload := bytes.Repeat([]byte("owned PGS bytes"), 5000)
			go func() { _, _ = output.Write(payload); _ = output.Close() }()
			var actual bytes.Buffer
			for {
				frame = sourceAssetFrame{}
				if err := decoder.Decode(&frame); err != nil {
					t.Fatal(err)
				}
				if frame.Type == "pending" {
					continue
				}
				if frame.Type == "data" {
					if len(frame.Data) > 32<<10 {
						t.Fatal("unbounded frame")
					}
					actual.Write(frame.Data)
					continue
				}
				if badSource {
					if frame.Type != "error" || frame.Code != "SOURCE_CHANGED" {
						t.Fatalf("source change marked complete: %+v", frame)
					}
				} else {
					digest := sha256.Sum256(payload)
					if frame.Type != "complete" || frame.Bytes != int64(len(payload)) || frame.SHA256 != hex.EncodeToString(digest[:]) {
						t.Fatalf("invalid completion: %+v", frame)
					}
				}
				break
			}
			if !bytes.Equal(payload, actual.Bytes()) {
				t.Fatal("payload altered")
			}
			<-done
		})
	}
}

func TestSourceAssetStreamASSKeepsStylesAndStartsBeforeCompletion(t *testing.T) {
	input, output := io.Pipe()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = writeSourceAssetStream(w, r, input, "owned-ass", "ass", func() bool { return true })
	}))
	defer func() { _ = input.Close(); _ = output.Close(); server.Close() }()
	response, err := server.Client().Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	decoder := json.NewDecoder(response.Body)
	var frame sourceAssetFrame
	if err := decoder.Decode(&frame); err != nil || frame.Type != "start" || frame.Format != "ass" || frame.Identity != "owned-ass" {
		t.Fatalf("ASS start: %+v / %v", frame, err)
	}
	data := []byte("[Script Info]\nScriptType: v4.00+\n[V4+ Styles]\nStyle: Owned,OwnedFont,24\n[Events]\nDialogue: 0,0:00:00.50,0:00:11.00,Owned,,0,0,0,,{\\pos(80,40)\\1c&H0000FF&}Owned 字幕\n")
	go func() { _, _ = output.Write(data) }()
	var got bytes.Buffer
	for got.Len() < len(data) {
		frame = sourceAssetFrame{}
		if err := decoder.Decode(&frame); err != nil {
			t.Fatal(err)
		}
		if frame.Type == "pending" {
			continue
		}
		if frame.Type != "data" {
			t.Fatalf("ASS prefix blocked until completion: %+v", frame)
		}
		got.Write(frame.Data)
	}
	if !bytes.Equal(data, got.Bytes()) {
		t.Fatal("ASS styling or UTF-8 changed")
	}
	_ = output.Close()
	frame = sourceAssetFrame{}
	if err := decoder.Decode(&frame); err != nil || frame.Type != "complete" || frame.Bytes != int64(len(data)) {
		t.Fatalf("ASS completion: %+v / %v", frame, err)
	}
	digest := sha256.Sum256(data)
	if frame.SHA256 != hex.EncodeToString(digest[:]) {
		t.Fatal("ASS digest mismatch")
	}
	status, _ := writeSourceAssetStream(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil), bytes.NewReader(data), "owned", "font", func() bool { return true })
	if status != http.StatusBadRequest {
		t.Fatal("font unexpectedly accepted the subtitle stream protocol")
	}
}
