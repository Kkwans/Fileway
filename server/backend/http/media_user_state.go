package fbhttp

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/gorilla/mux"
)

type mediaUserStatePatch struct {
	OperationID      string          `json:"operationId"`
	ExpectedRevision *int64          `json:"expectedRevision"`
	Favorite         json.RawMessage `json:"favorite"`
	ManualWatched    json.RawMessage `json:"manualWatched"`
}

func userStateBool(raw json.RawMessage) (*bool, error) {
	if len(raw) == 0 {
		return nil, nil
	}
	switch string(bytes.TrimSpace(raw)) {
	case "true":
		value := true
		return &value, nil
	case "false":
		value := false
		return &value, nil
	default:
		return nil, medialibrary.ErrInvalid
	}
}

type userStateFence struct {
	snapshot                catalogSnapshot
	itemRevision, structure int64
}

// Recheck the same live source/membership policy used by the catalog before
// and after publication. A post-write failure may follow a committed receipt.
func captureUserStateFence(r *http.Request, d *data, library *medialibrary.Library, itemID string) (userStateFence, error) {
	snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
	if err != nil {
		return userStateFence{}, err
	}
	detail, _, err := visibleItemDetail(r, d, library, itemID)
	if err != nil {
		return userStateFence{}, err
	}
	revision, structure, superseded, err := d.store.MediaLibrary.GetBrowseItemFence(r.Context(), d.user.ID, library.ID, itemID)
	if err != nil {
		return userStateFence{}, err
	}
	if superseded {
		return userStateFence{}, medialibrary.ErrNotFound
	}
	if revision != detail.Item.Revision {
		return userStateFence{}, medialibrary.ErrRevisionConflict
	}
	return userStateFence{snapshot: snapshot, itemRevision: revision, structure: structure}, nil
}

func userStateError(w http.ResponseWriter, err error) (int, error) {
	if errors.Is(err, medialibrary.ErrRevisionConflict) || errors.Is(err, errCatalogCursorStale) {
		return writeCatalogError(w, http.StatusConflict, "revision_conflict", "状态或权限已变化，请刷新", true)
	}
	return catalogError(w, err)
}

var mediaUserStateHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var patch medialibrary.UserStatePatch
	var body mediaUserStatePatch
	if r.Method == http.MethodPatch {
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision == nil || *body.ExpectedRevision < 0 {
			return userStateError(w, medialibrary.ErrInvalid)
		}
		var err error
		patch.Favorite, err = userStateBool(body.Favorite)
		if err != nil {
			return userStateError(w, err)
		}
		patch.ManualWatched, err = userStateBool(body.ManualWatched)
		if err != nil || (patch.Favorite == nil && patch.ManualWatched == nil) {
			return userStateError(w, medialibrary.ErrInvalid)
		}
	}
	library, err := itemLibrary(r, d)
	if err != nil {
		return userStateError(w, err)
	}
	itemID := mux.Vars(r)["itemId"]
	before, err := captureUserStateFence(r, d, library, itemID)
	if err != nil {
		return userStateError(w, err)
	}
	var state *medialibrary.UserState
	if r.Method == http.MethodPatch {
		state, err = d.store.MediaLibrary.SetUserState(r.Context(), d.user.ID, library.ID, itemID, *body.ExpectedRevision, body.OperationID, patch)
	} else {
		state, err = d.store.MediaLibrary.GetUserState(r.Context(), d.user.ID, library.ID, itemID)
	}
	if err != nil {
		if r.Method == http.MethodPatch && !errors.Is(err, medialibrary.ErrRevisionConflict) && !errors.Is(err, medialibrary.ErrNotFound) &&
			!errors.Is(err, medialibrary.ErrForbidden) && !errors.Is(err, medialibrary.ErrArchived) && !errors.Is(err, medialibrary.ErrInvalid) {
			return writeCatalogError(w, http.StatusServiceUnavailable, "write_outcome_unknown", "操作结果待确认，请刷新后使用原操作记录重试", true)
		}
		return userStateError(w, err)
	}
	after, err := captureUserStateFence(r, d, library, itemID)
	if err != nil {
		if r.Method == http.MethodPatch {
			return writeCatalogError(w, http.StatusServiceUnavailable, "write_outcome_unknown", "操作结果待确认，请刷新后使用原操作记录重试", true)
		}
		return userStateError(w, err)
	}
	if before != after {
		if r.Method == http.MethodPatch {
			return writeCatalogError(w, http.StatusServiceUnavailable, "write_outcome_unknown", "操作结果待确认，请刷新后使用原操作记录重试", true)
		}
		return userStateError(w, medialibrary.ErrRevisionConflict)
	}
	return renderJSON(w, r, state)
})
