package fbhttp

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestCatalogWorkSearchUsesVisibleProjectionAndQueryBoundCursor(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Echo (2020).mkv", "video")
	catalogFixtureWrite(t, f, "Echo Two (2021).mkv", "video")
	catalogFixtureWrite(t, f, "Other (2022).mkv", "video")
	catalogFixtureWrite(t, f, "100% Love (2023).mkv", "video")
	catalogFixtureWrite(t, f, "中文/film.mkv", "video")
	catalogFixtureWrite(t, f, "中文/film.nfo", `<movie><title>回声</title><plot>中文简介</plot></movie>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-search-scan")
	base := "/api/media-library/v1/catalog/items"
	response := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("eChO")+"&limit=1", nil)
	var page catalogCardPage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 ||
		!strings.Contains(strings.ToLower(page.Items[0].Title), "echo") || !page.HasMore || page.NextCursor == "" {
		t.Fatalf("English search: %d %s", response.Code, response.Body)
	}
	wrongQuery := f.call(t, f.owner.ID, http.MethodGet, base+"?q=other&limit=1&cursor="+url.QueryEscape(page.NextCursor), nil)
	if wrongQuery.Code != http.StatusBadRequest {
		t.Fatalf("cursor was reused under another query: %d %s", wrongQuery.Code, wrongQuery.Body)
	}
	chinese := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("回声"), nil)
	var chinesePage catalogCardPage
	if chinese.Code != http.StatusOK || json.Unmarshal(chinese.Body.Bytes(), &chinesePage) != nil || len(chinesePage.Items) != 1 ||
		chinesePage.Items[0].Title != "回声" {
		t.Fatalf("Chinese search: %d %s", chinese.Code, chinese.Body)
	}
	empty := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("不存在"), nil)
	if empty.Code != http.StatusOK || !strings.Contains(empty.Body.String(), `"items":[]`) {
		t.Fatalf("empty search: %d %s", empty.Code, empty.Body)
	}
	literal := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("%"), nil)
	var literalPage catalogCardPage
	if literal.Code != http.StatusOK || json.Unmarshal(literal.Body.Bytes(), &literalPage) != nil || len(literalPage.Items) != 1 ||
		!strings.Contains(literalPage.Items[0].Title, "%") {
		t.Fatalf("LIKE wildcard was not escaped: %d %s", literal.Code, literal.Body)
	}
	invalid := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape(strings.Repeat("x", 201)), nil)
	if invalid.Code != http.StatusUnprocessableEntity {
		t.Fatalf("oversized query accepted: %d %s", invalid.Code, invalid.Body)
	}
	hidden := f.call(t, f.viewer.ID, http.MethodGet, base+"?q="+url.QueryEscape("回声"), nil)
	var hiddenPage catalogCardPage
	if hidden.Code != http.StatusOK || json.Unmarshal(hidden.Body.Bytes(), &hiddenPage) != nil || len(hiddenPage.Items) != 0 {
		t.Fatalf("unseen search hit leaked: %d %s", hidden.Code, hidden.Body)
	}
	home := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/home", nil)
	var homePage catalogHome
	if home.Code != http.StatusOK || json.Unmarshal(home.Body.Bytes(), &homePage) != nil || !homePage.Capabilities["search"] {
		t.Fatalf("search capability missing: %d %s", home.Code, home.Body)
	}
}
