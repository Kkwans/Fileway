package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func TestCatalogPersonSearchAndWorksUseAuthorizedOnlineCredits(t *testing.T) {
	f := newMediaScanHTTPFixture(t, func(h *trashHTTPHarness) {
		vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{43}, 32))
		if err != nil {
			t.Fatal(err)
		}
		h.storage.MediaProviderVault = vault
		h.storage.TMDBHTTPClient = &http.Client{}
	})
	catalogFixtureWrite(t, f, "电影甲.mkv", "video")
	catalogFixtureWrite(t, f, "电影乙.mkv", "video")
	catalogFixtureWrite(t, f, "本地/nfo.mkv", "video")
	catalogFixtureWrite(t, f, "本地/nfo.nfo", `<movie><title>本地电影</title><actor><name>隐藏人物</name><role>演员</role></actor></movie>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "person-search-scan")
	owner := f.owner.ID
	created := f.call(t, owner, http.MethodPost, "/api/media-library/v1/provider-connections", map[string]any{
		"operationId": "person-provider", "ownerKind": "user", "token": "fixture-person-key",
	})
	var connection medialibrary.ProviderConnection
	if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &connection) != nil {
		t.Fatal(created.Code, created.Body)
	}
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "person-policy", 0,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	authority, err := f.store.CheckProviderUse(t.Context(), owner, library.ID, connection.ID)
	if err != nil {
		t.Fatal(err)
	}
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 3 {
		t.Fatalf("fixture items: %+v", items)
	}
	expectedPersonItemID := ""
	for index, name := range []string{"张三", "李四"} {
		workTitle := []string{"电影甲", "电影乙"}[index]
		itemID := items[index].ID
		if index == 0 {
			expectedPersonItemID = itemID
		}
		identityResponse := f.call(t, owner, http.MethodGet, "/api/media-library/v1/libraries/"+library.ID+"/items/"+itemID+"/identification", nil)
		var identity mediaIdentificationResponse
		if identityResponse.Code != http.StatusOK || json.Unmarshal(identityResponse.Body.Bytes(), &identity) != nil {
			t.Fatal(identityResponse.Code, identityResponse.Body)
		}
		document := medialibrary.ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {workTitle}},
			Credits: []mediametadata.Credit{{Kind: "actor", Fields: map[string][]string{"name": {name}, "role": {"演员"}},
				Artwork:     []mediametadata.ArtworkDeclaration{{Role: "portrait", Reference: "/" + []string{"zhang.jpg", "li.jpg"}[index]}},
				ExternalIDs: []mediametadata.ExternalID{{Provider: "tmdb", Value: []string{"8", "9"}[index]}}}}}}
		if _, err := f.store.ConfirmProviderMatch(t.Context(), owner, "person-match-"+name, medialibrary.ProviderMatchRequest{
			LibraryID: library.ID, ItemID: itemID, ConnectionID: connection.ID, Kind: "movie", ExternalID: int64(55 + index),
			ItemRevision: identity.ItemRevision, StructureRevision: identity.StructureRevision,
		}, *authority, "zh-CN", time.Now().UnixMilli(), document, medialibrary.ProviderMatchAudit{Source: "manual"}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := f.store.ListPersonCandidates(t.Context(), owner, medialibrary.PersonCandidateOptions{Query: "张", Limit: 20}); err != nil {
		t.Fatalf("person candidate query: %v", err)
	}
	response := f.call(t, owner, http.MethodGet, "/api/media-library/v1/catalog/people?q="+url.QueryEscape("张"), nil)
	var page catalogPersonPage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 ||
		page.Items[0].Name != "张三" || len(page.Items[0].Preview) != 1 || page.Items[0].Preview[0].Ref.ItemID != expectedPersonItemID {
		t.Fatalf("person search: %d %s", response.Code, response.Body)
	}
	person := page.Items[0]
	if person.Portrait == nil || person.Portrait.Role != "profile" || !strings.Contains(person.Portrait.Endpoint, "/artwork/tmdb_profile_") {
		t.Fatalf("person portrait: %+v", person.Portrait)
	}
	works := f.call(t, owner, http.MethodGet, "/api/media-library/v1/catalog/people/"+person.Ref.PersonID+"/works?libraryId="+library.ID, nil)
	var worksPage catalogCardPage
	if works.Code != http.StatusOK || json.Unmarshal(works.Body.Bytes(), &worksPage) != nil || len(worksPage.Items) != 1 ||
		worksPage.Items[0].Ref.ItemID != expectedPersonItemID {
		t.Fatalf("person works: %d %s", works.Code, works.Body)
	}
	hidden := f.call(t, owner, http.MethodGet, "/api/media-library/v1/catalog/people?q="+url.QueryEscape("隐藏人物"), nil)
	if hidden.Code != http.StatusOK || !strings.Contains(hidden.Body.String(), `"items":[]`) {
		t.Fatalf("NFO person leaked: %d %s", hidden.Code, hidden.Body)
	}
	unseen := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/catalog/people?q="+url.QueryEscape("张三"), nil)
	if unseen.Code != http.StatusOK || !strings.Contains(unseen.Body.String(), `"items":[]`) {
		t.Fatalf("unseen person leaked: %d %s", unseen.Code, unseen.Body)
	}
}
