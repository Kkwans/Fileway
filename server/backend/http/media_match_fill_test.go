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

func TestMediaMatchFillRespectsExplicitIdentityAndReplays(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Echo (2020) [tmdb-movie-42].mkv", "owned video")
	catalogFixtureWrite(t, f, "Remake (1998).mkv", "owned video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "auto-fixture-scan")
	owner := f.owner.ID
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 2 {
		t.Fatalf("fixture item count: %d", len(items))
	}
	conn, err := medialibrary.ProviderConnectionID(owner, "fill-fixture")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{12}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(conn)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "fixture-fill-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.PutProviderConnection(t.Context(), owner, false, conn, "user", "fill-fixture", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	var outbound int
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		outbound++
		if r.URL.Path != "/3/movie/42" || r.URL.Query().Has("query") || r.Header.Get("Authorization") != "Bearer fixture-fill-token" {
			t.Error("unexpected automatic provider request")
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"id":42,"title":"Echo","release_date":"2020-09-01","overview":"Saved automatically"}`))}, nil
	})}
	for _, item := range items {
		base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + item.ID
		identified := f.call(t, owner, http.MethodGet, base+"/identification", nil)
		var identification mediaIdentificationResponse
		if identified.Code != http.StatusOK || json.Unmarshal(identified.Body.Bytes(), &identification) != nil || identification.File == nil {
			t.Fatalf("identification: %d %s", identified.Code, identified.Body)
		}
		input := map[string]any{"operationId": "fill-" + item.ID, "itemRevision": identification.ItemRevision, "structureRevision": identification.StructureRevision}
		if len(identification.File.ProviderIDs) == 0 {
			if response := f.call(t, owner, http.MethodPost, base+"/match-fill", input); response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"state":"local_only"`) {
				t.Fatalf("offline fill: %d %s", response.Code, response.Body)
			}
		}
	}
	policy := medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: conn}
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "allow-fill", 0, policy); err != nil {
		t.Fatal(err)
	}
	for _, item := range items {
		base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + item.ID
		identified := f.call(t, owner, http.MethodGet, base+"/identification", nil)
		var identification mediaIdentificationResponse
		if identified.Code != http.StatusOK || json.Unmarshal(identified.Body.Bytes(), &identification) != nil {
			t.Fatalf("identification: %d %s", identified.Code, identified.Body)
		}
		input := map[string]any{"operationId": "fill-" + item.ID, "itemRevision": identification.ItemRevision, "structureRevision": identification.StructureRevision}
		response := f.call(t, owner, http.MethodPost, base+"/match-fill", input)
		if response.Code != http.StatusOK {
			t.Fatalf("fill: %d %s", response.Code, response.Body)
		}
		if strings.HasPrefix(identification.File.ParsedTitle, "Remake") {
			if !strings.Contains(response.Body.String(), `"reason":"typed_identifier_required"`) {
				t.Fatalf("title-only item: %s", response.Body)
			}
			continue
		}
		if !strings.Contains(response.Body.String(), `"state":"matched"`) {
			t.Fatalf("typed movie: %s", response.Body)
		}
		if replay := f.call(t, owner, http.MethodPost, base+"/match-fill", input); replay.Code != http.StatusOK || replay.Body.String() != response.Body.String() {
			t.Fatalf("typed movie replay: %d %s", replay.Code, replay.Body)
		}
		if detail := fixtureDetail(t, f, owner, library.ID, item.ID); detail.OnlineMatch == nil || detail.Fields["plot"].Values[0] != "Saved automatically" {
			t.Fatalf("automatic match was not persisted: %+v", detail.OnlineMatch)
		}
	}
	if outbound != 1 {
		t.Fatalf("provider request count: %d", outbound)
	}
}
