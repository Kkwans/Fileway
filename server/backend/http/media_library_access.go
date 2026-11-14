package fbhttp

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"io/fs"
	"os"
	"path"
	"strings"
	"unicode"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

// mediaAccessContext keeps the requesting viewer distinct from the source
// owner. Catalog evidence is a fence, never an authorization by itself.
type mediaAccessContext struct {
	viewer   uint
	session  string
	playback *medialibrary.PlaybackAccess
}

func (access *mediaAccessContext) checkViewer(ctx context.Context, d *data) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if d.user == nil || d.user.ID != access.viewer || d.deviceSession != access.session || d.store.Users == nil {
		return fs.ErrPermission
	}
	if d.authCheck == nil {
		return fs.ErrPermission
	}
	if err := d.authCheck(); err != nil {
		return err
	}
	viewer, err := d.store.Users.Get(d.server.Root, access.viewer)
	if err != nil || viewer == nil || viewer.ID != access.viewer {
		return fs.ErrPermission
	}
	if access.session != "" {
		if d.store.Devices == nil {
			return fs.ErrPermission
		}
		if _, err := d.store.Devices.Validate(access.session, access.viewer); err != nil {
			return fs.ErrPermission
		}
	}
	return nil
}

func currentMediaLibrary(ctx context.Context, d *data, viewer uint, libraryID string) (*medialibrary.Library, error) {
	library, err := d.store.MediaLibrary.GetLibrary(ctx, viewer, libraryID)
	if err != nil {
		return nil, err
	}
	if library.ArchivedAt != nil || !mediaLibraryAccessAllowed(d, library) {
		return nil, fs.ErrPermission
	}
	return library, nil
}

// openMediaChild authorizes the source-owner path and opens it relative to the
// same root handle that was checked against the persisted binding proof.
func openMediaChild(ctx context.Context, d *data, source medialibrary.LocalSource, ref medialibrary.SourceBindingRef, proof *medialibrary.BindingProof, locator []byte) (*files.SubtitleResource, error) {
	relative, err := mediascan.RelativeLocalLocator(ref.RootLocator, locator)
	if err != nil {
		return nil, err
	}
	authority, err := resolveLocalMediaConnection(ctx, d.store, d.server, source.OwnerUserID, source.ConfigurationRef)
	if err != nil {
		return nil, err
	}
	root, err := mediascan.OpenAttestedLocalRoot(ctx, source.OwnerUserID, source.ConfigurationRef, ref.RootLocator, authority, proof)
	if err != nil {
		return nil, err
	}
	defer func() { _ = root.Close() }()
	local, err := mediasource.NewLocal(string(ref.RootLocator), func(context.Context) (mediasource.LocalAuthority, error) { return authority, nil })
	if err != nil {
		return nil, err
	}
	return local.OpenFromVerifiedRoot(ctx, authority, root, relative)
}

func (access *mediaAccessContext) openPlayback(ctx context.Context, d *data) (*files.SubtitleResource, error) {
	if err := access.checkViewer(ctx, d); err != nil {
		return nil, err
	}
	expected := access.playback
	if expected == nil || expected.ViewerUserID != access.viewer || expected.MissingSince != nil {
		return nil, fs.ErrPermission
	}
	library, err := currentMediaLibrary(ctx, d, access.viewer, expected.LibraryID)
	if err != nil || library.OwnerUserID != expected.Source.OwnerUserID {
		return nil, fs.ErrPermission
	}
	resolve := func() (*medialibrary.PlaybackAccess, error) {
		return d.store.MediaLibrary.ResolvePlayback(ctx, access.viewer, expected.LibraryID, expected.ItemID, expected.EditionID, expected.VariantID, expected.BindingID)
	}
	current, err := resolve()
	if err != nil || !medialibrary.SamePlaybackAccess(expected, current) {
		return nil, fs.ErrPermission
	}
	file, err := openMediaChild(ctx, d, current.Source, current.Binding, current.Proof, current.Locator)
	if err != nil {
		return nil, err
	}
	if err = mediascan.MatchOpenedLocalReference(file, current.ObjectKey, current.IdentityKind, current.SourceVersion); err != nil {
		_ = file.Close()
		return nil, err
	}
	// Do not return a handle if a grant changed during the open.
	current, err = resolve()
	if err != nil || !medialibrary.SamePlaybackAccess(expected, current) {
		_ = file.Close()
		return nil, fs.ErrPermission
	}
	if err := access.checkViewer(ctx, d); err != nil {
		_ = file.Close()
		return nil, err
	}
	if _, err = currentMediaLibrary(ctx, d, access.viewer, expected.LibraryID); err != nil {
		_ = file.Close()
		return nil, err
	}
	return file, nil
}

func sameOpenMedia(held, current *files.SubtitleResource, stamp string, info os.FileInfo) error {
	now, nowStamp, err := current.State()
	if err != nil || now == nil || nowStamp != stamp || !os.SameFile(info, now) || files.IsPrivateMediaFile(now) {
		return fs.ErrPermission
	}
	heldInfo, heldStamp, err := held.State()
	if err != nil || heldInfo == nil || heldStamp != stamp || !os.SameFile(info, heldInfo) || files.IsPrivateMediaFile(heldInfo) {
		return fs.ErrPermission
	}
	return nil
}

func newMediaOpaqueID() (string, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw[:]), nil
}

func mediaDisplayName(locator []byte) string {
	name := files.DisplayName(path.Base(string(locator)))
	name = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, name)
	runes := []rune(strings.TrimSpace(name))
	if len(runes) > 240 {
		runes = runes[:240]
	}
	if len(runes) == 0 {
		return "Video"
	}
	return string(runes)
}

func mediaContentType(locator []byte) string {
	switch strings.ToLower(path.Ext(string(locator))) {
	case ".mp4", ".m4v":
		return "video/mp4"
	case ".mkv":
		return "video/x-matroska"
	case ".webm":
		return "video/webm"
	case ".mov":
		return "video/quicktime"
	case ".avi":
		return "video/x-msvideo"
	case ".ts", ".m2ts":
		return "video/mp2t"
	case ".mpg", ".mpeg":
		return "video/mpeg"
	case ".wmv":
		return "video/x-ms-wmv"
	case ".flv":
		return "video/x-flv"
	case ".vob":
		return "video/dvd"
	default:
		return "application/octet-stream"
	}
}

func mediaAccessError(error) (int, error) {
	return 404, errors.New("媒体不存在或不可访问")
}
