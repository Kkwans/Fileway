package fbhttp

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestMediaVariantsCanReportUnavailableSourceState(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	file := filepath.Join(f.root, "films", "missing.mkv")
	if err := os.WriteFile(file, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "variant-state")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("items: %+v", items)
	}
	if err := os.Remove(file); err != nil {
		t.Fatal(err)
	}
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + items[0].ID + "/variants"
	legacy := f.call(t, f.owner.ID, http.MethodGet, base, nil)
	var legacyPage struct {
		Items []mediaVariantResponse `json:"items"`
	}
	if legacy.Code != http.StatusOK || json.Unmarshal(legacy.Body.Bytes(), &legacyPage) != nil || len(legacyPage.Items) != 0 {
		t.Fatalf("legacy variant behavior changed: %d %s", legacy.Code, legacy.Body)
	}
	response := f.call(t, f.owner.ID, http.MethodGet, base+"?includeUnavailable=true", nil)
	var page struct {
		Items []mediaVariantResponse `json:"items"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 {
		t.Fatalf("unavailable variants: %d %s", response.Code, response.Body)
	}
	item := page.Items[0]
	if item.SourceKind != "server" || item.Availability != "missing" || item.ReasonCode != "file_missing" || item.RecoveryAction != "rescan" {
		t.Fatalf("source state: %+v", item)
	}
	if item.DisplayName == "" || item.ContentType == "" || item.WeakIdentity || item.MappingRevision <= 0 {
		t.Fatalf("source identity lost: %+v", item)
	}
	invalid := f.call(t, f.owner.ID, http.MethodGet, base+"?includeUnavailable=yes", nil)
	if invalid.Code != http.StatusBadRequest {
		t.Fatalf("invalid source-state option accepted: %d %s", invalid.Code, invalid.Body)
	}
	home := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/home", nil)
	var homePage catalogHome
	if home.Code != http.StatusOK || json.Unmarshal(home.Body.Bytes(), &homePage) != nil || !homePage.Capabilities["sourceStates"] {
		t.Fatalf("source-state capability missing: %d %s", home.Code, home.Body)
	}
}
