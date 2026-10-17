package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/shirou/gopsutil/v4/disk"
	"github.com/spf13/afero"

	fberrors "github.com/Kkwans/nas-file-browser/backend/errors"
	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/fileutils"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/Kkwans/nas-file-browser/backend/trash"
)

var resourceGetHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	checksum := r.URL.Query().Get("checksum")
	if checksum != "" && !d.user.Perm.Download {
		return http.StatusForbidden, fmt.Errorf("没有读取文件内容的权限")
	}
	metadataOnly := checksum != "" || r.URL.Query().Get("metadata") == "1" || r.URL.Query().Get("metadata") == "true"
	file, err := files.NewFileInfo(&files.FileOptions{
		Fs:         d.user.Fs,
		Path:       r.URL.Path,
		Modify:     d.user.Perm.Modify,
		Expand:     !metadataOnly,
		ReadHeader: d.server.TypeDetectionByHeader,
		CalcImgRes: d.user.Perm.Download && r.URL.Query().Get("dimensions") == "1",
		Checker:    d,
		Content:    d.user.Perm.Download && !metadataOnly,
	})
	if err != nil {
		return errToStatus(err), err
	}
	// Metadata requests intentionally skip directory expansion. Return the
	// target's base metadata before the listing-only sort path below; a
	// directory created with Expand=false has no embedded Listing value.
	if metadataOnly {
		if checksum != "" {
			err := file.ChecksumContext(r.Context(), checksum)
			switch {
			case errors.Is(err, fberrors.ErrInvalidOption), errors.Is(err, fberrors.ErrIsDirectory):
				return http.StatusBadRequest, fmt.Errorf("不支持的校验算法或文件类型")
			case errors.Is(err, files.ErrChecksumSourceChanged):
				return http.StatusConflict, fmt.Errorf("文件在计算期间已变化，请刷新后重试")
			case err != nil:
				return errToStatus(err), err
			}
		}
		return renderJSON(w, r, file)
	}

	encoding := r.Header.Get("X-Encoding")
	if file.IsDir {
		file.Sorting = d.user.Sorting
		file.ApplySort()
		return renderJSON(w, r, file)
	} else if encoding == "true" {
		if !d.user.Perm.Download {
			return http.StatusAccepted, nil
		}
		if file.Type != "text" {
			return renderJSON(w, r, file)
		}

		f, err := d.user.Fs.Open(r.URL.Path)
		if err != nil {
			return errToStatus(err), err
		}
		defer func() { _ = f.Close() }()

		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", fmt.Sprintf("%d", file.Size))
		w.WriteHeader(http.StatusOK)
		// Stream data directly instead of loading entire file into memory.
		_, err = io.Copy(w, f)
		return 0, err
	}

	return renderJSON(w, r, file)
})

func resourceDeleteHandler(fileCache FileCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if r.URL.Path == "/" || !d.user.Perm.Delete {
			return http.StatusForbidden, fmt.Errorf("没有删除权限")
		}

		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       r.URL.Path,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: d.server.TypeDetectionByHeader,
			Checker:    d,
		})
		if err != nil {
			return errToStatus(err), err
		}

		mode := r.URL.Query().Get("mode")
		switch mode {
		case "trash":
			return moveResourceToTrash(w, r, d, fileCache, file)
		case "", "permanent":
			// The empty mode intentionally preserves the historical permanent
			// delete contract for older clients. The new UI always sends trash.
		default:
			return http.StatusBadRequest, fmt.Errorf("不支持的删除模式 %q", mode)
		}

		if err := permanentDeleteResource(r.Context(), d, fileCache, file.Path); err != nil {
			return errToStatus(err), err
		}
		return http.StatusNoContent, nil
	})
}

// permanentDeleteResource is shared by the legacy immediate endpoint and the
// delayed deletion task. It keeps metadata, shares, thumbnails and hooks
// transactional enough to restore metadata when the filesystem operation
// fails.
func permanentDeleteResource(ctx context.Context, d *data, fileCache FileCache, resourcePath string) error {
	if resourcePath == "/" || !d.user.Perm.Delete {
		return fmt.Errorf("没有删除权限")
	}
	file, err := files.NewFileInfo(&files.FileOptions{
		Fs: d.user.Fs, Path: resourcePath, Modify: d.user.Perm.Modify,
		Expand: false, ReadHeader: d.server.TypeDetectionByHeader, Checker: d,
	})
	if err != nil {
		return err
	}
	favoriteMutation, tagMutation, recentMutation, err := removePathMetadata(d, file.Path)
	if err != nil {
		return fmt.Errorf("清理关联元数据失败，文件未删除: %w", err)
	}
	if err := d.store.Share.DeleteWithPathPrefix(file.Path); err != nil {
		log.Printf("WARNING: Error(s) occurred while deleting associated shares with file: %s", err)
	}
	if err := delThumbs(ctx, fileCache, file); err != nil {
		restoreErr := restorePathMetadata(d, favoriteMutation, tagMutation, recentMutation)
		return fmt.Errorf("清理缩略图失败，文件未删除: %w", errors.Join(err, restoreErr))
	}
	if err := d.RunHook(func() error {
		return d.user.Fs.RemoveAll(file.Path)
	}, "delete", file.Path, "", d.user); err != nil {
		restoreErr := restorePathMetadata(d, favoriteMutation, tagMutation, recentMutation)
		return fmt.Errorf("删除文件失败，关联元数据已回滚: %w", errors.Join(err, restoreErr))
	}
	recordHistory(d, "file.delete", file.Path, "", history.StatusSuccess)
	return nil
}

func moveResourceToTrash(w http.ResponseWriter, r *http.Request, d *data, fileCache FileCache, file *files.FileInfo) (int, error) {
	item, status, err := moveResourceToTrashItem(r.Context(), d, fileCache, file)
	if err != nil {
		return status, err
	}
	return renderJSON(w, r, item.Public())
}

func moveResourceToTrashItem(ctx context.Context, d *data, fileCache FileCache, file *files.FileInfo) (*trash.Item, int, error) {
	if fileCache == nil {
		return nil, http.StatusInternalServerError, fmt.Errorf("文件缓存服务不可用，文件未移入回收站")
	}
	if err := delThumbs(ctx, fileCache, file); err != nil {
		return nil, errToStatus(err), fmt.Errorf("清理缩略图失败，文件未移入回收站: %w", err)
	}

	service := newTrashService(d, d.user)
	var item *trash.Item
	err := d.RunHook(func() error {
		var moveErr error
		item, moveErr = service.Move(d.user.ID, d.user.Username, file.Path)
		return moveErr
	}, "delete", file.Path, "", d.user)
	if err != nil {
		if item != nil {
			_, rollbackErr := service.Restore(d.user.ID, item.ID, false, trash.ConflictFail)
			err = errors.Join(err, rollbackErr)
		}
		return nil, errToStatus(err), fmt.Errorf("文件未移入回收站: %w", err)
	}

	if err := d.store.Share.DeleteWithPathPrefix(file.Path); err != nil {
		log.Printf("WARNING: Error(s) occurred while deleting associated shares with trashed file: %s", err)
	}
	recordHistory(d, "trash.move", file.Path, item.ID, history.StatusSuccess)
	return item, 0, nil
}

func resourcePostHandler(fileCache FileCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (status int, err error) {
		if !d.user.Perm.Create || !d.Check(r.URL.Path) {
			return http.StatusForbidden, fmt.Errorf("没有创建权限")
		}

		// Directories creation on POST.
		if strings.HasSuffix(r.URL.Path, "/") {
			err := d.user.Fs.MkdirAll(r.URL.Path, d.settings.DirMode)
			if err == nil {
				recordHistory(d, "file.mkdir", r.URL.Path, "", history.StatusSuccess)
			}
			return errToStatus(err), err
		}

		tracker := newUploadTracker(r, d)
		if tracker != nil {
			defer func() { tracker.finish(status, err) }()
		}

		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       r.URL.Path,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: d.server.TypeDetectionByHeader,
			Checker:    d,
		})
		override := r.URL.Query().Get("override") == "true"
		if err == nil {
			if !override {
				return http.StatusConflict, fmt.Errorf("file already exists")
			}

			// Permission for overwriting the file
			if !d.user.Perm.Modify {
				return http.StatusForbidden, fmt.Errorf("没有修改权限")
			}

			err = delThumbs(r.Context(), fileCache, file)
			if err != nil {
				return errToStatus(err), err
			}
		}

		writeInvoked := false
		createdExclusive := false
		err = d.RunHook(func() error {
			writeInvoked = true
			var info os.FileInfo
			var writeErr error
			body := tracker.Reader(r.Body)
			if override {
				info, writeErr = writeFile(d.user.Fs, r.URL.Path, body, d.settings.FileMode, d.settings.DirMode)
			} else {
				info, writeErr = writeFileExclusive(d.user.Fs, r.URL.Path, body, d.settings.FileMode, d.settings.DirMode)
				createdExclusive = writeErr == nil
			}
			if writeErr != nil {
				return writeErr
			}

			etag := fmt.Sprintf(`"%x%x"`, info.ModTime().UnixNano(), info.Size())
			w.Header().Set("ETag", etag)
			return nil
		}, "upload", r.URL.Path, "", d.user)

		if err != nil && writeInvoked && (override || createdExclusive) {
			_ = d.user.Fs.RemoveAll(r.URL.Path)
		} else {
			if err == nil {
				recordHistory(d, "file.upload", r.URL.Path, "", history.StatusSuccess)
			}
		}

		return errToStatus(err), err
	})
}

var resourcePutHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if !d.user.Perm.Modify || !d.Check(r.URL.Path) {
		return http.StatusForbidden, fmt.Errorf("没有修改权限")
	}

	// Only allow PUT for files.
	if strings.HasSuffix(r.URL.Path, "/") {
		return http.StatusMethodNotAllowed, fmt.Errorf("不能直接修改目录内容")
	}
	if r.URL.Query().Get("conditional") == "true" {
		return conditionalResourceEdit(w, r, d)
	}
	// Ordinary Web edits share this serialization boundary with native edits.
	textEditMu.Lock()
	defer textEditMu.Unlock()

	exists, err := afero.Exists(d.user.Fs, r.URL.Path)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	if !exists {
		return http.StatusNotFound, fmt.Errorf("file not found")
	}

	err = d.RunHook(func() error {
		info, writeErr := writeFile(d.user.Fs, r.URL.Path, r.Body, d.settings.FileMode, d.settings.DirMode)
		if writeErr != nil {
			return writeErr
		}

		etag := fmt.Sprintf(`"%x%x"`, info.ModTime().UnixNano(), info.Size())
		w.Header().Set("ETag", etag)
		return nil
	}, "save", r.URL.Path, "", d.user)
	if err == nil {
		recordHistory(d, "file.save", r.URL.Path, "", history.StatusSuccess)
	}

	return errToStatus(err), err
})

func resourcePatchHandler(fileCache FileCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		src := r.URL.Path
		query := r.URL.Query()
		if query.Has("destinationWirePath") && query.Get("destinationWirePath") == "" {
			return http.StatusBadRequest, fmt.Errorf("目标原始路径不能为空")
		}
		dst, err := resourceOperationPath(query.Get("destination"), query.Get("destinationWirePath"), false)
		if err != nil {
			return http.StatusBadRequest, fmt.Errorf("目标路径无效: %w", err)
		}
		action := r.URL.Query().Get("action")
		src = normalizeResourcePath(src)
		if !d.Check(src) || !d.Check(dst) {
			return http.StatusForbidden, fmt.Errorf("没有权限执行此操作")
		}
		if dst == "/" || src == "/" {
			return http.StatusForbidden, fmt.Errorf("没有权限执行此操作")
		}

		err = checkParent(src, dst)
		if err != nil {
			return http.StatusBadRequest, err
		}

		srcInfo, _ := d.user.Fs.Stat(src)
		dstInfo, _ := d.user.Fs.Stat(dst)
		same := sameExistingFile(srcInfo, dstInfo)
		if action == "copy" && query.Get("rename") != "true" && (src == dst || same) {
			return http.StatusBadRequest, fmt.Errorf("不能将文件复制到自身")
		}
		overwrite := query.Get("override") == "true" && query.Get("rename") != "true"

		if action != "rename" || !same {
			override := r.URL.Query().Get("override") == "true"
			rename := r.URL.Query().Get("rename") == "true"
			if !override && !rename {
				if _, err = d.user.Fs.Stat(dst); err == nil {
					return http.StatusConflict, fmt.Errorf("文件冲突")
				}
			}
			if rename {
				dst = addVersionSuffix(dst, d.user.Fs)
			}

			if override && !d.user.Perm.Modify {
				return http.StatusForbidden, fmt.Errorf("没有权限执行此操作")
			}
		}

		err = d.RunHook(func() error {
			if action == "copy" {
				return patchCopyAction(src, dst, d, overwrite)
			}
			return patchAction(r.Context(), action, src, dst, d, fileCache)
		}, action, src, dst, d.user)
		if err == nil && action == "rename" {
			w.Header().Set("X-Resource-Destination", url.PathEscape(files.DisplayPath(dst)))
		}
		if err == nil {
			w.Header().Set("X-Resource-Destination-WirePath", files.EncodeWirePath(dst))
			recordHistory(d, "file."+action, dst, src, history.StatusSuccess)
		}

		return errToStatus(err), err
	})
}

// URL.Query().Get already performs percent-decoding. Cleaning that decoded
// value exactly once preserves literal names such as "%2F-not-a-directory"
// instead of turning them into a different path through a second decode.
func normalizeResourcePath(value string) string {
	return path.Clean("/" + value)
}

// Operation wire identities are decoded once. Reject dot traversal before the
// shared decoder cleans it; backslash filenames retain the existing OS policy
// enforced by data.Check (legal on Linux, rejected on Windows).
func decodeOperationWirePath(wire string) (string, error) {
	for _, segment := range strings.Split(wire, "/") {
		decoded, err := url.PathUnescape(segment)
		if err != nil || decoded == "." || decoded == ".." {
			return "", fmt.Errorf("原始路径编码无效或包含遍历")
		}
	}
	return decodeResourceWirePath(wire)
}

func literalOperationPath(value string) (string, error) {
	if value == "" || strings.ContainsRune(value, '\x00') {
		return "", fmt.Errorf("路径不能为空或包含空字节")
	}
	for _, segment := range strings.Split(value, "/") {
		if segment == "." || segment == ".." {
			return "", fmt.Errorf("路径不能包含遍历")
		}
	}
	return normalizeResourcePath(value), nil
}

func resourceOperationPath(display, wire string, allowFilesRoute bool) (string, error) {
	if wire == "" {
		if allowFilesRoute {
			return normalizeTransferPath(display)
		}
		return literalOperationPath(display)
	}
	raw, err := decodeOperationWirePath(wire)
	if err != nil {
		return "", err
	}
	if display != "" {
		literal, literalErr := literalOperationPath(display)
		if literalErr == nil && literal == files.DisplayPath(raw) {
			return raw, nil
		}
		if allowFilesRoute && (display == "/files" || strings.HasPrefix(display, "/files/")) {
			decoded, routeErr := normalizeTransferPath(display)
			if routeErr == nil && decoded == raw {
				return raw, nil
			}
		}
		return "", fmt.Errorf("显示路径与原始路径不一致")
	}
	return raw, nil
}

const taskWirePathEncoding = "wire-v1"

// Persisted paths are never /files UI routes. A legacy U+FFFD may be damage
// caused by encoding/json replacing arbitrary bytes; do not guess a sibling.
func restoreTaskPath(display, wire, encoding string) (string, error) {
	if encoding == taskWirePathEncoding {
		if wire == "" {
			return "", fmt.Errorf("任务缺少原始路径，请重新创建")
		}
		return resourceOperationPath(display, wire, false)
	}
	if encoding != "" || wire != "" {
		return "", fmt.Errorf("不支持的任务路径编码，请重新创建")
	}
	if !utf8.ValidString(display) || strings.ContainsRune(display, '\ufffd') {
		return "", fmt.Errorf("旧任务路径身份无法确认，请重新创建任务")
	}
	return literalOperationPath(display)
}

func sameExistingFile(left, right os.FileInfo) bool {
	return left != nil && right != nil && os.SameFile(left, right)
}

func checkParent(src, dst string) error {
	rel, err := filepath.Rel(src, dst)
	if err != nil {
		return err
	}

	rel = filepath.ToSlash(rel)
	if !strings.HasPrefix(rel, "../") && rel != ".." && rel != "." {
		return fberrors.ErrSourceIsParent
	}

	return nil
}

func addVersionSuffix(source string, afs afero.Fs) string {
	counter := 1
	dir, name := path.Split(source)
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)

	for {
		if _, err := afs.Stat(source); err != nil {
			break
		}
		renamed := fmt.Sprintf("%s(%d)%s", base, counter, ext)
		source = path.Join(dir, renamed)
		counter++
	}

	return source
}

func writeFile(afs afero.Fs, dst string, in io.Reader, fileMode, dirMode fs.FileMode) (info os.FileInfo, err error) {
	return writeFileWithFlags(afs, dst, in, fileMode, dirMode, os.O_RDWR|os.O_CREATE|os.O_TRUNC, false)
}

func writeFileExclusive(afs afero.Fs, dst string, in io.Reader, fileMode, dirMode fs.FileMode) (info os.FileInfo, err error) {
	return writeFileWithFlags(afs, dst, in, fileMode, dirMode, os.O_WRONLY|os.O_CREATE|os.O_EXCL, true)
}

func writeFileWithFlags(
	afs afero.Fs,
	dst string,
	in io.Reader,
	fileMode, dirMode fs.FileMode,
	flags int,
	removeOnError bool,
) (info os.FileInfo, err error) {
	dir := path.Dir(filepath.ToSlash(dst))
	if dir != "." && dir != "/" {
		if err = afs.MkdirAll(dir, dirMode); err != nil {
			return nil, err
		}
	}

	file, err := afs.OpenFile(dst, flags, fileMode)
	if err != nil {
		return nil, err
	}
	defer func() {
		if closeErr := file.Close(); err == nil {
			err = closeErr
		}
		if err != nil && removeOnError {
			_ = afs.Remove(dst)
		}
	}()

	_, err = io.Copy(file, in)
	if err != nil {
		return nil, err
	}

	// Sync the file to ensure all data is written to storage.
	// to prevent file corruption.
	if err := file.Sync(); err != nil {
		return nil, err
	}

	// Gets the info about the file.
	info, err = file.Stat()
	if err != nil {
		return nil, err
	}

	return info, nil
}

func delThumbs(ctx context.Context, fileCache FileCache, file *files.FileInfo) error {
	for _, previewSizeName := range PreviewSizeNames() {
		size, _ := ParsePreviewSize(previewSizeName)
		if err := fileCache.Delete(ctx, previewCacheKey(file, size)); err != nil {
			if ctxErr := context.Cause(ctx); ctxErr != nil {
				return ctxErr
			}
			log.Printf("WARNING: 清理 %s 的预览缓存失败，文件操作仍继续: %v", file.Path, err)
		}
	}

	return nil
}

func patchCopyAction(src, dst string, d *data, overwrite bool) error {
	if !d.user.Perm.Create {
		return fberrors.ErrPermissionDenied
	}
	return fileutils.CopyWithOverwrite(d.user.Fs, src, dst, d.settings.FileMode, d.settings.DirMode, overwrite)
}

func patchAction(ctx context.Context, action, src, dst string, d *data, fileCache FileCache) error {
	switch action {
	case "copy":
		return patchCopyAction(src, dst, d, false)
	case "rename":
		if !d.user.Perm.Rename {
			return fberrors.ErrPermissionDenied
		}
		src = path.Clean("/" + src)
		dst = path.Clean("/" + dst)

		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       src,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: false,
			Checker:    d,
		})
		if err != nil {
			return err
		}

		// delete thumbnails
		err = delThumbs(ctx, fileCache, file)
		if err != nil {
			return err
		}

		if err := fileutils.MoveFile(d.user.Fs, src, dst, d.settings.FileMode, d.settings.DirMode); err != nil {
			return err
		}

		if err := rewritePathMetadata(d, src, dst); err != nil {
			rollbackErr := fileutils.MoveFile(d.user.Fs, dst, src, d.settings.FileMode, d.settings.DirMode)
			return fmt.Errorf("文件已移动但关联元数据更新失败，已尝试回滚: %w", errors.Join(err, rollbackErr))
		}
		return nil
	default:
		return fmt.Errorf("不支持的操作 %s: %w", action, fberrors.ErrInvalidRequestParams)
	}
}

func rewritePathMetadata(d *data, from, to string) error {
	mapper := metadataPathMapper(d, from, to)
	favoriteMutation, err := d.store.Favorites.RewritePathPrefix(from, to, mapper)
	if err != nil {
		return err
	}

	tagMutation, err := d.store.Tags.RewritePathPrefix(from, to, mapper)
	if err != nil {
		return errors.Join(err, d.store.Favorites.RestorePathMutation(favoriteMutation))
	}
	if d.store.Recent != nil {
		if _, err := d.store.Recent.RewritePathPrefix(from, to, mapper); err != nil {
			return errors.Join(
				err,
				d.store.Tags.RestorePathMutation(tagMutation),
				d.store.Favorites.RestorePathMutation(favoriteMutation),
			)
		}
	}
	return nil
}

func removePathMetadata(d *data, prefix string) (*favorites.PathMutation, *tags.PathMutation, *recent.PathMutation, error) {
	mapper := metadataPathMapper(d, prefix, prefix)
	favoriteMutation, err := d.store.Favorites.RemovePathPrefix(prefix, mapper)
	if err != nil {
		return nil, nil, nil, err
	}

	tagMutation, err := d.store.Tags.RemovePathPrefix(prefix, mapper)
	if err != nil {
		return nil, nil, nil, errors.Join(err, d.store.Favorites.RestorePathMutation(favoriteMutation))
	}
	if d.store.Recent == nil {
		return favoriteMutation, tagMutation, nil, nil
	}
	recentMutation, err := d.store.Recent.RemovePathPrefix(prefix, mapper)
	if err != nil {
		return nil, nil, nil, errors.Join(
			err,
			d.store.Tags.RestorePathMutation(tagMutation),
			d.store.Favorites.RestorePathMutation(favoriteMutation),
		)
	}
	return favoriteMutation, tagMutation, recentMutation, nil
}

func restorePathMetadata(d *data, favoriteMutation *favorites.PathMutation, tagMutation *tags.PathMutation, recentMutation *recent.PathMutation) error {
	var recentErr error
	if d.store.Recent != nil {
		recentErr = d.store.Recent.RestorePathMutation(recentMutation)
	}
	return errors.Join(
		d.store.Favorites.RestorePathMutation(favoriteMutation),
		d.store.Tags.RestorePathMutation(tagMutation),
		recentErr,
	)
}

// RecursiveEntry is a single file/directory entry returned by the recursive listing endpoint.
type RecursiveEntry struct {
	Path    string    `json:"path"`
	Name    string    `json:"name"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"modified"`
	IsDir   bool      `json:"isDir"`
}

// resourceGetRecursiveHandler returns a flat list of every file and directory
// under the requested path, walking the tree recursively on the server side
// so the client only needs a single HTTP call.
var resourceGetRecursiveHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	rootPath := r.URL.Path
	if rootPath == "" {
		rootPath = "/"
	}

	// Make sure the root itself exists and is a directory.
	info, err := d.user.Fs.Stat(rootPath)
	if err != nil {
		return errToStatus(err), err
	}
	if !info.IsDir() {
		return http.StatusBadRequest, fmt.Errorf("路径不是目录")
	}

	entries := make([]RecursiveEntry, 0)

	err = afero.Walk(d.user.Fs, rootPath, func(fPath string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // skip entries we cannot read
		}

		// Skip the root directory itself.
		if fPath == rootPath {
			return nil
		}

		// Respect user rules.
		if !d.Check(fPath) {
			if info.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}

		entries = append(entries, RecursiveEntry{
			Path:    fPath,
			Name:    info.Name(),
			Size:    info.Size(),
			ModTime: info.ModTime(),
			IsDir:   info.IsDir(),
		})
		return nil
	})
	if err != nil {
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, entries)
})

type DiskUsageResponse struct {
	Total uint64 `json:"total"`
	Used  uint64 `json:"used"`
}

var diskUsage = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	file, err := files.NewFileInfo(&files.FileOptions{
		Fs:         d.user.Fs,
		Path:       r.URL.Path,
		Modify:     d.user.Perm.Modify,
		Expand:     false,
		ReadHeader: false,
		Checker:    d,
		Content:    false,
	})
	if err != nil {
		return errToStatus(err), err
	}
	fPath := file.RealPath()
	if !file.IsDir {
		return renderJSON(w, r, &DiskUsageResponse{
			Total: 0,
			Used:  0,
		})
	}

	usage, err := disk.UsageWithContext(r.Context(), fPath)
	if err != nil {
		return errToStatus(err), err
	}
	return renderJSON(w, r, &DiskUsageResponse{
		Total: usage.Total,
		Used:  usage.Used,
	})
})
