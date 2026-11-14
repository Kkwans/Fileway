package fbhttp

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func userStateFixture(t *testing.T) (*mediaScanHTTPFixture, mediaLibrarySummary, mediaLibrarySourceSummary, string) {
	t.Helper()
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "state.mkv", "video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "state-fixture-scan")
	response := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/catalog/items", nil)
	var page catalogCardPage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 {
		t.Fatalf("fixture catalog: %d %s", response.Code, response.Body)
	}
	return f, library, source, page.Items[0].Ref.ItemID
}

func TestMediaUserStateHTTPVisibilityAndIdempotency(t *testing.T) {
	f, library, source, itemID := userStateFixture(t)
	base := fmt.Sprintf("/api/media-library/v1/libraries/%s/items/%s", library.ID, itemID)
	path := base + "/user-state"
	if response := f.call(t, 0, http.MethodGet, path, nil); response.Code != http.StatusUnauthorized {
		t.Fatal("anonymous", response.Code)
	}
	if response := f.call(t, f.viewer.ID, http.MethodGet, path, nil); response.Code != http.StatusNotFound {
		t.Fatal("private", response.Code)
	}
	grant := f.call(t, f.owner.ID, http.MethodPut, fmt.Sprintf("/api/media-library/v1/libraries/%s/members/%d", library.ID, f.viewer.ID),
		map[string]any{"operationId": "state-share", "expectedRevision": source.LibraryRevision, "role": "viewer"})
	if grant.Code != http.StatusOK {
		t.Fatal(grant.Code, grant.Body)
	}
	initial := f.call(t, f.viewer.ID, http.MethodGet, path, nil)
	var state medialibrary.UserState
	if initial.Code != http.StatusOK || json.Unmarshal(initial.Body.Bytes(), &state) != nil || state.Revision != 0 || state.Favorite || state.ManualWatched != nil {
		t.Fatalf("initial: %d %s", initial.Code, initial.Body)
	}
	write := map[string]any{"operationId": "state-favorite", "expectedRevision": 0, "favorite": true}
	result := f.call(t, f.viewer.ID, http.MethodPatch, path, write)
	if result.Code != http.StatusOK || json.Unmarshal(result.Body.Bytes(), &state) != nil || !state.Favorite || state.Revision != 1 {
		t.Fatalf("favorite: %d %s", result.Code, result.Body)
	}
	owner := f.call(t, f.owner.ID, http.MethodGet, path, nil)
	var ownerState medialibrary.UserState
	if owner.Code != http.StatusOK || json.Unmarshal(owner.Body.Bytes(), &ownerState) != nil || ownerState.Revision != 0 || ownerState.Favorite {
		t.Fatalf("cross-user state: %d %s", owner.Code, owner.Body)
	}
	resetBody := map[string]any{"operationId": "state-reset", "expectedRevision": 1, "manualWatched": false}
	reset := f.call(t, f.viewer.ID, http.MethodPatch, path, resetBody)
	if reset.Code != http.StatusOK || json.Unmarshal(reset.Body.Bytes(), &state) != nil || state.Revision != 2 || state.ManualWatched == nil || *state.ManualWatched || state.WatchResetRevision != 1 {
		t.Fatalf("reset: %d %s", reset.Code, reset.Body)
	}
	replay := f.call(t, f.viewer.ID, http.MethodPatch, path, resetBody)
	if replay.Code != http.StatusOK || replay.Body.String() != reset.Body.String() {
		t.Fatalf("replay: %d %s", replay.Code, replay.Body)
	}
	stale := f.call(t, f.viewer.ID, http.MethodPatch, path, map[string]any{"operationId": "state-stale", "expectedRevision": 0, "favorite": false})
	if stale.Code != http.StatusConflict || !strings.Contains(stale.Body.String(), `"code":"revision_conflict"`) {
		t.Fatalf("stale: %d %s", stale.Code, stale.Body)
	}
	for _, invalid := range []map[string]any{
		{"operationId": "bad-1", "expectedRevision": 2},
		{"operationId": "bad-2", "expectedRevision": 2, "favorite": nil},
		{"operationId": "bad-3", "favorite": true},
		{"operationId": "bad-4", "expectedRevision": 2, "favorite": true, "userId": f.owner.ID},
	} {
		if response := f.call(t, f.viewer.ID, http.MethodPatch, path, invalid); response.Code != http.StatusUnprocessableEntity {
			t.Fatalf("invalid patch: %d %s", response.Code, response.Body)
		}
	}
	detail := f.call(t, f.viewer.ID, http.MethodGet, base, nil)
	var projected mediaItemDetail
	if detail.Code != http.StatusOK || json.Unmarshal(detail.Body.Bytes(), &projected) != nil || projected.UserState == nil || projected.UserState.Revision != 2 || projected.Card == nil || projected.Card.UserState == nil || projected.Card.UserState.Revision != 2 || !projected.Card.CanActions["favorite"] || !projected.Card.CanActions["markWatched"] {
		t.Fatalf("detail projection: %d %s", detail.Code, detail.Body)
	}
	// Source-owner revocation hides state even while membership remains active.
	ownerUser, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	ownerUser.Perm.Download = false
	if err = f.h.storage.Users.Update(ownerUser, "Perm"); err != nil {
		t.Fatal(err)
	}
	if response := f.call(t, f.viewer.ID, http.MethodGet, path, nil); response.Code != http.StatusNotFound {
		t.Fatalf("source revoked read: %d %s", response.Code, response.Body)
	}
	if response := f.call(t, f.viewer.ID, http.MethodPatch, path, resetBody); response.Code != http.StatusNotFound {
		t.Fatalf("source revoked replay: %d %s", response.Code, response.Body)
	}
}

type revokeUserStateAfterCommit struct {
	users.Store
	store             *medialibrary.Store
	actor             uint
	libraryID, itemID string
}

func (s *revokeUserStateAfterCommit) Get(root string, id interface{}) (*users.User, error) {
	user, err := s.Store.Get(root, id)
	if err != nil {
		return nil, err
	}
	state, stateErr := s.store.GetUserState(context.Background(), s.actor, s.libraryID, s.itemID)
	if stateErr == nil && state.Revision > 0 {
		copy := *user
		copy.Perm.Download = false
		return &copy, nil
	}
	return user, nil
}

func TestMediaUserStateHTTPPostCommitVisibilityIsUnknown(t *testing.T) {
	f, library, _, itemID := userStateFixture(t)
	path := fmt.Sprintf("/api/media-library/v1/libraries/%s/items/%s/user-state", library.ID, itemID)
	original := f.h.storage.Users
	f.h.storage.Users = &revokeUserStateAfterCommit{Store: original, store: f.store, actor: f.owner.ID, libraryID: library.ID, itemID: itemID}
	body := map[string]any{"operationId": "state-unknown", "expectedRevision": 0, "favorite": true}
	response := f.call(t, f.owner.ID, http.MethodPatch, path, body)
	f.h.storage.Users = original
	if response.Code != http.StatusServiceUnavailable || !strings.Contains(response.Body.String(), `"code":"write_outcome_unknown"`) || strings.Contains(response.Body.String(), itemID) {
		t.Fatalf("post-commit visibility: %d %s", response.Code, response.Body)
	}
	state, err := f.store.GetUserState(t.Context(), f.owner.ID, library.ID, itemID)
	if err != nil || !state.Favorite || state.Revision != 1 {
		t.Fatalf("write lost: %+v %v", state, err)
	}
	replay := f.call(t, f.owner.ID, http.MethodPatch, path, body)
	if replay.Code != http.StatusOK || !strings.Contains(replay.Body.String(), `"revision":1`) {
		t.Fatalf("safe replay: %d %s", replay.Code, replay.Body)
	}
}
