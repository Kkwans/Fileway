package fbhttp

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMediaLibraryDetailProjectsAuthorizedCollectionLabels(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	if err := os.WriteFile(filepath.Join(f.root, "films", "collection.mkv"), []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	var nfo strings.Builder
	nfo.WriteString(`<movie><title>合集电影</title>`)
	for index := range 25 {
		name := "系列甲"
		if index > 1 {
			name = "系列" + string(rune('A'+index))
		}
		tag := "set"
		if index == 1 {
			tag = "collection"
		}
		nfo.WriteString("<" + tag + "><name>" + name + "</name></" + tag + ">")
	}
	nfo.WriteString(`<set><name> </name></set><collectionnumber>2</collectionnumber></movie>`)
	if err := os.WriteFile(filepath.Join(f.root, "films", "collection.nfo"), []byte(nfo.String()), 0o600); err != nil {
		t.Fatal(err)
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "collection-detail")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("items: %+v", items)
	}
	stored, storeErr := f.store.ListItemCollections(t.Context(), f.owner.ID, library.ID, items[0].ID, "", 20)
	if storeErr != nil {
		t.Fatal(storeErr)
	}
	if len(stored.Items) != 20 || stored.NextCursor == "" {
		t.Fatalf("stored collections: %+v", stored)
	}
	response := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/libraries/"+library.ID+"/items/"+items[0].ID, nil)
	var detail struct {
		Collections []struct {
			Name   string `json:"name"`
			Number string `json:"number,omitempty"`
		} `json:"collections"`
		CollectionsNextCursor string `json:"collectionsNextCursor"`
		Item                  struct {
			Revision int64 `json:"revision"`
		} `json:"item"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &detail) != nil || len(detail.Collections) != 20 ||
		detail.Collections[0].Name != "系列甲" || detail.Collections[1].Name != "系列甲" ||
		detail.Collections[0].Number != "2" || detail.Collections[1].Number != "2" || detail.CollectionsNextCursor == "" {
		t.Fatalf("collection detail: %d %s", response.Code, response.Body)
	}
	remaining := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/libraries/"+library.ID+"/items/"+items[0].ID+
		"/collections?limit=100&cursor="+url.QueryEscape(detail.CollectionsNextCursor)+"&revision="+fmt.Sprint(detail.Item.Revision), nil)
	var remainingPage struct {
		Items []struct {
			Name string `json:"name"`
		} `json:"items"`
	}
	if remaining.Code != http.StatusOK || json.Unmarshal(remaining.Body.Bytes(), &remainingPage) != nil || len(remainingPage.Items) != 5 {
		t.Fatalf("collection continuation: %d %s", remaining.Code, remaining.Body)
	}
	hidden := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/libraries/"+library.ID+"/items/"+items[0].ID, nil)
	if hidden.Code != http.StatusNotFound {
		t.Fatalf("unseen collection detail leaked: %d %s", hidden.Code, hidden.Body)
	}
}
