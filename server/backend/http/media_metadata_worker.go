package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/gorilla/mux"
)

func newMetadataWorker(store *storage.Storage, server *settings.Server, slots chan struct{}, starts <-chan time.Time) mediascan.RunMetadataJob {
	if store.MediaProviderVault == nil || store.TMDBHTTPClient == nil {
		return nil
	}
	return func(ctx context.Context, coordinator *medialibrary.ScanCoordinator, job medialibrary.MetadataJob) {
		finish := func(status, reason string, retry *time.Time) {
			if ctx.Err() == nil {
				_ = coordinator.FinishMetadataJob(ctx, job, status, reason, retry)
			}
		}
		for ctx.Err() == nil {
			target, err := coordinator.NextMetadataJobTarget(ctx, job)
			if errors.Is(err, medialibrary.ErrNotFound) {
				finish("completed", "", nil)
				return
			}
			if err != nil {
				finish("failed", "catalog_unavailable", nil)
				return
			}
			owner, err := store.Users.Get(server.Root, job.OwnerUserID)
			if err != nil || owner == nil {
				finish("failed", "owner_unavailable", nil)
				return
			}
			library, err := store.MediaLibrary.GetLibrary(ctx, job.OwnerUserID, job.LibraryID)
			if err != nil || library.ArchivedAt != nil || library.Role != medialibrary.RoleOwner {
				finish("failed", "library_unavailable", nil)
				return
			}
			authority, err := store.MediaLibrary.CheckProviderUse(ctx, job.OwnerUserID, job.LibraryID, job.Authority.ConnectionID)
			if err != nil || *authority != job.Authority {
				finish("failed", "configuration_changed", nil)
				return
			}
			scan, err := store.MediaLibrary.GetScan(ctx, job.OwnerUserID, job.ScanID)
			if err != nil || scan.Status != "completed" || scan.Generation != job.ScanGeneration {
				finish("failed", "source_changed", nil)
				return
			}
			configuration, err := store.Settings.Get()
			if err != nil {
				finish("failed", "catalog_unavailable", nil)
				return
			}
			d := &data{store: store, server: server, settings: configuration, user: owner}
			if _, err := mediascan.CheckBinding(ctx, store.MediaLibrary, job.OwnerUserID, scan.Binding, mediaScanResolver(d)); err != nil {
				finish("failed", "source_changed", nil)
				return
			}
			select {
			case <-ctx.Done():
				return
			case slots <- struct{}{}:
			}
			result, err := fillCatalogMatch(ctx, d, library, target.ItemID, job.ID+"_"+target.ItemID,
				target.ItemRevision, target.StructureRevision, starts, &metadataFillLease{coordinator, job})
			<-slots
			if err != nil {
				var upstream *mediaprovider.Error
				switch {
				case errors.Is(err, errProviderResultUnknown):
					// The work and checkpoint committed together. If canceled,
					// the next coordinator query simply finds no active lease.
					job.Attempt = 1
					continue
				case errors.As(err, &upstream) && upstream.Retryable && job.Attempt < 3:
					retry := time.Now().Add(15 * time.Second)
					if upstream.RetryAfter.After(retry) {
						retry = upstream.RetryAfter
					}
					finish("queued", string(upstream.Code), &retry)
					return
				case upstream != nil && (upstream.Code == mediaprovider.InvalidResponse || upstream.Code == mediaprovider.ResponseTooLarge):
					result.State, result.Reason = "failed", string(upstream.Code)
				case upstream != nil:
					finish("failed", string(upstream.Code), nil)
					return
				case errors.Is(err, medialibrary.ErrNotFound), errors.Is(err, medialibrary.ErrRevisionConflict):
					result.State, result.Reason = "source_changed", "item_changed"
				default:
					finish("failed", "metadata_unavailable", nil)
					return
				}
			}
			if result.State == "local_only" {
				finish("failed", "configuration_changed", nil)
				return
			}
			if result.State != "matched" {
				if err := coordinator.ResolveMetadataJobTarget(ctx, job, target.ItemID, result.State, result.Reason); err != nil {
					finish("failed", "metadata_unavailable", nil)
					return
				}
			}
			job.Attempt = 1
		}
	}
}

func mediaMetadataJobHandler(scheduler *mediascan.Scheduler) handleFunc {
	return func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		job, err := d.store.MediaLibrary.MetadataJobForScan(r.Context(), d.user.ID, library.ID, mux.Vars(r)["scanId"])
		if err != nil {
			return catalogError(w, err)
		}
		w.Header().Set("Cache-Control", "no-store")
		if r.Method == http.MethodDelete || r.Method == http.MethodPost {
			generation, err := strconv.ParseInt(r.URL.Query().Get("generation"), 10, 64)
			if err != nil || generation < 1 {
				return catalogError(w, medialibrary.ErrInvalid)
			}
			if scheduler == nil {
				return http.StatusServiceUnavailable, fmt.Errorf("后台扫描服务暂不可用")
			}
			if r.Method == http.MethodDelete {
				err = scheduler.CancelMetadataJob(r.Context(), d.user.ID, library.ID, job.ID, generation)
			} else {
				scan, lookup := d.store.MediaLibrary.GetScan(r.Context(), d.user.ID, job.ScanID)
				if lookup != nil {
					return mediaScanError(lookup)
				}
				if _, check := mediascan.CheckBinding(r.Context(), d.store.MediaLibrary, d.user.ID, scan.Binding, mediaScanResolver(d)); check != nil {
					return mediaScanError(check)
				}
				err = scheduler.RetryMetadataJob(r.Context(), d.user.ID, job, generation)
			}
			if err != nil {
				if errors.Is(err, medialibrary.ErrCoordinatorUnavailable) {
					return http.StatusServiceUnavailable, fmt.Errorf("后台扫描服务暂不可用")
				}
				return catalogError(w, err)
			}
			return http.StatusNoContent, nil
		}
		return renderJSON(w, r, struct {
			Status      string `json:"status"`
			FailureCode string `json:"failureCode,omitempty"`
			Generation  int64  `json:"generation"`
			Matched     int64  `json:"matched"`
			Review      int64  `json:"review"`
			Skipped     int64  `json:"skipped"`
			Failed      int64  `json:"failed"`
			Pending     int64  `json:"pending"`
		}{job.Status, job.FailureCode, job.Generation, job.Matched, job.Review, job.Skipped, job.Failed, job.Pending})
	}
}

var mediaMetadataReviewHandler = func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	ctx := r.Context()
	library, err := itemLibrary(r, d)
	if err != nil {
		return catalogError(w, err)
	}
	limit, err := providerPageLimit(r)
	after := r.URL.Query().Get("cursor")
	if err != nil || len(after) > 128 {
		return catalogError(w, medialibrary.ErrInvalid)
	}
	job, err := d.store.MediaLibrary.MetadataJobForScan(ctx, d.user.ID, library.ID, mux.Vars(r)["scanId"])
	if err != nil {
		return catalogError(w, err)
	}
	snapshot, err := beginCatalogSnapshot(ctx, d, library.ID)
	if err != nil {
		return catalogError(w, err)
	}
	candidates, next, err := d.store.MediaLibrary.ListMetadataReviewTargets(ctx, d.user.ID, job, after, limit)
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	type reviewRow struct {
		medialibrary.MetadataReviewTarget
		Title string `json:"title"`
	}
	items := make([]reviewRow, 0, len(candidates))
	for index, item := range candidates {
		detail, err := d.store.MediaLibrary.GetCatalogItem(ctx, d.user.ID, library.ID, item.ItemID)
		if errors.Is(err, medialibrary.ErrNotFound) {
			continue
		}
		if err != nil {
			return catalogError(w, err)
		}
		access, visible, complete, _, err := view.itemAccess(ctx, library, detail, nil)
		if err != nil {
			return catalogError(w, err)
		}
		if !complete || view.budget.remaining <= catalogContinuationReserve {
			if index == 0 {
				return catalogError(w, errCatalogWorkPending)
			}
			next = candidates[index-1].ItemID
			break
		}
		if visible {
			title := access.fallbackTitle
			if card, cardErr := view.structureCard(ctx, library, item.ItemID, access); cardErr == nil {
				title = card.Title
			} else if !errors.Is(cardErr, errCatalogWorkPending) {
				return catalogError(w, cardErr)
			}
			items = append(items, reviewRow{item, title})
		}
	}
	if err := view.revalidateSources(ctx); err != nil {
		return catalogError(w, err)
	}
	if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
		return catalogError(w, err)
	}
	w.Header().Set("Cache-Control", "no-store")
	return renderJSON(w, r, struct {
		Items []reviewRow `json:"items"`
		Next  string      `json:"nextCursor,omitempty"`
	}{items, next})
}
