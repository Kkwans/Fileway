//go:build windows

package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestWindowsSubtitlePagesScopeAndRange(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: "/owned", Perm: users.Permissions{Download: true}})
	user := firstTrashHTTPUser(h)
	h.server.Root = t.TempDir()
	owned := filepath.Join(h.server.Root, "owned")
	outside := filepath.Join(h.server.Root, "outside")
	for _, directory := range []string{owned, filepath.Join(outside, "nested")} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	h.fs[user.ID] = afero.NewBasePathFs(afero.NewOsFs(), owned)
	name := "字幕 %+.srt"
	if err := os.WriteFile(filepath.Join(owned, name), []byte("abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(outside, "nested", "private.srt"), []byte("private"), 0o600); err != nil {
		t.Fatal(err)
	}
	junction := filepath.Join(owned, "jump")
	if output, err := exec.Command("cmd.exe", "/C", "mklink", "/J", junction, outside).CombinedOutput(); err != nil {
		t.Fatalf("create owned junction fixture: %s: %v", output, err)
	}
	defer os.Remove(junction)
	service := newSubtitlePages()
	t.Cleanup(service.close)
	request := func(path string) *httptest.ResponseRecorder {
		body, err := json.Marshal(subtitlePageRequest{Action: "page", WirePath: path, BrowserNonce: "windows-subtitle-browser"})
		if err != nil {
			t.Fatal(err)
		}
		return h.request(t, user.ID, service.pageHandler(), http.MethodPost, subtitlePagesRoute, bytes.NewReader(body), nil)
	}
	response := request("/")
	var page subtitlePage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 {
		t.Fatalf("scoped listing status=%d body=%s", response.Code, response.Body.String())
	}
	row := page.Items[0]
	wire := files.EncodeWirePath("/" + name)
	if row.WirePath != wire || row.Version == "" || row.IsDir {
		t.Fatalf("unexpected subtitle row: %#v", row)
	}
	if denied := request("/jump/nested"); denied.Code != http.StatusForbidden {
		t.Fatalf("junction traversal status=%d body=%s", denied.Code, denied.Body.String())
	}
	resource := httptest.NewRequest(http.MethodGet, "/api"+subtitleFileRoute+wire+"?version="+row.Version, nil)
	resource.Header.Set("X-Auth", signedTrashHTTPToken(t, user.ID))
	resource.Header.Set("Range", "bytes=1-3")
	result := httptest.NewRecorder()
	handle(service.fileHandler(), "/api"+subtitleFileRoute, h.storage, h.server).ServeHTTP(result, resource)
	if result.Code != http.StatusPartialContent || result.Header().Get("Content-Range") != "bytes 1-3/6" || result.Body.String() != "bcd" {
		t.Fatalf("range status=%d headers=%v body=%q", result.Code, result.Header(), result.Body.String())
	}
}
