package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

const mediaReadChunk = 256 << 10

type mediaVariantResponse struct {
	ItemID          string   `json:"itemId"`
	EditionID       string   `json:"editionId"`
	VariantID       string   `json:"variantId"`
	BindingID       string   `json:"bindingId"`
	SourceFileID    string   `json:"sourceFileId"`
	EditionName     string   `json:"editionName"`
	IsDefault       bool     `json:"isDefault"`
	SourceLabel     string   `json:"sourceLabel"`
	DisplayName     string   `json:"displayName"`
	ContentType     string   `json:"contentType"`
	Size            int64    `json:"size"`
	FileRevision    int64    `json:"fileRevision"`
	IdentityEpoch   int64    `json:"identityEpoch"`
	WeakIdentity    bool     `json:"weakIdentity"`
	DurationSeconds *float64 `json:"durationSeconds"`
	Resolution      *string  `json:"resolution"`
}

func variantResponse(item *medialibrary.PlaybackAccess, variant medialibrary.PlaybackVariant, info os.FileInfo) mediaVariantResponse {
	return mediaVariantResponse{ItemID: item.ItemID, EditionID: item.EditionID, VariantID: item.VariantID, BindingID: item.BindingID,
		SourceFileID: item.SourceFileID, EditionName: variant.EditionName, IsDefault: variant.IsDefault, SourceLabel: "本地来源",
		DisplayName: mediaDisplayName(item.Locator), ContentType: mediaContentType(item.Locator), Size: info.Size(),
		FileRevision: item.FileRevision, IdentityEpoch: item.IdentityEpoch, WeakIdentity: item.IdentityKind != string(mediascan.IdentityNative)}
}

// First playable binding is selected only after proving live owner authority;
// no hidden source count or raw locator is included in the response.
func firstPlayableBinding(ctx context.Context, d *data, libraryID, itemID string, variant medialibrary.PlaybackVariant) (*medialibrary.PlaybackAccess, os.FileInfo, error) {
	after := ""
	for pageCount := 0; pageCount < 10; pageCount++ {
		page, err := d.store.MediaLibrary.ListPlaybackAccess(ctx, d.user.ID, libraryID, itemID, variant.EditionID, variant.VariantID, after, 100)
		if err != nil {
			return nil, nil, err
		}
		for i := range page.Items {
			evidence := &page.Items[i]
			access := mediaAccessContext{viewer: d.user.ID, session: d.deviceSession, playback: evidence}
			opened, err := access.openPlayback(ctx, d)
			if err != nil {
				if ctx.Err() != nil {
					return nil, nil, ctx.Err()
				}
				continue
			}
			info, _, err := opened.State()
			_ = opened.Close()
			if err == nil && info != nil {
				return evidence, info, nil
			}
		}
		if page.NextCursor == "" {
			return nil, nil, fs.ErrNotExist
		}
		if page.NextCursor == after {
			return nil, nil, fs.ErrInvalid
		}
		after = page.NextCursor
	}
	return nil, nil, fs.ErrNotExist
}

var mediaVariantsHandler = mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	library, err := itemLibrary(r, d)
	if err != nil {
		return mediaAccessError(err)
	}
	limit := 20
	if raw := r.URL.Query().Get("limit"); raw != "" {
		limit, err = strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > 100 {
			return http.StatusBadRequest, fmt.Errorf("片源分页参数无效")
		}
	}
	page, err := d.store.MediaLibrary.ListPlaybackVariants(r.Context(), d.user.ID, library.ID, mux.Vars(r)["itemId"], r.URL.Query().Get("cursor"), limit)
	if err != nil {
		return mediaLibraryError(err)
	}
	results := make([]mediaVariantResponse, 0, len(page.Items))
	for _, variant := range page.Items {
		evidence, info, err := firstPlayableBinding(r.Context(), d, library.ID, mux.Vars(r)["itemId"], variant)
		if err == nil {
			results = append(results, variantResponse(evidence, variant, info))
		}
		if r.Context().Err() != nil {
			return mediaAccessError(r.Context().Err())
		}
	}
	return renderJSON(w, r, struct {
		Items      []mediaVariantResponse `json:"items"`
		NextCursor string                 `json:"nextCursor,omitempty"`
	}{results, page.NextCursor})
})

func mediaPlaybackResolutionHandler(leases *mediaPlaybackLeases) handleFunc {
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		library, err := itemLibrary(r, d)
		if err != nil {
			return mediaAccessError(err)
		}
		var body struct {
			EditionID        string `json:"editionId"`
			VariantID        string `json:"variantId"`
			BindingID        string `json:"bindingId"`
			WatchProgress    bool   `json:"watchProgress,omitempty"`
			FromStart        bool   `json:"fromStart,omitempty"`
			ContinueRevision string `json:"continueRevision,omitempty"`
		}
		if err := deviceBody(w, r, &body); err != nil || body.EditionID == "" || body.VariantID == "" || body.BindingID == "" ||
			body.ContinueRevision != "" && (!body.WatchProgress || !validContinueRevision(body.ContinueRevision)) {
			return http.StatusBadRequest, fmt.Errorf("播放请求无效")
		}
		evidence, err := d.store.MediaLibrary.ResolvePlayback(r.Context(), d.user.ID, library.ID, mux.Vars(r)["itemId"], body.EditionID, body.VariantID, body.BindingID)
		if err != nil {
			return mediaAccessError(err)
		}
		weak := evidence.IdentityKind != string(mediascan.IdentityNative)
		if body.ContinueRevision != "" && weak {
			return mediaAccessError(fs.ErrPermission)
		}
		access := mediaAccessContext{viewer: d.user.ID, session: d.deviceSession, playback: evidence}
		file, err := access.openPlayback(r.Context(), d)
		if err != nil {
			return mediaAccessError(err)
		}
		info, stamp, err := file.State()
		if err != nil || info == nil {
			_ = file.Close()
			return mediaAccessError(err)
		}
		admission, err := leases.create(access, file, info, stamp)
		if err != nil {
			_ = file.Close()
			return http.StatusServiceUnavailable, fmt.Errorf("播放服务繁忙，请稍后重试")
		}
		var progress *medialibrary.WatchProgress
		if body.WatchProgress && !weak {
			state, err := d.store.MediaLibrary.BeginWatchProgress(r.Context(), evidence, stamp, admission.SessionID, body.FromStart, body.ContinueRevision)
			if err != nil {
				if lease := leases.session(admission.SessionID, d.user.ID, d.deviceSession); lease != nil {
					leases.revoke(lease)
				}
				if body.ContinueRevision != "" && errors.Is(err, medialibrary.ErrRevisionConflict) {
					return writeCatalogError(w, http.StatusConflict, "watch_checkpoint_changed", "续播进度已更新，请刷新", true)
				}
				return mediaAccessError(err)
			}
			progress = &state
		}
		// Opted-in clients use the checkpoint below for this exact native
		// timeline. The existing same-session lease contract stays compatible.
		endpoint := "/api/media-library/v1/streams/" + admission.LeaseID + "/original"
		return renderJSON(w, r, struct {
			ItemID                     string                      `json:"itemId"`
			EditionID                  string                      `json:"editionId"`
			VariantID                  string                      `json:"variantId"`
			SourceFileID               string                      `json:"sourceFileId"`
			FileRevision               int64                       `json:"fileRevision"`
			IdentityEpoch              int64                       `json:"identityEpoch"`
			WeakIdentity               bool                        `json:"weakIdentity"`
			CanResumeAcrossOpen        bool                        `json:"canResumeAcrossOpen"`
			ResumeRequiresSameRevision bool                        `json:"resumeRequiresSameRevision"`
			DisplayName                string                      `json:"displayName"`
			ContentType                string                      `json:"contentType"`
			Size                       int64                       `json:"size"`
			DurationSeconds            *float64                    `json:"durationSeconds"`
			Resolution                 *string                     `json:"resolution"`
			RangeSupported             bool                        `json:"rangeSupported"`
			URL                        string                      `json:"url"`
			Endpoint                   string                      `json:"endpoint"`
			WatchProgress              *medialibrary.WatchProgress `json:"watchProgress,omitempty"`
			mediaAdmission
		}{evidence.ItemID, evidence.EditionID, evidence.VariantID, evidence.SourceFileID, evidence.FileRevision, evidence.IdentityEpoch, weak, false, true,
			mediaDisplayName(evidence.Locator), mediaContentType(evidence.Locator), info.Size(), nil, nil, true,
			d.server.BaseURL + endpoint, endpoint, progress, admission})
	})
}

type mediaAuthorizedReadSeeker struct {
	ctx    context.Context
	d      *data
	leases *mediaPlaybackLeases
	lease  *mediaPlaybackLease
	reader *io.SectionReader
}

// ServeContent uses io.CopyN. Offering a bounded buffer here avoids thousands
// of small authorization round trips for ordinary high-bitrate media while
// still rechecking before every chunk returned by the reader.
type mediaServeWriter struct{ http.ResponseWriter }
type mediaWriterOnly struct{ io.Writer }

func (writer mediaServeWriter) ReadFrom(source io.Reader) (int64, error) {
	return io.CopyBuffer(mediaWriterOnly{writer.ResponseWriter}, source, make([]byte, mediaReadChunk))
}

func checkMediaPlayback(ctx context.Context, d *data, leases *mediaPlaybackLeases, lease *mediaPlaybackLease) error {
	if !leases.active(lease) {
		return fs.ErrPermission
	}
	current, err := lease.access.openPlayback(ctx, d)
	if err != nil {
		if ctx.Err() == nil && !errors.Is(err, errMediaRequestExpired) {
			leases.revoke(lease)
		}
		return err
	}
	err = sameOpenMedia(lease.held, current, lease.stamp, lease.info)
	_ = current.Close()
	if err != nil {
		leases.revoke(lease)
		return err
	}
	return ctx.Err()
}

func (reader *mediaAuthorizedReadSeeker) Read(p []byte) (int, error) {
	if len(p) > mediaReadChunk {
		p = p[:mediaReadChunk]
	}
	if err := checkMediaPlayback(reader.ctx, reader.d, reader.leases, reader.lease); err != nil {
		return 0, err
	}
	n, err := reader.reader.Read(p)
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	if !reader.leases.active(reader.lease) {
		return 0, fs.ErrPermission
	}
	return n, err
}

func (reader *mediaAuthorizedReadSeeker) Seek(offset int64, whence int) (int64, error) {
	return reader.reader.Seek(offset, whence)
}

func mediaPlaybackStreamHandler(leases *mediaPlaybackLeases) handleFunc {
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		lease, err := leases.admit(mux.Vars(r)["lease"], d.user.ID, d.deviceSession)
		if err != nil {
			if errors.Is(err, errMediaReadersBusy) {
				return http.StatusServiceUnavailable, err
			}
			return mediaAccessError(err)
		}
		defer leases.release(lease)
		if err := checkMediaPlayback(r.Context(), d, leases, lease); err != nil {
			return mediaAccessError(err)
		}
		evidence := lease.access.playback
		w.Header().Set("ETag", `"`+lease.id+`"`)
		w.Header().Set("Content-Type", mediaContentType(evidence.Locator))
		w.Header().Set("X-Content-Type-Options", "nosniff")
		reader := &mediaAuthorizedReadSeeker{ctx: r.Context(), d: d, leases: leases, lease: lease, reader: io.NewSectionReader(lease.held.File, 0, lease.info.Size())}
		http.ServeContent(mediaServeWriter{w}, r, "", lease.info.ModTime(), reader)
		return 0, nil
	})
}

var _ io.ReadSeeker = (*mediaAuthorizedReadSeeker)(nil)
