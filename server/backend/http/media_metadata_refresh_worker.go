package fbhttp

import (
	"context"
	"errors"
	"reflect"
	"slices"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
)

func newMetadataRefreshWorker(store *storage.Storage, server *settings.Server, slots chan struct{}, starts <-chan time.Time) mediascan.RunMetadataRefreshJob {
	if store.MediaProviderVault == nil || store.TMDBHTTPClient == nil {
		return nil
	}
	return func(ctx context.Context, coordinator *medialibrary.ScanCoordinator, job medialibrary.MetadataRefreshJob) {
		finish := func(status, reason string, retry *time.Time) {
			if ctx.Err() == nil {
				_ = coordinator.FinishMetadataRefreshJob(ctx, job, status, reason, retry)
			}
		}
		for ctx.Err() == nil {
			target, err := coordinator.NextMetadataRefreshJobTarget(ctx, job)
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
			configuration, err := store.Settings.Get()
			if err != nil {
				finish("failed", "catalog_unavailable", nil)
				return
			}
			d := &data{store: store, server: server, settings: configuration, user: owner}
			item, view, err := identifyCatalogItem(ctx, d, library, target.ItemID)
			if err != nil {
				finish("failed", "catalog_unavailable", nil)
				return
			}
			if item.ItemRevision != target.ItemRevision || item.StructureRevision != target.StructureRevision {
				if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "source_changed", "item_changed"); resolveErr != nil {
					finish("failed", "metadata_unavailable", nil)
				}
				job.Attempt = 0
				continue
			}
			match, err := store.MediaLibrary.VisibleProviderMatch(ctx, job.OwnerUserID, job.LibraryID, target.ItemID, item.ItemRevision)
			if err != nil || match == nil {
				if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "source_changed", "match_changed"); resolveErr != nil {
					finish("failed", "metadata_unavailable", nil)
				}
				job.Attempt = 0
				continue
			}
			snapshot, err := beginCatalogSnapshot(ctx, d, job.LibraryID)
			if err != nil {
				finish("failed", "catalog_unavailable", nil)
				return
			}
			if err := view.revalidateSources(ctx); err != nil {
				finish("failed", "source_unavailable", nil)
				return
			}
			fence, sealed, err := store.MediaLibrary.GetProviderCredentialForUse(ctx, job.OwnerUserID, job.LibraryID, job.Authority.ConnectionID)
			if err != nil || *fence != job.Authority {
				finish("failed", "configuration_changed", nil)
				return
			}
			scope, err := medialibrary.ProviderCredentialContext(job.Authority.ConnectionID)
			if err != nil {
				finish("failed", "configuration_changed", nil)
				return
			}
			client := mediaprovider.NewTMDBClient(store.TMDBHTTPClient, func(context.Context) (string, error) {
				return store.MediaProviderVault.Open(scope, sealed)
			})
			select {
			case <-ctx.Done():
				return
			case slots <- struct{}{}:
			}
			document, fetchErr := client.Fetch(ctx, mediaprovider.EntityRef{Provider: "tmdb", Kind: mediaprovider.Kind(match.Kind), ID: match.ExternalID},
				mediaprovider.FetchOptions{Language: job.Language, Parts: []mediaprovider.Part{mediaprovider.Credits, mediaprovider.ExternalIDs, mediaprovider.AlternativeTitles}})
			<-slots
			if fetchErr != nil {
				var upstream *mediaprovider.Error
				switch {
				case errors.As(fetchErr, &upstream) && upstream.Retryable && job.Attempt < 3:
					retry := time.Now().Add(15 * time.Second)
					if upstream.RetryAfter.After(retry) {
						retry = upstream.RetryAfter
					}
					finish("queued", string(upstream.Code), &retry)
					return
				case errors.As(fetchErr, &upstream) && upstream.Code == mediaprovider.NotFound:
					if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "source_changed", "provider_not_found"); resolveErr != nil {
						finish("failed", "metadata_unavailable", nil)
					}
				case errors.As(fetchErr, &upstream):
					if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "failed", string(upstream.Code)); resolveErr != nil {
						finish("failed", "metadata_unavailable", nil)
					}
				default:
					finish("failed", "metadata_unavailable", nil)
					return
				}
				job.Attempt = 0
				continue
			}
			projection, err := mediaprovider.ProjectTMDB(document)
			if err != nil {
				if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "failed", "invalid_provider_document"); resolveErr != nil {
					finish("failed", "metadata_unavailable", nil)
				}
				job.Attempt = 0
				continue
			}
			matchDocument, err := withProviderEpisodeDocuments(ctx, store.MediaLibrary, job.OwnerUserID, job.LibraryID, target.ItemID, match.ExternalID, client, job.Language,
				medialibrary.ProviderMatchDocument{Item: projection.Item, PosterPath: projection.PosterPath, BackdropPath: projection.BackdropPath})
			if err != nil {
				if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "failed", "episode_metadata_unavailable"); resolveErr != nil {
					finish("failed", "metadata_unavailable", nil)
				}
				job.Attempt = 0
				continue
			}
			if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
				if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "source_changed", "catalog_changed"); resolveErr != nil {
					finish("failed", "metadata_unavailable", nil)
				}
				job.Attempt = 0
				continue
			}
			if err := view.revalidateSources(ctx); err != nil {
				if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "source_changed", "source_changed"); resolveErr != nil {
					finish("failed", "metadata_unavailable", nil)
				}
				job.Attempt = 0
				continue
			}
			states, err := store.MediaLibrary.MetadataRefreshFieldStates(ctx, job.OwnerUserID, job.LibraryID, target.ItemID)
			if err != nil {
				finish("failed", "metadata_unavailable", nil)
				return
			}
			changed := changedMetadataFields(match.Document.Item.Fields, projection.Item.Fields)
			if match.Audit.Language != job.Language {
				changed = append(changed, "language")
			}
			if !reflect.DeepEqual(match.Document.Item.Credits, projection.Item.Credits) {
				changed = append(changed, "credits")
			}
			if match.Document.PosterPath != projection.PosterPath || match.Document.BackdropPath != projection.BackdropPath {
				changed = append(changed, "artwork")
			}
			protected, preserved := []string{}, []string{}
			for name, state := range states {
				if state.Locked || state.ExplicitClear {
					protected = append(protected, name)
				} else if state.Source == "nfo" && job.Mode != medialibrary.MetadataOnlineFirst {
					preserved = append(preserved, name)
				}
			}
			effective := changed[:0]
			for _, name := range changed {
				if !slices.Contains(protected, name) && !slices.Contains(preserved, name) {
					effective = append(effective, name)
				}
			}
			seasonsChanged := !reflect.DeepEqual(match.Document.Seasons, matchDocument.Seasons)
			if seasonsChanged {
				changed = append(changed, "seasons")
			}
			episodesChanged := !reflect.DeepEqual(match.Document.Episodes, matchDocument.Episodes)
			if len(effective) == 0 && !seasonsChanged && !episodesChanged {
				outcome := "already_current"
				if len(changed) != 0 {
					outcome = "preserved"
				}
				if err := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, outcome, ""); err != nil {
					finish("failed", "metadata_unavailable", nil)
					return
				}
				job.Attempt = 0
				continue
			}
			kind := match.Kind
			request := medialibrary.ProviderMatchRequest{LibraryID: job.LibraryID, ItemID: target.ItemID, Kind: kind,
				ExternalID: match.ExternalID, ItemRevision: target.ItemRevision, StructureRevision: target.StructureRevision}
			_, err = coordinator.CommitMetadataRefreshTarget(ctx, job, target, request, document.Language, document.FetchedAt.UnixMilli(),
				matchDocument,
				medialibrary.ProviderMatchAudit{Source: "refresh", Region: job.Region, AdapterVersion: "tmdb-v1", Evidence: []string{"language_refresh"}})
			if err != nil {
				if errors.Is(err, medialibrary.ErrRevisionConflict) || errors.Is(err, medialibrary.ErrNotFound) {
					if resolveErr := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, target.ItemID, "source_changed", "item_changed"); resolveErr != nil {
						finish("failed", "metadata_unavailable", nil)
						return
					}
				} else {
					finish("failed", "metadata_unavailable", nil)
					return
				}
			} else if current, checkErr := store.MediaLibrary.CheckProviderUse(ctx, job.OwnerUserID, job.LibraryID, job.Authority.ConnectionID); checkErr != nil ||
				*current != job.Authority || verifyCatalogSnapshot(ctx, d, snapshot) != nil || view.revalidateSources(ctx) != nil {
				finish("failed", "provider_result_unknown", nil)
				return
			}
			job.Attempt = 0
		}
	}
}
