//go:build linux

package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func subtitleHTTPFixture(t *testing.T) (*trashHTTPHarness, *subtitlePages, string, uint, uint) {
	t.Helper()
	if !files.SubtitleScopeAvailable() {
		t.Skip("kernel does not provide safe subtitle opens")
	}
	h := newTrashHTTPHarness(t,
		users.User{Username: "first", Scope: "/first", Perm: users.Permissions{Download: true}},
		users.User{Username: "second", Scope: "/second", Perm: users.Permissions{Download: true}})
	h.server.Root = t.TempDir()
	var first, second uint
	for id, user := range h.users {
		native := filepath.Join(h.server.Root, user.Scope)
		if err := os.Mkdir(native, 0o700); err != nil {
			t.Fatal(err)
		}
		h.fs[id] = afero.NewBasePathFs(afero.NewOsFs(), native)
		if user.Username == "first" {
			first = id
		} else {
			second = id
		}
	}
	service := newSubtitlePages()
	t.Cleanup(service.close)
	return h, service, filepath.Join(h.server.Root, "first"), first, second
}

func requestSubtitlePage(t *testing.T, h *trashHTTPHarness, service *subtitlePages, id uint, input subtitlePageRequest) *httptest.ResponseRecorder {
	t.Helper()
	body, err := json.Marshal(input)
	if err != nil {
		t.Fatal(err)
	}
	return h.request(t, id, service.pageHandler(), http.MethodPost, subtitlePagesRoute, bytes.NewReader(body), nil)
}

func decodeSubtitlePage(t *testing.T, response *httptest.ResponseRecorder) subtitlePage {
	t.Helper()
	var page subtitlePage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || page.Items == nil || page.Version != 1 {
		t.Fatalf("page status=%d body=%s", response.Code, response.Body.String())
	}
	return page
}

func TestSubtitlePagesTraverseBeyondLegacyLimitAndEmptyWindows(t *testing.T) {
	h, service, root, user, other := subtitleHTTPFixture(t)
	for _, sub := range []string{"large", "sparse"} {
		if err := os.Mkdir(filepath.Join(root, sub), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	for i := range 4103 {
		if err := os.WriteFile(filepath.Join(root, "large", fmt.Sprintf("sub-%d.srt", i)), []byte("sub"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for i := range 1100 {
		if err := os.WriteFile(filepath.Join(root, "sparse", fmt.Sprintf("ignore-%d.mkv", i)), nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	request := subtitlePageRequest{Action: "page", WirePath: "/large", BrowserNonce: "large-directory-test"}
	seen := make(map[string]bool)
	for n := 0; ; n++ {
		response := requestSubtitlePage(t, h, service, user, request)
		page := decodeSubtitlePage(t, response)
		if page.WirePath != request.WirePath || len(page.Items) > 32 || response.Body.Len() > subtitlePageBytes || n > 200 {
			t.Fatalf("invalid page %d: %#v", n, page)
		}
		if n == 0 {
			retry := requestSubtitlePage(t, h, service, user, request)
			if !bytes.Equal(retry.Body.Bytes(), response.Body.Bytes()) || retry.Code != response.Code || page.NextCursor == nil {
				t.Fatal("first-page retry lost its original response")
			}
			stolen := request
			stolen.Cursor = *page.NextCursor
			if result := requestSubtitlePage(t, h, service, other, stolen); result.Code != http.StatusConflict {
				t.Fatalf("cross-account cursor status=%d", result.Code)
			}
			stolen.BrowserNonce = "different-browser-owner"
			if result := requestSubtitlePage(t, h, service, user, stolen); result.Code != http.StatusConflict {
				t.Fatalf("cross-browser cursor status=%d", result.Code)
			}
		}
		for _, row := range page.Items {
			if seen[row.WirePath] || row.IsDir || row.Version == "" {
				t.Fatalf("duplicate or invalid row: %#v", row)
			}
			seen[row.WirePath] = true
		}
		if page.NextCursor == nil {
			break
		}
		request.Cursor = *page.NextCursor
	}
	if len(seen) != 4103 {
		t.Fatalf("saw %d results", len(seen))
	}
	closeRequest := subtitlePageRequest{Action: "close", WirePath: request.WirePath, BrowserNonce: request.BrowserNonce}
	if closed := requestSubtitlePage(t, h, service, user, closeRequest); closed.Code != http.StatusOK {
		t.Fatalf("close status=%d", closed.Code)
	}
	if expired := requestSubtitlePage(t, h, service, user, request); expired.Code != http.StatusConflict {
		t.Fatalf("closed cursor status=%d", expired.Code)
	}
	sparse := subtitlePageRequest{Action: "page", WirePath: "/sparse", BrowserNonce: "sparse-directory-test"}
	page := decodeSubtitlePage(t, requestSubtitlePage(t, h, service, user, sparse))
	if len(page.Items) != 0 || page.NextCursor == nil {
		t.Fatalf("empty scan window dropped its continuation: %#v", page)
	}
	for n := 0; page.NextCursor != nil && n < 20; n++ {
		sparse.Cursor = *page.NextCursor
		page = decodeSubtitlePage(t, requestSubtitlePage(t, h, service, user, sparse))
	}
	if page.NextCursor != nil || len(page.Items) != 0 {
		t.Fatalf("sparse directory did not terminate: %#v", page)
	}
}

func TestSubtitlePagesPermissionChangeAndVersionedRange(t *testing.T) {
	h, service, root, user, other := subtitleHTTPFixture(t)
	name := "legacy-\xff%+ #?.srt"
	native := filepath.Join(root, name)
	if err := os.WriteFile(native, []byte("abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	otherRoot := filepath.Join(h.server.Root, "second")
	if err := os.Mkdir(filepath.Join(otherRoot, "nested"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(otherRoot, "nested", "protected.srt"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(otherRoot, filepath.Join(root, "outside")); err != nil {
		t.Fatal(err)
	}
	request := subtitlePageRequest{Action: "page", WirePath: "/", BrowserNonce: "range-directory-test"}
	page := decodeSubtitlePage(t, requestSubtitlePage(t, h, service, user, request))
	wire := files.EncodeWirePath("/" + name)
	if len(page.Items) != 1 || page.Items[0].WirePath != wire {
		t.Fatalf("opaque name or link filter was lost: %#v", page.Items)
	}
	verify := subtitlePageRequest{Action: "verify", WirePath: wire, Version: page.Items[0].Version}
	if result := requestSubtitlePage(t, h, service, user, verify); result.Code != http.StatusOK {
		t.Fatalf("verify status=%d body=%s", result.Code, result.Body.String())
	}
	linked := subtitlePageRequest{Action: "page", WirePath: "/outside/nested", BrowserNonce: "blocked-directory-test"}
	if result := requestSubtitlePage(t, h, service, user, linked); result.Code != http.StatusForbidden {
		t.Fatalf("intermediate symlink listing status=%d", result.Code)
	}
	if result := requestSubtitlePage(t, h, service, other, verify); result.Code != http.StatusNotFound {
		t.Fatalf("foreign resource version status=%d", result.Code)
	}
	read := func() *httptest.ResponseRecorder {
		request := httptest.NewRequest(http.MethodGet, "/api"+subtitleFileRoute+wire+"?version="+verify.Version, nil)
		request.Header.Set("X-Auth", signedTrashHTTPToken(t, user))
		request.Header.Set("Range", "bytes=1-3")
		response := httptest.NewRecorder()
		handle(service.fileHandler(), "/api"+subtitleFileRoute, h.storage, h.server).ServeHTTP(response, request)
		return response
	}
	if result := read(); result.Code != http.StatusPartialContent || result.Header().Get("Content-Range") != "bytes 1-3/6" || result.Body.String() != "bcd" {
		t.Fatalf("range status=%d headers=%v body=%q", result.Code, result.Header(), result.Body.String())
	}
	if err := os.Rename(native, native+".old"); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(native, []byte("123456"), 0o600); err != nil {
		t.Fatal(err)
	}
	old, err := os.Stat(native + ".old")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(native, old.ModTime(), old.ModTime()); err != nil {
		t.Fatal(err)
	}
	if result := read(); result.Code != http.StatusConflict {
		t.Fatalf("replaced file version status=%d body=%q", result.Code, result.Body.String())
	}
	if result := requestSubtitlePage(t, h, service, user, request); result.Code != http.StatusConflict {
		t.Fatalf("changed directory retry status=%d", result.Code)
	}
	request.BrowserNonce = "changed-policy-browser"
	page = decodeSubtitlePage(t, requestSubtitlePage(t, h, service, user, request))
	verify.Version = page.Items[0].Version
	account, err := h.storage.Users.Get(h.server.Root, user)
	if err != nil {
		t.Fatal(err)
	}
	account.Perm.Download = false
	account.Rules = []rules.Rule{{Regex: true, Regexp: &rules.Regexp{Raw: `(?i)\.srt$`}, Allow: false}}
	if err := h.storage.Users.Update(account, "Perm", "Rules"); err != nil {
		t.Fatal(err)
	}
	if result := read(); result.Code != http.StatusForbidden {
		t.Fatalf("revoked download status=%d", result.Code)
	}
	if result := requestSubtitlePage(t, h, service, user, request); result.Code != http.StatusConflict {
		t.Fatalf("changed policy status=%d", result.Code)
	}
	request.BrowserNonce = "after-rules-change-test"
	if page := decodeSubtitlePage(t, requestSubtitlePage(t, h, service, user, request)); len(page.Items) != 0 {
		t.Fatalf("denied rows remained visible: %#v", page.Items)
	}
}

func TestSubtitlePagesSerializesCursorAndClosesExpiredDescriptors(t *testing.T) {
	h, service, _, user, _ := subtitleHTTPFixture(t)
	key := subtitleScanKey{user: user, nonce: "serialized-cursor-test", path: "/"}
	ctx, scan, err := service.acquire(context.Background(), key, "")
	if err != nil {
		t.Fatal(err)
	}
	scan.directory, err = files.OpenSubtitleResource(h.fs[user], h.server.Root, "/", true, true)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := service.acquire(context.Background(), key, ""); !errors.Is(err, errSubtitleBusy) {
		t.Fatalf("duplicate in-flight page: %v", err)
	}
	service.mu.Lock()
	service.closeScan(key)
	service.mu.Unlock()
	if ctx.Err() != context.Canceled {
		t.Fatalf("closing did not cancel the active read: %v", ctx.Err())
	}
	service.release(key, scan, true)
	if _, _, err := scan.directory.State(); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("closing did not release the directory: %v", err)
	}
	_, scan, err = service.acquire(context.Background(), key, "")
	if err != nil {
		t.Fatal(err)
	}
	scan.directory, err = files.OpenSubtitleResource(h.fs[user], h.server.Root, "/", true, true)
	if err != nil {
		t.Fatal(err)
	}
	service.release(key, scan, true)
	service.mu.Lock()
	scan.expires = time.Now().Add(-time.Second)
	service.mu.Unlock()
	if _, _, err := service.acquire(context.Background(), key, "previous-cursor"); !errors.Is(err, errSubtitlePageChanged) {
		t.Fatalf("expired cursor status: %v", err)
	}
	if _, _, err := scan.directory.State(); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("expired directory was not closed: %v", err)
	}
}
