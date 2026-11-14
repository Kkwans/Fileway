package fbhttp

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func TestMetadataWorkerRunsAfterScanAndFencesCancellation(t *testing.T) {
	for _, cancel := range []bool{false, true} {
		t.Run(strconv.FormatBool(cancel), func(t *testing.T) {
			var calls atomic.Int32
			requestStarted, requestCanceled := make(chan struct{}), make(chan struct{})
			f := newMediaScanHTTPFixture(t, func(h *trashHTTPHarness) {
				vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{37}, 32))
				if err != nil {
					t.Fatal(err)
				}
				h.storage.MediaProviderVault = vault
				h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
					calls.Add(1)
					if r.URL.Path != "/3/movie/42" {
						t.Error("unexpected metadata request", r.URL.Path)
					}
					if cancel {
						close(requestStarted)
						<-r.Context().Done()
						close(requestCanceled)
						// Simulate an upstream result arriving in spite of cancellation.
					}
					return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
						`{"id":42,"title":"Echo","release_date":"2020-09-01","overview":"From the background scan"}`))}, nil
				})}
			})
			catalogFixtureWrite(t, f, "Echo (2020) [tmdb-movie-42].mkv", "owned video")
			catalogFixtureWrite(t, f, "Missing ID (1998).mkv", "owned video")
			library, source := f.libraryAndSource(t)
			owner := f.owner.ID
			created := f.call(t, owner, http.MethodPost, "/api/media-library/v1/provider-connections", map[string]any{
				"operationId": "worker-provider", "ownerKind": "user", "token": "fixture-worker-key",
			})
			var connection medialibrary.ProviderConnection
			if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &connection) != nil {
				t.Fatal(created.Code, created.Body)
			}
			if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "worker-policy", 0,
				medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: connection.ID}); err != nil {
				t.Fatal(err)
			}
			scan := scanFixtureLibrary(t, f, library, source, "metadata-worker-scan")
			path := "/api/media-library/v1/libraries/" + library.ID + "/scans/" + scan.ID + "/metadata"
			readJob := func() medialibrary.MetadataJob {
				t.Helper()
				response := f.call(t, owner, http.MethodGet, path, nil)
				var job medialibrary.MetadataJob
				if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &job) != nil {
					t.Fatal(response.Code, response.Body)
				}
				return job
			}
			if cancel {
				select {
				case <-requestStarted:
				case <-time.After(12 * time.Second):
					t.Fatal("metadata request did not start")
				}
				job := readJob()
				if response := f.call(t, owner, http.MethodDelete, path+"?generation="+strconv.FormatInt(job.Generation, 10), nil); response.Code != http.StatusNoContent {
					t.Fatal(response.Code, response.Body)
				}
				select {
				case <-requestCanceled:
				case <-time.After(6 * time.Second):
					t.Fatal("in-flight request was not canceled")
				}
				if job := readJob(); job.Status != "canceled" || job.Matched != 0 || job.Pending == 0 {
					t.Fatalf("canceled job: %+v", job)
				}
			} else {
				deadline := time.Now().Add(12 * time.Second)
				job := readJob()
				for (job.Status == "queued" || job.Status == "running") && time.Now().Before(deadline) {
					time.Sleep(50 * time.Millisecond)
					job = readJob()
				}
				if job.Status != "completed" || job.Matched != 1 || job.Review != 1 || job.Pending != 0 {
					t.Fatalf("completed job: %+v", job)
				}
				review := f.call(t, owner, http.MethodGet, path+"/review?limit=1", nil)
				var rows struct {
					Items []medialibrary.MetadataReviewTarget
				}
				if review.Code != http.StatusOK || json.Unmarshal(review.Body.Bytes(), &rows) != nil || len(rows.Items) != 1 || rows.Items[0].Reason != "typed_identifier_required" {
					t.Fatalf("review list: %d %s", review.Code, review.Body)
				}
				if response := f.call(t, f.viewer.ID, http.MethodGet, path, nil); response.Code != http.StatusNotFound {
					t.Fatal(response.Code, response.Body)
				}
			}
			items, _ := fixtureItems(t, f, owner, library.ID, "")
			matches := 0
			for _, item := range items {
				if detail := fixtureDetail(t, f, owner, library.ID, item.ID); detail.OnlineMatch != nil {
					matches++
				}
			}
			if calls.Load() != 1 || !cancel && matches != 1 || cancel && matches != 0 {
				t.Fatalf("calls=%d matches=%d", calls.Load(), matches)
			}
		})
	}
}
