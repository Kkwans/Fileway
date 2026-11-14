package fbhttp

import (
	"context"
	"net/http"
	"strconv"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

type matchConfirmBody struct {
	OperationID       string `json:"operationId"`
	ConnectionID      string `json:"connectionId"`
	Kind              string `json:"kind"`
	ExternalID        string `json:"externalId"`
	ItemRevision      int64  `json:"itemRevision"`
	StructureRevision int64  `json:"structureRevision"`
}

func mediaMatchConfirmHandler(slots chan struct{}, starts <-chan time.Time) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body matchConfirmBody
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) ||
			body.ItemRevision < 1 || body.StructureRevision < 1 ||
			body.Kind != "movie" && body.Kind != "series" {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		externalID, err := strconv.ParseInt(body.ExternalID, 10, 64)
		if err != nil || externalID < 1 || strconv.FormatInt(externalID, 10) != body.ExternalID {
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
		connection := body.ConnectionID
		if connection == "" {
			connection = policy.ConnectionID
		}
		// Keep the client request stable for receipt replay, including the case
		// where the library's default connection has since changed.
		request := medialibrary.ProviderMatchRequest{LibraryID: library.ID, ItemID: item.Ref.ItemID,
			ConnectionID: body.ConnectionID, Kind: body.Kind, ExternalID: externalID,
			ItemRevision: body.ItemRevision, StructureRevision: body.StructureRevision}
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
		if item.StructureKind != body.Kind && (item.StructureKind != "video" || body.Kind != "movie") {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		if !policy.OnlineEnabled() {
			return writeCatalogError(w, http.StatusUnprocessableEntity, "library_offline_policy", "此媒体库尚未启用在线补全", false)
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
		fence, sealed, err := store.GetProviderCredentialForUse(ctx, d.user.ID, library.ID, connection)
		if err != nil {
			return catalogError(w, err)
		}
		if fence.PolicyRevision != policy.Revision {
			return catalogError(w, medialibrary.ErrRevisionConflict)
		}
		scope, err := medialibrary.ProviderCredentialContext(connection)
		if err != nil {
			return catalogError(w, err)
		}
		client := mediaprovider.NewTMDBClient(d.store.TMDBHTTPClient, func(context.Context) (string, error) {
			return d.store.MediaProviderVault.Open(scope, sealed)
		})
		doc, err := client.Fetch(ctx, mediaprovider.EntityRef{Provider: "tmdb", Kind: mediaprovider.Kind(body.Kind), ID: externalID},
			mediaprovider.FetchOptions{Language: policy.Language, Parts: []mediaprovider.Part{mediaprovider.Credits, mediaprovider.ExternalIDs}})
		if err != nil {
			return matchProviderError(w, err)
		}
		projection, err := mediaprovider.ProjectTMDB(doc)
		if err != nil {
			return matchProviderError(w, err)
		}
		if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
			return catalogError(w, err)
		}
		if err := view.revalidateSources(ctx); err != nil {
			return catalogError(w, err)
		}
		receipt, err := store.ConfirmProviderMatch(ctx, d.user.ID, body.OperationID, request, *fence, doc.Language, doc.FetchedAt.UnixMilli(),
			medialibrary.ProviderMatchDocument{Item: projection.Item, PosterPath: projection.PosterPath, BackdropPath: projection.BackdropPath})
		if err != nil {
			return catalogError(w, err)
		}
		current, err := store.CheckProviderUse(ctx, d.user.ID, library.ID, connection)
		if err != nil || *current != *fence || verifyCatalogSnapshot(ctx, d, snapshot) != nil || view.revalidateSources(ctx) != nil {
			return providerUnknown(w)
		}
		return renderJSON(w, r, receipt)
	})
}
