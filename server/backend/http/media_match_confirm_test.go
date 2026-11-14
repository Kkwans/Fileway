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

func TestMediaMatchConfirmationPersistsAndHonorsPrecedence(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "film.mkv", "video")
	catalogFixtureWrite(t, f, "film.nfo", `<movie><title>Local film</title><originaltitle>Local original</originaltitle></movie>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "confirm-fixture-scan")
	owner := f.owner.ID
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("expected one item: %+v", items)
	}
	itemID := items[0].ID
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + itemID
	connection, err := medialibrary.ProviderConnectionID(owner, "match-account")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{5}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(connection)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "fixture-confirm-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.PutProviderConnection(t.Context(), owner, false, connection, "user", "match-account", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	calls := 0
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Path != "/3/movie/55" || r.URL.Query().Get("append_to_response") != "credits,external_ids" || r.Header.Get("Authorization") != "Bearer fixture-confirm-token" {
			t.Errorf("unexpected provider route: %s", r.URL.Path)
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"id":55,"title":"TMDB film","original_title":"Online original","overview":"Online synopsis","poster_path":"/example.jpg","vote_average":7.2,"vote_count":15}`))}, nil
	})}
	identityResponse := f.call(t, owner, http.MethodGet, base+"/identification", nil)
	var identity mediaIdentificationResponse
	if identityResponse.Code != http.StatusOK || json.Unmarshal(identityResponse.Body.Bytes(), &identity) != nil {
		t.Fatalf("identification: %d %s", identityResponse.Code, identityResponse.Body)
	}
	body := matchConfirmBody{OperationID: "confirm-selection", Kind: "movie", ExternalID: "55", ItemRevision: identity.ItemRevision, StructureRevision: identity.StructureRevision}
	if res := f.call(t, owner, http.MethodPost, base+"/match-confirmation", body); res.Code != http.StatusUnprocessableEntity || calls != 0 {
		t.Fatalf("local-only confirmation: %d %s", res.Code, res.Body)
	}
	policy := medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: connection}
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "enable-match", 0, policy); err != nil {
		t.Fatal(err)
	}
	response := f.call(t, owner, http.MethodPost, base+"/match-confirmation", body)
	if response.Code != http.StatusOK || calls != 1 {
		t.Fatalf("confirmation: %d %s", response.Code, response.Body)
	}
	if replay := f.call(t, owner, http.MethodPost, base+"/match-confirmation", body); replay.Code != http.StatusOK || calls != 1 || replay.Body.String() != response.Body.String() {
		t.Fatalf("receipt replay: %d %s", replay.Code, replay.Body)
	}
	local := fixtureDetail(t, f, owner, library.ID, itemID)
	if local.Item.Title != "Local film" || local.Fields["plot"].Source != "online" || local.OnlineMatch == nil || local.OnlineMatch.ExternalID != "55" ||
		len(local.Ratings) != 1 || local.Ratings[0].Origin != "online" || local.Card == nil || local.Card.MatchState != "provider_confirmed" || local.Card.Artwork != nil {
		t.Fatalf("local-first projection: %+v", local)
	}
	policy.Mode = medialibrary.MetadataOnlineFirst
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "prefer-online", 1, policy); err != nil {
		t.Fatal(err)
	}
	online := fixtureDetail(t, f, owner, library.ID, itemID)
	if online.Item.Title != "TMDB film" || calls != 1 {
		t.Fatalf("online-first projection: %+v", online)
	}
	edit := medialibrary.MetadataPatch{Fields: map[string]medialibrary.MetadataFieldPatch{"title": {Values: []string{"Manual title"}}, "plot": {Clear: true}}}
	if _, err := f.store.ApplyMetadata(t.Context(), owner, library.ID, itemID, online.Item.Revision, "lock-local-edit", edit); err != nil {
		t.Fatal(err)
	}
	locked := fixtureDetail(t, f, owner, library.ID, itemID)
	if locked.Item.Title != "Manual title" || !locked.Fields["plot"].ExplicitClear {
		t.Fatalf("manual override: %+v", locked)
	}
	if res := f.call(t, f.viewer.ID, http.MethodGet, base, nil); res.Code != http.StatusNotFound {
		t.Fatalf("private online data: %d %s", res.Code, res.Body)
	}
	policy.Mode = medialibrary.MetadataLocalOnly
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "turn-off-provider", 2, policy); err != nil {
		t.Fatal(err)
	}
	offline := fixtureDetail(t, f, owner, library.ID, itemID)
	if offline.Item.Title != "Manual title" || offline.OnlineMatch != nil || len(offline.Ratings) != 0 || calls != 1 {
		t.Fatalf("local-only projection: %+v", offline)
	}
}
