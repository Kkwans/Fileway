package fbhttp

import (
	"encoding/json"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestMediaLibraryHTTPContentKind(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := trashHTTPUserByName(t, h, "owner")
	catalog, err := medialibrary.Open(t.Context(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = catalog.Close() })
	h.storage.MediaLibrary = catalog
	url := "/api/media-library/v1/libraries"
	created := h.request(t, owner.ID, mediaLibraryCreateHandler, http.MethodPost, url, strings.NewReader(`{"operationId":"typed-create","name":"Typed","contentKind":"movie"}`), nil)
	if created.Code != http.StatusCreated || !strings.Contains(created.Body.String(), `"contentKind":"movie"`) {
		t.Fatalf("create: %d %s", created.Code, created.Body)
	}
	var summary mediaLibrarySummary
	if err := json.Unmarshal(created.Body.Bytes(), &summary); err != nil {
		t.Fatal(err)
	}
	changed := h.request(t, owner.ID, mediaLibraryChangeHandler("update"), http.MethodPost, url+"/"+summary.ID, strings.NewReader(`{"operationId":"typed-update","expectedRevision":`+strconv.FormatInt(summary.Revision, 10)+`,"contentKind":"series"}`), map[string]string{"id": summary.ID})
	if changed.Code != http.StatusOK || !strings.Contains(changed.Body.String(), `"contentKind":"series"`) {
		t.Fatalf("update: %d %s", changed.Code, changed.Body)
	}
	invalid := h.request(t, owner.ID, mediaLibraryChangeHandler("update"), http.MethodPost, url+"/"+summary.ID, strings.NewReader(`{"operationId":"typed-invalid","expectedRevision":`+strconv.FormatInt(summary.Revision+1, 10)+`,"contentKind":"episode"}`), map[string]string{"id": summary.ID})
	if invalid.Code != http.StatusBadRequest {
		t.Fatalf("invalid content kind: %d %s", invalid.Code, invalid.Body)
	}
}

func TestMediaLibraryHTTPReadsArePrivateAndOptional(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"}, users.User{Username: "viewer"})
	owner := trashHTTPUserByName(t, h, "owner")
	viewer := trashHTTPUserByName(t, h, "viewer")
	url := "/api/media-library/v1/libraries"
	if r := h.request(t, owner.ID, mediaLibraryListHandler, http.MethodGet, url, nil, nil); r.Code != 503 {
		t.Fatalf("disabled: %d", r.Code)
	}
	if r := h.request(t, owner.ID, mediaScanSupportHandler(nil), http.MethodGet, "/api/media-library/v1/scan-support", nil, nil); r.Code != 200 || !strings.Contains(r.Body.String(), `"reason":"not_enabled"`) {
		t.Fatalf("disabled scan support: %d %s", r.Code, r.Body)
	}
	catalog, err := medialibrary.Open(t.Context(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = catalog.Close() })
	h.storage.MediaLibrary = catalog
	lib, err := catalog.CreateLibrary(t.Context(), owner.ID, "Private cinema")
	if err != nil {
		t.Fatal(err)
	}
	get := func(actor uint) int {
		return h.request(t, actor, mediaLibraryGetHandler, http.MethodGet, url+"/"+lib.ID, nil, map[string]string{"id": lib.ID}).Code
	}
	if get(viewer.ID) != 404 || get(owner.ID) != 200 {
		t.Fatal("private library leaked or owner denied")
	}
	if r := h.request(t, viewer.ID, mediaLibraryListHandler, http.MethodGet, url, nil, nil); r.Code != 200 || strings.Contains(r.Body.String(), lib.Name) {
		t.Fatalf("list leak: %s", r.Body.String())
	}
	grant, err := catalog.GrantMember(t.Context(), owner.ID, lib.ID, lib.Revision, viewer.ID, medialibrary.RoleViewer)
	if err != nil {
		t.Fatal(err)
	}
	if get(viewer.ID) != 200 {
		t.Fatal("shared read denied")
	}
	if _, err := catalog.ArchiveLibrary(t.Context(), owner.ID, lib.ID, grant.LibraryRevision); err != nil {
		t.Fatal(err)
	}
	if get(viewer.ID) != 404 || get(owner.ID) != 200 {
		t.Fatal("archive visibility")
	}
	for _, query := range []string{"?limit=0", "?limit=101", "?archived=yes"} {
		if r := h.request(t, owner.ID, mediaLibraryListHandler, http.MethodGet, url+query, nil, nil); r.Code != 400 {
			t.Fatalf("invalid query %s: %d", query, r.Code)
		}
	}
	r := h.request(t, owner.ID, mediaLibraryListHandler, http.MethodGet, url+"?archived=true", nil, nil)
	if r.Header().Get("Cache-Control") != "no-store" || !strings.Contains(r.Body.String(), lib.Name) {
		t.Fatal("archive or private cache response")
	}
}
