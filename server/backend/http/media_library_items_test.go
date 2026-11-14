package fbhttp

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/spf13/afero"
)

func scanFixtureLibrary(t *testing.T, f *mediaScanHTTPFixture, library mediaLibrarySummary, source mediaLibrarySourceSummary, operation string) mediaScanSummary {
	t.Helper()
	path := "/api/media-library/v1/libraries/" + library.ID + "/scans"
	response := f.call(t, f.owner.ID, http.MethodPost, path, map[string]any{"bindingId": source.ID, "operationId": operation, "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision})
	var job mediaScanSummary
	if response.Code != http.StatusAccepted || json.Unmarshal(response.Body.Bytes(), &job) != nil {
		t.Fatalf("scan enqueue: %d %s", response.Code, response.Body)
	}
	return f.waitJob(t, library.ID, job.ID, "completed")
}

func fixtureItems(t *testing.T, f *mediaScanHTTPFixture, actor uint, libraryID, query string) ([]mediaItemSummary, string) {
	t.Helper()
	response := f.call(t, actor, http.MethodGet, "/api/media-library/v1/libraries/"+libraryID+"/items"+query, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("list items: %d %s", response.Code, response.Body)
	}
	var page struct {
		Items      []mediaItemSummary `json:"items"`
		NextCursor string             `json:"nextCursor"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	return page.Items, page.NextCursor
}

func fixtureDetail(t *testing.T, f *mediaScanHTTPFixture, actor uint, libraryID, itemID string) mediaItemDetail {
	t.Helper()
	response := f.call(t, actor, http.MethodGet, "/api/media-library/v1/libraries/"+libraryID+"/items/"+itemID, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("item detail: %d %s", response.Code, response.Body)
	}
	var detail mediaItemDetail
	if err := json.Unmarshal(response.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(response.Body.String(), "example.invalid") || strings.Contains(response.Body.String(), "/films/") || strings.Contains(response.Body.String(), "sourceKey") {
		t.Fatalf("private NFO reference leaked: %s", response.Body)
	}
	return detail
}

func TestMediaLibraryHTTPNFOManualRescanAndUndoConflict(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	if err := os.WriteFile(filepath.Join(f.root, "films", "film.mkv"), []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	nfoPath := filepath.Join(f.root, "films", "film.nfo")
	nfo := `<movie><title>Original title</title><plot>Original plot</plot><ratings><rating name="douban" max="10"><value>8.6</value><votes>125</votes></rating></ratings><actor><name>Actor One</name><role>Lead</role></actor><thumb>https://example.invalid/private.jpg</thumb></movie>`
	if err := os.WriteFile(nfoPath, []byte(nfo), 0o600); err != nil {
		t.Fatal(err)
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "metadata-scan-1")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 1 || items[0].Title != "Original title" {
		t.Fatalf("NFO title not projected: %+v", items)
	}
	detail := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID)
	if detail.Fields["plot"].Values[0] != "Original plot" || len(detail.Ratings) != 1 || detail.Ratings[0].Rating.Provider != "douban" || len(detail.Credits) != 1 || len(detail.Artwork) != 1 {
		t.Fatalf("NFO fields: %+v", detail)
	}
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + items[0].ID
	patch := map[string]any{"operationId": "manual-1", "expectedRevision": detail.Item.Revision, "fields": map[string]any{"title": map[string]any{"values": []string{"Manual title"}, "locked": true}}}
	response := f.call(t, f.owner.ID, http.MethodPatch, base+"/metadata", patch)
	var first struct {
		Revision         int64 `json:"revision"`
		MetadataRevision int64 `json:"metadataRevision"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &first) != nil || first.MetadataRevision < 1 {
		t.Fatalf("manual edit: %d %s", response.Code, response.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPatch, base+"/metadata", patch); replay.Code != http.StatusOK || replay.Body.String() != response.Body.String() {
		t.Fatalf("manual receipt replay: %d %s", replay.Code, replay.Body)
	}
	updatedNFO := strings.ReplaceAll(strings.ReplaceAll(nfo, "Original title", "Updated NFO title"), "Original plot", "Updated plot")
	if err := os.WriteFile(nfoPath, []byte(updatedNFO), 0o600); err != nil {
		t.Fatal(err)
	}
	scanFixtureLibrary(t, f, library, source, "metadata-scan-2")
	detail = fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID)
	if detail.Item.Title != "Manual title" || !detail.Fields["title"].Locked || detail.Fields["plot"].Values[0] != "Updated plot" {
		t.Fatalf("rescan overwrote manual or missed unlocked field: %+v", detail)
	}
	conflict := f.call(t, f.owner.ID, http.MethodPost, base+"/metadata/undo", map[string]any{"operationId": "undo-old", "expectedRevision": detail.Item.Revision, "targetRevision": first.MetadataRevision})
	if conflict.Code != http.StatusConflict {
		t.Fatalf("stale undo accepted: %d %s", conflict.Code, conflict.Body)
	}
	if retained := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID); retained.Item.Title != "Manual title" {
		t.Fatalf("conflict overwrote user input: %+v", retained)
	}
	secondPatch := map[string]any{"operationId": "manual-2", "expectedRevision": detail.Item.Revision, "fields": map[string]any{"title": map[string]any{"values": []string{"Second manual"}}}}
	response = f.call(t, f.owner.ID, http.MethodPatch, base+"/metadata", secondPatch)
	var second struct {
		Revision         int64 `json:"revision"`
		MetadataRevision int64 `json:"metadataRevision"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &second) != nil {
		t.Fatalf("second edit: %d %s", response.Code, response.Body)
	}
	response = f.call(t, f.owner.ID, http.MethodPost, base+"/metadata/undo", map[string]any{"operationId": "undo-latest", "expectedRevision": second.Revision, "targetRevision": second.MetadataRevision})
	if response.Code != http.StatusOK {
		t.Fatalf("latest undo: %d %s", response.Code, response.Body)
	}
	restored := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID)
	if restored.Item.Title != "Manual title" || !restored.Fields["title"].Locked {
		t.Fatalf("undo did not restore manual field: %+v", restored)
	}
	response = f.call(t, f.owner.ID, http.MethodPatch, base+"/metadata", map[string]any{"operationId": "clear-plot", "expectedRevision": restored.Item.Revision, "fields": map[string]any{"plot": map[string]any{"clear": true}}})
	if response.Code != http.StatusOK {
		t.Fatalf("explicit clear: %d %s", response.Code, response.Body)
	}
	scanFixtureLibrary(t, f, library, source, "metadata-scan-locked-clear")
	cleared := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID)
	if !cleared.Fields["plot"].ExplicitClear || !cleared.Fields["plot"].Locked || len(cleared.Fields["plot"].Values) != 0 {
		t.Fatalf("rescan restored explicit clear: %+v", cleared.Fields["plot"])
	}
	response = f.call(t, f.owner.ID, http.MethodPatch, base+"/metadata", map[string]any{"operationId": "reset-plot", "expectedRevision": cleared.Item.Revision, "fields": map[string]any{"plot": map[string]any{"reset": true}}})
	if response.Code != http.StatusOK {
		t.Fatalf("reset explicit clear: %d %s", response.Code, response.Body)
	}
	scanFixtureLibrary(t, f, library, source, "metadata-scan-after-reset")
	if reset := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID); len(reset.Fields["plot"].Values) != 1 || reset.Fields["plot"].Values[0] != "Updated plot" {
		t.Fatalf("reset did not permit next rescan: %+v", reset.Fields["plot"])
	}
	if err := os.WriteFile(nfoPath, []byte("<movie><title>"), 0o600); err != nil {
		t.Fatal(err)
	}
	scanFixtureLibrary(t, f, library, source, "metadata-scan-invalid")
	if kept := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID); kept.Item.Title != "Manual title" || kept.Fields["plot"].Values[0] != "Updated plot" {
		t.Fatalf("bad NFO cleared prior data: %+v", kept)
	}
}

func TestMediaLibraryHTTPItemSourceAuthorityAndPagination(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for _, name := range []string{"a", "b", "c"} {
		if err := os.WriteFile(filepath.Join(f.root, "films", name+".mp4"), []byte("video"), 0o600); err != nil {
			t.Fatal(err)
		}
		content := "<movie><title>NFO-" + name + "</title><plot>Private plot " + name + "</plot><actor><name>Actor " + name + "</name></actor><thumb>https://example.invalid/" + name + ".jpg</thumb></movie>"
		if err := os.WriteFile(filepath.Join(f.root, "films", name+".nfo"), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "source-auth-scan")
	base := "/api/media-library/v1/libraries/" + library.ID
	grant := f.call(t, f.owner.ID, http.MethodPut, fmt.Sprintf("%s/members/%d", base, f.viewer.ID), map[string]any{"operationId": "share-viewer", "expectedRevision": source.LibraryRevision, "role": "viewer"})
	if grant.Code != http.StatusOK {
		t.Fatalf("grant viewer: %d %s", grant.Code, grant.Body)
	}
	viewer, err := f.h.storage.Users.Get(f.h.server.Root, f.viewer.ID)
	if err != nil {
		t.Fatal(err)
	}
	viewer.Perm.Download = false
	if err := f.h.storage.Users.Update(viewer, "Perm"); err != nil {
		t.Fatal(err)
	}
	all, _ := fixtureItems(t, f, f.viewer.ID, library.ID, "")
	if len(all) != 3 {
		t.Fatalf("viewer with restricted library access: %+v", all)
	}
	ids := map[string]string{}
	for _, item := range all {
		ids[item.Title] = item.ID
	}
	if r := f.call(t, f.viewer.ID, http.MethodPatch, base+"/items/"+ids["NFO-a"]+"/metadata", map[string]any{"operationId": "viewer-edit", "expectedRevision": all[0].Revision, "fields": map[string]any{"title": map[string]any{"values": []string{"x"}}}}); r.Code != http.StatusForbidden {
		t.Fatalf("viewer edit accepted: %d %s", r.Code, r.Body)
	}
	manualTarget := fixtureDetail(t, f, f.owner.ID, library.ID, ids["NFO-c"])
	manual := f.call(t, f.owner.ID, http.MethodPatch, base+"/items/"+ids["NFO-c"]+"/metadata", map[string]any{"operationId": "independent-manual", "expectedRevision": manualTarget.Item.Revision, "fields": map[string]any{"tagline": map[string]any{"values": []string{"Owner note"}}}})
	if manual.Code != http.StatusOK {
		t.Fatalf("manual field: %d %s", manual.Code, manual.Body)
	}
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Rules = []rules.Rule{{Path: "/films/b.mp4", Allow: false}, {Path: "/films/a.nfo", Allow: false}, {Path: "/films/c.nfo", Allow: false}}
	if err := f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	restricted := fixtureDetail(t, f, f.viewer.ID, library.ID, ids["NFO-a"])
	if restricted.Item.Title != "a" || restricted.Item.MetadataState != "restricted" || len(restricted.Fields) != 0 || len(restricted.Credits) != 0 || len(restricted.Artwork) != 0 {
		t.Fatalf("denied NFO not filtered: %+v", restricted)
	}
	if r := f.call(t, f.owner.ID, http.MethodPatch, base+"/items/"+ids["NFO-a"]+"/metadata", map[string]any{"operationId": "blind-edit", "expectedRevision": restricted.Item.Revision, "fields": map[string]any{"plot": map[string]any{"values": []string{"overwritten"}}}}); r.Code != http.StatusForbidden {
		t.Fatalf("hidden field overwritten: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodPost, base+"/items/"+ids["NFO-a"]+"/metadata/undo", map[string]any{"operationId": "blind-undo", "expectedRevision": restricted.Item.Revision, "targetRevision": restricted.MetadataRevision}); r.Code != http.StatusForbidden {
		t.Fatalf("hidden metadata undo allowed: %d %s", r.Code, r.Body)
	}
	manualRetained := fixtureDetail(t, f, f.viewer.ID, library.ID, ids["NFO-c"])
	if manualRetained.Item.Title != "c" || manualRetained.Item.MetadataState != "restricted" || manualRetained.Fields["tagline"].Values[0] != "Owner note" {
		t.Fatalf("independent manual field lost: %+v", manualRetained)
	}
	if r := f.call(t, f.owner.ID, http.MethodPatch, base+"/items/"+ids["NFO-c"]+"/metadata", map[string]any{"operationId": "edit-visible-manual", "expectedRevision": manualRetained.Item.Revision, "fields": map[string]any{"tagline": map[string]any{"values": []string{"Updated owner note"}}}}); r.Code != http.StatusOK {
		t.Fatalf("visible manual field blocked: %d %s", r.Code, r.Body)
	}
	visible, cursor := fixtureItems(t, f, f.viewer.ID, library.ID, "?limit=1")
	if len(visible) != 1 || cursor == "" {
		t.Fatalf("filtered first page: %+v cursor=%q", visible, cursor)
	}
	last, lastCursor := fixtureItems(t, f, f.viewer.ID, library.ID, "?limit=1&cursor="+cursor)
	if len(last) != 1 || lastCursor != "" || visible[0].ID == last[0].ID || (visible[0].Title != "a" && visible[0].Title != "c") || (last[0].Title != "a" && last[0].Title != "c") {
		t.Fatalf("filtered pagination leaked or skipped sources: first=%+v last=%+v cursor=%q", visible, last, lastCursor)
	}
	owner.Rules = nil
	if err := f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	oldWorkspace := f.h.fs[f.owner.ID]
	if err := os.MkdirAll(filepath.Join(f.root, "new-workspace", "films"), 0o700); err != nil {
		t.Fatal(err)
	}
	f.h.fs[f.owner.ID] = afero.NewBasePathFs(afero.NewOsFs(), filepath.Join(f.root, "new-workspace"))
	if r := f.call(t, f.viewer.ID, http.MethodGet, base+"/items/"+ids["NFO-a"], nil); r.Code != http.StatusNotFound {
		t.Fatalf("workspace scope change leaked old metadata: %d %s", r.Code, r.Body)
	}
	f.h.fs[f.owner.ID] = oldWorkspace
	if err := os.Remove(filepath.Join(f.root, "films", "a.mp4")); err != nil {
		t.Fatal(err)
	}
	offline := fixtureDetail(t, f, f.viewer.ID, library.ID, ids["NFO-a"])
	if offline.Item.SourceState != mediascan.ReferenceOffline {
		t.Fatalf("attested missing source was not retained offline: %+v", offline.Item)
	}
	if _, err := f.store.GetCatalogItem(t.Context(), f.owner.ID, library.ID, ids["NFO-a"]); err != nil {
		t.Fatalf("offline source metadata was removed: %v", err)
	}
	owner.Perm.Download = false
	if err := f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, base+"/items/"+ids["NFO-c"], nil); r.Code != http.StatusNotFound {
		t.Fatalf("owner revocation not enforced: %d %s", r.Code, r.Body)
	}
	if remaining, _ := fixtureItems(t, f, f.viewer.ID, library.ID, ""); len(remaining) != 0 {
		t.Fatalf("revoked sources visible: %+v", remaining)
	}
}

func TestMediaLibraryHTTPNFOAmbiguityRemainsCandidate(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	if err := os.Mkdir(filepath.Join(f.root, "films", "single"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(f.root, "films", "multiple"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(f.root, "films", "conflict"), 0o700); err != nil {
		t.Fatal(err)
	}
	for name, content := range map[string]string{
		"single/only.mp4":       "video",
		"single/movie.nfo":      "<movie><title>Single title</title></movie>",
		"multiple/one.mp4":      "video",
		"multiple/two.mkv":      "video",
		"multiple/movie.nfo":    "<movie><title>Uncertain title</title></movie>",
		"conflict/identity.mkv": "video",
		"conflict/identity.nfo": `<movie><title>Wrong identity</title><uniqueid type="tmdb">1</uniqueid><uniqueid type="tmdb">2</uniqueid></movie>`,
		"conflict/part.mkv":     "video",
		"conflict/part.nfo":     `<episodedetails><title>First</title></episodedetails><episodedetails><title>Second</title></episodedetails>`,
		"conflict/series.mp4":   "video",
		"conflict/series.nfo":   `<tvshow><title>Series title</title></tvshow>`,
		"conflict/season.mp4":   "video",
		"conflict/season.nfo":   `<season><title>Season title</title></season>`,
	} {
		if err := os.WriteFile(filepath.Join(f.root, "films", filepath.FromSlash(name)), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "ambiguous-scan")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 7 {
		t.Fatalf("items: %+v", items)
	}
	found := map[string]mediaItemDetail{}
	for _, item := range items {
		found[item.Title] = fixtureDetail(t, f, f.owner.ID, library.ID, item.ID)
	}
	single, ok := found["Single title"]
	if !ok || len(single.Fields["title"].Values) != 1 || single.Fields["title"].Values[0] != "Single title" {
		t.Fatalf("single movie.nfo was not projected: %+v", found)
	}
	for _, name := range []string{"one", "two"} {
		detail, ok := found[name]
		if !ok || len(detail.Issues) != 1 || detail.Issues[0] != "ambiguous_association" || len(detail.Candidates) != 1 || len(detail.Candidates[0].SourceKinds) != 1 || detail.Candidates[0].SourceKinds[0] != "movie" || detail.Fields["title"].Source != "filename" || detail.Item.Title != name {
			t.Fatalf("ambiguous NFO was silently applied: %s %+v", name, detail)
		}
	}
	for name, code := range map[string]string{"identity": "external_id_conflict", "part": "multiple_items"} {
		detail, ok := found[name]
		if !ok || len(detail.Issues) != 1 || detail.Issues[0] != code || detail.Fields["title"].Source != "filename" || detail.Item.Title != name || len(detail.ExternalIDs) != 0 {
			t.Fatalf("conflicting NFO was projected: %s %+v", name, detail)
		}
	}
	for name, sourceKind := range map[string]string{"series": "tvshow", "season": "season"} {
		detail, ok := found[name]
		if !ok || len(detail.Candidates) != 1 || detail.Candidates[0].Code != "context_required" || len(detail.Candidates[0].SourceKinds) != 1 || detail.Candidates[0].SourceKinds[0] != sourceKind || detail.Item.Kind != "video" {
			t.Fatalf("context was guessed: %s %+v", name, detail)
		}
	}
}

func TestMediaLibraryHTTPExplicitRebindDoesNotInheritOldMetadata(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	if err := os.WriteFile(filepath.Join(f.root, "films", "film.mkv"), []byte("old video"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.root, "films", "film.nfo"), []byte("<movie><title>Old workspace title</title></movie>"), 0o600); err != nil {
		t.Fatal(err)
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "before-rebind")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 1 || items[0].Title != "Old workspace title" {
		t.Fatalf("original item: %+v", items)
	}
	base := "/api/media-library/v1/libraries/" + library.ID
	itemBase := base + "/items/" + items[0].ID
	variantsResponse := f.call(t, f.owner.ID, http.MethodGet, itemBase+"/variants", nil)
	var variants struct {
		Items []mediaVariantResponse `json:"items"`
	}
	if variantsResponse.Code != http.StatusOK || json.Unmarshal(variantsResponse.Body.Bytes(), &variants) != nil || len(variants.Items) != 1 {
		t.Fatalf("original variants: %d %s", variantsResponse.Code, variantsResponse.Body)
	}
	selected := variants.Items[0]
	resolutionBody := map[string]any{"editionId": selected.EditionID, "variantId": selected.VariantID, "bindingId": selected.BindingID}
	resolved := f.call(t, f.owner.ID, http.MethodPost, itemBase+"/playback-resolutions", resolutionBody)
	var playback struct {
		URL string `json:"url"`
	}
	if resolved.Code != http.StatusOK || json.Unmarshal(resolved.Body.Bytes(), &playback) != nil || playback.URL == "" {
		t.Fatalf("original playback: %d %s", resolved.Code, resolved.Body)
	}
	proofBefore, err := f.store.GetLibrarySourceByID(t.Context(), f.owner.ID, source.ID)
	if err != nil {
		t.Fatal(err)
	}
	var domainsBefore int
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		return tx.QueryRowContext(t.Context(), "SELECT count(*) FROM binding_domain WHERE binding_id=?", source.ID).Scan(&domainsBefore)
	}); err != nil {
		t.Fatal(err)
	}
	manual := f.call(t, f.owner.ID, http.MethodPatch, base+"/items/"+items[0].ID+"/metadata", map[string]any{"operationId": "old-private-note", "expectedRevision": items[0].Revision, "fields": map[string]any{"tagline": map[string]any{"values": []string{"Old private note"}}}})
	if manual.Code != http.StatusOK {
		t.Fatalf("manual old data: %d %s", manual.Code, manual.Body)
	}
	newWorkspace := filepath.Join(f.root, "new-workspace")
	if err := os.MkdirAll(filepath.Join(newWorkspace, "films"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(newWorkspace, "films", "film.mkv"), []byte("new video"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(newWorkspace, "films", "film.nfo"), []byte("<movie><title>New workspace title</title></movie>"), 0o600); err != nil {
		t.Fatal(err)
	}
	f.h.fs[f.owner.ID] = afero.NewBasePathFs(afero.NewOsFs(), newWorkspace)
	if r := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil); r.Code != http.StatusOK || !strings.Contains(r.Body.String(), `"access":"rebind_required"`) || strings.Contains(r.Body.String(), "/films") {
		t.Fatalf("changed workspace not isolated: %d %s", r.Code, r.Body)
	}
	if hidden, _ := fixtureItems(t, f, f.owner.ID, library.ID, ""); len(hidden) != 0 {
		t.Fatalf("old workspace items leaked: %+v", hidden)
	}
	replay := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/local", map[string]any{"operationId": "create-source-1", "expectedRevision": library.Revision, "wireRoot": "/films"})
	var replaySource mediaLibrarySourceSummary
	if replay.Code != http.StatusCreated || json.Unmarshal(replay.Body.Bytes(), &replaySource) != nil || replaySource != source {
		t.Fatalf("create receipt not replayed: %d %s", replay.Code, replay.Body)
	}
	encodedReplay := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/local", map[string]any{"operationId": "create-source-1", "expectedRevision": library.Revision, "wireRoot": "/f%69lms"})
	if encodedReplay.Code != http.StatusCreated || encodedReplay.Body.String() != replay.Body.String() {
		t.Fatalf("equivalent normalized root did not replay: %d %s", encodedReplay.Code, encodedReplay.Body)
	}
	if strings.Contains(replay.Body.String(), newWorkspace) || strings.Contains(replay.Body.String(), localMediaWorkspaceConfiguration) {
		t.Fatalf("receipt exposed current workspace configuration: %s", replay.Body)
	}
	if foreign := f.call(t, f.viewer.ID, http.MethodPost, base+"/sources/local", map[string]any{"operationId": "create-source-1", "expectedRevision": library.Revision, "wireRoot": "/films"}); foreign.Code != http.StatusNotFound && foreign.Code != http.StatusForbidden {
		t.Fatalf("foreign actor recovered owner receipt: %d %s", foreign.Code, foreign.Body)
	}
	if hidden, _ := fixtureItems(t, f, f.owner.ID, library.ID, ""); len(hidden) != 0 {
		t.Fatalf("receipt restored source authority: %+v", hidden)
	}
	proofAfter, err := f.store.GetLibrarySourceByID(t.Context(), f.owner.ID, source.ID)
	if err != nil || proofAfter.ProofEpoch != proofBefore.ProofEpoch || proofAfter.Revision != proofBefore.Revision || proofAfter.Proof == nil || proofBefore.Proof == nil || !bytes.Equal(proofAfter.Proof.MappingDigest, proofBefore.Proof.MappingDigest) || !bytes.Equal(proofAfter.Proof.RootIdentity, proofBefore.Proof.RootIdentity) {
		t.Fatalf("receipt replaced proof: %+v %v", proofAfter, err)
	}
	var domainsAfter int
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		return tx.QueryRowContext(t.Context(), "SELECT count(*) FROM binding_domain WHERE binding_id=?", source.ID).Scan(&domainsAfter)
	}); err != nil || domainsAfter != domainsBefore {
		t.Fatalf("receipt changed domains: %d -> %d %v", domainsBefore, domainsAfter, err)
	}
	if r := f.call(t, f.owner.ID, http.MethodGet, playback.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("receipt restored old playback lease: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodPost, itemBase+"/playback-resolutions", resolutionBody); r.Code != http.StatusNotFound {
		t.Fatalf("receipt allowed new playback: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", map[string]any{"bindingId": source.ID, "operationId": "wrong-workspace", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision}); r.Code != http.StatusConflict {
		t.Fatalf("changed workspace scanned without confirmation: %d %s", r.Code, r.Body)
	}
	attest := map[string]any{"operationId": "explicit-rebind", "expectedRevision": source.Revision, "wireRoot": "/films"}
	response := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/"+source.ID+"/attest", attest)
	var rebound mediaLibrarySourceSummary
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &rebound) != nil || rebound.Revision <= source.Revision {
		t.Fatalf("explicit rebind: %d %s", response.Code, response.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/"+source.ID+"/attest", attest); replay.Code != http.StatusOK || replay.Body.String() != response.Body.String() {
		t.Fatalf("rebind replay: %d %s", replay.Code, replay.Body)
	}
	if hidden, _ := fixtureItems(t, f, f.owner.ID, library.ID, ""); len(hidden) != 0 {
		t.Fatalf("old proof epoch remained visible after rebind: %+v", hidden)
	}
	scanFixtureLibrary(t, f, library, rebound, "after-rebind")
	items, _ = fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 1 || items[0].Title != "New workspace title" {
		t.Fatalf("new workspace did not scan independently: %+v", items)
	}
	detail := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID)
	if _, exists := detail.Fields["tagline"]; exists {
		t.Fatalf("old manual metadata crossed rebind: %+v", detail.Fields)
	}
}

func TestMediaLibraryHTTPMetadataCollectionsAreBoundedAndRevisionPinned(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	if err := os.WriteFile(filepath.Join(f.root, "films", "many.mkv"), []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	var nfo strings.Builder
	nfo.WriteString("<movie><title>Many actors</title>")
	for i := range 25 {
		fmt.Fprintf(&nfo, "<actor><name>Actor %02d</name><role>Role</role></actor>", i)
	}
	nfo.WriteString("</movie>")
	if err := os.WriteFile(filepath.Join(f.root, "films", "many.nfo"), []byte(nfo.String()), 0o600); err != nil {
		t.Fatal(err)
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "many-actors")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("items: %+v", items)
	}
	detail := fixtureDetail(t, f, f.owner.ID, library.ID, items[0].ID)
	if len(detail.Credits) != 20 || detail.CreditsNextCursor == "" {
		t.Fatalf("detail credit window: count=%d cursor=%q", len(detail.Credits), detail.CreditsNextCursor)
	}
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + items[0].ID
	path := fmt.Sprintf("%s/credits?limit=100&cursor=%s&revision=%d", base, detail.CreditsNextCursor, detail.Item.Revision)
	response := f.call(t, f.owner.ID, http.MethodGet, path, nil)
	var page struct {
		Items []struct {
			Name string `json:"name"`
		} `json:"items"`
		NextCursor string `json:"nextCursor"`
		Revision   int64  `json:"revision"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 5 || page.NextCursor != "" || page.Revision != detail.Item.Revision {
		t.Fatalf("remaining credits: %d %s", response.Code, response.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodGet, base+"/credits?cursor="+detail.CreditsNextCursor, nil); r.Code != http.StatusBadRequest {
		t.Fatalf("unversioned continuation accepted: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodGet, fmt.Sprintf("%s/credits?cursor=%s&revision=%d", base, detail.CreditsNextCursor, detail.Item.Revision+1), nil); r.Code != http.StatusConflict {
		t.Fatalf("stale continuation accepted: %d %s", r.Code, r.Body)
	}
}

func TestMediaLibraryHTTPLegacyBindingRequiresExplicitAttestation(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	library, err := f.store.CreateLibrary(t.Context(), f.owner.ID, "Legacy fixture")
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := f.store.CreateLocalSource(t.Context(), f.owner.ID, library.ID, library.Revision, localMediaWorkspaceConfiguration, []byte("/films"))
	if err != nil {
		t.Fatal(err)
	}
	base := "/api/media-library/v1/libraries/" + library.ID
	list := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil)
	if list.Code != http.StatusOK || !strings.Contains(list.Body.String(), `"access":"unverified"`) || strings.Contains(list.Body.String(), "/films") {
		t.Fatalf("legacy source leaked or silently trusted: %d %s", list.Code, list.Body)
	}
	start := map[string]any{"bindingId": legacy.ID, "operationId": "legacy-scan", "expectedRevision": legacy.Revision, "rulesRevision": mediascan.LocalRulesRevision}
	if r := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", start); r.Code != http.StatusConflict {
		t.Fatalf("unverified legacy binding scanned: %d %s", r.Code, r.Body)
	}
	body := map[string]any{"operationId": "legacy-attest", "expectedRevision": legacy.Revision, "wireRoot": "/films"}
	attested := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/"+legacy.ID+"/attest", body)
	var source mediaLibrarySourceSummary
	if attested.Code != http.StatusOK || json.Unmarshal(attested.Body.Bytes(), &source) != nil || source.ID != legacy.ID || source.Revision <= legacy.Revision {
		t.Fatalf("legacy attest: %d %s", attested.Code, attested.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/"+legacy.ID+"/attest", body); replay.Code != http.StatusOK || replay.Body.String() != attested.Body.String() {
		t.Fatalf("legacy attest replay: %d %s", replay.Code, replay.Body)
	}
	if err := os.WriteFile(filepath.Join(f.root, "films", "legacy.mp4"), []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	job := scanFixtureLibrary(t, f, mediaLibrarySummary{ID: library.ID}, source, "after-legacy-attest")
	if job.Processed != 1 {
		t.Fatalf("attested legacy scan: %+v", job)
	}
}

func TestMediaLibraryHTTPAttestReceiptReplayAfterOwnerAccessRevoked(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID
	endpoint := base + "/sources/" + source.ID + "/attest"
	body := map[string]any{"operationId": "attest-lost-response", "expectedRevision": source.Revision, "wireRoot": "/films"}
	original := f.call(t, f.owner.ID, http.MethodPost, endpoint, body)
	var acknowledged mediaLibrarySourceSummary
	if original.Code != http.StatusOK || json.Unmarshal(original.Body.Bytes(), &acknowledged) != nil {
		t.Fatalf("attest: %d %s", original.Code, original.Body)
	}
	before, err := f.store.GetLibrarySourceByID(t.Context(), f.owner.ID, source.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Perm.Download = false
	if err := f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	replay := f.call(t, f.owner.ID, http.MethodPost, endpoint, body)
	if replay.Code != http.StatusOK || replay.Body.String() != original.Body.String() {
		t.Fatalf("attest receipt after access change: %d %s", replay.Code, replay.Body)
	}
	after, err := f.store.GetLibrarySourceByID(t.Context(), f.owner.ID, source.ID)
	if err != nil || after.ProofEpoch != before.ProofEpoch || after.Revision != before.Revision {
		t.Fatalf("replay resampled proof: %+v %v", after, err)
	}
	if changed := f.call(t, f.owner.ID, http.MethodPost, endpoint, map[string]any{"operationId": "attest-lost-response", "expectedRevision": source.Revision, "wireRoot": "/other"}); changed.Code != http.StatusConflict {
		t.Fatalf("different root replay: %d %s", changed.Code, changed.Body)
	}
	if foreign := f.call(t, f.viewer.ID, http.MethodPost, endpoint, body); foreign.Code != http.StatusNotFound && foreign.Code != http.StatusForbidden {
		t.Fatalf("foreign receipt: %d %s", foreign.Code, foreign.Body)
	}
	if list := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil); list.Code != http.StatusOK || !strings.Contains(list.Body.String(), `"access":"unavailable"`) {
		t.Fatalf("receipt granted current source access: %d %s", list.Code, list.Body)
	}
	if scan := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", map[string]any{"bindingId": source.ID, "operationId": "denied-after-receipt", "expectedRevision": acknowledged.Revision, "rulesRevision": mediascan.LocalRulesRevision}); scan.Code != http.StatusForbidden {
		t.Fatalf("receipt granted scan access: %d %s", scan.Code, scan.Body)
	}
}

func TestMediaLibraryHTTPV1ReplayDoesNotPresentV2ProofAsAvailable(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	library, err := f.store.CreateLibrary(t.Context(), f.owner.ID, "Receipt fixture")
	if err != nil {
		t.Fatal(err)
	}
	proof := medialibrary.BindingProof{Version: 2, MappingDigest: bytes.Repeat([]byte{1}, 32)}
	observation := medialibrary.DomainObservation{MappingDigest: bytes.Repeat([]byte{2}, 32), AnchorDigest: bytes.Repeat([]byte{3}, 32), EvidenceKind: "path_observed"}
	ack, err := f.store.CreateDomainAttestedLocalSourceWithCapture(t.Context(), f.owner.ID, library.ID, library.Revision, "v2-internal-receipt", localMediaWorkspaceConfiguration, []byte("/films"), func(context.Context) (medialibrary.BindingProof, medialibrary.DomainObservation, error) {
		return proof, observation, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	base := "/api/media-library/v1/libraries/" + library.ID
	replay := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/local", map[string]any{"operationId": "v2-internal-receipt", "expectedRevision": library.Revision, "wireRoot": "/films"})
	var summary mediaLibrarySourceSummary
	if replay.Code != http.StatusCreated || json.Unmarshal(replay.Body.Bytes(), &summary) != nil || summary.ID != ack.BindingID || summary.Access != "unverified" {
		t.Fatalf("v2 receipt was upgraded to v1 access: %d %s", replay.Code, replay.Body)
	}
	current, err := f.store.GetLibrarySourceByID(t.Context(), f.owner.ID, ack.BindingID)
	if err != nil || current.Proof == nil || current.Proof.Version != 2 || current.ProofEpoch != ack.ProofEpoch {
		t.Fatalf("v2 proof changed: %+v %v", current, err)
	}
	if scan := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", map[string]any{"bindingId": ack.BindingID, "operationId": "v1-reader-denied", "expectedRevision": ack.BindingRevision, "rulesRevision": mediascan.LocalRulesRevision}); scan.Code != http.StatusConflict {
		t.Fatalf("v1 scan accepted v2 proof: %d %s", scan.Code, scan.Body)
	}
}
