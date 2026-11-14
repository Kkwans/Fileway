package fbhttp

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path"
	"runtime"
	"strings"
	"unicode"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
	_ "golang.org/x/image/webp"
)

const mediaArtworkBytes = 8 << 20
const mediaArtworkPixels = 16 << 20
const mediaArtworkDimension = 8192

type mediaArtworkContext struct {
	viewer       *mediaAccessContext
	evidence     *medialibrary.ArtworkAccess
	nfo          *files.SubtitleResource
	nfoInfo      os.FileInfo
	nfoStamp     string
	image        *files.SubtitleResource
	imageInfo    os.FileInfo
	imageStamp   string
	imageLocator []byte
}

func (access *mediaArtworkContext) check(ctx context.Context, d *data) error {
	if err := access.viewer.checkViewer(ctx, d); err != nil {
		return err
	}
	current, err := d.store.MediaLibrary.ResolveArtwork(ctx, access.viewer.viewer, access.evidence.LibraryID, access.evidence.ItemID, access.evidence.ArtworkID)
	if err != nil || !medialibrary.SameArtworkAccess(access.evidence, current) {
		return fs.ErrPermission
	}
	library, err := currentMediaLibrary(ctx, d, access.viewer.viewer, current.LibraryID)
	if err != nil || library.OwnerUserID != current.Source.OwnerUserID {
		return fs.ErrPermission
	}
	// An artwork declaration alone cannot authorize disclosure: at least one
	// actual video source must still be accessible to this viewer.
	video, err := access.viewer.openPlayback(ctx, d)
	if err != nil {
		return err
	}
	_ = video.Close()
	if access.nfo != nil {
		nfo, err := openMediaChild(ctx, d, current.Source, current.Binding, current.Proof, current.SourceKey)
		if err != nil {
			return err
		}
		err = sameOpenMedia(access.nfo, nfo, access.nfoStamp, access.nfoInfo)
		_ = nfo.Close()
		if err != nil {
			return err
		}
	}
	if access.image != nil {
		picture, err := openMediaChild(ctx, d, current.Source, current.Binding, current.Proof, access.imageLocator)
		if err != nil {
			return err
		}
		err = sameOpenMedia(access.image, picture, access.imageStamp, access.imageInfo)
		_ = picture.Close()
		if err != nil {
			return err
		}
	}
	current, err = d.store.MediaLibrary.ResolveArtwork(ctx, access.viewer.viewer, access.evidence.LibraryID, access.evidence.ItemID, access.evidence.ArtworkID)
	if err != nil || !medialibrary.SameArtworkAccess(access.evidence, current) {
		return fs.ErrPermission
	}
	return access.viewer.checkViewer(ctx, d)
}

func firstPlayableForArtwork(ctx context.Context, d *data, libraryID, itemID string) (*mediaAccessContext, error) {
	after := ""
	for pageCount := 0; pageCount < 10; pageCount++ {
		page, err := d.store.MediaLibrary.ListPlaybackVariants(ctx, d.user.ID, libraryID, itemID, after, 100)
		if err != nil {
			return nil, err
		}
		for _, variant := range page.Items {
			evidence, _, err := firstPlayableBinding(ctx, d, libraryID, itemID, variant)
			if err == nil {
				return &mediaAccessContext{viewer: d.user.ID, session: d.deviceSession, playback: evidence}, nil
			}
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
		}
		if page.NextCursor == "" || page.NextCursor == after {
			break
		}
		after = page.NextCursor
	}
	return nil, fs.ErrPermission
}

// Interpret the NFO text as a relative filesystem name, never as a URL and
// never percent-decode it. Parent components may not escape the binding root.
func mediaArtworkLocator(root, nfo, reference []byte) ([]byte, error) {
	base, err := mediascan.RelativeLocalLocator(root, nfo)
	if err != nil {
		return nil, err
	}
	ref := string(reference)
	if ref == "" || strings.ContainsRune(ref, 0) || strings.HasPrefix(ref, "/") || runtime.GOOS == "windows" && strings.Contains(ref, `\`) {
		return nil, fs.ErrInvalid
	}
	// Any URI scheme or Windows drive prefix is rejected without interpreting
	// otherwise opaque filename characters such as '%' and '#'.
	if colon := strings.IndexByte(ref, ':'); colon >= 0 {
		scheme := ref[:colon]
		if scheme != "" && unicode.IsLetter(rune(scheme[0])) {
			valid := true
			for _, char := range scheme[1:] {
				validSchemeChar := char >= 'a' && char <= 'z' || char >= 'A' && char <= 'Z' || char >= '0' && char <= '9' || char == '+' || char == '-' || char == '.'
				if !validSchemeChar {
					valid = false
					break
				}
			}
			if valid {
				return nil, fs.ErrInvalid
			}
		}
	}
	relative := path.Clean(path.Join(path.Dir(base), ref))
	if relative == "." || relative == ".." || strings.HasPrefix(relative, "../") || strings.HasPrefix(relative, "/") {
		return nil, fs.ErrPermission
	}
	result := append([]byte(nil), root...)
	if !bytes.Equal(root, []byte("/")) {
		result = append(result, '/')
	}
	result = append(result, relative...)
	return result, nil
}

type artworkAuthorizedReader struct {
	check  func() error
	reader io.Reader
}

func (reader *artworkAuthorizedReader) Read(p []byte) (int, error) {
	if len(p) > mediaReadChunk {
		p = p[:mediaReadChunk]
	}
	if err := reader.check(); err != nil {
		return 0, err
	}
	return reader.reader.Read(p)
}

type artworkAuthorizedReadSeeker struct {
	*artworkAuthorizedReader
	seeker io.Seeker
}

func (reader *artworkAuthorizedReadSeeker) Seek(offset int64, whence int) (int64, error) {
	return reader.seeker.Seek(offset, whence)
}

type boundedArtworkWriter struct{ bytes.Buffer }

func (out *boundedArtworkWriter) Write(p []byte) (int, error) {
	if len(p) > mediaArtworkBytes-out.Len() {
		return 0, fs.ErrInvalid
	}
	return out.Buffer.Write(p)
}

func sanitizedArtwork(raw []byte) ([]byte, string, error) {
	config, kind, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil || kind != "jpeg" && kind != "png" && kind != "webp" || config.Width < 1 || config.Height < 1 || config.Width > mediaArtworkDimension || config.Height > mediaArtworkDimension || int64(config.Width)*int64(config.Height) > mediaArtworkPixels {
		return nil, "", fs.ErrInvalid
	}
	picture, format, err := image.Decode(bytes.NewReader(raw))
	if err != nil || format != kind || picture.Bounds().Dx() != config.Width || picture.Bounds().Dy() != config.Height {
		return nil, "", fs.ErrInvalid
	}
	var output boundedArtworkWriter
	contentType := "image/png"
	if kind == "jpeg" {
		contentType = "image/jpeg"
		err = jpeg.Encode(&output, picture, &jpeg.Options{Quality: 90})
	} else {
		err = png.Encode(&output, picture)
	}
	if err != nil {
		return nil, "", err
	}
	return output.Bytes(), contentType, nil
}

func mediaArtworkHandler(permits chan struct{}) handleFunc {
	flights := newPreviewCoordinator()
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		select {
		case permits <- struct{}{}:
			defer func() { <-permits }()
		case <-r.Context().Done():
			return mediaAccessError(r.Context().Err())
		default:
			return http.StatusServiceUnavailable, fs.ErrPermission
		}
		library, err := itemLibrary(r, d)
		if err != nil {
			return mediaAccessError(err)
		}
		itemID, artID := mux.Vars(r)["itemId"], mux.Vars(r)["artworkId"]
		if strings.HasPrefix(artID, tmdbArtworkPrefix) {
			return serveTMDBArtwork(w, r, d, library, itemID, artID, flights)
		}
		return serveLocalArtwork(w, r, d, library, itemID, artID)
	})
}

func serveLocalArtwork(w http.ResponseWriter, r *http.Request, d *data, library *medialibrary.Library, itemID, artID string) (int, error) {
	evidence, err := d.store.MediaLibrary.ResolveArtwork(r.Context(), d.user.ID, library.ID, itemID, artID)
	if err != nil {
		return mediaAccessError(err)
	}
	locator, err := mediaArtworkLocator(evidence.Binding.RootLocator, evidence.SourceKey, evidence.Reference)
	if err != nil {
		return mediaAccessError(err)
	}
	viewer, err := firstPlayableForArtwork(r.Context(), d, library.ID, itemID)
	if err != nil {
		return mediaAccessError(err)
	}
	access := &mediaArtworkContext{viewer: viewer, evidence: evidence, imageLocator: locator}
	check := func() error { return access.check(r.Context(), d) }
	if err := check(); err != nil {
		return mediaAccessError(err)
	}
	access.nfo, err = openMediaChild(r.Context(), d, evidence.Source, evidence.Binding, evidence.Proof, evidence.SourceKey)
	if err != nil {
		return mediaAccessError(err)
	}
	defer func() { _ = access.nfo.Close() }()
	access.nfoInfo, access.nfoStamp, err = access.nfo.State()
	if err != nil || access.nfoInfo == nil || access.nfoInfo.Size() > mediametadata.DefaultMaxBytes {
		return mediaAccessError(err)
	}
	nfo, err := io.ReadAll(io.LimitReader(&artworkAuthorizedReader{check: check, reader: access.nfo.File}, mediametadata.DefaultMaxBytes+1))
	digest := sha256.Sum256(nfo)
	if err != nil || int64(len(nfo)) > mediametadata.DefaultMaxBytes || !bytes.Equal(digest[:], evidence.Digest) {
		return mediaAccessError(err)
	}
	access.image, err = openMediaChild(r.Context(), d, evidence.Source, evidence.Binding, evidence.Proof, locator)
	if err != nil {
		return mediaAccessError(err)
	}
	defer func() { _ = access.image.Close() }()
	access.imageInfo, access.imageStamp, err = access.image.State()
	if err != nil || access.imageInfo == nil || access.imageInfo.Size() > mediaArtworkBytes {
		return mediaAccessError(err)
	}
	raw, err := io.ReadAll(io.LimitReader(&artworkAuthorizedReader{check: check, reader: access.image.File}, mediaArtworkBytes+1))
	if err != nil || len(raw) > mediaArtworkBytes {
		return mediaAccessError(err)
	}
	output, contentType, err := sanitizedArtwork(raw)
	if err != nil {
		return mediaAccessError(err)
	}
	if err := check(); err != nil {
		return mediaAccessError(err)
	}
	sum := sha256.Sum256(output)
	w.Header().Set("ETag", `"`+hex.EncodeToString(sum[:])+`"`)
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	buffer := bytes.NewReader(output)
	reader := &artworkAuthorizedReadSeeker{artworkAuthorizedReader: &artworkAuthorizedReader{check: check, reader: buffer}, seeker: buffer}
	http.ServeContent(mediaServeWriter{w}, r, "", access.imageInfo.ModTime(), reader)
	return 0, nil
}
