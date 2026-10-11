package fbhttp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/auth"
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
		if len(result) != 6 || result["authMethod"] != "json" || result["enableExec"] != enabled || result["minimumPasswordLength"] != float64(12) || result["conditionalTextSave"] != true || result["resourceWireOperations"] != true || result["searchWirePathV1"] != true {
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
