package fbhttp

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestCatalogFileSearchUsesAuthorizedFilenamesAndBoundCursor(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Echo (2020).mkv", "video")
	catalogFixtureWrite(t, f, "Echo Two (2021).mkv", "video")
	catalogFixtureWrite(t, f, "100% Love (2023).mkv", "video")
	catalogFixtureWrite(t, f, "中文/film.mkv", "video")
	catalogFixtureWrite(t, f, "中文/film.nfo", `<movie><title>回声</title><plot>中文简介</plot></movie>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-file-search-scan")
	base := "/api/media-library/v1/catalog/files"
	response := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("eChO")+"&limit=1", nil)
	var page catalogFilePage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 ||
		!strings.Contains(strings.ToLower(page.Items[0].FileName), "echo") || !page.HasMore || page.NextCursor == "" {
		t.Fatalf("filename search: %d %s", response.Code, response.Body)
	}
	if strings.Contains(response.Body.String(), `"locator"`) || strings.Contains(response.Body.String(), `"path"`) {
		t.Fatalf("private path leaked: %s", response.Body)
	}
	wrongQuery := f.call(t, f.owner.ID, http.MethodGet, base+"?q=other&limit=1&cursor="+url.QueryEscape(page.NextCursor), nil)
	if wrongQuery.Code != http.StatusBadRequest {
		t.Fatalf("cursor was reused under another query: %d %s", wrongQuery.Code, wrongQuery.Body)
	}
	continued := f.call(t, f.owner.ID, http.MethodGet, base+"?q=eChO&limit=1&cursor="+url.QueryEscape(page.NextCursor), nil)
	var continuedPage catalogFilePage
	if continued.Code != http.StatusOK || json.Unmarshal(continued.Body.Bytes(), &continuedPage) != nil || len(continuedPage.Items) != 1 ||
		continuedPage.Items[0].Card.Ref.ItemID == page.Items[0].Card.Ref.ItemID {
		t.Fatalf("filename continuation: %d %s", continued.Code, continued.Body)
	}
	metadataOnly := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("回声"), nil)
	if metadataOnly.Code != http.StatusOK || !strings.Contains(metadataOnly.Body.String(), `"items":[]`) {
		t.Fatalf("metadata title entered filename search: %d %s", metadataOnly.Code, metadataOnly.Body)
	}
	literal := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape("%"), nil)
	var literalPage catalogFilePage
	if literal.Code != http.StatusOK || json.Unmarshal(literal.Body.Bytes(), &literalPage) != nil || len(literalPage.Items) != 1 ||
		literalPage.Items[0].FileName != "100% Love (2023).mkv" {
		t.Fatalf("LIKE wildcard was not escaped: %d %s", literal.Code, literal.Body)
	}
	hidden := f.call(t, f.viewer.ID, http.MethodGet, base+"?q="+url.QueryEscape("Echo"), nil)
	var hiddenPage catalogFilePage
	if hidden.Code != http.StatusOK || json.Unmarshal(hidden.Body.Bytes(), &hiddenPage) != nil || len(hiddenPage.Items) != 0 {
		t.Fatalf("unseen filename leaked: %d %s", hidden.Code, hidden.Body)
	}
	invalid := f.call(t, f.owner.ID, http.MethodGet, base+"?q="+url.QueryEscape(strings.Repeat("x", 201)), nil)
	if invalid.Code != http.StatusUnprocessableEntity {
		t.Fatalf("oversized query accepted: %d %s", invalid.Code, invalid.Body)
	}
	home := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/home", nil)
	var homePage catalogHome
	if home.Code != http.StatusOK || json.Unmarshal(home.Body.Bytes(), &homePage) != nil || !homePage.Capabilities["fileSearch"] {
		t.Fatalf("file search capability missing: %d %s", home.Code, home.Body)
	}
}
