package fbhttp

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

const mediaLeaseLifetime = 5 * time.Minute
const mediaLeaseCapacity = 4096
const mediaReadChunk = 256 << 10

type mediaPlaybackLease struct {
	id        string
	access    mediaAccessContext
	expires   time.Time
	held      *files.SubtitleResource
	info      os.FileInfo
	stamp     string
	closeOnce sync.Once
	timer     *time.Timer
}

func (lease *mediaPlaybackLease) close() {
	lease.closeOnce.Do(func() {
		if lease.timer != nil {
			lease.timer.Stop()
		}
		_ = lease.held.Close()
	})
}

type mediaPlaybackLeases struct {
	mu    sync.Mutex
	items map[string]*mediaPlaybackLease
	now   func() time.Time
}

func newMediaPlaybackLeases() *mediaPlaybackLeases {
	return &mediaPlaybackLeases{items: make(map[string]*mediaPlaybackLease), now: time.Now}
}

func (leases *mediaPlaybackLeases) create(access mediaAccessContext, held *files.SubtitleResource, info os.FileInfo, stamp string) (string, time.Time, error) {
	id, err := newMediaOpaqueID()
	if err != nil {
		return "", time.Time{}, err
	}
	leases.mu.Lock()
	defer leases.mu.Unlock()
	now := leases.now()
	for key, value := range leases.items {
		if !now.Before(value.expires) {
			delete(leases.items, key)
			value.close()
		}
	}
	if len(leases.items) >= mediaLeaseCapacity {
		return "", time.Time{}, fs.ErrPermission
	}
	expires := now.Add(mediaLeaseLifetime)
	lease := &mediaPlaybackLease{id: id, access: access, expires: expires, held: held, info: info, stamp: stamp}
	leases.items[id] = lease
	lease.timer = time.AfterFunc(mediaLeaseLifetime, func() {
		leases.mu.Lock()
		if leases.items[id] == lease {
			delete(leases.items, id)
		}
		leases.mu.Unlock()
		lease.close()
	})
	return id, expires, nil
}

func (leases *mediaPlaybackLeases) revoke(lease *mediaPlaybackLease) {
	leases.mu.Lock()
	if leases.items[lease.id] == lease {
		delete(leases.items, lease.id)
	}
	leases.mu.Unlock()
	lease.close()
}

func (leases *mediaPlaybackLeases) close() {
	leases.mu.Lock()
	defer leases.mu.Unlock()
	for id, lease := range leases.items {
		delete(leases.items, id)
		lease.close()
	}
}

func (leases *mediaPlaybackLeases) get(id string, viewer uint, session string) *mediaPlaybackLease {
	leases.mu.Lock()
	defer leases.mu.Unlock()
	lease := leases.items[id]
	if lease == nil || !leases.now().Before(lease.expires) {
		delete(leases.items, id)
		if lease != nil {
			lease.close()
		}
		return nil
	}
	if lease.access.viewer != viewer || lease.access.session != session {
		return nil
	}
	return lease
}

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
			EditionID string `json:"editionId"`
			VariantID string `json:"variantId"`
			BindingID string `json:"bindingId"`
		}
		if err := deviceBody(w, r, &body); err != nil || body.EditionID == "" || body.VariantID == "" || body.BindingID == "" {
			return http.StatusBadRequest, fmt.Errorf("播放请求无效")
		}
		evidence, err := d.store.MediaLibrary.ResolvePlayback(r.Context(), d.user.ID, library.ID, mux.Vars(r)["itemId"], body.EditionID, body.VariantID, body.BindingID)
		if err != nil {
			return mediaAccessError(err)
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
		id, expiry, err := leases.create(access, file, info, stamp)
		if err != nil {
			_ = file.Close()
			return http.StatusServiceUnavailable, fmt.Errorf("播放服务繁忙，请稍后重试")
		}
		weak := evidence.IdentityKind != string(mediascan.IdentityNative)
		// Neither a native file ID nor the v1 size/mtime marker establishes a
		// reliable timeline across independent opens. Preserve same-lease seeks;
		// advertise cross-open resume only after stronger timeline evidence exists.
		return renderJSON(w, r, struct {
			ItemID                     string   `json:"itemId"`
			EditionID                  string   `json:"editionId"`
			VariantID                  string   `json:"variantId"`
			SourceFileID               string   `json:"sourceFileId"`
			FileRevision               int64    `json:"fileRevision"`
			IdentityEpoch              int64    `json:"identityEpoch"`
			WeakIdentity               bool     `json:"weakIdentity"`
			CanResumeAcrossOpen        bool     `json:"canResumeAcrossOpen"`
			ResumeRequiresSameRevision bool     `json:"resumeRequiresSameRevision"`
			DisplayName                string   `json:"displayName"`
			ContentType                string   `json:"contentType"`
			Size                       int64    `json:"size"`
			DurationSeconds            *float64 `json:"durationSeconds"`
			Resolution                 *string  `json:"resolution"`
			RangeSupported             bool     `json:"rangeSupported"`
			URL                        string   `json:"url"`
			Endpoint                   string   `json:"endpoint"`
			ExpiresAt                  int64    `json:"expiresAt"`
		}{evidence.ItemID, evidence.EditionID, evidence.VariantID, evidence.SourceFileID, evidence.FileRevision, evidence.IdentityEpoch, weak, false, true,
			mediaDisplayName(evidence.Locator), mediaContentType(evidence.Locator), info.Size(), nil, nil, true,
			d.server.BaseURL + "/api/media-library/v1/streams/" + id + "/original", "/api/media-library/v1/streams/" + id + "/original", expiry.UnixMilli()})
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

func (reader *mediaAuthorizedReadSeeker) Read(p []byte) (int, error) {
	if len(p) > mediaReadChunk {
		p = p[:mediaReadChunk]
	}
	if !reader.leases.now().Before(reader.lease.expires) {
		reader.leases.revoke(reader.lease)
		return 0, fs.ErrPermission
	}
	current, err := reader.lease.access.openPlayback(reader.ctx, reader.d)
	if err != nil {
		if reader.ctx.Err() == nil {
			reader.leases.revoke(reader.lease)
		}
		return 0, err
	}
	err = sameOpenMedia(reader.lease.held, current, reader.lease.stamp, reader.lease.info)
	_ = current.Close()
	if err != nil {
		reader.leases.revoke(reader.lease)
		return 0, err
	}
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(p)
}

func (reader *mediaAuthorizedReadSeeker) Seek(offset int64, whence int) (int64, error) {
	return reader.reader.Seek(offset, whence)
}

func mediaPlaybackStreamHandler(leases *mediaPlaybackLeases) handleFunc {
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		lease := leases.get(mux.Vars(r)["lease"], d.user.ID, d.deviceSession)
		if lease == nil {
			return mediaAccessError(fs.ErrPermission)
		}
		file, err := lease.access.openPlayback(r.Context(), d)
		if err != nil {
			if r.Context().Err() == nil {
				leases.revoke(lease)
			}
			return mediaAccessError(err)
		}
		defer func() { _ = file.Close() }()
		if err = sameOpenMedia(lease.held, file, lease.stamp, lease.info); err != nil {
			leases.revoke(lease)
			return mediaAccessError(err)
		}
		// Check again before ServeContent can answer HEAD or a conditional 304.
		current, err := lease.access.openPlayback(r.Context(), d)
		if err != nil {
			if r.Context().Err() == nil {
				leases.revoke(lease)
			}
			return mediaAccessError(err)
		}
		err = sameOpenMedia(lease.held, current, lease.stamp, lease.info)
		_ = current.Close()
		if err != nil || !leases.now().Before(lease.expires) {
			leases.revoke(lease)
			return mediaAccessError(err)
		}
		evidence := lease.access.playback
		digest := sha256.Sum256(append(append([]byte(nil), evidence.ObjectKey...), []byte(evidence.SourceVersion+lease.stamp)...))
		w.Header().Set("ETag", `"`+hex.EncodeToString(digest[:])+`"`)
		w.Header().Set("Content-Type", mediaContentType(evidence.Locator))
		w.Header().Set("X-Content-Type-Options", "nosniff")
		reader := &mediaAuthorizedReadSeeker{ctx: r.Context(), d: d, leases: leases, lease: lease, reader: io.NewSectionReader(lease.held.File, 0, lease.info.Size())}
		http.ServeContent(mediaServeWriter{w}, r, "", lease.info.ModTime(), reader)
		return 0, nil
	})
}

var _ io.ReadSeeker = (*mediaAuthorizedReadSeeker)(nil)
