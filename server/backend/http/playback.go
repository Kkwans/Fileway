package fbhttp

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/playback"
)

type playbackRequest struct {
	Path     string  `json:"path"`
	Position float64 `json:"position"`
	Duration float64 `json:"duration"`
}

type playbackResponse struct {
	Path      string  `json:"path"`
	Identity  string  `json:"identity"`
	Position  float64 `json:"position"`
	Duration  float64 `json:"duration"`
	UpdatedAt int64   `json:"updatedAt"`
	Exists    bool    `json:"exists"`
}

var playbackRecentHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if !d.user.Perm.Download {
		return http.StatusForbidden, fmt.Errorf("没有读取播放记录的权限")
	}
	limit := 100
	if value := r.URL.Query().Get("limit"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 || parsed > 100 {
			return http.StatusBadRequest, fmt.Errorf("播放记录数量应在1到100之间")
		}
		limit = parsed
	}
	entries, err := d.store.Playback.Recent(d.user.ID, limit)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	type response struct {
		playbackResponse
		WirePath string `json:"wirePath"`
		Name     string `json:"name"`
		Type     string `json:"type"`
	}
	result := make([]response, 0, len(entries))
	for _, entry := range entries {
		file, identity, status, failure := playbackFile(d, entry.Path)
		if failure != nil {
			if status >= 500 {
				return status, failure
			}
			continue // Missing or newly forbidden objects must not leak through history.
		}
		if identity != entry.Identity {
			continue // Do not relabel old progress as a replacement file.
		}
		result = append(result, response{
			playbackResponse: playbackResponse{Path: files.DisplayPath(file.Path), Identity: identity,
				Position: entry.Position, Duration: entry.Duration, UpdatedAt: entry.UpdatedAt, Exists: true},
			WirePath: files.EncodeWirePath(file.Path), Name: files.DisplayName(file.Name), Type: file.Type,
		})
	}
	return renderJSON(w, r, result)
})

var playbackGetHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	file, identity, status, err := playbackFile(d, r.URL.Query().Get("path"))
	if err != nil {
		return status, err
	}
	entry, err := d.store.Playback.Get(d.user.ID, file.Path, identity)
	if errors.Is(err, playback.ErrNotExist) {
		return renderJSON(w, r, playbackResponse{Path: file.Path, Identity: identity})
	}
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, playbackResponse{
		Path: entry.Path, Identity: entry.Identity, Position: entry.Position,
		Duration: entry.Duration, UpdatedAt: entry.UpdatedAt, Exists: true,
	})
})

var playbackPutHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var request playbackRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		return http.StatusBadRequest, fmt.Errorf("播放位置参数无效: %w", err)
	}
	if !finiteNonNegative(request.Position) || !finiteNonNegative(request.Duration) {
		return http.StatusBadRequest, fmt.Errorf("播放位置必须是有限的非负数")
	}
	file, identity, status, err := playbackFile(d, request.Path)
	if err != nil {
		return status, err
	}
	if request.Duration > 0 && request.Position > request.Duration {
		request.Position = request.Duration
	}
	entry, err := d.store.Playback.Save(
		d.user.ID, file.Path, identity, request.Position, request.Duration,
	)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, playbackResponse{
		Path: entry.Path, Identity: entry.Identity, Position: entry.Position,
		Duration: entry.Duration, UpdatedAt: entry.UpdatedAt, Exists: true,
	})
})

var playbackDeleteHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	file, _, status, err := playbackFile(d, r.URL.Query().Get("path"))
	if err != nil {
		return status, err
	}
	if err := d.store.Playback.Delete(d.user.ID, file.Path); err != nil {
		return http.StatusInternalServerError, err
	}
	return http.StatusNoContent, nil
})

func playbackFile(d *data, value string) (*files.FileInfo, string, int, error) {
	if !d.user.Perm.Download {
		return nil, "", http.StatusForbidden, fmt.Errorf("没有播放该视频的权限")
	}
	if value == "" {
		return nil, "", http.StatusBadRequest, fmt.Errorf("视频路径不能为空")
	}
	value = pathmeta.Clean(value)
	file, err := files.NewFileInfo(&files.FileOptions{
		Fs: d.user.Fs, Path: value, Modify: d.user.Perm.Modify,
		// Playback only needs a permission check and the file identity.  Do not
		// expand the resource here: expansion reads the file header and scans
		// the parent directory for subtitles, which makes every position poll
		// contend with the NAS filesystem.
		Expand: false, Checker: d,
	})
	if err != nil {
		return nil, "", errToStatus(err), err
	}
	if file.IsDir {
		return nil, "", http.StatusBadRequest, fmt.Errorf("播放位置仅适用于视频文件")
	}
	file.DetectTypeFromExt()
	// Extension detection covers normal video formats without touching the
	// file.  Keep the old header-based fallback for files whose extension is
	// not registered as a video, so unusual containers remain supported.
	if file.Type == "blob" {
		file, err = files.NewFileInfo(&files.FileOptions{
			Fs: d.user.Fs, Path: value, Modify: d.user.Perm.Modify,
			Expand: true, SkipSubtitles: true,
			ReadHeader: d.server.TypeDetectionByHeader, Checker: d,
		})
		if err != nil {
			return nil, "", errToStatus(err), err
		}
	}
	if file.Type != "video" && file.Type != "audio" {
		return nil, "", http.StatusBadRequest, fmt.Errorf("播放位置仅适用于音频和视频文件")
	}
	identity := "v1:" + strconv.FormatInt(file.Size, 10) + ":" + strconv.FormatInt(file.ModTime.UnixNano(), 10)
	return file, identity, 0, nil
}

func finiteNonNegative(value float64) bool {
	return value >= 0 && !math.IsNaN(value) && !math.IsInf(value, 0)
}

var playbackSelectedDeleteHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var request struct {
		Items []struct {
			WirePath  string `json:"wirePath"`
			Identity  string `json:"identity"`
			UpdatedAt int64  `json:"updatedAt"`
		} `json:"items"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&request); err != nil || len(request.Items) < 1 || len(request.Items) > 100 {
		return http.StatusBadRequest, fmt.Errorf("请选择1到100条播放记录")
	}
	refs := make([]playback.DeleteRef, 0, len(request.Items))
	for _, item := range request.Items {
		value, err := decodeResourceWirePath(item.WirePath)
		if err != nil || item.Identity == "" || item.UpdatedAt <= 0 {
			return http.StatusBadRequest, fmt.Errorf("播放记录标识无效")
		}
		refs = append(refs, playback.DeleteRef{Path: value, Identity: item.Identity, UpdatedAt: item.UpdatedAt})
	}
	deleted, err := d.store.Playback.DeleteSelected(d.user.ID, refs)
	if errors.Is(err, playback.ErrRevisionChanged) {
		return http.StatusConflict, fmt.Errorf("播放记录已变化，请刷新后重新选择")
	}
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, map[string]int{"deleted": deleted, "acknowledged": len(request.Items)})
})

var playbackClearHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	deleted, err := d.store.Playback.DeleteSelected(d.user.ID, nil)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, map[string]int{"deleted": deleted})
})
