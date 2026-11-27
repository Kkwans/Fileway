package fbhttp

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func TestMetadataRefreshPartsIncludeAlternatives(t *testing.T) {
	for _, kind := range []string{"movie", "series"} {
		parts := metadataRefreshParts(kind)
		if !slices.Contains(parts, mediaprovider.AlternativeTitles) {
			t.Fatalf("%s refresh parts = %v", kind, parts)
		}
	}
}

func TestMediaMetadataRefreshPreviewApplyAndUndo(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "film.mkv", "video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "refresh-fixture-scan")
	owner := f.owner.ID
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("fixture items: %+v", items)
	}
	itemID := items[0].ID
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + itemID
	connection, err := medialibrary.ProviderConnectionID(owner, "refresh-account")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{9}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(connection)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "fixture-refresh-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.PutProviderConnection(t.Context(), owner, false, connection, "user", "refresh-account", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	calls := 0
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Path == "/3/search/movie" {
			return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
				`{"page":1,"total_pages":1,"total_results":1,"results":[{"id":55,"title":"English film","release_date":"2020-01-01"}]}`))}, nil
		}
		if r.URL.Path != "/3/movie/55" {
			t.Fatalf("unexpected refresh route: %s", r.URL.Path)
		}
		if r.URL.Query().Get("language") == "en-US" {
			return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
				`{"id":55,"title":"English film","overview":"English plot","release_date":"2020-01-01","credits":{"cast":[{"id":8,"name":"English actor"}]}}`))}, nil
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"id":55,"title":"中文电影","overview":"中文简介","release_date":"2020-01-01","credits":{"cast":[{"id":8,"name":"中文演员"}]}}`))}, nil
	})}
	if _, err = f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "refresh-english-policy", 0,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataOnlineFirst, ConnectionID: connection, Language: "en-US", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	identityResponse := f.call(t, owner, http.MethodGet, base+"/identification", nil)
	var identity mediaIdentificationResponse
	if identityResponse.Code != http.StatusOK || json.Unmarshal(identityResponse.Body.Bytes(), &identity) != nil {
		t.Fatalf("identity: %d %s", identityResponse.Code, identityResponse.Body)
	}
	search := f.call(t, owner, http.MethodPost, base+"/match-candidates", matchCandidateSearch{ConnectionID: connection,
		Title: "English film", Kind: "movie", Page: 1, ItemRevision: identity.ItemRevision, StructureRevision: identity.StructureRevision})
	var candidates struct {
		CandidateSetID string `json:"candidateSetId"`
	}
	if search.Code != http.StatusOK || json.Unmarshal(search.Body.Bytes(), &candidates) != nil || candidates.CandidateSetID == "" {
		t.Fatalf("search: %d %s", search.Code, search.Body)
	}
	confirm := f.call(t, owner, http.MethodPost, base+"/match-confirmation", matchConfirmBody{OperationID: "refresh-initial-confirm",
		ConnectionID: connection, CandidateSetID: candidates.CandidateSetID, Kind: "movie", ExternalID: "55",
		ItemRevision: identity.ItemRevision, StructureRevision: identity.StructureRevision})
	if confirm.Code != http.StatusOK {
		t.Fatalf("confirm: %d %s", confirm.Code, confirm.Body)
	}
	if detail := fixtureDetail(t, f, owner, library.ID, itemID); detail.Item.Title != "English film" || detail.OnlineMatch == nil || detail.OnlineMatch.Language != "en-US" {
		t.Fatalf("initial English detail: %+v", detail)
	}
	if _, err = f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "refresh-chinese-policy", 1,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataOnlineFirst, ConnectionID: connection, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	beforeLock := fixtureDetail(t, f, owner, library.ID, itemID)
	if _, err = f.store.ApplyMetadata(t.Context(), owner, library.ID, itemID, beforeLock.Item.Revision, "refresh-lock-title",
		medialibrary.MetadataPatch{Fields: map[string]medialibrary.MetadataFieldPatch{"title": {Values: []string{"手动锁定标题"}}}}); err != nil {
		t.Fatal(err)
	}
	providerCallsBeforeLibraryPreview := calls
	libraryPreview := f.call(t, owner, http.MethodPost, "/api/media-library/v1/libraries/"+library.ID+"/metadata-refresh-preview", map[string]any{})
	var libraryPreviewBody struct {
		TargetLanguage        string         `json:"targetLanguage"`
		MatchedItems          int            `json:"matchedItems"`
		LanguageMismatchItems int            `json:"languageMismatchItems"`
		AlreadyCurrentItems   int            `json:"alreadyCurrentItems"`
		LockedItems           int            `json:"lockedItems"`
		ProtectedFields       map[string]int `json:"protectedFields"`
		CurrentLanguages      map[string]int `json:"currentLanguages"`
		CanRefresh            bool           `json:"canRefresh"`
	}
	if libraryPreview.Code != http.StatusOK || json.Unmarshal(libraryPreview.Body.Bytes(), &libraryPreviewBody) != nil ||
		libraryPreviewBody.TargetLanguage != "zh-CN" || libraryPreviewBody.MatchedItems != 1 ||
		libraryPreviewBody.LanguageMismatchItems != 1 || libraryPreviewBody.AlreadyCurrentItems != 0 ||
		libraryPreviewBody.LockedItems != 1 || libraryPreviewBody.ProtectedFields["title"] != 1 ||
		libraryPreviewBody.CurrentLanguages["en-US"] != 1 || !libraryPreviewBody.CanRefresh || calls != providerCallsBeforeLibraryPreview {
		t.Fatalf("library refresh preview: %d %s calls=%d/%d", libraryPreview.Code, libraryPreview.Body, calls, providerCallsBeforeLibraryPreview)
	}
	preview := f.call(t, owner, http.MethodPost, base+"/metadata-refresh-preview", map[string]any{})
	var previewBody struct {
		CurrentLanguage string   `json:"currentLanguage"`
		TargetLanguage  string   `json:"targetLanguage"`
		ChangedFields   []string `json:"changedFields"`
		ProtectedFields []string `json:"protectedFields"`
	}
	if preview.Code != http.StatusOK || json.Unmarshal(preview.Body.Bytes(), &previewBody) != nil || previewBody.TargetLanguage != "zh-CN" ||
		!slices.Contains(previewBody.ChangedFields, "title") || !slices.Contains(previewBody.ChangedFields, "plot") ||
		!slices.Contains(previewBody.ProtectedFields, "title") {
		t.Fatalf("refresh preview: %d %s", preview.Code, preview.Body)
	}
	identityResponse = f.call(t, owner, http.MethodGet, base+"/identification", nil)
	if identityResponse.Code != http.StatusOK || json.Unmarshal(identityResponse.Body.Bytes(), &identity) != nil {
		t.Fatalf("refresh identity: %d %s", identityResponse.Code, identityResponse.Body)
	}
	refreshed := f.call(t, owner, http.MethodPost, base+"/metadata-refresh", map[string]any{"operationId": "refresh-chinese",
		"itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision})
	if refreshed.Code != http.StatusOK {
		t.Fatalf("refresh: %d %s", refreshed.Code, refreshed.Body)
	}
	refreshCalls := calls
	currentPreview := f.call(t, owner, http.MethodPost, "/api/media-library/v1/libraries/"+library.ID+"/metadata-refresh-preview", map[string]any{})
	var currentPreviewBody struct {
		LanguageMismatchItems int  `json:"languageMismatchItems"`
		AlreadyCurrentItems   int  `json:"alreadyCurrentItems"`
		CanRefresh            bool `json:"canRefresh"`
	}
	if currentPreview.Code != http.StatusOK || json.Unmarshal(currentPreview.Body.Bytes(), &currentPreviewBody) != nil ||
		currentPreviewBody.LanguageMismatchItems != 0 || currentPreviewBody.AlreadyCurrentItems != 1 || currentPreviewBody.CanRefresh || calls != refreshCalls {
		t.Fatalf("current library refresh preview: %d %s calls=%d/%d", currentPreview.Code, currentPreview.Body, calls, refreshCalls)
	}
	if replay := f.call(t, owner, http.MethodPost, base+"/metadata-refresh", map[string]any{"operationId": "refresh-chinese",
		"itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision}); replay.Code != http.StatusOK || calls != refreshCalls || replay.Body.String() != refreshed.Body.String() {
		t.Fatalf("refresh replay: %d %s calls=%d/%d", replay.Code, replay.Body, calls, refreshCalls)
	}
	detail := fixtureDetail(t, f, owner, library.ID, itemID)
	if detail.Item.Title != "手动锁定标题" || detail.Fields["plot"].Values[0] != "中文简介" || len(detail.Credits) != 1 || detail.Credits[0].Name != "中文演员" ||
		detail.OnlineMatch == nil || detail.OnlineMatch.DecisionSource != "refresh" || detail.OnlineMatch.Language != "zh-CN" {
		t.Fatalf("refreshed detail: %+v", detail)
	}
	identityResponse = f.call(t, owner, http.MethodGet, base+"/identification", nil)
	if identityResponse.Code != http.StatusOK || json.Unmarshal(identityResponse.Body.Bytes(), &identity) != nil || identity.UndoMatchRevision < 1 {
		t.Fatalf("undo identity: %d %s", identityResponse.Code, identityResponse.Body)
	}
	undo := f.call(t, owner, http.MethodPost, base+"/match-confirmation/undo", map[string]any{"operationId": "undo-refresh",
		"itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision, "matchRevision": identity.UndoMatchRevision})
	if undo.Code != http.StatusOK {
		t.Fatalf("undo refresh: %d %s", undo.Code, undo.Body)
	}
	if restored := fixtureDetail(t, f, owner, library.ID, itemID); restored.Item.Title != "手动锁定标题" || restored.OnlineMatch == nil || restored.OnlineMatch.Language != "en-US" {
		t.Fatalf("restored English detail: %+v", restored)
	}
	configuredVault := vault
	f.h.storage.MediaProviderVault = nil
	withoutProvider := f.call(t, owner, http.MethodPost, "/api/media-library/v1/libraries/"+library.ID+"/metadata-refresh-preview", map[string]any{})
	var withoutProviderBody struct {
		CanRefresh bool `json:"canRefresh"`
	}
	if withoutProvider.Code != http.StatusOK || json.Unmarshal(withoutProvider.Body.Bytes(), &withoutProviderBody) != nil || withoutProviderBody.CanRefresh {
		t.Fatalf("preview without provider: %d %s", withoutProvider.Code, withoutProvider.Body)
	}
	f.h.storage.MediaProviderVault = configuredVault
	policy, err := f.store.GetLibraryMetadataPolicy(t.Context(), owner, library.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "refresh-local-policy", policy.Revision,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalOnly, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	offlineCalls := calls
	offline := f.call(t, owner, http.MethodPost, "/api/media-library/v1/libraries/"+library.ID+"/metadata-refresh-preview", map[string]any{})
	if offline.Code != http.StatusUnprocessableEntity || calls != offlineCalls {
		t.Fatalf("offline library preview: %d %s calls=%d/%d", offline.Code, offline.Body, calls, offlineCalls)
	}
}

func TestMetadataRefreshRepairsEmptyLanguageWhenDocumentIsUnchanged(t *testing.T) {
	var testVault *mediaprovider.CredentialVault
	f := newMediaScanHTTPFixture(t, func(h *trashHTTPHarness) {
		var err error
		testVault, err = mediaprovider.NewCredentialVault(bytes.Repeat([]byte{13}, 32))
		if err != nil {
			t.Fatal(err)
		}
		h.storage.MediaProviderVault = testVault
		h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
			if r.URL.Path != "/3/movie/55" || r.URL.Query().Get("language") != "zh-CN" {
				t.Fatalf("unexpected refresh route: %s", r.URL.String())
			}
			return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
				`{"id":55,"title":"中文电影","overview":"中文简介","release_date":"2020-01-01"}`))}, nil
		})}
	})
	catalogFixtureWrite(t, f, "film.mkv", "video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "empty-language-refresh-fixture")
	owner := f.owner.ID
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("fixture items: %+v", items)
	}
	itemID := items[0].ID
	structure, err := f.store.GetItemStructure(t.Context(), owner, library.ID, itemID)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := medialibrary.ProviderConnectionID(owner, "empty-language-refresh")
	if err != nil {
		t.Fatal(err)
	}
	vault := testVault
	scope, err := medialibrary.ProviderCredentialContext(connection)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "empty-language-refresh-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.PutProviderConnection(t.Context(), owner, false, connection, "user", "empty-language-refresh", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	policy, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "empty-language-policy", 0,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataOnlineFirst, ConnectionID: connection, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	authority, err := f.store.CheckProviderUse(t.Context(), owner, library.ID, connection)
	if err != nil {
		t.Fatal(err)
	}
	document := medialibrary.ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{
		"title": {"中文电影"}, "plot": {"中文简介"}, "year": {"2020"},
	}}}
	request := medialibrary.ProviderMatchRequest{LibraryID: library.ID, ItemID: itemID, ConnectionID: connection,
		Kind: "movie", ExternalID: 55, ItemRevision: items[0].Revision, StructureRevision: structure.Revision}
	if _, err = f.store.ConfirmProviderMatch(t.Context(), owner, "empty-language-initial", request, *authority, "", time.Now().UnixMilli(), document,
		medialibrary.ProviderMatchAudit{Source: "automatic", Region: "CN", AdapterVersion: "tmdb-v1"}); err != nil {
		t.Fatal(err)
	}
	base := "/api/media-library/v1/libraries/" + library.ID
	enqueue := f.call(t, owner, http.MethodPost, base+"/metadata-refresh-jobs", map[string]any{
		"operationId": "empty-language-enqueue", "mode": "refresh_unlocked", "expectedPolicyRevision": policy.Revision,
	})
	var job metadataRefreshJobResponse
	if enqueue.Code != http.StatusAccepted || json.Unmarshal(enqueue.Body.Bytes(), &job) != nil || job.Total != 1 {
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
	if job.Status != "completed" || job.Refreshed != 1 || job.AlreadyCurrent != 0 {
		t.Fatalf("empty language refresh job: %+v", job)
	}
	detail := fixtureDetail(t, f, owner, library.ID, itemID)
	if detail.OnlineMatch == nil || detail.OnlineMatch.Language != "zh-CN" {
		t.Fatalf("refreshed language: %+v", detail.OnlineMatch)
	}

	items, _ = fixtureItems(t, f, owner, library.ID, "")
	structure, err = f.store.GetItemStructure(t.Context(), owner, library.ID, itemID)
	if err != nil {
		t.Fatal(err)
	}
	request.ItemRevision = items[0].Revision
	request.StructureRevision = structure.Revision
	if _, err = f.store.ConfirmProviderMatch(t.Context(), owner, "empty-language-single-initial", request, *authority, "", time.Now().UnixMilli(), document,
		medialibrary.ProviderMatchAudit{Source: "automatic", Region: "CN", AdapterVersion: "tmdb-v1"}); err != nil {
		t.Fatal(err)
	}
	identityResponse := f.call(t, owner, http.MethodGet, base+"/items/"+itemID+"/identification", nil)
	var identity mediaIdentificationResponse
	if identityResponse.Code != http.StatusOK || json.Unmarshal(identityResponse.Body.Bytes(), &identity) != nil {
		t.Fatalf("identity: %d %s", identityResponse.Code, identityResponse.Body)
	}
	refreshed := f.call(t, owner, http.MethodPost, base+"/items/"+itemID+"/metadata-refresh", map[string]any{
		"operationId": "empty-language-single-refresh", "itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision,
	})
	if refreshed.Code != http.StatusOK {
		t.Fatalf("single refresh: %d %s", refreshed.Code, refreshed.Body)
	}
	if detail = fixtureDetail(t, f, owner, library.ID, itemID); detail.OnlineMatch == nil || detail.OnlineMatch.Language != "zh-CN" {
		t.Fatalf("single refreshed language: %+v", detail.OnlineMatch)
	}
}
