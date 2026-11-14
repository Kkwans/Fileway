package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/archivefs"
	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/hls"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/tvupdate"
)

type modifyRequest struct {
	What            string   `json:"what"`             // Answer to: what data type?
	Which           []string `json:"which"`            // Answer to: which fields?
	CurrentPassword string   `json:"current_password"` // Answer to: user logged password
}

const appContentSecurityPolicy = `default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; media-src 'self' blob:; font-src 'self'; connect-src 'self';`

type archiveCacheHandler struct {
	http.Handler
	cache          *archivefs.EntryCache
	subtitlePages  *subtitlePages
	mediaScans     *mediascan.Scheduler
	mediaLeases    *mediaPlaybackLeases
	providerStarts *time.Ticker
}

func (handler *archiveCacheHandler) Close() error {
	handler.subtitlePages.close()
	handler.mediaLeases.close()
	err := handler.mediaScans.Close()
	handler.providerStarts.Stop()
	return errors.Join(err, handler.cache.Close())
}

func NewHandler(
	imgSvc ImgService,
	fileCache FileCache,
	uploadCache UploadCache,
	store *storage.Storage,
	server *settings.Server,
	assetsFs fs.FS,
	videoPreviewWorkers int,
	hlsService *hls.Service,
	tvUpdates *tvupdate.Service,
) (http.Handler, error) {
	if hlsService == nil {
		return nil, fmt.Errorf("HLS compatibility playback service is not configured")
	}
	server.Clean()
	mediaAssets := newMediaAssetService(hlsService.SourceAssetDirectory())
	taskRuntime, err := tasks.NewRuntime(store.Tasks)
	if err != nil {
		return nil, err
	}

	if err := initializeDirectoryPublications(store); err != nil {
		return nil, fmt.Errorf("load directory recovery journals: %w", err)
	}

	if err := store.Trash.RecoverSizes(); err != nil {
		return nil, err
	}
	entryCache, err := archivefs.NewEntryCache(archivefs.EntryCacheConfig{WorkDir: os.TempDir()})
	if err != nil {
		return nil, err
	}
	var mediaScans *mediascan.Scheduler
	providerStarts := time.NewTicker(500 * time.Millisecond)
	providerSlots := make(chan struct{}, 2)
	if store.MediaLibrary != nil {
		mediaScans, err = mediascan.NewScheduler(context.Background(), store.MediaLibrary, func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
			authority, _, err := resolveAttestedMediaConnection(ctx, store, server, binding.Source.OwnerUserID, binding.Source.ConfigurationRef, binding.RootLocator, binding.Proof)
			return authority, err
		}, newMetadataWorker(store, server, providerSlots, providerStarts.C))
		if err != nil {
			// The catalog may be in use by another process. Do not fence its jobs,
			// leak the catalog path, or disable the existing file service.
			log.Print("Media scan service unavailable")
		}
	}

	r := mux.NewRouter()
	r.Use(func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Security-Policy", appContentSecurityPolicy)
			next.ServeHTTP(w, r)
		})
	})
	index, static := getStaticHandlers(store, server, assetsFs)

	monkey := func(fn handleFunc, prefix string) http.Handler {
		return handle(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
			d.taskRuntime = taskRuntime
			d.fileCache = fileCache
			d.mediaAssets = mediaAssets
			return fn(w, r, d)
		}, prefix, store, server)
	}

	r.HandleFunc("/health", healthHandler)
	r.PathPrefix("/static").Handler(static)
	r.NotFoundHandler = index

	api := r.PathPrefix("/api").Subrouter()
	spriteService := newVideoSpriteService(fileCache)
	subtitlePages := newSubtitlePages()
	mediaLeases := newMediaPlaybackLeases()
	artworkPermits := make(chan struct{}, 2)

	api.Handle("/login", monkey(loginHandler(), ""))
	api.Handle("/signup", monkey(signupHandler, ""))
	api.Handle("/renew", monkey(renewHandler(), ""))
	api.Handle("/instance", monkey(deviceInstanceHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/scan-support", monkey(mediaScanSupportHandler(mediaScans), "")).Methods("GET")
	api.Handle("/media-library/v1/provider-status", monkey(mediaProviderStatusHandler(mediaScans), "")).Methods("GET")
	api.Handle("/media-library/v1/provider-connections", monkey(mediaProviderConnectionsHandler, "")).Methods("GET", "POST")
	api.Handle("/media-library/v1/provider-connections/{connectionId}", monkey(mediaProviderConnectionHandler, "")).Methods("PUT", "DELETE")
	api.Handle("/media-library/v1/provider-connections/{connectionId}/check", monkey(mediaProviderCheckHandler(providerSlots, providerStarts.C), "")).Methods("POST")
	api.Handle("/media-library/v1/provider-connections/{connectionId}/grants", monkey(mediaProviderGrantsHandler, "")).Methods("GET", "PUT", "DELETE")
	api.Handle("/media-library/v1/libraries/{id}/metadata-policy", monkey(mediaLibraryMetadataPolicyHandler, "")).Methods("GET", "PUT")
	api.Handle("/media-library/v1/home", monkey(mediaCatalogHomeHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/home/rows/{rowId}", monkey(mediaCatalogHomeRowHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/catalog/items", monkey(mediaCatalogItemsHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries", monkey(mediaLibraryListHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries", monkey(mediaLibraryCreateHandler, "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}", monkey(mediaLibraryGetHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}", monkey(mediaLibraryChangeHandler("update"), "")).Methods("PATCH")
	api.Handle("/media-library/v1/libraries/{id}/archive", monkey(mediaLibraryChangeHandler("archive"), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/restore", monkey(mediaLibraryChangeHandler("restore"), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/members/{userId:[0-9]+}", monkey(mediaLibraryMemberHandler(true), "")).Methods("PUT")
	api.Handle("/media-library/v1/libraries/{id}/members/{userId:[0-9]+}", monkey(mediaLibraryMemberHandler(false), "")).Methods("DELETE")
	api.Handle("/media-library/v1/libraries/{id}/sources", monkey(mediaLibrarySourcesHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/sources/local", monkey(mediaLibraryCreateSourceHandler, "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/sources/{bindingId}/attest", monkey(mediaLibraryAttestSourceHandler, "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/items", monkey(mediaItemsListHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}", monkey(mediaItemGetHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/user-state", monkey(mediaUserStateHandler, "")).Methods("GET", "PATCH")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/identification", monkey(mediaIdentificationHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/match-candidates", monkey(mediaMatchCandidatesHandler(providerSlots, providerStarts.C), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/match-confirmation", monkey(mediaMatchConfirmHandler(providerSlots, providerStarts.C), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/match-confirmation/undo", monkey(mediaMatchUndoHandler, "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/match-fill", monkey(mediaMatchFillHandler(providerSlots, providerStarts.C), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/seasons", monkey(mediaCatalogSeasonsHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/episodes", monkey(mediaCatalogEpisodesHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/episode-counts", monkey(mediaCatalogEpisodeCountsHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/variants", monkey(mediaVariantsHandler, "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/artwork/{artworkId}", monkey(mediaArtworkHandler(artworkPermits), "")).Methods("GET", "HEAD")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/playback-resolutions", monkey(mediaPlaybackResolutionHandler(mediaLeases), "")).Methods("POST")
	api.Handle("/media-library/v1/playback-sessions/{session}/renew", monkey(mediaPlaybackRenewHandler(mediaLeases), "")).Methods("POST")
	api.Handle("/media-library/v1/playback-sessions/{session}", monkey(mediaPlaybackCloseHandler(mediaLeases), "")).Methods("DELETE")
	api.Handle("/media-library/v1/streams/{lease}/original", monkey(mediaPlaybackStreamHandler(mediaLeases), "")).Methods("GET", "HEAD")
	for _, kind := range []string{"credits", "ratings", "artwork", "external-ids", "issues"} {
		api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/"+kind, monkey(mediaItemCollectionHandler(kind), "")).Methods("GET")
	}
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/metadata", monkey(mediaMetadataChangeHandler(false), "")).Methods("PATCH")
	api.Handle("/media-library/v1/libraries/{id}/items/{itemId}/metadata/undo", monkey(mediaMetadataChangeHandler(true), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/scans", monkey(mediaScanCreateHandler(mediaScans), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/scans", monkey(mediaScanManagement(mediaScanListHandler), "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/scan-batches", monkey(mediaScanBatchHandler(mediaScans), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/scans/{scanId}", monkey(mediaScanManagement(mediaScanGetHandler), "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/scans/{scanId}/metadata", monkey(mediaScanManagement(mediaMetadataJobHandler(mediaScans)), "")).Methods("GET", "DELETE", "POST")
	api.Handle("/media-library/v1/libraries/{id}/scans/{scanId}/metadata/review", monkey(mediaScanManagement(mediaMetadataReviewHandler), "")).Methods("GET")
	api.Handle("/media-library/v1/libraries/{id}/scans/{scanId}/cancel", monkey(mediaScanCancelHandler(mediaScans), "")).Methods("POST")
	api.Handle("/media-library/v1/libraries/{id}/scans/{scanId}/retry", monkey(mediaScanRetryHandler(mediaScans), "")).Methods("POST")
	api.Handle("/device-auth/instance-proof", monkey(devicePublic(deviceProofHandler), "")).Methods("POST")
	api.Handle("/device-auth/requests", monkey(devicePublic(deviceRequestHandler), "")).Methods("POST")
	api.Handle("/device-auth/requests/{id}/approve", monkey(deviceApproveHandler, "")).Methods("POST")
	api.Handle("/device-auth/exchange", monkey(devicePublic(deviceCredentialHandler(false)), "")).Methods("POST")
	api.Handle("/device-auth/refresh", monkey(devicePublic(deviceCredentialHandler(true)), "")).Methods("POST")
	api.Handle("/device-sessions", monkey(deviceSessionsHandler, "")).Methods("GET")
	api.Handle("/device-sessions/{id}", monkey(deviceSessionsHandler, "")).Methods("DELETE")

	users := api.PathPrefix("/users").Subrouter()
	users.Handle("", monkey(usersGetHandler, "")).Methods("GET")
	users.Handle("", monkey(userPostHandler, "")).Methods("POST")
	users.Handle("/{id:[0-9]+}", monkey(userPutHandler, "")).Methods("PUT")
	users.Handle("/{id:[0-9]+}", monkey(userGetHandler, "")).Methods("GET")
	users.Handle("/{id:[0-9]+}", monkey(userDeleteHandler, "")).Methods("DELETE")

	api.Handle("/resources/batch", monkey(resourceBatchHandler, "")).Methods("POST")
	api.Handle("/resources/batch-rename", monkey(resourceBatchRenameHandler(fileCache), "")).Methods("POST")
	api.Handle("/resources/transfer", monkey(fileTransferTaskHandler(taskRuntime), "")).Methods("POST")
	api.Handle(subtitlePagesRoute, monkey(subtitlePages.pageHandler(), "")).Methods("POST")
	api.PathPrefix(subtitleFileRoute+"/").Handler(monkey(subtitlePages.fileHandler(), "/api"+subtitleFileRoute)).Methods("GET", "HEAD")
	api.Handle("/deletions/pending", monkey(pendingDeletionHandler(taskRuntime), "")).Methods("POST")
	api.PathPrefix("/resources/recursive").Handler(monkey(resourceGetRecursiveHandler, "/api/resources/recursive")).Methods("GET")
	api.PathPrefix("/resources").Handler(monkey(resourceGetHandler, "/api/resources")).Methods("GET")
	api.PathPrefix("/resources").Handler(monkey(resourceDeleteHandler(fileCache), "/api/resources")).Methods("DELETE")
	api.PathPrefix("/resources").Handler(monkey(withTransferCancellation(resourcePostHandler(fileCache), transfers.KindUpload), "/api/resources")).Methods("POST")
	api.PathPrefix("/resources").Handler(monkey(resourcePutHandler, "/api/resources")).Methods("PUT")
	api.PathPrefix("/resources").Handler(monkey(resourcePatchHandler(fileCache), "/api/resources")).Methods("PATCH")

	api.Handle("/trash", monkey(trashListHandler, "")).Methods("GET")
	api.Handle("/trash", monkey(trashClearHandler(taskRuntime), "")).Methods("DELETE")
	api.Handle("/trash/{id}/size", monkey(trashSizeHandler(taskRuntime), "")).Methods("POST")
	api.Handle("/trash/{id}/restore", monkey(trashRestoreHandler, "")).Methods("POST")
	api.Handle("/trash/{id}", monkey(trashDeleteHandler, "")).Methods("DELETE")

	api.Handle("/tasks", monkey(taskListHandler, "")).Methods("GET")
	api.Handle("/tasks", monkey(taskDeleteRecordsHandler, "")).Methods("DELETE")
	api.Handle("/tasks/batch", monkey(taskBatchHandler(taskRuntime, hlsService), "")).Methods("POST")
	api.Handle("/tasks/{id}", monkey(taskGetHandler, "")).Methods("GET")
	api.Handle("/tasks/{id}/cancel", monkey(taskCancelHandler(taskRuntime), "")).Methods("POST")
	api.Handle("/tasks/{id}/retry", monkey(taskRetryHandler(taskRuntime, hlsService), "")).Methods("POST")
	api.Handle("/tasks/{id}/archive", monkey(taskArchiveHandler(true), "")).Methods("POST")
	api.Handle("/tasks/{id}/unarchive", monkey(taskArchiveHandler(false), "")).Methods("POST")
	api.Handle("/task-center/events", monkey(taskCenterEventsHandler, "")).Methods("GET")
	api.Handle("/transfers", monkey(transferListHandler, "")).Methods("GET")
	api.Handle("/transfers", monkey(transferDeleteAllHandler, "")).Methods("DELETE")
	api.Handle("/transfers/downloads", monkey(transferDownloadCreateHandler, "")).Methods("POST")
	api.Handle("/transfers/{id}", monkey(transferGetHandler, "")).Methods("GET")
	api.Handle("/transfers/{id}/cancel", monkey(transferCancelHandler, "")).Methods("POST")
	api.Handle("/transfers/{id}", monkey(transferDeleteHandler, "")).Methods("DELETE")
	api.Handle("/analysis/duplicates", monkey(duplicateAnalysisStartHandler(taskRuntime), "")).Methods("POST")
	api.Handle("/analysis/duplicates/{id}/cleanup", monkey(duplicateCleanupStartHandler(taskRuntime), "")).Methods("POST")
	api.Handle("/analysis/duplicates/{id}/cleanup", monkey(duplicateCleanupForReportHandler, "")).Methods("GET")
	api.Handle("/analysis/duplicates/cleanup/{id}", monkey(duplicateCleanupResultHandler, "")).Methods("GET")
	api.Handle("/analysis/storage", monkey(storageAnalysisStartHandler(taskRuntime), "")).Methods("POST")
	api.Handle("/analysis/recent", monkey(analysisRecentHandler, "")).Methods("GET")
	api.Handle("/analysis/storage/{id}", monkey(storageAnalysisResultHandler, "")).Methods("GET")
	api.Handle("/analysis/{id}", monkey(analysisResultHandler, "")).Methods("GET")
	api.Handle("/archives/entries", monkey(archiveEntriesHandler, "")).Methods("GET")
	api.Handle("/archives/open", monkey(archiveEntryPrepareHandler(entryCache), "")).Methods("POST")
	api.Handle("/archives/open/{id}/content", monkey(archiveEntryContentHandler(entryCache), "")).Methods("GET", "HEAD")
	api.Handle("/archives/open/{id}", monkey(archiveEntryStatusHandler(entryCache), "")).Methods("GET")
	api.Handle("/archives/open/{id}", monkey(archiveEntryCancelHandler(entryCache), "")).Methods("DELETE")
	api.Handle("/archives/extractions", monkey(archiveExtractStartHandler(taskRuntime), "")).Methods("POST")
	api.Handle("/archives/extractions/{id}", monkey(archiveExtractResultHandler, "")).Methods("GET")
	api.Handle("/history", monkey(historyListHandler, "")).Methods("GET")
	api.Handle("/history", monkey(historyDeleteAllHandler, "")).Methods("DELETE")
	api.Handle("/history/delete", monkey(historyDeleteSelectedHandler, "")).Methods("POST")
	api.Handle("/recent", monkey(recentListHandler, "")).Methods("GET")
	api.Handle("/recent", monkey(recentRecordHandler, "")).Methods("POST")
	api.Handle("/recent/delete", monkey(recentDeleteHandler, "")).Methods("POST")
	api.Handle("/recent", monkey(recentClearHandler, "")).Methods("DELETE")
	api.Handle("/media/playback/delete", monkey(playbackSelectedDeleteHandler, "")).Methods("POST")
	api.Handle("/media/playback/recent", monkey(playbackClearHandler, "")).Methods("DELETE")
	api.Handle("/media/playback", monkey(playbackGetHandler, "")).Methods("GET")
	api.Handle("/media/playback/recent", monkey(playbackRecentHandler, "")).Methods("GET")
	api.Handle("/media/playback", monkey(playbackPutHandler, "")).Methods("PUT")
	api.Handle("/media/playback", monkey(playbackDeleteHandler, "")).Methods("DELETE")
	api.Handle("/media/attachments/{id}", monkey(mediaAttachmentHandler, "")).Methods("GET")
	api.Handle("/media/info", monkey(mediaInfoHandler(defaultMediaProbe), "")).Methods("GET")
	api.Handle("/media/transcodes", monkey(mediaTranscodesHandler(hlsService, taskRuntime), "")).Methods("POST")
	api.Handle("/media/transcodes/{id}/playback", monkey(mediaTranscodePlaybackHandler(hlsService), "")).Methods("GET")
	api.Handle("/media/transcodes/{id}/assets/{asset}", monkey(mediaTranscodeAssetHandler(hlsService), "")).Methods("GET")
	api.Handle("/media/sprite", monkey(spriteService.metaHandler(), "")).Methods("GET")
	api.Handle("/media/sprite.jpg", monkey(spriteService.imageHandler(), "")).Methods("GET")
	api.Handle("/media/hls", monkey(mediaHLSStartHandler(hlsService, taskRuntime), "")).Methods("POST")
	api.Handle("/media/hls/{id:[a-f0-9]{64}}", monkey(mediaHLSGetHandler(hlsService), "")).Methods("GET")
	api.Handle("/media/hls/{id:[a-f0-9]{64}}/cancel", monkey(mediaHLSCancelHandler(hlsService, taskRuntime), "")).Methods("POST")
	api.Handle("/media/hls/{id:[a-f0-9]{64}}/{asset}", monkey(mediaHLSAssetHandler(hlsService), "")).Methods("GET")

	api.PathPrefix("/tus").Handler(monkey(tusPostHandler(uploadCache), "/api/tus")).Methods("POST")
	api.PathPrefix("/tus").Handler(monkey(tusHeadHandler(uploadCache), "/api/tus")).Methods("HEAD", "GET")
	api.PathPrefix("/tus").Handler(monkey(withTransferCancellation(tusPatchHandler(uploadCache), transfers.KindUpload), "/api/tus")).Methods("PATCH")
	api.PathPrefix("/tus").Handler(monkey(tusDeleteHandler(uploadCache), "/api/tus")).Methods("DELETE")

	api.PathPrefix("/usage").Handler(monkey(diskUsage, "/api/usage")).Methods("GET")

	api.Handle("/shares", monkey(shareListHandler, "")).Methods("GET")
	api.PathPrefix("/share").Handler(monkey(shareGetsHandler, "/api/share")).Methods("GET")
	api.PathPrefix("/share").Handler(monkey(sharePostHandler, "/api/share")).Methods("POST")
	api.PathPrefix("/share").Handler(monkey(shareDeleteHandler, "/api/share")).Methods("DELETE")

	api.Handle("/settings", monkey(settingsGetHandler, "")).Methods("GET")
	capabilities := clientCapabilitiesHandler
	if tvUpdates != nil {
		capabilities = clientCapabilities(1)
		api.Handle(tvUpdateCheckPath, monkey(tvUpdateCheckHandler(tvUpdates), "")).Methods("GET")
		api.Handle(tvUpdateDownloadsPath, monkey(tvUpdateStartHandler(tvUpdates), "")).Methods("POST")
		api.Handle(tvUpdateDownloadsPath+"/"+tvUpdateHandle, monkey(tvUpdateStatusHandler(tvUpdates), "")).Methods("GET")
		api.Handle(tvUpdateDownloadsPath+"/"+tvUpdateHandle+"/content", monkey(tvUpdateContentHandler(tvUpdates), "")).Methods("GET", "HEAD")
	}
	api.Handle("/client-capabilities", monkey(capabilities, "")).Methods("GET")
	api.Handle("/settings", monkey(settingsPutHandler, "")).Methods("PUT")

	api.PathPrefix("/raw").Handler(monkey(withTransferCancellation(rawHandler, transfers.KindDownload), "/api/raw")).Methods("GET")
	api.PathPrefix("/preview/{size}/{path:.*}").
		Handler(monkey(previewHandler(imgSvc, fileCache, server.EnableThumbnails, server.ResizePreview, videoPreviewWorkers), "/api/preview")).Methods("GET")
	api.PathPrefix("/command").Handler(monkey(commandsHandler, "/api/command")).Methods("GET")
	api.PathPrefix("/search").Handler(monkey(searchHandler, "/api/search")).Methods("GET")
	api.PathPrefix("/subtitle").Handler(monkey(subtitleHandler, "/api/subtitle")).Methods("GET")

	api.Handle("/favorites", monkey(favoritesGetHandler, "")).Methods("GET")
	api.Handle("/favorites", monkey(favoritesPostHandler, "")).Methods("POST")
	api.Handle("/favorites/reorder", monkey(favoritesReorderHandler, "")).Methods("PUT")
	api.Handle("/favorites/{id}", monkey(favoritePutHandler, "")).Methods("PUT")
	api.Handle("/favorites/{id}", monkey(favoriteDeleteHandler, "")).Methods("DELETE")

	api.Handle("/favorites/groups", monkey(favoriteGroupsGetHandler, "")).Methods("GET")
	api.Handle("/favorites/groups", monkey(favoriteGroupsPostHandler, "")).Methods("POST")
	api.Handle("/favorites/groups/reorder", monkey(favoriteGroupsReorderHandler, "")).Methods("PUT")
	api.Handle("/favorites/groups/{id}", monkey(favoriteGroupPutHandler, "")).Methods("PUT")
	api.Handle("/favorites/groups/{id}", monkey(favoriteGroupDeleteHandler, "")).Methods("DELETE")

	api.Handle("/tags", monkey(tagsGetHandler, "")).Methods("GET")
	api.Handle("/tags", monkey(tagsPostHandler, "")).Methods("POST")
	api.Handle("/tags/{id}", monkey(tagPutHandler, "")).Methods("PUT")
	api.Handle("/tags/{id}", monkey(tagDeleteHandler, "")).Methods("DELETE")
	api.Handle("/tags/{id}/paths", monkey(tagAddPathHandler, "")).Methods("POST")
	api.Handle("/tags/{id}/paths", monkey(tagRemovePathHandler, "")).Methods("DELETE")

	api.Handle("/volumes", monkey(volumesHandler, "")).Methods("GET")
	api.Handle("/categories", monkey(categoriesHandler, "")).Methods("GET")
	api.Handle("/classify", monkey(classifyHandler, "")).Methods("GET")

	public := api.PathPrefix("/public").Subrouter()
	public.PathPrefix("/dl").Handler(monkey(publicDlHandler, "/api/public/dl/")).Methods("GET")
	public.PathPrefix("/share").Handler(monkey(publicShareHandler, "/api/public/share/")).Methods("GET")

	return &archiveCacheHandler{Handler: stripPrefix(server.BaseURL, r), cache: entryCache, subtitlePages: subtitlePages, mediaScans: mediaScans, mediaLeases: mediaLeases, providerStarts: providerStarts}, nil
}
