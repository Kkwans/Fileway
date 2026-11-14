package fbhttp

import (
	"errors"
	"io/fs"
	"net/http"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/gorilla/mux"
)

func mediaWatchProgressHandler(leases *mediaPlaybackLeases) handleFunc {
	return catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var event medialibrary.WatchEvent
		if err := deviceBody(w, r, &event); err != nil {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		ctx := r.Context()
		lease := leases.session(mux.Vars(r)["session"], d.user.ID, d.deviceSession)
		if lease == nil {
			return mediaAccessError(fs.ErrPermission)
		}
		if err := checkMediaPlayback(ctx, d, leases, lease); err != nil {
			return mediaAccessError(err)
		}
		progress, err := d.store.MediaLibrary.RecordWatchProgress(ctx, lease.access.playback, lease.stamp, lease.sessionID, event)
		if errors.Is(err, medialibrary.ErrRevisionConflict) {
			return writeCatalogError(w, http.StatusConflict, "watch_session_changed", "观看会话已变化，请重新打开影片", false)
		}
		if err != nil {
			return catalogError(w, err)
		}
		if err := checkMediaPlayback(ctx, d, leases, lease); err != nil {
			return providerUnknown(w)
		}
		return renderJSON(w, r, progress)
	})
}
