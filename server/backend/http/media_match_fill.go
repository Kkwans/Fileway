package fbhttp

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

type fillMatchResult struct {
	State  string                            `json:"state"`
	Reason string                            `json:"reason,omitempty"`
	Match  *medialibrary.ProviderMatchResult `json:"match,omitempty"`
}

// mediaMatchFillHandler runs the same fill-only rules that the batch worker
// will run. It cannot replace an existing manual or automatic confirmation.
func mediaMatchFillHandler(slots chan struct{}, starts <-chan time.Time) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body struct {
			OperationID       string `json:"operationId"`
			ItemRevision      int64  `json:"itemRevision"`
			StructureRevision int64  `json:"structureRevision"`
		}
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ItemRevision < 1 || body.StructureRevision < 1 {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		if d.store.MediaProviderVault == nil || d.store.TMDBHTTPClient == nil {
			return writeCatalogError(w, http.StatusServiceUnavailable, "provider_configuration_required", "TMDB 连接尚未配置", true)
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			w.Header().Set("Retry-After", "1")
			return writeCatalogError(w, http.StatusTooManyRequests, "provider_busy", "TMDB 请求较多，请稍后再试", true)
		}
		result, err := fillCatalogMatch(r.Context(), d, library, mux.Vars(r)["itemId"], body.OperationID, body.ItemRevision, body.StructureRevision, starts)
		if err != nil {
			var providerFailure *mediaprovider.Error
			if errors.As(err, &providerFailure) {
				return matchProviderError(w, err)
			}
			if errors.Is(err, errProviderResultUnknown) {
				return providerUnknown(w)
			}
			return providerError(w, err)
		}
		return renderJSON(w, r, result)
	})
}

var errProviderResultUnknown = errors.New("provider match outcome not confirmed for request")

func fillCatalogMatch(ctx context.Context, d *data, library *medialibrary.Library, itemID, operationID string, itemRevision, structureRevision int64, starts <-chan time.Time) (fillMatchResult, error) {
	store := d.store.MediaLibrary
	policy, err := store.GetLibraryMetadataPolicy(ctx, d.user.ID, library.ID)
	if err != nil {
		return fillMatchResult{}, err
	}
	if !policy.OnlineEnabled() {
		return fillMatchResult{State: "local_only"}, nil
	}
	snapshot, err := beginCatalogSnapshot(ctx, d, library.ID)
	if err != nil {
		return fillMatchResult{}, err
	}
	item, view, err := identifyCatalogItem(ctx, d, library, itemID)
	if err != nil {
		return fillMatchResult{}, err
	}
	input := automaticMatchInput(item)
	request := medialibrary.ProviderMatchRequest{LibraryID: library.ID, ItemID: itemID, Kind: string(input.Kind),
		ItemRevision: itemRevision, StructureRevision: structureRevision, Automatic: true}
	if receipt, found, err := store.ReplayProviderMatch(ctx, d.user.ID, operationID, request); err != nil {
		return fillMatchResult{}, err
	} else if found {
		if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
			return fillMatchResult{}, err
		}
		return fillMatchResult{State: "matched", Match: receipt}, nil
	}
	if item.ItemRevision != itemRevision || item.StructureRevision != structureRevision {
		return fillMatchResult{}, medialibrary.ErrRevisionConflict
	}
	if item.MatchState == "provider_confirmed" {
		return fillMatchResult{State: "already_matched"}, nil
	}
	// A leaf with multiple distinct files can have contradictory filename
	// evidence. Do not auto-confirm from the one chosen for presentation.
	detail, err := store.GetCatalogItem(ctx, d.user.ID, library.ID, itemID)
	if err != nil {
		return fillMatchResult{}, err
	}
	input.Incomplete = input.Incomplete || detail.FileCount > 1
	if detail.Kind == "video" || detail.Kind == "movie" {
		page, err := store.ListItemSourceAccess(ctx, d.user.ID, library.ID, itemID, "", 1)
		if err != nil {
			return fillMatchResult{}, err
		}
		input.Incomplete = input.Incomplete || page.NextCursor != "" || len(page.Items) != 1
	}
	for _, source := range view.checks {
		input.Incomplete = input.Incomplete || source.State != mediascan.ReferenceAvailable
	}
	for _, metadata := range view.metadataChecks {
		input.Incomplete = input.Incomplete || metadata.State != mediascan.ReferenceAvailable
	}
	fence, sealed, err := store.GetProviderCredentialForUse(ctx, d.user.ID, library.ID, policy.ConnectionID)
	if err != nil {
		return fillMatchResult{}, err
	}
	if fence.PolicyRevision != policy.Revision {
		return fillMatchResult{}, medialibrary.ErrRevisionConflict
	}
	check := func(ctx context.Context) error {
		if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
			return err
		}
		if err := view.revalidateSources(ctx); err != nil {
			return err
		}
		current, err := store.CheckProviderUse(ctx, d.user.ID, library.ID, fence.ConnectionID)
		if err == nil && *current != *fence {
			return medialibrary.ErrRevisionConflict
		}
		return err
	}
	scope, err := medialibrary.ProviderCredentialContext(fence.ConnectionID)
	if err != nil {
		return fillMatchResult{}, err
	}
	client := mediaprovider.NewTMDBClient(d.store.TMDBHTTPClient, func(context.Context) (string, error) {
		return d.store.MediaProviderVault.Open(scope, sealed)
	})
	decision, err := client.ResolveAutomaticMatch(ctx, input, policy.Language, func(ctx context.Context) error {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-starts:
			return check(ctx)
		}
	})
	if err != nil {
		return fillMatchResult{}, err
	}
	if err := check(ctx); err != nil {
		return fillMatchResult{}, err
	}
	if decision.State != mediaprovider.AutoMatched {
		return fillMatchResult{State: string(decision.State), Reason: decision.Reason}, nil
	}
	request.ExternalID = decision.Document.Entity.ID
	receipt, err := store.ConfirmProviderMatch(ctx, d.user.ID, operationID, request, *fence, decision.Document.Language, decision.Document.FetchedAt.UnixMilli(),
		medialibrary.ProviderMatchDocument{Item: decision.Projection.Item, PosterPath: decision.Projection.PosterPath, BackdropPath: decision.Projection.BackdropPath})
	if err != nil {
		return fillMatchResult{}, err
	}
	if err := check(ctx); err != nil {
		return fillMatchResult{}, errProviderResultUnknown
	}
	return fillMatchResult{State: "matched", Match: receipt}, nil
}

func automaticMatchInput(item mediaIdentificationResponse) mediaprovider.AutomaticMatchInput {
	input := mediaprovider.AutomaticMatchInput{Kind: mediaprovider.Kind(item.StructureKind), Incomplete: item.ExternalIDsCursor != "" || item.incompleteEvidence}
	if item.StructureKind == "video" {
		input.Kind = mediaprovider.Movie
	}
	if item.MatchState == "needs_review" {
		input.Ambiguities = append(input.Ambiguities, "local_match_conflict")
	}
	for _, id := range item.ExternalIDs {
		kind := mediaprovider.Kind(id.EntityKind)
		if id.EntityKind == "tvshow" {
			kind = mediaprovider.Series
		}
		input.Identifiers = append(input.Identifiers, mediaprovider.WorkIdentifier{Kind: kind, ExternalRef: mediaprovider.ExternalRef{Provider: id.Provider, ID: id.Value}})
	}
	if file := item.File; file != nil {
		input.Ambiguities = append(input.Ambiguities, file.Ambiguities...)
		if file.TypeHint == "episode" {
			input.Ambiguities = append(input.Ambiguities, "episode_requires_parent")
		}
		if file.Year != nil {
			input.Year = *file.Year
		}
		for _, id := range file.ProviderIDs {
			kind := mediaprovider.Kind(id.MediaType)
			if id.MediaType == "tv" {
				kind = mediaprovider.Series
			}
			input.Identifiers = append(input.Identifiers, mediaprovider.WorkIdentifier{Kind: kind, ExternalRef: mediaprovider.ExternalRef{Provider: id.Provider, ID: id.ID}})
		}
	}
	return input
}
