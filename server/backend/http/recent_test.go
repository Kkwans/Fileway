package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestRecentHTTPPreservesOpaquePathsAndDoesNotMergeDisplayNameSiblings(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"}, users.User{Username: "other"})
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	paths := []string{"/\xd6\xd0\xce\xc4", "/中文", "/100% +?#", "/a%2Fb"}
	expectedPaths := make([]string, 0, len(paths))
	for index, resourcePath := range paths {
		if index < 2 {
			if err := h.fs[owner.ID].MkdirAll(resourcePath, 0o755); err != nil {
				t.Fatal(err)
			}
		} else {
			file, err := h.fs[owner.ID].Create(resourcePath)
			if err != nil {
				t.Fatal(err)
			}
			if err := file.Close(); err != nil {
				t.Fatal(err)
			}
		}
		body, _ := json.Marshal(map[string]string{"wirePath": files.EncodeWirePath(resourcePath)})
		response := h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewReader(body), nil)
		if runtime.GOOS == "windows" && !utf8.ValidString(resourcePath) {
			if response.Code != http.StatusForbidden {
				t.Fatalf("Windows must reject lossy UTF-16 path %q: status=%d body=%s", resourcePath, response.Code, response.Body.String())
			}
			entries, err := h.storage.Recent.List(owner.ID, 100)
			if err != nil || len(entries) != len(expectedPaths) {
				t.Fatalf("rejected Windows path changed recent history: %#v %v", entries, err)
			}
			continue
		}
		if response.Code != http.StatusOK {
			t.Fatalf("record %q status = %d body=%s", resourcePath, response.Code, response.Body.String())
		}
		var row struct {
			Path     string `json:"path"`
			WirePath string `json:"wirePath"`
			IsDir    bool   `json:"isDir"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &row); err != nil {
			t.Fatal(err)
		}
		if row.Path != files.DisplayPath(resourcePath) || row.WirePath != files.EncodeWirePath(resourcePath) || row.IsDir != (index < 2) {
			t.Fatalf("path identity lost for %q: %#v", resourcePath, row)
		}
		expectedPaths = append(expectedPaths, resourcePath)
	}
	stored, err := h.storage.Recent.List(owner.ID, 100)
	if err != nil || len(stored) != len(expectedPaths) {
		t.Fatalf("stored entries = %#v, %v", stored, err)
	}
	for _, expected := range expectedPaths {
		found := false
		for _, row := range stored {
			if row.Path == expected {
				found = true
			}
		}
		if !found {
			t.Fatalf("raw bytes absent for %q", expected)
		}
	}
	response := h.request(t, owner.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	var rows []struct {
		WirePath   string `json:"wirePath"`
		AccessedAt int64  `json:"accessedAt"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &rows) != nil || len(rows) != len(expectedPaths) {
		t.Fatalf("list status = %d body=%s", response.Code, response.Body.String())
	}
	for index := 1; index < len(rows); index++ {
		if rows[index-1].AccessedAt < rows[index].AccessedAt {
			t.Fatal("list is not ordered by server access time")
		}
	}
	response = h.request(t, other.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	if response.Code != http.StatusOK || strings.TrimSpace(response.Body.String()) != "[]" {
		t.Fatalf("recent leaked across accounts: %s", response.Body.String())
	}
}

func TestRecentHTTPRejectsConflictingAndMalformedWirePathsBeforeRecording(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := firstTrashHTTPUser(h)
	if err := h.fs[owner.ID].MkdirAll("/existing", 0o755); err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{
		`{"path":"/other","wirePath":"/existing"}`,
		`{"wirePath":"/bad%"}`, `{"wirePath":"/a%2Fb"}`, `{"wirePath":"/bad%00"}`,
		`{"wirePath":"//host/existing"}`, `{"wirePath":"/existing?query"}`,
	} {
		response := h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", strings.NewReader(body), nil)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("body %s status = %d", body, response.Code)
		}
	}
	entries, err := h.storage.Recent.List(owner.ID, 100)
	if err != nil || len(entries) != 0 {
		t.Fatalf("invalid requests recorded entries: %#v %v", entries, err)
	}
	// Supplying both matching fields remains compatible with Web and old clients.
	response := h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", strings.NewReader(`{"path":"/existing","wirePath":"/existing"}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("matching fields status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestRecentHTTPRecordsExistingPathsAndStaysPrivate(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true}},
		users.User{Username: "member"},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	if err := h.fs[admin.ID].MkdirAll("/docs", 0o755); err != nil {
		t.Fatal(err)
	}
	if err := h.fs[member.ID].MkdirAll("/private", 0o755); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, admin.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/docs"}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("record recent status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, member.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/private"}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("member recent status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, admin.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("list recent status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "/private") || strings.Contains(response.Body.String(), "userId") {
		t.Fatalf("private recent leaked: %s", response.Body.String())
	}
	var entries []*recent.Entry
	if err := json.Unmarshal(response.Body.Bytes(), &entries); err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Path != "/docs" || !entries[0].IsDir {
		t.Fatalf("admin recent = %#v", entries)
	}

	response = h.request(t, admin.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/missing"}`), nil)
	if response.Code != http.StatusNotFound {
		t.Fatalf("missing recent status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, admin.ID, recentListHandler, http.MethodGet, "/recent?limit=invalid", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid limit status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestRecentHTTPUsesRoleAwareRootLabel(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true}},
		users.User{Username: "member"},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")

	for _, userID := range []uint{admin.ID, member.ID} {
		response := h.request(t, userID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/"}`), nil)
		if response.Code != http.StatusOK {
			t.Fatalf("record root status = %d body=%s", response.Code, response.Body.String())
		}
	}

	response := h.request(t, admin.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("admin root list status = %d body=%s", response.Code, response.Body.String())
	}
	var adminEntries []*recent.Entry
	if err := json.Unmarshal(response.Body.Bytes(), &adminEntries); err != nil {
		t.Fatal(err)
	}
	if len(adminEntries) != 1 || adminEntries[0].Name != "根目录" {
		t.Fatalf("admin root entry = %#v", adminEntries)
	}

	response = h.request(t, member.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("member root list status = %d body=%s", response.Code, response.Body.String())
	}
	var memberEntries []*recent.Entry
	if err := json.Unmarshal(response.Body.Bytes(), &memberEntries); err != nil {
		t.Fatal(err)
	}
	if len(memberEntries) != 1 || memberEntries[0].Name != "我的文件" {
		t.Fatalf("member root entry = %#v", memberEntries)
	}
}

func TestRecentPathsFollowRenameAndDisappearInTrash(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
		Scope:    filepath.Join(t.TempDir(), "owner"),
		Perm: users.Permissions{
			Create: true, Delete: true, Modify: true, Rename: true, Download: true,
		},
	})
	owner := firstTrashHTTPUser(h)
	if err := h.fs[owner.ID].MkdirAll("/docs", 0o755); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/docs"}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("record status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/docs?action=rename&destination=%2Farchive", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("rename status = %d body=%s", response.Code, response.Body.String())
	}
	entries, err := h.storage.Recent.List(owner.ID, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Path != "/archive" || entries[0].Name != "archive" {
		t.Fatalf("recent after rename = %#v", entries)
	}
	response = h.request(t, owner.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, "/archive?mode=trash", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("trash status = %d body=%s", response.Code, response.Body.String())
	}
	entries, err = h.storage.Recent.List(owner.ID, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatalf("recent after trash = %#v", entries)
	}
}

func TestRecentSelectedAndClearDeleteStayPrivate(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"}, users.User{Username: "other"})
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	first, err := h.storage.Recent.Record(owner.ID, "/one", "one", false)
	if err != nil {
		t.Fatal(err)
	}
	second, err := h.storage.Recent.Record(owner.ID, "/two", "two", false)
	if err != nil {
		t.Fatal(err)
	}
	foreign, err := h.storage.Recent.Record(other.ID, "/private", "private", false)
	if err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, recentDeleteHandler, http.MethodPost, "/recent/delete", bytes.NewBufferString(`{"ids":[]}`), nil)
	if response.Code != http.StatusBadRequest {
		t.Fatal(response.Code, response.Body.String())
	}
	body, _ := json.Marshal(map[string]interface{}{"ids": []string{first.ID, first.ID, foreign.ID}})
	response = h.request(t, owner.ID, recentDeleteHandler, http.MethodPost, "/recent/delete", bytes.NewReader(body), nil)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"deleted":1`) {
		t.Fatal(response.Code, response.Body.String())
	}
	rows, err := h.storage.Recent.List(owner.ID, 100)
	if err != nil || len(rows) != 1 || rows[0].ID != second.ID {
		t.Fatal(rows, err)
	}
	response = h.request(t, owner.ID, recentClearHandler, http.MethodDelete, "/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatal(response.Code, response.Body.String())
	}
	rows, err = h.storage.Recent.List(other.ID, 100)
	if err != nil || len(rows) != 1 || rows[0].ID != foreign.ID {
		t.Fatal(rows, err)
	}
}
