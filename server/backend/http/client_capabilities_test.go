package fbhttp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/auth"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestClientCapabilitiesAuthenticatedMemberSeesOnlyNonSecretPolicy(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "member"})
	member := firstTrashHTTPUser(h)
	policy, err := h.storage.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	policy.AuthMethod = auth.MethodJSONAuth
	policy.MinimumPasswordLength = 12
	policy.Shell = []string{"private-shell-path"}
	policy.Commands = map[string][]string{"after_save": {"private-command"}}
	policy.UserHomeBasePath = "/private-users"
	if err := h.storage.Settings.Save(policy); err != nil {
		t.Fatal(err)
	}
	for _, enabled := range []bool{false, true} {
		h.server.EnableExec = enabled
		response := h.request(t, member.ID, clientCapabilitiesHandler, http.MethodGet, "/client-capabilities", nil, nil)
		var result map[string]interface{}
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil {
			t.Fatalf("capabilities status=%d body=%s", response.Code, response.Body.String())
		}
		if len(result) != 13 || result["authMethod"] != "json" || result["enableExec"] != enabled || result["minimumPasswordLength"] != float64(12) || result["conditionalTextSave"] != true || result["conditionalDownloadV1"] != true || result["resourceWireOperations"] != true || result["searchWirePathV1"] != true || result["mediaPlaybackVersion"] != float64(1) || result["sdrCompatibility"] != true || result["sourceSubtitles"] != true || result["subtitleAttachments"] != true || result["sourcePGSStreaming"] != float64(1) || result["sourceASSStreaming"] != float64(1) {
			t.Fatalf("unexpected capability shape: %#v", result)
		}
		for _, secret := range []string{"private-shell-path", "private-command", "/private-users", "password", "key", "scope", "rules"} {
			if strings.Contains(response.Body.String(), secret) {
				t.Fatalf("capabilities leaked %s", secret)
			}
		}
	}
}

func TestClientCapabilitiesRejectsUnauthenticatedRequest(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "member"})
	request := httptest.NewRequest(http.MethodGet, "/client-capabilities", nil)
	response := httptest.NewRecorder()
	handle(clientCapabilitiesHandler, "", h.storage, h.server).ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized || strings.Contains(response.Body.String(), "enableExec") {
		t.Fatalf("unauthenticated capabilities status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestClientCapabilitiesMediaLibraryVersionTracksReadableStore(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "member"})
	member := firstTrashHTTPUser(h)
	check := func(wantAvailable bool) {
		t.Helper()
		response := h.request(t, member.ID, clientCapabilitiesHandler, http.MethodGet, "/api/client-capabilities", nil, nil)
		var result map[string]any
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil {
			t.Fatalf("capabilities status=%d body=%s", response.Code, response.Body.String())
		}
		version, present := result["mediaLibraryVersion"]
		if present != wantAvailable || present && version != float64(1) {
			t.Fatalf("mediaLibraryVersion=%v present=%v, want available=%v", version, present, wantAvailable)
		}
		watch, present := result["catalogWatchProgressV1"]
		if present != wantAvailable || present && watch != true {
			t.Fatalf("catalogWatchProgressV1=%v present=%v, want available=%v", watch, present, wantAvailable)
		}
		if result["resourceWireOperations"] != true || result["mediaPlaybackVersion"] != float64(1) {
			t.Fatalf("existing capabilities changed: %#v", result)
		}
	}
	check(false)
	catalog, err := medialibrary.Open(t.Context(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = catalog.Close() })
	h.storage.MediaLibrary = catalog
	// An empty catalog is supported, even when sharing is disabled.
	h.server.DisableMediaLibrarySharing = true
	check(true)
	if err := catalog.Close(); err != nil {
		t.Fatal(err)
	}
	check(false)
}
