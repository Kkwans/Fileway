package fbhttp

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func TestCatalogCollectionSearchAndWorksUseProviderDeclarations(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for _, name := range []string{"电影甲", "电影乙"} {
		if err := os.WriteFile(filepath.Join(f.root, "films", name+".mkv"), []byte("video"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(f.root, "films", name+".nfo"), []byte("<movie><title>"+name+"</title></movie>"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "provider-collection-search")
	connection, err := medialibrary.ProviderConnectionID(f.owner.ID, "collection")
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
	sealed, fingerprint, err := vault.Seal(scope, "fixture-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.PutProviderConnection(t.Context(), f.owner.ID, false, connection, "user", "collection", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	policy, err := f.store.GetLibraryMetadataPolicy(t.Context(), f.owner.ID, library.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.SetLibraryMetadataPolicy(t.Context(), f.owner.ID, library.ID, "provider-collection-policy", policy.Revision,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataOnlineFirst, ConnectionID: connection, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	itemCount := 0
	err = f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		rows, err := tx.Query(`SELECT id,revision FROM library_item WHERE library_id=? AND kind IN ('movie','video') ORDER BY id`, library.ID)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var itemID string
			var revision int64
			if err := rows.Scan(&itemID, &revision); err != nil {
				return err
			}
			document, err := json.Marshal(map[string]any{"item": map[string]any{
				"kind": "movie", "fields": map[string][]string{"title": {[]string{"电影甲", "电影乙"}[itemCount]}},
				"collections": []map[string]any{{"tag": "set", "fields": map[string][]string{"name": {"系列甲"}},
					"externalIds": []map[string]string{{"provider": "tmdb", "value": "456"}}}},
			}})
			if err != nil {
				return err
			}
			if _, err = tx.Exec(`INSERT INTO item_provider_match(library_id,item_id,connection_id,provider,entity_kind,external_id,language,document_json,fetched_at,revision) VALUES(?,?,?,'tmdb','movie',?,'zh-CN',?,?,?)`,
				library.ID, itemID, connection, 100+itemCount, string(document), 1, revision); err != nil {
				return err
			}
			itemCount++
		}
		return rows.Err()
	})
	if err != nil {
		t.Fatal(err)
	}
	if itemCount != 2 {
		t.Fatalf("provider collection items: %d", itemCount)
	}
	candidates, err := f.store.ListCollectionCandidates(t.Context(), f.owner.ID, medialibrary.CollectionCandidateOptions{Query: "系列", Limit: 10})
	if err != nil || len(candidates.Items) != 1 {
		t.Fatalf("provider collection candidates: %+v err=%v", candidates, err)
	}
	sources, err := f.store.ListCollectionSources(t.Context(), f.owner.ID, candidates.Items[0], "", "", 10)
	if err != nil || len(sources.Items) != 2 {
		t.Fatalf("provider collection sources: %+v err=%v", sources, err)
	}
	for _, source := range sources.Items {
		revision, _, superseded, err := f.store.GetBrowseItemFence(t.Context(), f.owner.ID, library.ID, source.ItemID)
		if err != nil || superseded {
			t.Fatalf("provider collection fence: %+v %v", source, err)
		}
		match, err := f.store.VisibleProviderMatch(t.Context(), f.owner.ID, library.ID, source.ItemID, revision)
		if err != nil || match == nil {
			t.Fatalf("provider collection match: %+v %v", source, err)
		}
	}
	detailResponse := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/libraries/"+library.ID+"/items/"+sources.Items[0].ItemID, nil)
	var detail mediaItemDetail
	if detailResponse.Code != http.StatusOK || json.Unmarshal(detailResponse.Body.Bytes(), &detail) != nil || len(detail.Collections) != 1 || detail.Collections[0].Name != "系列甲" {
		t.Fatalf("provider collection detail: %d %s", detailResponse.Code, detailResponse.Body)
	}
	base := "/api/media-library/v1/catalog/collections"
	response := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("系列"), nil)
	var page catalogCollectionPage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 || page.Items[0].Name != "系列甲" || len(page.Items[0].Preview) != 2 {
		t.Fatalf("provider collection search: %d %s", response.Code, response.Body)
	}
	collection := page.Items[0]
	works := f.call(t, f.owner.ID, http.MethodGet, base+"/"+collection.Ref.CollectionID+"/works?libraryId="+library.ID, nil)
	var worksPage catalogCardPage
	if works.Code != http.StatusOK || json.Unmarshal(works.Body.Bytes(), &worksPage) != nil || len(worksPage.Items) != 2 {
		t.Fatalf("provider collection works: %d %s", works.Code, works.Body)
	}
	hidden := f.call(t, f.viewer.ID, http.MethodGet, base+"?q="+url.QueryEscape("系列"), nil)
	var hiddenPage catalogCollectionPage
	if hidden.Code != http.StatusOK || json.Unmarshal(hidden.Body.Bytes(), &hiddenPage) != nil || len(hiddenPage.Items) != 0 {
		t.Fatalf("unseen provider collection leaked: %d %s", hidden.Code, hidden.Body)
	}
}

func TestCatalogCollectionSearchAndWorksUseAuthorizedNFODeclarations(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for index, name := range []string{"电影甲", "电影乙", "另一部"} {
		fileName := []string{"甲.mkv", "乙.mkv", "另一部.mkv"}[index]
		if err := os.WriteFile(filepath.Join(f.root, "films", fileName), []byte("video"), 0o600); err != nil {
			t.Fatal(err)
		}
		collection := "系列甲"
		if index == 2 {
			collection = "系列乙"
		}
		nfo := "<movie><title>" + name + "</title><set><name>" + collection + "</name></set><set><name>" + collection + "</name></set></movie>"
		if err := os.WriteFile(filepath.Join(f.root, "films", strings.TrimSuffix(fileName, ".mkv")+".nfo"), []byte(nfo), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "collection-search")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 3 {
		t.Fatalf("items: %+v", items)
	}
	base := "/api/media-library/v1/catalog/collections"
	response := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("系列"), nil)
	var page catalogCollectionPage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 2 ||
		len(page.Items[0].Preview) == 0 || page.Items[0].Name == "" {
		t.Fatalf("collection search: %d %s", response.Code, response.Body)
	}
	collection := page.Items[0]
	for _, item := range page.Items {
		if item.Name == "系列甲" {
			collection = item
		}
	}
	if collection.Name != "系列甲" {
		t.Fatalf("expected collection missing: %s", response.Body)
	}
	works := f.call(t, f.owner.ID, http.MethodGet, base+"/"+collection.Ref.CollectionID+"/works?libraryId="+library.ID, nil)
	var worksPage catalogCardPage
	if works.Code != http.StatusOK || json.Unmarshal(works.Body.Bytes(), &worksPage) != nil || len(worksPage.Items) != 2 {
		t.Fatalf("collection works: %d %s", works.Code, works.Body)
	}
	seen := map[string]bool{}
	for _, card := range worksPage.Items {
		if seen[card.Ref.ItemID] {
			t.Fatalf("duplicate declaration duplicated a work: %s", works.Body)
		}
		seen[card.Ref.ItemID] = true
	}
	hidden := f.call(t, f.viewer.ID, http.MethodGet, base+"?q="+url.QueryEscape("系列"), nil)
	var hiddenPage catalogCollectionPage
	if hidden.Code != http.StatusOK || json.Unmarshal(hidden.Body.Bytes(), &hiddenPage) != nil || len(hiddenPage.Items) != 0 {
		t.Fatalf("unseen collection leaked: %d %s", hidden.Code, hidden.Body)
	}
	first := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("系列")+"&limit=1", nil)
	var firstPage catalogCollectionPage
	if first.Code != http.StatusOK || json.Unmarshal(first.Body.Bytes(), &firstPage) != nil || len(firstPage.Items) != 1 ||
		!firstPage.HasMore || firstPage.NextCursor == "" {
		t.Fatalf("collection first page: %d %s", first.Code, first.Body)
	}
	wrongQuery := f.call(t, f.owner.ID, http.MethodGet, base+"?q=other&limit=1&cursor="+url.QueryEscape(firstPage.NextCursor), nil)
	if wrongQuery.Code != http.StatusBadRequest {
		t.Fatalf("collection cursor was reused under another query: %d %s", wrongQuery.Code, wrongQuery.Body)
	}
	home := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/home", nil)
	var homePage catalogHome
	if home.Code != http.StatusOK || json.Unmarshal(home.Body.Bytes(), &homePage) != nil || !homePage.Capabilities["collections"] {
		t.Fatalf("collection capability missing: %d %s", home.Code, home.Body)
	}
}
