package fbhttp

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"mime"
	"net/http"
	"net/url"
	gopath "path"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/mholt/archives"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/fileutils"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

// Browsers commonly request bytes=0- when opening an inline video. If the
// container metadata is at the end of a large file, ServeContent would honor
// that open-ended range by sending the entire file before the browser can
// request the tail metadata. Keep each open-ended inline video request
// bounded; the browser will issue follow-up ranges as needed for playback.
const inlineVideoRangeChunkSize int64 = 2 * 1024 * 1024

func boundInlineVideoRange(r *http.Request, file *files.FileInfo) {
	if r.URL.Query().Get("inline") != "true" || !isInlineVideoFile(file.Name) {
		return
	}

	rangeHeader := strings.TrimSpace(r.Header.Get("Range"))
	if !strings.HasPrefix(rangeHeader, "bytes=") {
		return
	}
	spec := strings.TrimPrefix(rangeHeader, "bytes=")
	if strings.Contains(spec, ",") || !strings.HasSuffix(spec, "-") {
		return
	}
	start, err := strconv.ParseInt(strings.TrimSuffix(spec, "-"), 10, 64)
	if err != nil || start < 0 {
		return
	}
	end := start + inlineVideoRangeChunkSize - 1
	r.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
}

func isInlineVideoFile(name string) bool {
	extension := strings.ToLower(filepath.Ext(name))
	if strings.HasPrefix(mime.TypeByExtension(extension), "video/") {
		return true
	}
	switch extension {
	case ".mkv", ".avi", ".flv", ".wmv", ".rm", ".rmvb", ".ts", ".m2ts":
		return true
	default:
		return false
	}
}

func slashClean(name string) string {
	if name == "" || name[0] != '/' {
		name = "/" + name
	}
	return gopath.Clean(name)
}

func parseQueryFiles(r *http.Request, f *files.FileInfo, _ *users.User) ([]string, error) {
	var fileSlice []string
	if wires, present := r.URL.Query()["fileWirePath"]; present {
		if len(wires) == 0 || len(wires) > 1000 || r.URL.Query().Has("files") {
			return nil, fmt.Errorf("ZIP选择参数无效")
		}
		seen := make(map[string]struct{}, len(wires))
		for _, wire := range wires {
			raw, err := decodeResourceWirePath(wire)
			if err != nil || !pathmeta.Contains(raw, f.Path) {
				return nil, fmt.Errorf("ZIP选择路径无效或不在请求目录内")
			}
			if _, exists := seen[raw]; !exists {
				seen[raw] = struct{}{}
				fileSlice = append(fileSlice, raw)
			}
		}
		return fileSlice, nil
	}
	names := strings.Split(r.URL.Query().Get("files"), ",")

	if len(names) == 0 {
		fileSlice = append(fileSlice, f.Path)
	} else {
		for _, name := range names {
			name, err := url.QueryUnescape(strings.ReplaceAll(name, "+", "%2B"))
			if err != nil {
				return nil, err
			}

			name = slashClean(name)
			fileSlice = append(fileSlice, gopath.Join(f.Path, name))
		}
	}

	return fileSlice, nil
}

func parseQueryAlgorithm(r *http.Request) (string, archives.Archival, error) {
	switch r.URL.Query().Get("algo") {
	case "zip", "true", "":
		return ".zip", archives.Zip{}, nil
	case "tar":
		return ".tar", archives.Tar{}, nil
	case "targz":
		return ".tar.gz", archives.CompressedArchive{Compression: archives.Gz{}, Archival: archives.Tar{}}, nil
	case "tarbz2":
		return ".tar.bz2", archives.CompressedArchive{Compression: archives.Bz2{}, Archival: archives.Tar{}}, nil
	case "tarxz":
		return ".tar.xz", archives.CompressedArchive{Compression: archives.Xz{}, Archival: archives.Tar{}}, nil
	case "tarlz4":
		return ".tar.lz4", archives.CompressedArchive{Compression: archives.Lz4{}, Archival: archives.Tar{}}, nil
	case "tarsz":
		return ".tar.sz", archives.CompressedArchive{Compression: archives.Sz{}, Archival: archives.Tar{}}, nil
	case "tarbr":
		return ".tar.br", archives.CompressedArchive{Compression: archives.Brotli{}, Archival: archives.Tar{}}, nil
	case "tarzst":
		return ".tar.zst", archives.CompressedArchive{Compression: archives.Zstd{}, Archival: archives.Tar{}}, nil
	default:
		return "", nil, errors.New("format not implemented")
	}
}

func setContentDisposition(w http.ResponseWriter, r *http.Request, file *files.FileInfo) {
	if r.URL.Query().Get("inline") == "true" {
		// As per RFC6266 section 4.3
		w.Header().Set("Content-Disposition", "inline; filename*=utf-8''"+url.PathEscape(file.Name))
	} else {
		// As per RFC6266 section 4.3
		w.Header().Set("Content-Disposition", "attachment; filename*=utf-8''"+url.PathEscape(file.Name))
	}
}

var rawHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (status int, err error) {
	if !d.user.Perm.Download {
		return http.StatusAccepted, nil
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

	tracker := newTransferTracker(w, r, d, file)
	if tracker != nil {
		defer func() {
			tracker.finish(status, err)
		}()
		w = tracker
	}

	if files.IsNamedPipe(file.Mode) {
		setContentDisposition(w, r, file)
		return 0, nil
	}

	if !file.IsDir {
		return rawFileHandler(w, r, file)
	}

	return rawDirHandler(w, r, d, file)
})

func getFiles(d *data, path, commonPath string, strict ...bool) ([]archives.FileInfo, error) {
	if !d.Check(path) {
		return nil, nil
	}

	info, err := d.user.Fs.Stat(path)
	if err != nil {
		return nil, err
	}

	var archiveFiles []archives.FileInfo

	if path != commonPath {
		nameInArchive := strings.TrimPrefix(path, commonPath)
		nameInArchive = strings.TrimPrefix(nameInArchive, "/")

		archiveFiles = append(archiveFiles, archives.FileInfo{
			FileInfo:      info,
			NameInArchive: nameInArchive,
			Open: func() (fs.File, error) {
				return d.user.Fs.Open(path)
			},
		})
	}

	if info.IsDir() {
		f, err := d.user.Fs.Open(path)
		if err != nil {
			return nil, err
		}
		defer func() { _ = f.Close() }()

		names, err := f.Readdirnames(0)
		if err != nil {
			return nil, err
		}

		for _, name := range names {
			fPath := gopath.Join(path, name)
			subFiles, err := getFiles(d, fPath, commonPath, strict...)
			if err != nil {
				if len(strict) > 0 && strict[0] {
					return nil, err
				}
				log.Printf("Failed to get files from %s: %v", fPath, err)
				continue
			}
			archiveFiles = append(archiveFiles, subFiles...)
		}
	}

	return archiveFiles, nil
}

func rawDirHandler(w http.ResponseWriter, r *http.Request, d *data, file *files.FileInfo) (int, error) {
	filenames, err := parseQueryFiles(r, file, d.user)
	if err != nil {
		if r.URL.Query().Has("fileWirePath") {
			return http.StatusBadRequest, err
		}
		return http.StatusInternalServerError, err
	}
	explicit := r.URL.Query().Has("fileWirePath")
	if explicit {
		if algo := r.URL.Query().Get("algo"); algo != "" && algo != "true" && algo != "zip" {
			return http.StatusBadRequest, fmt.Errorf("原始路径导出仅支持ZIP")
		}
		for _, name := range filenames {
			if !d.Check(name) {
				return http.StatusForbidden, fmt.Errorf("没有访问所选ZIP项目的权限")
			}
			if _, err := d.user.Fs.Stat(name); err != nil {
				return errToStatus(err), err
			}
		}
	}

	extension, archiver, err := parseQueryAlgorithm(r)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	commonDir := fileutils.CommonPrefix('/', filenames...)
	if explicit {
		commonDir = file.Path
	}

	var allFiles []archives.FileInfo
	for _, fname := range filenames {
		archiveFiles, err := getFiles(d, fname, commonDir, explicit)
		if err != nil {
			if explicit {
				return errToStatus(err), err
			}
			log.Printf("Failed to get files from %s: %v", fname, err)
			continue
		}
		allFiles = append(allFiles, archiveFiles...)
	}

	name := gopath.Base(commonDir)
	if name == "." || name == "" || name == "/" {
		if file.Name != "" {
			name = file.Name
		} else {
			actual, statErr := file.Fs.Stat(".")
			if statErr != nil {
				return http.StatusInternalServerError, statErr
			}
			name = actual.Name()
		}
	}
	if len(filenames) > 1 {
		name = "_" + name
	}
	name += extension
	w.Header().Set("Content-Disposition", "attachment; filename*=utf-8''"+url.PathEscape(name))
	if explicit {
		// A failed source read must not let archives.Zip's deferred Close emit a
		// valid-looking EOCD for a partial export after HTTP headers were sent.
		failure := &zipStreamFailure{}
		for index := range allFiles {
			open := allFiles[index].Open
			expected := allFiles[index].Size()
			allFiles[index].Open = func() (fs.File, error) {
				file, err := open()
				if err != nil {
					failure.err = err
					return nil, err
				}
				return &zipSourceReader{File: file, expected: expected, failure: failure}, nil
			}
		}
		w.Header().Set("Content-Type", "application/zip; fileway-selection=wire-v1")
		if err := archiver.Archive(r.Context(), zipGuardWriter{writer: w, failure: failure}, allFiles); err != nil {
			return http.StatusInternalServerError, err
		}
		if failure.err != nil {
			return http.StatusInternalServerError, failure.err
		}
		return 0, nil
	}

	if err := archiver.Archive(r.Context(), w, allFiles); err != nil {
		return http.StatusInternalServerError, err
	}

	return 0, nil
}

type zipStreamFailure struct{ err error }
type zipGuardWriter struct {
	writer  io.Writer
	failure *zipStreamFailure
}

func (guard zipGuardWriter) Write(data []byte) (int, error) {
	if guard.failure.err != nil {
		return 0, guard.failure.err
	}
	n, err := guard.writer.Write(data)
	if err != nil {
		guard.failure.err = err
	} else if n != len(data) {
		guard.failure.err = io.ErrShortWrite
		err = io.ErrShortWrite
	}
	return n, err
}

type zipSourceReader struct {
	fs.File
	expected int64
	read     int64
	failure  *zipStreamFailure
}

func (reader *zipSourceReader) Read(data []byte) (int, error) {
	n, err := reader.File.Read(data)
	reader.read += int64(n)
	if err != nil && err != io.EOF {
		reader.failure.err = err
	}
	if reader.read > reader.expected || err == io.EOF && reader.read != reader.expected {
		reader.failure.err = io.ErrUnexpectedEOF
		return n, io.ErrUnexpectedEOF
	}
	return n, err
}

func rawFileHandler(w http.ResponseWriter, r *http.Request, file *files.FileInfo) (int, error) {
	version := file.SourceVersion()
	if version != "" {
		w.Header().Set("X-Fileway-Source-Version", version)
		expected := strings.TrimSpace(r.Header.Get("X-Fileway-Source-Version"))
		if expected == "" {
			expected = strings.TrimSpace(r.URL.Query().Get("sourceVersion"))
		}
		if expected != "" && expected != version {
			return http.StatusPreconditionFailed, fmt.Errorf("文件版本已变化")
		}
	}
	fd, err := file.Fs.Open(file.Path)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	defer func() { _ = fd.Close() }()

	setContentDisposition(w, r, file)
	w.Header().Add("Content-Security-Policy", `script-src 'none';`)
	w.Header().Set("Cache-Control", "private")
	boundInlineVideoRange(r, file)
	http.ServeContent(w, r, file.Name, file.ModTime, fd)
	return 0, nil
}
