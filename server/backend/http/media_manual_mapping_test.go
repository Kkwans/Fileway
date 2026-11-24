package fbhttp

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func mappingIdentity(t *testing.T, f *mediaScanHTTPFixture, base string) mediaIdentificationResponse {
	t.Helper()
	response := f.call(t, f.owner.ID, http.MethodGet, base+"/identification", nil)
	var identity mediaIdentificationResponse
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &identity) != nil {
		t.Fatalf("identity: %d %s", response.Code, response.Body)
	}
	return identity
}

func TestMediaManualMappingOfflineCombinedJourneyAndUndo(t *testing.T) {
	f, library, _, itemID := userStateFixture(t)
	owner := f.owner.ID
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + itemID
	identity := mappingIdentity(t, f, base)
	input := map[string]any{"action": "assign_series", "operationId": "local-preview", "itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision, "newSeriesTitle": "Explicit offline show", "seasonNumber": 0, "episodeNumbers": []int{1, 2}}
	response := f.call(t, owner, http.MethodPost, base+"/mapping-previews", input)
	var preview medialibrary.SeriesMappingPreview
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &preview) != nil || preview.PlayMode != "combined_file" || preview.IndependentStart {
		t.Fatalf("preview: %d %s", response.Code, response.Body)
	}
	command := map[string]any{"action": "assign_series", "operationId": "local-confirm", "previewId": preview.PreviewID}
	response = f.call(t, owner, http.MethodPost, base+"/mapping-commands", command)
	var result medialibrary.SeriesMappingResult
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || len(result.EpisodeIDs) != 2 || result.EpisodeIDs[0] != itemID {
		t.Fatalf("confirm: %d %s", response.Code, response.Body)
	}
	if replay := f.call(t, owner, http.MethodPost, base+"/mapping-commands", command); replay.Code != http.StatusOK || replay.Body.String() != response.Body.String() {
		t.Fatalf("replay: %d %s", replay.Code, replay.Body)
	}
	if current := mappingIdentity(t, f, base); current.UndoMappingRevision != result.MappingRevision {
		t.Fatal("missing recoverable mapping undo", current)
	}
	detail := fixtureDetail(t, f, owner, library.ID, itemID)
	if detail.Item.Kind != "episode" || detail.Item.ParentID != result.SeasonID {
		t.Fatal(detail)
	}
	parent := fixtureDetail(t, f, owner, library.ID, result.SeriesID)
	if parent.Item.Title != "Explicit offline show" {
		t.Fatal(parent.Item)
	}
	seasonPage, err := f.store.ListStructureSeasons(t.Context(), owner, library.ID, result.SeriesID, nil, 100)
	if err != nil || len(seasonPage.Items) != 1 || seasonPage.Items[0].Number != 0 {
		t.Fatal(seasonPage, err)
	}
	undo := map[string]any{"operationId": "local-undo", "mappingRevision": result.MappingRevision}
	response = f.call(t, owner, http.MethodPost, base+"/mapping-undo", undo)
	if response.Code != http.StatusOK {
		t.Fatalf("undo: %d %s", response.Code, response.Body)
	}
	if restored := fixtureDetail(t, f, owner, library.ID, itemID); restored.Item.Kind != "video" {
		t.Fatal(restored.Item)
	}
	if current := mappingIdentity(t, f, base); current.UndoMappingRevision != "" {
		t.Fatal("undone revision still offered", current)
	}
	if replay := f.call(t, owner, http.MethodPost, base+"/mapping-undo", undo); replay.Code != http.StatusOK || replay.Body.String() != response.Body.String() {
		t.Fatalf("undo replay: %d %s", replay.Code, replay.Body)
	}
	if forbidden := f.call(t, f.viewer.ID, http.MethodPost, base+"/mapping-previews", input); forbidden.Code == http.StatusOK || strings.Contains(forbidden.Body.String(), "Explicit offline show") {
		t.Fatal("private mapping leak", forbidden.Code, forbidden.Body)
	}
}

func TestMediaManualMappingSeriesCandidateRequiresExplicitStructure(t *testing.T) {
	f, library, _, itemID := userStateFixture(t)
	owner := f.owner.ID
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + itemID
	connection, err := medialibrary.ProviderConnectionID(owner, "series-account")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{29}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(connection)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "fixture-series-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.PutProviderConnection(t.Context(), owner, false, connection, "user", "series-account", 0, medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "series-online", 0, medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: connection}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	calls := 0
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.Header.Get("Authorization") != "Bearer fixture-series-token" {
			t.Error("missing credential")
		}
		body := `{"id":55,"name":"Selected show","original_name":"Original show","overview":"Series summary"}`
		if r.URL.Path == "/3/search/tv" {
			body = `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":55,"name":"Selected show"}]}`
		} else if r.URL.Path != "/3/tv/55" {
			t.Errorf("unexpected route %s", r.URL.Path)
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}, nil
	})}
	identity := mappingIdentity(t, f, base)
	candidate := map[string]any{"kind": "series", "title": "Selected show", "page": 1, "itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision}
	if response := f.call(t, owner, http.MethodPost, base+"/match-candidates", candidate); response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"kind":"series"`) {
		t.Fatalf("series candidates: %d %s", response.Code, response.Body)
	}
	// Old clients cannot turn a playable file into a series container by merely
	// loosening kind validation. The explicit mapping preview is mandatory.
	if response := f.call(t, owner, http.MethodPost, base+"/match-confirmation", map[string]any{"operationId": "old-confirm", "kind": "series", "externalId": "55", "itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision}); response.Code != http.StatusUnprocessableEntity || calls != 1 {
		t.Fatalf("unsafe compatibility path: %d %s", response.Code, response.Body)
	}
	input := map[string]any{"action": "assign_series", "operationId": "provider-preview", "kind": "series", "externalId": "55", "itemRevision": identity.ItemRevision, "structureRevision": identity.StructureRevision, "episodeNumbers": []int{3}}
	if response := f.call(t, owner, http.MethodPost, base+"/mapping-previews", input); response.Code != http.StatusUnprocessableEntity || calls != 1 {
		t.Fatalf("missing explicit season: %d %s", response.Code, response.Body)
	}
	input["seasonNumber"] = 1
	response := f.call(t, owner, http.MethodPost, base+"/mapping-previews", input)
	var preview medialibrary.SeriesMappingPreview
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &preview) != nil || preview.Request.NewSeriesTitle != "Selected show" || calls != 2 {
		t.Fatalf("provider preview: %d %s", response.Code, response.Body)
	}
	response = f.call(t, owner, http.MethodPost, base+"/mapping-commands", map[string]any{"action": "assign_series", "operationId": "provider-confirm", "previewId": preview.PreviewID})
	var result medialibrary.SeriesMappingResult
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || calls != 2 {
		t.Fatalf("provider mapping: %d %s", response.Code, response.Body)
	}
	parent := fixtureDetail(t, f, owner, library.ID, result.SeriesID)
	if parent.OnlineMatch == nil || parent.OnlineMatch.ExternalID != "55" || parent.OnlineMatch.Kind != "series" || parent.Item.Title != "Selected show" ||
		parent.OnlineMatch.DecisionSource != "mapping" || parent.OnlineMatch.Language != "zh-CN" || parent.OnlineMatch.Region != "CN" {
		t.Fatal(parent)
	}
}
