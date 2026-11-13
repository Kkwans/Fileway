package fbhttp

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/gorilla/mux"
)

// Public library summaries never include source paths, credential references or
// an implied playback grant. Catalog membership is only one access boundary.
type mediaLibrarySummary struct {
	ID       string                  `json:"id"`
	Name     string                  `json:"name"`
	Revision int64                   `json:"revision"`
	Role     medialibrary.MemberRole `json:"role"`
	Archived bool                    `json:"archived"`
}

func librarySummary(value *medialibrary.Library) mediaLibrarySummary {
	return mediaLibrarySummary{ID: value.ID, Name: value.Name, Revision: value.Revision, Role: value.Role, Archived: value.ArchivedAt != nil}
}

func mediaLibraryRead(fn handleFunc) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("Cache-Control", "no-store")
		if d.store.MediaLibrary == nil {
			return http.StatusServiceUnavailable, fmt.Errorf("影视中心尚未启用")
		}
		return fn(w, r, d)
	})
}

var mediaLibraryListHandler = mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	options := medialibrary.LibraryListOptions{AfterID: r.URL.Query().Get("cursor")}
	if len(options.AfterID) > 128 {
		return http.StatusBadRequest, fmt.Errorf("媒体库分页参数无效")
	}
	if raw := r.URL.Query().Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > 100 {
			return http.StatusBadRequest, fmt.Errorf("媒体库分页数量须为1至100")
		}
		options.Limit = limit
	}
	if raw := r.URL.Query().Get("archived"); raw != "" {
		if raw != "true" && raw != "false" {
			return http.StatusBadRequest, fmt.Errorf("归档筛选参数无效")
		}
		options.Archived = raw == "true"
	}
	page, err := d.store.MediaLibrary.ListLibraries(r.Context(), d.user.ID, options)
	if err != nil {
		return mediaLibraryError(err)
	}
	items := make([]mediaLibrarySummary, 0, len(page.Items))
	for i := range page.Items {
		items = append(items, librarySummary(&page.Items[i]))
	}
	return renderJSON(w, r, struct {
		Items      []mediaLibrarySummary `json:"items"`
		NextCursor string                `json:"nextCursor,omitempty"`
	}{items, page.NextCursor})
})

var mediaLibraryGetHandler = mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	value, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, mux.Vars(r)["id"])
	if err != nil {
		return mediaLibraryError(err)
	}
	return renderJSON(w, r, librarySummary(value))
})

func mediaLibraryError(err error) (int, error) {
	switch {
	case errors.Is(err, medialibrary.ErrNotFound):
		return http.StatusNotFound, fmt.Errorf("媒体库不存在或不可访问")
	case errors.Is(err, medialibrary.ErrForbidden):
		return http.StatusForbidden, fmt.Errorf("没有此媒体库操作权限")
	case errors.Is(err, medialibrary.ErrInvalid):
		return http.StatusBadRequest, fmt.Errorf("媒体库参数无效")
	case errors.Is(err, medialibrary.ErrRevisionConflict):
		return http.StatusConflict, fmt.Errorf("媒体库已更新，请重新读取")
	default:
		return http.StatusInternalServerError, fmt.Errorf("暂时无法读取影视目录")
	}
}
