package fbhttp

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/tvupdate"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/gorilla/mux"
)

func TestTVUpdateAuthenticatedRoutesAndCapability(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := firstTrashHTTPUser(h)
	for _, test := range []struct {
		handler     handleFunc
		wantVersion bool
	}{
		{clientCapabilitiesHandler, false},
		{clientCapabilities(1), true},
	} {
		response := h.request(t, owner.ID, test.handler, http.MethodGet, "/api/client-capabilities", nil, nil)
		var payload map[string]any
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &payload) != nil {
			t.Fatalf("capabilities: %d %s", response.Code, response.Body)
		}
		if value, exists := payload["tvUpdateProxyVersion"]; exists != test.wantVersion || (exists && value != float64(1)) {
			t.Fatalf("capability tvUpdateProxyVersion=%v present=%v", value, exists)
		}
	}
	check := handle(tvUpdateCheckHandler(nil), "", h.storage, h.server)
	anonymous := httptest.NewRecorder()
	check.ServeHTTP(anonymous, httptest.NewRequest(http.MethodGet, "/api/app-updates/tv?versionCode=1&api=28&abi=x86", nil))
	if anonymous.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous check returned %d", anonymous.Code)
	}
	for _, query := range []string{
		"versionCode=1&api=28&abi=x86&url=https://example.invalid/evil.apk",
		"versionCode=1&versionCode=2&api=28&abi=x86",
		"versionCode=not-a-number&api=28&abi=x86",
	} {
		response := h.request(t, owner.ID, tvUpdateCheckHandler(nil), http.MethodGet, "/api/app-updates/tv?"+query, nil, nil)
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), `"code":"INVALID_REQUEST"`) {
			t.Fatalf("invalid check request: %d %s", response.Code, response.Body)
		}
	}
	// Match the production mux's nested repetition pattern and exact route.
	const goodID = "0123456789abcdefghijklmnopqrstuv"
	router := mux.NewRouter()
	router.HandleFunc(tvUpdateDownloadsPath+"/"+tvUpdateHandle+"/content", func(w http.ResponseWriter, r *http.Request) {
		if mux.Vars(r)["id"] != goodID {
			t.Fatalf("unexpected captured ID: %q", mux.Vars(r)["id"])
		}
		w.WriteHeader(http.StatusNoContent)
	}).Methods("GET", "HEAD")
	for id, want := range map[string]int{goodID: 204, "short": 404, goodID + "!": 404} {
		response := httptest.NewRecorder()
		router.ServeHTTP(response, httptest.NewRequest(http.MethodGet, tvUpdateDownloadsPath+"/"+id+"/content", nil))
		if response.Code != want {
			t.Fatalf("id %q: got %d, want %d", id, response.Code, want)
		}
	}
}

func TestTVUpdateProxyCapabilityWithWholeFilesystemRoots(t *testing.T) {
	for _, root := range []string{"/", files.VirtualComputerRoot} {
		t.Run(root, func(t *testing.T) {
			h := newTrashHTTPHarness(t, users.User{Username: "owner"})
			h.server.Root = root
			owner := firstTrashHTTPUser(h)
			updates, err := tvupdate.New()
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(updates.Close)
			hlsService := newHTTPHLSService(t, false)
			router, err := NewHandler(nil, noopTrashFileCache{}, nil, h.storage, h.server, fstest.MapFS{}, 1, hlsService, updates)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = router.(io.Closer).Close() })
			request := httptest.NewRequest(http.MethodGet, "/api/client-capabilities", nil)
			request.Header.Set("X-Auth", signedTrashHTTPToken(t, owner.ID))
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"tvUpdateProxyVersion":1`) {
				t.Fatalf("whole-filesystem root %q disabled TV updates: %d %s", root, response.Code, response.Body)
			}
		})
	}
}
