package fbhttp

import (
	"context"
	"net/http"
	"reflect"
	"slices"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

type metadataRefreshBody struct {
	OperationID       string `json:"operationId"`
	ItemRevision      int64  `json:"itemRevision"`
	StructureRevision int64  `json:"structureRevision"`
}

func mediaMetadataLibraryRefreshPreviewHandler() handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		store := d.store.MediaLibrary
		policy, err := store.GetLibraryMetadataPolicy(r.Context(), d.user.ID, library.ID)
		if err != nil {
			return catalogError(w, err)
		}
		if !policy.OnlineEnabled() {
			return writeCatalogError(w, http.StatusUnprocessableEntity, "library_offline_policy", "此媒体库尚未启用在线补全", false)
		}
		preview, err := store.MetadataRefreshLibraryPreview(r.Context(), d.user.ID, library.ID, policy.Language, policy.Mode)
		if err != nil {
			return catalogError(w, err)
		}
		preview.Region = policy.Region
		preview.PolicyRevision = policy.Revision
		preview.CanRefresh = preview.CanRefresh && policy.ConnectionID != "" && d.store.MediaProviderVault != nil && d.store.TMDBHTTPClient != nil
		return renderJSON(w, r, preview)
	})
}

func metadataRefreshParts(_ string) []mediaprovider.Part {
	return []mediaprovider.Part{mediaprovider.Credits, mediaprovider.ExternalIDs, mediaprovider.AlternativeTitles}
}

func mediaMetadataRefreshHandler(preview bool, slots chan struct{}, starts <-chan time.Time) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body metadataRefreshBody
		if decodeMediaScanBody(w, r, &body) != nil || body.ItemRevision < 0 || body.StructureRevision < 0 ||
			!preview && (!validScanID(body.OperationID) || body.ItemRevision < 1 || body.StructureRevision < 1) {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		ctx := r.Context()
		store := d.store.MediaLibrary
		item, view, err := mediaIdentification(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		policy, err := store.GetLibraryMetadataPolicy(ctx, d.user.ID, library.ID)
		if err != nil {
			return catalogError(w, err)
		}
		if !policy.OnlineEnabled() {
			return writeCatalogError(w, http.StatusUnprocessableEntity, "library_offline_policy", "此媒体库尚未启用在线补全", false)
		}
		match, err := store.VisibleProviderMatch(ctx, d.user.ID, library.ID, item.Ref.ItemID, item.ItemRevision)
		if err != nil {
			return catalogError(w, err)
		}
		if match == nil {
			return writeCatalogError(w, http.StatusUnprocessableEntity, "online_match_required", "当前条目尚未匹配在线资料", false)
		}
		itemRevision, structureRevision := item.ItemRevision, item.StructureRevision
		if !preview {
			itemRevision, structureRevision = body.ItemRevision, body.StructureRevision
		}
		request := medialibrary.ProviderMatchRequest{LibraryID: library.ID, ItemID: item.Ref.ItemID,
			ConnectionID: policy.ConnectionID, Kind: match.Kind, ExternalID: match.ExternalID,
			ItemRevision: itemRevision, StructureRevision: structureRevision}
		if !preview {
			if receipt, found, err := store.ReplayProviderMatch(ctx, d.user.ID, body.OperationID, request); err != nil {
				return catalogError(w, err)
			} else if found {
				if err = view.revalidateSources(ctx); err != nil {
					return catalogError(w, err)
				}
				return renderJSON(w, r, receipt)
			}
			if item.ItemRevision != body.ItemRevision || item.StructureRevision != body.StructureRevision {
				return catalogError(w, medialibrary.ErrRevisionConflict)
			}
			if match.Kind != "series" && match.Audit.Language == policy.Language {
				return renderJSON(w, r, map[string]string{"state": "already_current", "language": policy.Language})
			}
		}
		if d.store.MediaProviderVault == nil || d.store.TMDBHTTPClient == nil {
			return writeCatalogError(w, http.StatusServiceUnavailable, "provider_configuration_required", "TMDB 连接尚未配置", true)
		}
		snapshot, err := beginCatalogSnapshot(ctx, d, library.ID)
		if err != nil {
			return catalogError(w, err)
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			w.Header().Set("Retry-After", "1")
			return writeCatalogError(w, http.StatusTooManyRequests, "provider_busy", "TMDB 查询正在处理，请稍后再试", true)
		}
		select {
		case <-ctx.Done():
			return catalogError(w, ctx.Err())
		case <-starts:
		}
		if err = view.revalidateSources(ctx); err != nil {
			return catalogError(w, err)
		}
		fence, sealed, err := store.GetProviderCredentialForUse(ctx, d.user.ID, library.ID, policy.ConnectionID)
		if err != nil {
			return catalogError(w, err)
		}
		if fence.PolicyRevision != policy.Revision {
			return catalogError(w, medialibrary.ErrRevisionConflict)
		}
		scope, err := medialibrary.ProviderCredentialContext(policy.ConnectionID)
		if err != nil {
			return catalogError(w, err)
		}
		client := mediaprovider.NewTMDBClient(d.store.TMDBHTTPClient, func(context.Context) (string, error) {
			return d.store.MediaProviderVault.Open(scope, sealed)
		})
		document, err := client.Fetch(ctx, mediaprovider.EntityRef{Provider: "tmdb", Kind: mediaprovider.Kind(match.Kind), ID: match.ExternalID},
			mediaprovider.FetchOptions{Language: policy.Language, Parts: metadataRefreshParts(match.Kind)})
		if err != nil {
			return matchProviderError(w, err)
		}
		projection, err := mediaprovider.ProjectTMDB(document)
		if err != nil {
			return matchProviderError(w, err)
		}
		matchDocument, err := withProviderEpisodeDocuments(ctx, store, d.user.ID, library.ID, item.Ref.ItemID, match.ExternalID, client, policy.Language,
			medialibrary.ProviderMatchDocument{Item: projection.Item, PosterPath: projection.PosterPath, BackdropPath: projection.BackdropPath})
		if err != nil {
			return matchProviderError(w, err)
		}
		if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
			return catalogError(w, err)
		}
		if err = view.revalidateSources(ctx); err != nil {
			return catalogError(w, err)
		}
		changed := changedMetadataFields(match.Document.Item.Fields, projection.Item.Fields)
		if !reflect.DeepEqual(match.Document.Item.Credits, projection.Item.Credits) {
			changed = append(changed, "credits")
		}
		if match.Document.PosterPath != projection.PosterPath || match.Document.BackdropPath != projection.BackdropPath {
			changed = append(changed, "artwork")
		}
		episodesChanged := !reflect.DeepEqual(match.Document.Episodes, matchDocument.Episodes)
		if episodesChanged {
			changed = append(changed, "episodes")
		}
		if preview {
			states, err := store.MetadataRefreshFieldStates(ctx, d.user.ID, library.ID, item.Ref.ItemID)
			if err != nil {
				return catalogError(w, err)
			}
			protected, preserved := []string{}, []string{}
			for name, state := range states {
				if state.Locked || state.ExplicitClear {
					protected = append(protected, name)
				} else if state.Source == "nfo" && policy.Mode != medialibrary.MetadataOnlineFirst {
					preserved = append(preserved, name)
				}
			}
			slices.Sort(changed)
			slices.Sort(protected)
			slices.Sort(preserved)
			return renderJSON(w, r, struct {
				CurrentLanguage string   `json:"currentLanguage"`
				TargetLanguage  string   `json:"targetLanguage"`
				ChangedFields   []string `json:"changedFields"`
				ProtectedFields []string `json:"protectedFields"`
				PreservedFields []string `json:"preservedFields"`
				CanRefresh      bool     `json:"canRefresh"`
			}{match.Audit.Language, policy.Language, changed, protected, preserved, true})
		}
		if len(changed) == 0 {
			return renderJSON(w, r, map[string]string{"state": "already_current", "language": policy.Language})
		}
		receipt, err := store.ConfirmProviderMatch(ctx, d.user.ID, body.OperationID, request, *fence, document.Language, document.FetchedAt.UnixMilli(),
			matchDocument,
			medialibrary.ProviderMatchAudit{Source: "refresh", Region: policy.Region, AdapterVersion: "tmdb-v1", Evidence: []string{"language_refresh"}})
		if err != nil {
			return catalogError(w, err)
		}
		current, err := store.CheckProviderUse(ctx, d.user.ID, library.ID, policy.ConnectionID)
		if err != nil || *current != *fence || verifyCatalogSnapshot(ctx, d, snapshot) != nil || view.revalidateSources(ctx) != nil {
			return providerUnknown(w)
		}
		return renderJSON(w, r, receipt)
	})
}

func changedMetadataFields(current, next map[string][]string) []string {
	keys := map[string]struct{}{}
	for name := range current {
		keys[name] = struct{}{}
	}
	for name := range next {
		keys[name] = struct{}{}
	}
	changed := []string{}
	for name := range keys {
		if !slices.Equal(current[name], next[name]) {
			changed = append(changed, name)
		}
	}
	return changed
}
