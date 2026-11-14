package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

type rejectProviderNetwork struct{ calls atomic.Int64 }

func (r *rejectProviderNetwork) RoundTrip(*http.Request) (*http.Response, error) {
	r.calls.Add(1)
	return nil, errors.New("unexpected provider request")
}

type revokeProviderAdminAfterWrite struct {
	users.Store
	store        *medialibrary.Store
	actor        uint
	connectionID string
}

func (s *revokeProviderAdminAfterWrite) Get(root string, id interface{}) (*users.User, error) {
	user, err := s.Store.Get(root, id)
	if err != nil {
		return nil, err
	}
	connection, err := s.store.GetManagedProviderConnection(context.Background(), s.actor, true, s.connectionID)
	if err == nil && connection.Revision > 1 {
		copy := *user
		copy.Perm.Admin = false
		return &copy, nil
	}
	return user, nil
}

func TestMediaProviderConfigPostCommitAdminRevocationIsUnknown(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Perm.Admin = true
	if err = f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{42}, 32))
	if err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	base := "/api/media-library/v1/provider-connections"
	created := f.call(t, f.owner.ID, http.MethodPost, base, map[string]any{"operationId": "instance-for-revoke", "ownerKind": "instance", "token": "fixture-token"})
	var instance medialibrary.ProviderConnection
	if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &instance) != nil {
		t.Fatalf("create: %d %s", created.Code, created.Body)
	}
	original := f.h.storage.Users
	f.h.storage.Users = &revokeProviderAdminAfterWrite{Store: original, store: f.store, actor: f.owner.ID, connectionID: instance.ID}
	path := base + "/" + instance.ID + "/grants"
	body := map[string]any{"operationId": "grant-after-revoke", "expectedRevision": 1, "userId": f.viewer.ID}
	response := f.call(t, f.owner.ID, http.MethodPut, path, body)
	f.h.storage.Users = original
	if response.Code != http.StatusServiceUnavailable || !strings.Contains(response.Body.String(), `"code":"write_outcome_unknown"`) || strings.Contains(response.Body.String(), instance.ID) {
		t.Fatalf("post-commit authority: %d %s", response.Code, response.Body)
	}
	replay := f.call(t, f.owner.ID, http.MethodPut, path, body)
	if replay.Code != http.StatusOK || !strings.Contains(replay.Body.String(), `"connectionRevision":2`) {
		t.Fatalf("idempotent replay: %d %s", replay.Code, replay.Body)
	}
}

func TestMediaProviderConfigHTTPNoSecretOrImplicitNetwork(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Perm.Admin = true
	if err = f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	network := &rejectProviderNetwork{}
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: network}
	base := "/api/media-library/v1/provider-connections"
	status := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/provider-status", nil)
	if status.Code != http.StatusOK || !strings.Contains(status.Body.String(), `"credentialStoreAvailable":false`) || !strings.Contains(status.Body.String(), `"networkJobsAvailable":false`) {
		t.Fatalf("missing vault status: %d %s", status.Code, status.Body)
	}
	body := map[string]any{"operationId": "personal-provider", "ownerKind": "user", "token": "fixture-token-one"}
	if response := f.call(t, f.owner.ID, http.MethodPost, base, body); response.Code != http.StatusServiceUnavailable || strings.Contains(response.Body.String(), "fixture-token-one") {
		t.Fatalf("missing vault: %d %s", response.Code, response.Body)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{42}, 32))
	if err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	created := f.call(t, f.owner.ID, http.MethodPost, base, body)
	var personal medialibrary.ProviderConnection
	if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &personal) != nil || personal.OwnerKind != "user" || personal.Revision != 1 {
		t.Fatalf("personal create: %d %s", created.Code, created.Body)
	}
	for _, forbidden := range []string{"fixture-token-one", "ciphertext", "fingerprint", "credentialRef"} {
		if strings.Contains(created.Body.String(), forbidden) {
			t.Fatalf("response leaked %s", forbidden)
		}
	}
	replay := f.call(t, f.owner.ID, http.MethodPost, base, body)
	if replay.Code != http.StatusCreated || replay.Body.String() != created.Body.String() {
		t.Fatalf("replay: %d %s", replay.Code, replay.Body)
	}
	changed := f.call(t, f.owner.ID, http.MethodPost, base, map[string]any{"operationId": "personal-provider", "ownerKind": "user", "token": "fixture-token-two"})
	if changed.Code != http.StatusConflict || !strings.Contains(changed.Body.String(), `"code":"revision_conflict"`) {
		t.Fatalf("changed replay: %d %s", changed.Code, changed.Body)
	}
	viewerList := f.call(t, f.viewer.ID, http.MethodGet, base, nil)
	if viewerList.Code != http.StatusOK || strings.Contains(viewerList.Body.String(), personal.ID) {
		t.Fatalf("personal connection leaked: %d %s", viewerList.Code, viewerList.Body)
	}
	if response := f.call(t, f.viewer.ID, http.MethodPost, base, map[string]any{"operationId": "unauthorized-instance", "ownerKind": "instance", "token": "fixture-token"}); response.Code != http.StatusNotFound && response.Code != http.StatusForbidden {
		t.Fatalf("non-admin instance: %d %s", response.Code, response.Body)
	}
	instanceResponse := f.call(t, f.owner.ID, http.MethodPost, base, map[string]any{"operationId": "instance-provider", "ownerKind": "instance", "token": "fixture-instance"})
	var instance medialibrary.ProviderConnection
	if instanceResponse.Code != http.StatusCreated || json.Unmarshal(instanceResponse.Body.Bytes(), &instance) != nil || instance.OwnerKind != "instance" {
		t.Fatalf("instance create: %d %s", instanceResponse.Code, instanceResponse.Body)
	}
	library, _ := f.libraryAndSource(t)
	policyPath := fmt.Sprintf("/api/media-library/v1/libraries/%s/metadata-policy", library.ID)
	initial := f.call(t, f.owner.ID, http.MethodGet, policyPath, nil)
	var policy medialibrary.LibraryMetadataPolicy
	if initial.Code != http.StatusOK || json.Unmarshal(initial.Body.Bytes(), &policy) != nil || policy.Mode != "local_only" || policy.Revision != 0 {
		t.Fatalf("default policy: %d %s", initial.Code, initial.Body)
	}
	policyBody := map[string]any{"operationId": "policy", "expectedRevision": 0, "mode": "local_first_online", "connectionId": instance.ID, "language": "zh-CN", "region": "CN", "allowEditorJobs": false}
	if response := f.call(t, f.owner.ID, http.MethodPut, policyPath, policyBody); response.Code != http.StatusNotFound {
		t.Fatalf("ungranted policy: %d %s", response.Code, response.Body)
	}
	grantPath := base + "/" + instance.ID + "/grants"
	grant := f.call(t, f.owner.ID, http.MethodPut, grantPath, map[string]any{"operationId": "grant-library", "expectedRevision": 1, "libraryId": library.ID})
	if grant.Code != http.StatusOK {
		t.Fatalf("grant: %d %s", grant.Code, grant.Body)
	}
	set := f.call(t, f.owner.ID, http.MethodPut, policyPath, policyBody)
	if set.Code != http.StatusOK || json.Unmarshal(set.Body.Bytes(), &policy) != nil || policy.Revision != 1 || policy.ConnectionID != instance.ID {
		t.Fatalf("policy: %d %s", set.Code, set.Body)
	}
	if _, _, err = f.store.GetProviderCredentialForUse(t.Context(), f.owner.ID, library.ID, instance.ID); err != nil {
		t.Fatal("authorized use", err)
	}
	clearBody := map[string]any{"operationId": "clear-personal", "expectedRevision": 1}
	cleared := f.call(t, f.owner.ID, http.MethodDelete, base+"/"+personal.ID, clearBody)
	if cleared.Code != http.StatusOK || !strings.Contains(cleared.Body.String(), `"status":"missing"`) {
		t.Fatalf("clear personal: %d %s", cleared.Code, cleared.Body)
	}
	clearedReplay := f.call(t, f.owner.ID, http.MethodDelete, base+"/"+personal.ID, clearBody)
	if clearedReplay.Code != http.StatusOK || clearedReplay.Body.String() != cleared.Body.String() {
		t.Fatalf("clear replay: %d %s", clearedReplay.Code, clearedReplay.Body)
	}
	if deviceMediaRoute(http.MethodGet, "/api/media-library/v1/provider-status", f.owner.ID) || deviceMediaRoute(http.MethodPost, base, f.owner.ID) {
		t.Fatal("device scope exposed provider configuration")
	}
	if network.calls.Load() != 0 {
		t.Fatal("configuration generated provider traffic")
	}
}

func TestMediaProviderCheckUsesManagedCredential(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{42}, 32))
	if err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	const path = "/api/media-library/v1/provider-connections"
	const token = "fixture-check-key"
	created := f.call(t, f.owner.ID, http.MethodPost, path, map[string]any{"operationId": "check-key", "ownerKind": "user", "token": token})
	var connection medialibrary.ProviderConnection
	if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &connection) != nil {
		t.Fatalf("create: %d %s", created.Code, created.Body)
	}
	checkPath := path + "/" + connection.ID + "/check"
	calls := 0
	rejected := false
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "api.themoviedb.org" || r.URL.Path != "/3/configuration" || r.Header.Get("Authorization") != "Bearer "+token {
			t.Error("unexpected TMDB probe")
		}
		if rejected {
			return &http.Response{StatusCode: http.StatusUnauthorized, Header: make(http.Header), Body: http.NoBody}, nil
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"images":{"secure_base_url":"https://image.tmdb.org/t/p/","poster_sizes":["w500"],"backdrop_sizes":["w780"],"profile_sizes":["w185"],"still_sizes":["w300"]}}`))}, nil
	})}
	if response := f.call(t, f.viewer.ID, http.MethodPost, checkPath, nil); response.Code != http.StatusNotFound || calls != 0 {
		t.Fatalf("unauthorized check: %d", response.Code)
	}
	if response := f.call(t, f.owner.ID, http.MethodPost, checkPath, nil); response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"reachable":true`) || calls != 1 || strings.Contains(response.Body.String(), token) {
		t.Fatalf("connection check: %d", response.Code)
	}
	rejected = true
	if response := f.call(t, f.owner.ID, http.MethodPost, checkPath, nil); response.Code != http.StatusUnprocessableEntity || !strings.Contains(response.Body.String(), "provider_credentials_rejected") || calls != 2 {
		t.Fatalf("rejected key: %d", response.Code)
	}
}
