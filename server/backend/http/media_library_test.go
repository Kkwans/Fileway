package fbhttp

import (
	"net/http"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestMediaLibraryHTTPReadsArePrivateAndOptional(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"}, users.User{Username: "viewer"})
	owner := trashHTTPUserByName(t, h, "owner")
	viewer := trashHTTPUserByName(t, h, "viewer")
	url := "/api/media-library/v1/libraries"
	if r := h.request(t, owner.ID, mediaLibraryListHandler, http.MethodGet, url, nil, nil); r.Code != 503 {
		t.Fatalf("disabled: %d", r.Code)
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
