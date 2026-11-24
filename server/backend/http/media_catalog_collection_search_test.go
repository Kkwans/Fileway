package fbhttp

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

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
