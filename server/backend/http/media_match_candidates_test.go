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

type matchTestTransport func(*http.Request) (*http.Response, error)

func (f matchTestTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestMediaCandidatesChecksPolicyAndRejectsRevocationDuringLookup(t *testing.T) {
	f, library, _, itemID := userStateFixture(t)
	owner := f.owner.ID
	conn, err := medialibrary.ProviderConnectionID(owner, "candidate-test")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{17}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(conn)
	if err != nil {
		t.Fatal(err)
	}
	encrypted, fingerprint, err := vault.Seal(scope, "fixture-tmdb-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.PutProviderConnection(t.Context(), owner, false, conn, "user", "candidate-test", 0,
		medialibrary.SealedProviderCredential{Ciphertext: encrypted, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	var outbound int
	var switchOffline bool
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		outbound++
		if r.URL.Host != "api.themoviedb.org" || r.URL.Path != "/3/search/movie" ||
			r.URL.Query().Get("query") != "Fixture work" || r.Header.Get("Authorization") != "Bearer fixture-tmdb-token" {
			t.Error("wrong provider request")
		}
		if switchOffline {
			_, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "offline-during-search", 1,
				medialibrary.LibraryMetadataPolicy{Mode: "local_only"})
			if err != nil {
				t.Error(err)
			}
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"page":1,"total_pages":1,"total_results":1,"results":[{"id":5,"title":"Fixture work"}]}`))}, nil
	})}
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + itemID
	identity := f.call(t, owner, http.MethodGet, base+"/identification", nil)
	var item mediaIdentificationResponse
	if identity.Code != http.StatusOK || json.Unmarshal(identity.Body.Bytes(), &item) != nil {
		t.Fatal("failed fixture identification")
	}
	body := matchCandidateSearch{
		ConnectionID: conn, Title: "Fixture work", Kind: "movie", Page: 1,
		ItemRevision: item.ItemRevision, StructureRevision: item.StructureRevision,
	}
	if res := f.call(t, owner, http.MethodPost, base+"/match-candidates", body); res.Code != http.StatusUnprocessableEntity || outbound != 0 {
		t.Fatalf("offline policy: %d %s", res.Code, res.Body)
	}
	if _, err = f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "online-search-policy", 0,
		medialibrary.LibraryMetadataPolicy{Mode: "local_first_online", ConnectionID: conn}); err != nil {
		t.Fatal(err)
	}
	if res := f.call(t, owner, http.MethodPost, base+"/match-candidates", body); res.Code != http.StatusOK || !strings.Contains(res.Body.String(), "Fixture work") || outbound != 1 {
		t.Fatalf("candidate result: %d %s", res.Code, res.Body)
	}
	switchOffline = true
	if res := f.call(t, owner, http.MethodPost, base+"/match-candidates", body); res.Code == http.StatusOK || strings.Contains(res.Body.String(), "Fixture work") || outbound != 2 {
		t.Fatalf("revoked candidate leak: %d %s", res.Code, res.Body)
	}
}
