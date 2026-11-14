package fbhttp

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

const tmdbArtworkPrefix = "tmdb_"

func tmdbCatalogArtwork(libraryID, itemID string, itemRevision int64, match *medialibrary.ProviderMatch, role mediaprovider.ArtworkKind) *catalogArtwork {
	if match == nil || tmdbArtworkPath(match, role) == "" {
		return nil
	}
	id := tmdbArtworkPrefix + string(role) + "_" + strconv.FormatInt(match.Revision, 10)
	return &catalogArtwork{AssetID: id, Revision: itemRevision, Role: string(role),
		Endpoint: "/api/media-library/v1/libraries/" + libraryID + "/items/" + itemID + "/artwork/" + id}
}

func tmdbArtworkPath(match *medialibrary.ProviderMatch, role mediaprovider.ArtworkKind) string {
	switch role {
	case mediaprovider.Poster:
		return match.Document.PosterPath
	case mediaprovider.Backdrop:
		return match.Document.BackdropPath
	default:
		return ""
	}
}

func serveTMDBArtwork(w http.ResponseWriter, r *http.Request, d *data, library *medialibrary.Library, itemID, artID string, flights *previewCoordinator) (int, error) {
	role, _, _ := strings.Cut(strings.TrimPrefix(artID, tmdbArtworkPrefix), "_")
	kind := mediaprovider.ArtworkKind(role)
	if kind != mediaprovider.Poster && kind != mediaprovider.Backdrop {
		return mediaAccessError(fs.ErrPermission)
	}
	ctx := r.Context()
	snapshot, err := beginCatalogSnapshot(ctx, d, library.ID)
	if err != nil {
		return mediaAccessError(err)
	}
	item, err := d.store.MediaLibrary.GetCatalogItem(ctx, d.user.ID, library.ID, itemID)
	if err != nil {
		return mediaAccessError(err)
	}
	view := newCatalogView(d)
	access, visible, complete, _, err := view.itemAccess(ctx, library, item, nil)
	if err != nil || !visible || !complete {
		return mediaAccessError(err)
	}
	store := d.store.MediaLibrary
	match, err := store.VisibleProviderMatch(ctx, d.user.ID, library.ID, itemID, item.Revision)
	if err != nil {
		return mediaAccessError(err)
	}
	art := tmdbCatalogArtwork(library.ID, itemID, item.Revision, match, kind)
	if art == nil || art.AssetID != artID {
		return mediaAccessError(fs.ErrPermission)
	}
	check := func() error {
		if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
			return err
		}
		if err := view.revalidateSources(ctx); err != nil {
			return err
		}
		revision, _, superseded, err := store.GetBrowseItemFence(ctx, d.user.ID, library.ID, itemID)
		if err == nil && (superseded || revision != item.Revision) {
			return medialibrary.ErrRevisionConflict
		}
		return err
	}
	if err := check(); err != nil {
		return mediaAccessError(err)
	}
	w.Header().Set("Cache-Control", "private, no-store")
	address, err := mediaprovider.CatalogArtworkURL(kind, tmdbArtworkPath(match, kind))
	if err != nil {
		return mediaAccessError(err)
	}
	key := fmt.Sprintf("media-artwork/v1/%s/%s/%s/%s", library.ID, itemID, artID, address)
	cache, transport := d.store.MediaArtworkCache, d.store.TMDBHTTPClient
	image, err := flights.Do(ctx, key, func(ctx context.Context) ([]byte, error) {
		if cache != nil {
			if image, ok, err := cache.Load(ctx, key); err == nil && ok && len(image) > 0 && len(image) <= mediaArtworkBytes {
				return image, nil
			}
		}
		image, err := fetchTMDBArtwork(ctx, transport, address)
		if err == nil && cache != nil {
			_ = cache.Store(ctx, key, image)
		}
		return image, err
	})
	if checkErr := check(); checkErr != nil {
		return mediaAccessError(checkErr)
	}
	if err != nil {
		// Preserve available NFO artwork if the preferred online image fails.
		if err = loadItemMetadataAccess(ctx, d, library, itemID, &access.mediaItemAccess); err != nil {
			return mediaAccessError(err)
		}
		page, err := visibleArtwork(ctx, d, library, itemID, item.Revision, &access.mediaItemAccess, "", 100)
		if err != nil {
			return mediaAccessError(err)
		}
		for _, local := range page.Items {
			if local.Status == "declared" && local.Role == role {
				return serveLocalArtwork(w, r, d, library, itemID, local.ID)
			}
		}
		w.Header().Set("Retry-After", "15")
		return writeCatalogError(w, http.StatusBadGateway, "artwork_unavailable", "暂时无法读取影视图片", true)
	}
	contentType := http.DetectContentType(image)
	if contentType != "image/jpeg" && contentType != "image/png" {
		return mediaAccessError(fs.ErrInvalid)
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	buffer := bytes.NewReader(image)
	reader := &artworkAuthorizedReadSeeker{artworkAuthorizedReader: &artworkAuthorizedReader{check: check, reader: buffer}, seeker: buffer}
	http.ServeContent(mediaServeWriter{w}, r, "", time.Time{}, reader)
	return 0, nil
}

// The transport's HOST mapping and TLS apply to this credential-free request.
func fetchTMDBArtwork(ctx context.Context, transport *http.Client, address string) ([]byte, error) {
	if transport == nil {
		return nil, errors.New("TMDB artwork transport unavailable")
	}
	client := *transport
	client.Jar = nil
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return nil, err
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusOK || response.ContentLength > mediaArtworkBytes {
		return nil, errors.New("TMDB artwork unavailable")
	}
	image, err := io.ReadAll(io.LimitReader(response.Body, mediaArtworkBytes+1))
	if err != nil || len(image) > mediaArtworkBytes {
		return nil, errors.New("invalid TMDB artwork size")
	}
	clean, _, err := sanitizedArtwork(image)
	return clean, err
}
