package fbhttp

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/trash"
)

type recentRecordRequest struct {
	Path     string `json:"path"`
	WirePath string `json:"wirePath"`
}

var recentListHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	limit := recent.MaxEntriesPerUser
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed <= 0 {
			return http.StatusBadRequest, fmt.Errorf("最近访问数量无效")
		}
		limit = parsed
	}
	entries, err := d.store.Recent.List(d.user.ID, limit)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	// The root entry is a virtual scope whose display name depends on the
	// authenticated user's view. Normalize it at read time so records created
	// under earlier product labels remain consistent across pages.
	rootName := "我的文件"
	if d.user.Perm.Admin {
		rootName = "根目录"
	}
	for _, entry := range entries {
		if entry != nil && pathmeta.Clean(entry.Path) == "/" {
			entry.Name = rootName
		}
	}
	return renderJSON(w, r, entries)
})

var recentRecordHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var request recentRecordRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		return http.StatusBadRequest, fmt.Errorf("最近访问参数无效: %w", err)
	}
	resourcePath, err := recentResourcePath(request)
	if err != nil {
		return http.StatusBadRequest, err
	}
	if trash.IsInternalPath(resourcePath) {
		return http.StatusBadRequest, fmt.Errorf("最近访问路径无效")
	}
	if !d.Check(resourcePath) {
		return http.StatusForbidden, fmt.Errorf("没有访问该路径的权限")
	}
	info, err := d.user.Fs.Stat(resourcePath)
	if err != nil {
		return errToStatus(err), err
	}
	name := info.Name()
	if resourcePath == "/" {
		name = "我的文件"
		if d.user.Perm.Admin {
			name = "根目录"
		}
	}
	entry, err := d.store.Recent.Record(d.user.ID, resourcePath, name, info.IsDir())
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, entry)
})

func recentResourcePath(request recentRecordRequest) (string, error) {
	if request.WirePath == "" {
		if request.Path == "" || strings.ContainsRune(request.Path, '\x00') {
			return "", fmt.Errorf("最近访问路径无效")
		}
		// Old clients send canonical JSON paths, never URI-decode these values.
		return pathmeta.Clean(request.Path), nil
	}
	resourcePath, err := decodeResourceWirePath(request.WirePath)
	if err != nil {
		return "", fmt.Errorf("最近访问%w", err)
	}
	if request.Path != "" && pathmeta.Clean(request.Path) != files.DisplayPath(resourcePath) {
		return "", fmt.Errorf("最近访问路径与原始路径不一致")
	}
	return resourcePath, nil
}

var recentDeleteHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var request struct {
		IDs []string `json:"ids"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32768)).Decode(&request); err != nil || len(request.IDs) < 1 || len(request.IDs) > 100 {
		return http.StatusBadRequest, fmt.Errorf("请选择1到100条最近访问记录")
	}
	for _, id := range request.IDs {
		if strings.TrimSpace(id) == "" || len(id) > 128 {
			return http.StatusBadRequest, fmt.Errorf("最近访问记录标识无效")
		}
	}
	deleted, err := d.store.Recent.DeleteSelected(d.user.ID, request.IDs)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, map[string]int{"deleted": deleted})
})

var recentClearHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	deleted, err := d.store.Recent.DeleteSelected(d.user.ID, nil)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, map[string]int{"deleted": deleted})
})
