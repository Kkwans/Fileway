package fbhttp

import (
	"bytes"
	"encoding/json"
	"image"
	"image/png"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/diskcache"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/spf13/afero"
)

func TestMediaMatchConfirmationPersistsAndHonorsPrecedence(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "film.mkv", "video")
	catalogFixtureWrite(t, f, "film.nfo", `<movie><title>Local film</title><originaltitle>Local original</originaltitle><thumb aspect="poster">cover.png</thumb></movie>`)
	var picture bytes.Buffer
	if err := png.Encode(&picture, image.NewRGBA(image.Rect(0, 0, 2, 2))); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.root, "films", "cover.png"), picture.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	cache, err := diskcache.NewBounded(afero.NewOsFs(), t.TempDir(), 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaArtworkCache = cache
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
	imageCalls := 0
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host == "image.tmdb.org" {
			imageCalls++
			if r.Header.Get("Authorization") != "" || r.URL.RawQuery != "" {
				t.Error("provider credentials sent to CDN")
			}
			switch r.URL.Path {
			case "/t/p/w780/backdrop.png":
				return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(bytes.NewReader(picture.Bytes()))}, nil
			case "/t/p/w500/example.jpg":
				return &http.Response{StatusCode: http.StatusBadGateway, Header: make(http.Header), Body: http.NoBody}, nil
			default:
				t.Errorf("unexpected CDN path: %s", r.URL.Path)
				return &http.Response{StatusCode: http.StatusNotFound, Header: make(http.Header), Body: http.NoBody}, nil
			}
		}
		calls++
		if r.URL.Path != "/3/movie/55" || r.URL.Query().Get("append_to_response") != "credits,external_ids" || r.Header.Get("Authorization") != "Bearer fixture-confirm-token" {
			t.Errorf("unexpected provider route: %s", r.URL.Path)
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"id":55,"title":"TMDB film","original_title":"Online original","overview":"Online synopsis","poster_path":"/example.jpg","backdrop_path":"/backdrop.png","vote_average":7.2,"vote_count":15,"credits":{"cast":[{"id":8,"name":"Online actor","character":"Hero"}],"crew":[{"id":9,"name":"Online director","job":"Director"}]}}`))}, nil
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
		len(local.Ratings) != 1 || local.Ratings[0].Origin != "online" || local.Card == nil || local.Card.MatchState != "provider_confirmed" ||
		local.Card.Artwork == nil || !strings.HasPrefix(local.Card.Artwork.AssetID, "art_") ||
		local.Card.Backdrop == nil || !strings.HasPrefix(local.Card.Backdrop.AssetID, "tmdb_backdrop_") || imageCalls != 0 {
		t.Fatalf("local-first projection: %+v", local)
	}
	policy.Mode = medialibrary.MetadataOnlineFirst
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "prefer-online", 1, policy); err != nil {
		t.Fatal(err)
	}
	online := fixtureDetail(t, f, owner, library.ID, itemID)
	if online.Item.Title != "TMDB film" || calls != 1 || online.Card.Artwork == nil || !strings.HasPrefix(online.Card.Artwork.AssetID, "tmdb_poster_") {
		t.Fatalf("online-first projection: %+v", online)
	}
	if len(online.Credits) != 2 || online.Credits[0].Role != "Hero" || online.Credits[1].Kind != "director" {
		t.Fatalf("online credits: %+v", online.Credits)
	}
	firstCredit := f.call(t, owner, http.MethodGet, base+"/credits?limit=1", nil)
	var creditPage visibleMetadataPage[medialibrary.MetadataCredit]
	if firstCredit.Code != http.StatusOK || json.Unmarshal(firstCredit.Body.Bytes(), &creditPage) != nil || creditPage.NextCursor == "" || len(creditPage.Items) != 1 || creditPage.Items[0].ID != online.Credits[0].ID {
		t.Fatalf("first credit page: %d %s", firstCredit.Code, firstCredit.Body)
	}
	nextCreditPath := base + "/credits?limit=1&revision=" + strconv.FormatInt(creditPage.Revision, 10) + "&cursor=" + url.QueryEscape(creditPage.NextCursor)
	nextCredit := f.call(t, owner, http.MethodGet, nextCreditPath, nil)
	creditPage = visibleMetadataPage[medialibrary.MetadataCredit]{}
	if nextCredit.Code != http.StatusOK || json.Unmarshal(nextCredit.Body.Bytes(), &creditPage) != nil || creditPage.NextCursor != "" || len(creditPage.Items) != 1 || creditPage.Items[0].ID != online.Credits[1].ID {
		t.Fatalf("next credit page: %d %s", nextCredit.Code, nextCredit.Body)
	}
	if response := f.call(t, owner, http.MethodGet, online.Card.Artwork.Endpoint, nil); response.Code != http.StatusOK || response.Header().Get("Content-Type") != "image/png" || imageCalls != 1 {
		t.Fatalf("failed online poster did not use NFO fallback: %d", response.Code)
	}
	backdrop := online.Card.Backdrop.Endpoint
	if response := f.call(t, owner, http.MethodGet, backdrop, nil); response.Code != http.StatusOK || response.Header().Get("Content-Type") != "image/png" || imageCalls != 2 {
		t.Fatalf("initial backdrop: %d", response.Code)
	}
	f.h.storage.TMDBHTTPClient = nil
	if response := f.call(t, owner, http.MethodGet, backdrop, nil); response.Code != http.StatusOK || imageCalls != 2 {
		t.Fatalf("cached backdrop offline: %d", response.Code)
	}
	grant, err := f.store.GrantMember(t.Context(), owner, library.ID, source.LibraryRevision, f.viewer.ID, medialibrary.RoleViewer)
	if err != nil {
		t.Fatal(err)
	}
	if response := f.call(t, f.viewer.ID, http.MethodGet, backdrop, nil); response.Code != http.StatusOK {
		t.Fatalf("member cache access: %d", response.Code)
	}
	if _, err := f.store.RevokeMember(t.Context(), owner, library.ID, grant.LibraryRevision, f.viewer.ID); err != nil {
		t.Fatal(err)
	}
	if response := f.call(t, f.viewer.ID, http.MethodGet, backdrop, nil); response.Code != http.StatusNotFound {
		t.Fatalf("revoked member could read cached backdrop: %d", response.Code)
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
	if offline.Item.Title != "Manual title" || offline.OnlineMatch != nil || len(offline.Ratings) != 0 || len(offline.Credits) != 0 || calls != 1 {
		t.Fatalf("local-only projection: %+v", offline)
	}
	if response := f.call(t, owner, http.MethodGet, backdrop, nil); response.Code != http.StatusNotFound || imageCalls != 2 {
		t.Fatalf("local-only policy exposed cached backdrop: %d", response.Code)
	}
}
