package fbhttp

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/gorilla/mux"
)

type seriesMappingBody struct {
	Action            string `json:"action"`
	OperationID       string `json:"operationId"`
	ItemRevision      int64  `json:"itemRevision"`
	StructureRevision int64  `json:"structureRevision"`
	SeriesID          string `json:"seriesId,omitempty"`
	NewSeriesTitle    string `json:"newSeriesTitle,omitempty"`
	SeasonNumber      *int   `json:"seasonNumber"`
	EpisodeNumbers    []int  `json:"episodeNumbers"`
	Kind              string `json:"kind,omitempty"`
	ConnectionID      string `json:"connectionId,omitempty"`
	ExternalID        string `json:"externalId,omitempty"`
}

func mappingError(w http.ResponseWriter, err error) (int, error) {
	if errors.Is(err, medialibrary.ErrMappingUnsupported) {
		return writeCatalogError(w, http.StatusUnprocessableEntity, "mapping_shape_unsupported", "此关联需要独立的合并或拆分预览；请选择单一文件、未占用的季集位置", false)
	}
	return catalogError(w, err)
}

// The selected item's sources and an existing parent are authorized separately.
// Creating a container never turns a provider ID into a source capability.
func authorizeSeriesMapping(r *http.Request, d *data, seriesID string) (*catalogView, *catalogView, error) {
	_, view, err := mediaIdentification(r, d)
	if err != nil {
		return nil, nil, err
	}
	var parentView *catalogView
	if seriesID != "" {
		library, err := itemLibrary(r, d)
		if err != nil {
			return nil, nil, err
		}
		parent, parentViewResult, err := identifyCatalogItem(r.Context(), d, library, seriesID)
		if err != nil {
			return nil, nil, err
		}
		if parent.StructureKind != "series" {
			return nil, nil, medialibrary.ErrInvalid
		}
		parentView = parentViewResult
	}
	return view, parentView, nil
}

func revalidateMappingViews(ctx context.Context, views ...*catalogView) error {
	for _, view := range views {
		if view != nil {
			if err := view.revalidateSources(ctx); err != nil {
				return err
			}
		}
	}
	return nil
}

func mediaMappingPreviewHandler(slots chan struct{}, starts <-chan time.Time) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body seriesMappingBody
		if decodeMediaScanBody(w, r, &body) != nil || body.Action != "assign_series" || !validScanID(body.OperationID) || body.SeasonNumber == nil || body.ItemRevision < 1 || body.StructureRevision < 1 || len(body.EpisodeNumbers) < 1 || len(body.EpisodeNumbers) > 100 {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		if body.Kind != "" && body.Kind != "series" {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		item, err := d.store.MediaLibrary.GetCatalogItem(r.Context(), d.user.ID, library.ID, mux.Vars(r)["itemId"])
		if err != nil {
			return catalogError(w, err)
		}
		// Check role before any optional provider request. Read visibility alone
		// is insufficient permission to use metadata credentials or create a plan.
		if _, err = d.store.MediaLibrary.GetLibraryMetadataPolicy(r.Context(), d.user.ID, library.ID); err != nil {
			return catalogError(w, err)
		}
		view, parentView, err := authorizeSeriesMapping(r, d, body.SeriesID)
		if err != nil {
			return catalogError(w, err)
		}
		request := medialibrary.SeriesMappingRequest{LibraryID: library.ID, ItemID: item.ID, ItemRevision: body.ItemRevision, StructureRevision: body.StructureRevision, SeriesID: body.SeriesID, NewSeriesTitle: body.NewSeriesTitle, SeasonNumber: *body.SeasonNumber, EpisodeNumbers: body.EpisodeNumbers}
		var document *medialibrary.SeriesMappingProviderDocument
		var authority *medialibrary.ProviderUseAuthority
		if body.ExternalID != "" {
			if body.SeriesID != "" || body.Kind != "series" {
				return catalogError(w, medialibrary.ErrInvalid)
			}
			id, parseErr := strconv.ParseInt(body.ExternalID, 10, 64)
			if parseErr != nil || id < 1 || strconv.FormatInt(id, 10) != body.ExternalID {
				return catalogError(w, medialibrary.ErrInvalid)
			}
			policy, err := d.store.MediaLibrary.GetLibraryMetadataPolicy(r.Context(), d.user.ID, library.ID)
			if err != nil {
				return catalogError(w, err)
			}
			if !policy.OnlineEnabled() {
				return writeCatalogError(w, http.StatusUnprocessableEntity, "library_offline_policy", "此媒体库尚未启用在线补全", false)
			}
			if d.store.MediaProviderVault == nil || d.store.TMDBHTTPClient == nil {
				return writeCatalogError(w, http.StatusServiceUnavailable, "provider_configuration_required", "TMDB 连接尚未配置", true)
			}
			connection := body.ConnectionID
			if connection == "" {
				connection = policy.ConnectionID
			}
			fence, sealed, err := d.store.MediaLibrary.GetProviderCredentialForUse(r.Context(), d.user.ID, library.ID, connection)
			if err != nil {
				return catalogError(w, err)
			}
			if fence.PolicyRevision != policy.Revision {
				return catalogError(w, medialibrary.ErrRevisionConflict)
			}
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			default:
				w.Header().Set("Retry-After", "1")
				return writeCatalogError(w, http.StatusTooManyRequests, "provider_busy", "TMDB 查询正在处理，请稍后再试", true)
			}
			select {
			case <-r.Context().Done():
				return catalogError(w, r.Context().Err())
			case <-starts:
			}
			scope, err := medialibrary.ProviderCredentialContext(connection)
			if err != nil {
				return catalogError(w, err)
			}
			client := mediaprovider.NewTMDBClient(d.store.TMDBHTTPClient, func(context.Context) (string, error) { return d.store.MediaProviderVault.Open(scope, sealed) })
			doc, err := client.Fetch(r.Context(), mediaprovider.EntityRef{Provider: "tmdb", Kind: mediaprovider.Series, ID: id}, mediaprovider.FetchOptions{Language: policy.Language, Parts: []mediaprovider.Part{mediaprovider.Credits, mediaprovider.ExternalIDs}})
			if err != nil {
				return matchProviderError(w, err)
			}
			projection, err := mediaprovider.ProjectTMDB(doc)
			if err != nil {
				return matchProviderError(w, err)
			}
			if len(projection.Item.Fields["title"]) == 0 {
				return catalogError(w, medialibrary.ErrInvalid)
			}
			request.NewSeriesTitle = projection.Item.Fields["title"][0]
			request.ConnectionID = connection
			request.ExternalID = id
			document = &medialibrary.SeriesMappingProviderDocument{ProviderMatchDocument: medialibrary.ProviderMatchDocument{Item: projection.Item, PosterPath: projection.PosterPath, BackdropPath: projection.BackdropPath}, Language: doc.Language, FetchedAt: doc.FetchedAt.UnixMilli()}
			authority = fence
		} else if body.ConnectionID != "" {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		if err = revalidateMappingViews(r.Context(), view, parentView); err != nil {
			return catalogError(w, err)
		}
		preview, err := d.store.MediaLibrary.PreviewSeriesMapping(r.Context(), d.user.ID, body.OperationID, request, document, authority)
		if err != nil {
			return mappingError(w, err)
		}
		if err = revalidateMappingViews(r.Context(), view, parentView); err != nil {
			return catalogError(w, err)
		}
		return renderJSON(w, r, preview)
	})
}

var mediaMappingCommandHandler = providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body struct {
		Action      string `json:"action"`
		OperationID string `json:"operationId"`
		PreviewID   string `json:"previewId"`
	}
	if decodeMediaScanBody(w, r, &body) != nil || body.Action != "assign_series" || !validScanID(body.OperationID) || body.PreviewID == "" {
		return catalogError(w, medialibrary.ErrInvalid)
	}
	library, err := itemLibrary(r, d)
	if err != nil {
		return catalogError(w, err)
	}
	itemID := mux.Vars(r)["itemId"]
	request, err := d.store.MediaLibrary.MappingPreviewRequest(r.Context(), d.user.ID, library.ID, itemID, body.PreviewID)
	if err != nil {
		return catalogError(w, err)
	}
	view, parentView, err := authorizeSeriesMapping(r, d, request.SeriesID)
	if err != nil {
		return catalogError(w, err)
	}
	if err = revalidateMappingViews(r.Context(), view, parentView); err != nil {
		return catalogError(w, err)
	}
	result, err := d.store.MediaLibrary.ConfirmSeriesMapping(r.Context(), d.user.ID, body.OperationID, library.ID, itemID, body.PreviewID)
	if err != nil {
		return mappingError(w, err)
	}
	if err = revalidateMappingViews(r.Context(), view, parentView); err != nil {
		return providerUnknown(w)
	}
	return renderJSON(w, r, result)
})

var mediaMappingUndoHandler = providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body struct {
		OperationID     string `json:"operationId"`
		MappingRevision string `json:"mappingRevision"`
	}
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.MappingRevision == "" {
		return catalogError(w, medialibrary.ErrInvalid)
	}
	library, err := itemLibrary(r, d)
	if err != nil {
		return catalogError(w, err)
	}
	itemID := mux.Vars(r)["itemId"]
	view, _, err := authorizeSeriesMapping(r, d, "")
	if err != nil {
		return catalogError(w, err)
	}
	if err = view.revalidateSources(r.Context()); err != nil {
		return catalogError(w, err)
	}
	result, err := d.store.MediaLibrary.UndoSeriesMapping(r.Context(), d.user.ID, body.OperationID, library.ID, itemID, body.MappingRevision)
	if err != nil {
		return mappingError(w, err)
	}
	if err = view.revalidateSources(r.Context()); err != nil {
		return providerUnknown(w)
	}
	return renderJSON(w, r, result)
})
