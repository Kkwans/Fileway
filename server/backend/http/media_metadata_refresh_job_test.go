package fbhttp

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func TestMetadataRefreshJobRefreshesLockedEnglishMatchThroughAPI(t *testing.T) {
	var calls atomic.Int32
	var block atomic.Bool
	requestStarted := make(chan struct{})
	releaseRequest := make(chan struct{})
	var startedOnce sync.Once
	f := newMediaScanHTTPFixture(t, func(h *trashHTTPHarness) {
		vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{41}, 32))
		if err != nil {
			t.Fatal(err)
		}
		h.storage.MediaProviderVault = vault
		h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
			calls.Add(1)
			if r.URL.Path != "/3/movie/55" || r.URL.Query().Get("language") != "zh-CN" {
				t.Errorf("unexpected refresh request: %s language=%s", r.URL.Path, r.URL.Query().Get("language"))
			}
			if block.Load() {
				startedOnce.Do(func() { close(requestStarted) })
				select {
				case <-r.Context().Done():
				case <-releaseRequest:
				}
			}
			return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
				`{"id":55,"title":"中文电影","overview":"中文简介","release_date":"2020-01-01","credits":{"cast":[{"id":8,"name":"中文演员"}]}}`))}, nil
		})}
	})
	catalogFixtureWrite(t, f, "film.mkv", "owned video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "refresh-job-api-scan")
	owner := f.owner.ID
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("fixture items: %+v", items)
	}
	itemID := items[0].ID
	base := "/api/media-library/v1/libraries/" + library.ID
	created := f.call(t, owner, http.MethodPost, "/api/media-library/v1/provider-connections", map[string]any{
		"operationId": "refresh-job-provider", "ownerKind": "user", "token": "fixture-refresh-job-key",
	})
	var connection medialibrary.ProviderConnection
	if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &connection) != nil {
		t.Fatal(created.Code, created.Body)
	}
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "refresh-job-english", 0,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: connection.ID, Language: "en-US", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	identityResponse := f.call(t, owner, http.MethodGet, base+"/items/"+itemID+"/identification", nil)
	var identity mediaIdentificationResponse
	if identityResponse.Code != http.StatusOK || json.Unmarshal(identityResponse.Body.Bytes(), &identity) != nil {
		t.Fatal(identityResponse.Code, identityResponse.Body)
	}
	authority, err := f.store.CheckProviderUse(t.Context(), owner, library.ID, connection.ID)
	if err != nil {
		t.Fatal(err)
	}
	english := medialibrary.ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{
		"title": {"English film"}, "plot": {"English plot"},
	}}}
	if _, err := f.store.ConfirmProviderMatch(t.Context(), owner, "refresh-job-english-match", medialibrary.ProviderMatchRequest{
		LibraryID: library.ID, ItemID: itemID, ConnectionID: connection.ID, Kind: "movie", ExternalID: 55,
		ItemRevision: identity.ItemRevision, StructureRevision: identity.StructureRevision,
	}, *authority, "en-US", time.Now().UnixMilli(), english, medialibrary.ProviderMatchAudit{Source: "manual"}); err != nil {
		t.Fatal(err)
	}
	beforeLock := fixtureDetail(t, f, owner, library.ID, itemID)
	if _, err := f.store.ApplyMetadata(t.Context(), owner, library.ID, itemID, beforeLock.Item.Revision, "refresh-job-lock",
		medialibrary.MetadataPatch{Fields: map[string]medialibrary.MetadataFieldPatch{"title": {Values: []string{"手动锁定标题"}}}}); err != nil {
		t.Fatal(err)
	}
	policy, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "refresh-job-chinese", 1,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	enqueue := f.call(t, owner, http.MethodPost, base+"/metadata-refresh-jobs", map[string]any{
		"operationId": "refresh-job-enqueue", "mode": "refresh_unlocked", "expectedPolicyRevision": policy.Revision,
	})
	var job metadataRefreshJobResponse
	if enqueue.Code != http.StatusAccepted || json.Unmarshal(enqueue.Body.Bytes(), &job) != nil || job.JobID == "" || job.Total != 1 {
		t.Fatalf("enqueue: %d %s", enqueue.Code, enqueue.Body)
	}
	deadline := time.Now().Add(12 * time.Second)
	for {
		response := f.call(t, owner, http.MethodGet, base+"/metadata-refresh-jobs/"+job.JobID, nil)
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &job) != nil {
			t.Fatal(response.Code, response.Body)
		}
		if job.Status != "queued" && job.Status != "running" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("refresh did not finish: %+v", job)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if job.Status != "completed" || job.Refreshed != 1 || job.Pending != 0 || calls.Load() != 1 {
		t.Fatalf("completed job: %+v calls=%d", job, calls.Load())
	}
	detail := fixtureDetail(t, f, owner, library.ID, itemID)
	if detail.Item.Title != "手动锁定标题" || detail.Fields["plot"].Values[0] != "中文简介" || len(detail.Credits) != 1 ||
		detail.Credits[0].Name != "中文演员" || detail.OnlineMatch == nil || detail.OnlineMatch.Language != "zh-CN" || detail.OnlineMatch.DecisionSource != "refresh" {
		t.Fatalf("refreshed detail: %+v", detail)
	}
	replay := f.call(t, owner, http.MethodPost, base+"/metadata-refresh-jobs", map[string]any{
		"operationId": "refresh-job-enqueue", "mode": "refresh_unlocked", "expectedPolicyRevision": policy.Revision,
	})
	var replayed metadataRefreshJobResponse
	if replay.Code != http.StatusAccepted || json.Unmarshal(replay.Body.Bytes(), &replayed) != nil || replayed.JobID != job.JobID || calls.Load() != 1 {
		t.Fatalf("enqueue replay: %d %s calls=%d", replay.Code, replay.Body, calls.Load())
	}
	undo := f.call(t, owner, http.MethodPost, base+"/metadata-refresh-jobs/"+job.JobID+"/undo", map[string]any{
		"operationId": "refresh-job-undo", "generation": job.Generation,
	})
	var undone medialibrary.MetadataRefreshUndoResult
	if undo.Code != http.StatusOK || json.Unmarshal(undo.Body.Bytes(), &undone) != nil || undone.Undone != 1 {
		t.Fatalf("undo: %d %s", undo.Code, undo.Body)
	}
	restored := fixtureDetail(t, f, owner, library.ID, itemID)
	if restored.Item.Title != "手动锁定标题" || restored.Fields["plot"].Values[0] != "English plot" ||
		restored.OnlineMatch == nil || restored.OnlineMatch.Language != "en-US" {
		t.Fatalf("restored detail: %+v", restored)
	}
	progress := f.call(t, owner, http.MethodGet, base+"/metadata-refresh-jobs/"+job.JobID, nil)
	if progress.Code != http.StatusOK || json.Unmarshal(progress.Body.Bytes(), &job) != nil || job.Undone != 1 {
		t.Fatalf("undo progress: %d %s", progress.Code, progress.Body)
	}
	block.Store(true)
	enqueueAgain := f.call(t, owner, http.MethodPost, base+"/metadata-refresh-jobs", map[string]any{
		"operationId": "refresh-job-retry-enqueue", "mode": "refresh_unlocked", "expectedPolicyRevision": policy.Revision,
	})
	var retryJob metadataRefreshJobResponse
	if enqueueAgain.Code != http.StatusAccepted || json.Unmarshal(enqueueAgain.Body.Bytes(), &retryJob) != nil {
		t.Fatal(enqueueAgain.Code, enqueueAgain.Body)
	}
	select {
	case <-requestStarted:
	case <-time.After(12 * time.Second):
		t.Fatal("retry fixture request did not start")
	}
	runningResponse := f.call(t, owner, http.MethodGet, base+"/metadata-refresh-jobs/"+retryJob.JobID, nil)
	if runningResponse.Code != http.StatusOK || json.Unmarshal(runningResponse.Body.Bytes(), &retryJob) != nil || retryJob.Status != "running" {
		t.Fatalf("running retry job: %d %s", runningResponse.Code, runningResponse.Body)
	}
	canceled := f.call(t, owner, http.MethodDelete, base+"/metadata-refresh-jobs/"+retryJob.JobID+"?generation="+strconv.FormatInt(retryJob.Generation, 10), nil)
	if canceled.Code != http.StatusNoContent {
		t.Fatal(canceled.Code, canceled.Body)
	}
	block.Store(false)
	close(releaseRequest)
	canceledResponse := f.call(t, owner, http.MethodGet, base+"/metadata-refresh-jobs/"+retryJob.JobID, nil)
	if canceledResponse.Code != http.StatusOK || json.Unmarshal(canceledResponse.Body.Bytes(), &retryJob) != nil || retryJob.Status != "canceled" {
		t.Fatalf("canceled retry job: %d %s", canceledResponse.Code, canceledResponse.Body)
	}
	retried := f.call(t, owner, http.MethodPost, base+"/metadata-refresh-jobs/"+retryJob.JobID+"/retry", map[string]any{
		"operationId": "refresh-job-retry", "generation": retryJob.Generation,
	})
	if retried.Code != http.StatusAccepted || json.Unmarshal(retried.Body.Bytes(), &retryJob) != nil || retryJob.Status != "queued" {
		t.Fatalf("retry: %d %s", retried.Code, retried.Body)
	}
	deadline = time.Now().Add(12 * time.Second)
	for {
		response := f.call(t, owner, http.MethodGet, base+"/metadata-refresh-jobs/"+retryJob.JobID, nil)
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &retryJob) != nil {
			t.Fatal(response.Code, response.Body)
		}
		if retryJob.Status != "queued" && retryJob.Status != "running" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("retry did not finish: %+v", retryJob)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if retryJob.Status != "completed" || retryJob.Refreshed != 1 || calls.Load() != 3 {
		t.Fatalf("retried job: %+v calls=%d", retryJob, calls.Load())
	}
}
