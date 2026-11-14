package fbhttp

import (
	"context"
	"encoding/base64"
	"errors"
	"io/fs"
	"math"
	"net/http"
	"os"
	"path"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

const continueEndpoint = "/api/media-library/v1/home/rows/continue"

type catalogContinueItem struct {
	Card       catalogCard          `json:"card"`
	Variant    mediaVariantResponse `json:"variant"`
	Revision   string               `json:"revision"`
	PositionMs int64                `json:"positionMs"`
	DurationMs *int64               `json:"durationMs"`
}

func validContinueRevision(value string) bool {
	if len(value) != 43 {
		return false
	}
	_, err := base64.RawURLEncoding.Strict().DecodeString(value)
	return err == nil
}

func openContinueTimeline(ctx context.Context, d *data, evidence *medialibrary.PlaybackAccess) (os.FileInfo, string, error) {
	if evidence.IdentityKind != string(mediascan.IdentityNative) {
		return nil, "", fs.ErrPermission
	}
	access := mediaAccessContext{viewer: d.user.ID, session: d.deviceSession, playback: evidence}
	file, err := access.openPlayback(ctx, d)
	if err != nil {
		return nil, "", err
	}
	defer func() { _ = file.Close() }()
	info, stamp, err := file.State()
	if err != nil || info == nil || stamp == "" {
		return nil, "", fs.ErrPermission
	}
	return info, stamp, nil
}

func (v *catalogView) projectContinue(ctx context.Context, candidate medialibrary.ContinueCandidate) (*catalogContinueItem, error) {
	library, err := currentMediaLibrary(ctx, v.d, v.d.user.ID, candidate.LibraryID)
	if err != nil {
		return nil, err
	}
	page, err := v.d.store.MediaLibrary.ListPlaybackAccess(ctx, v.d.user.ID, candidate.LibraryID, candidate.ItemID, candidate.EditionID, candidate.VariantID, "", 100)
	if err != nil {
		return nil, err
	}
	for i := range page.Items {
		if v.budget.remaining <= catalogContinuationReserve {
			return nil, errCatalogWorkPending
		}
		v.budget.take()
		evidence := &page.Items[i]
		info, stamp, err := openContinueTimeline(ctx, v.d, evidence)
		if err != nil || !candidate.Matches(evidence, stamp) {
			continue
		}
		size := info.Size()
		access := catalogAccess{
			mediaItemAccess: mediaItemAccess{sourceState: mediascan.ReferenceAvailable, metadataState: "none",
				fallbackTitle: mediaDisplayName(evidence.Locator), allowedInputs: make(map[string]bool), budget: v.budget, view: v},
			size: &size, extension: strings.ToLower(strings.TrimPrefix(path.Ext(string(evidence.Locator)), ".")),
			playable: true, sourceFileID: evidence.SourceFileID, bindingID: evidence.BindingID, proofEpoch: evidence.Binding.ProofEpoch,
		}
		card, err := v.structureCard(ctx, library, candidate.ItemID, access)
		if err != nil {
			return nil, err
		}
		if card.classificationIncomplete {
			return nil, errCatalogWorkPending
		}
		card.CanActions["resume"], card.CanActions["startOver"], card.CanActions["removeFromContinue"] = true, true, true
		delete(card.ActionReasons, "resume")
		delete(card.ActionReasons, "startOver")
		delete(card.ActionReasons, "removeFromContinue")
		// Reopen after metadata projection to fence permission or file changes.
		_, currentStamp, err := openContinueTimeline(ctx, v.d, evidence)
		if err != nil || currentStamp != stamp {
			return nil, nil
		}
		revision := candidate.Revision()
		current, err := v.d.store.MediaLibrary.ContinueCurrent(ctx, evidence, currentStamp, revision)
		if err != nil || !current {
			return nil, err
		}
		// Metadata may change while the exact-source checkpoint stays valid.
		// Publish only the card revision that was used to build the snapshot.
		return &catalogContinueItem{
			Card: card, Variant: variantResponse(evidence, medialibrary.PlaybackVariant{}, info),
			Revision: revision, PositionMs: candidate.PositionMs, DurationMs: candidate.DurationMs,
		}, nil
	}
	if page.NextCursor != "" {
		return nil, errCatalogWorkPending
	}
	return nil, ctx.Err()
}

func (v *catalogView) continueRow(ctx context.Context, snapshot catalogSnapshot, token string) (catalogHomeRow, error) {
	const limit = 12
	const chunk = 24
	row := catalogHomeRow{ID: "continue", Kind: "continue", State: "ready", Endpoint: continueEndpoint}
	cursor, err := newCatalogCursor(v.d, snapshot, "continue", token)
	if err != nil {
		return row, err
	}
	before := int64(math.MaxInt64)
	if cursor.AfterValue != nil {
		before = *cursor.AfterValue
	}
walk:
	for v.budget.remaining > catalogContinuationReserve {
		candidates, err := v.d.store.MediaLibrary.ListContinueCandidates(ctx, v.d.user.ID, mediaSharingDisabled(v.d), before, cursor.AfterID, chunk)
		if err != nil {
			return row, err
		}
		for _, candidate := range candidates {
			if v.budget.remaining <= catalogContinuationReserve {
				row.State, row.HasMore = "partial", true
				break walk
			}
			v.budget.take()
			item, err := v.projectContinue(ctx, candidate)
			switch {
			case errors.Is(err, errCatalogWorkPending):
				row.State = "deferred"
				if cursor.AfterValue != nil {
					row.HasMore = true
				}
				break walk
			case errors.Is(err, medialibrary.ErrNotFound), errors.Is(err, fs.ErrPermission), errors.Is(err, medialibrary.ErrArchived):
			case err != nil:
				return row, err
			}
			if item != nil {
				if len(row.Continuing) == limit {
					row.HasMore = true
					break walk
				}
				row.Continuing = append(row.Continuing, *item)
				row.Items = append(row.Items, item.Card)
			}
			before, cursor.AfterID = candidate.UpdatedAt(), candidate.VariantID
			cursor.AfterValue = &before
		}
		if len(candidates) < chunk {
			break
		}
		if v.budget.remaining <= catalogContinuationReserve {
			row.State, row.HasMore = "partial", true
		}
	}
	if err := verifyCatalogCards(ctx, v.d, row.Items); err != nil {
		return row, err
	}
	if row.HasMore {
		row.NextCursor, err = encodeCatalogCursor(v.d.settings.Key, cursor)
		if err != nil {
			return row, err
		}
	}
	row.Revision = catalogPublicRevision(v.d, snapshot, "continue", row.Continuing)
	return row, nil
}

var mediaRemoveContinueHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body struct {
		EditionID string `json:"editionId"`
		VariantID string `json:"variantId"`
		BindingID string `json:"bindingId"`
		Revision  string `json:"expectedRevision"`
	}
	if err := deviceBody(w, r, &body); err != nil || body.EditionID == "" || body.VariantID == "" ||
		body.BindingID == "" || !validContinueRevision(body.Revision) {
		return catalogError(w, medialibrary.ErrInvalid)
	}
	ctx := r.Context()
	library, err := itemLibrary(r, d)
	if err != nil {
		return mediaAccessError(err)
	}
	evidence, err := d.store.MediaLibrary.ResolvePlayback(ctx, d.user.ID, library.ID, mux.Vars(r)["itemId"], body.EditionID, body.VariantID, body.BindingID)
	if err != nil {
		return mediaAccessError(err)
	}
	_, stamp, err := openContinueTimeline(ctx, d, evidence)
	if err != nil {
		return mediaAccessError(err)
	}
	if err := d.store.MediaLibrary.RemoveContinue(ctx, evidence, stamp, body.Revision); err != nil {
		return catalogError(w, err)
	}
	if _, current, err := openContinueTimeline(ctx, d, evidence); err != nil || current != stamp {
		return writeCatalogError(w, http.StatusServiceUnavailable, "write_outcome_unknown", "未能确认续播操作结果，请刷新", true)
	}
	return http.StatusNoContent, nil
})
