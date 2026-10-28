package fbhttp

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/mediatools"
	"github.com/gorilla/mux"
)

const sourceTextLimit int64 = 16 << 20
const sourceFontLimit int64 = 8 << 20
const sourcePGSLimit int64 = 500 << 20
const sourceAssetCacheLimit int64 = 1 << 30

// A single bounded extraction lane deduplicates identical requests by checking
// the identity-keyed disk cache after acquiring the lane. No full PGS file is
// retained in the server heap. Requests waiting for the lane remain cancellable.
type mediaAssetService struct {
	directory string
	lane      chan struct{}
}

func newMediaAssetService(directory string) *mediaAssetService {
	return &mediaAssetService{directory: directory, lane: make(chan struct{}, 1)}
}

type boundedAssetWriter struct {
	file      *os.File
	remaining int64
	cancel    context.CancelFunc
}

func (w *boundedAssetWriter) Write(value []byte) (int, error) {
	if int64(len(value)) > w.remaining {
		w.cancel()
		return 0, errors.New("source asset exceeds its size limit")
	}
	n, err := w.file.Write(value)
	w.remaining -= int64(n)
	return n, err
}

func (service *mediaAssetService) prepare(ctx context.Context, key string, limit int64, run func(context.Context, io.Writer) error) (*os.File, error) {
	select {
	case service.lane <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	defer func() { <-service.lane }()
	if err := os.MkdirAll(service.directory, 0o700); err != nil {
		return nil, err
	}
	name := filepath.Join(service.directory, key)
	if info, err := os.Lstat(name); err == nil && info.Mode().IsRegular() && info.Size() > 0 && info.Size() <= limit {
		_ = os.Chtimes(name, time.Now(), time.Now())
		return os.Open(name)
	}
	entries, err := os.ReadDir(service.directory)
	if err != nil {
		return nil, err
	}
	var cached []os.FileInfo
	var total int64
	for _, entry := range entries {
		if !hlsAssetKey(entry.Name()) {
			continue
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		cached = append(cached, info)
		total += info.Size()
	}
	sort.Slice(cached, func(i, j int) bool { return cached[i].ModTime().Before(cached[j].ModTime()) })
	for _, info := range cached {
		if total+limit <= sourceAssetCacheLimit && time.Since(info.ModTime()) < 24*time.Hour {
			break
		}
		if err := os.Remove(filepath.Join(service.directory, info.Name())); err == nil {
			total -= info.Size()
		}
	}
	if total+limit > sourceAssetCacheLimit {
		return nil, errors.New("source asset cache is busy")
	}
	file, err := os.CreateTemp(service.directory, ".extract-")
	if err != nil {
		return nil, err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	work, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	writer := &boundedAssetWriter{file: file, remaining: limit, cancel: cancel}
	if err := run(work, writer); err != nil {
		return nil, err
	}
	if writer.remaining == limit {
		return nil, errors.New("source asset is empty")
	}
	if err := file.Sync(); err != nil {
		return nil, err
	}
	if err := file.Close(); err != nil {
		return nil, err
	}
	if err := os.Rename(file.Name(), name); err != nil {
		return nil, err
	}
	return os.Open(name)
}

func hlsAssetKey(value string) bool {
	if len(value) != 64 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func sourceAssetKey(d *data, file *files.FileInfo, identity, kind string, index int) string {
	key := fmt.Sprintf("source-asset-v1\x00%d\x00%s\x00%s\x00%s\x00%s\x00%d", d.user.ID, file.RealPath(), identity, d.user.Scope, kind, index)
	value := sha256.Sum256([]byte(key))
	return hex.EncodeToString(value[:])
}

func sourceSubtitleFormat(codec string) (format, extension, mime string, limit int64) {
	switch codec {
	case "ass", "ssa":
		return "ass", "ass", "text/x-ssa; charset=utf-8", sourceTextLimit
	case "subrip":
		return "srt", "srt", "application/x-subrip; charset=utf-8", sourceTextLimit
	case "webvtt":
		return "webvtt", "vtt", "text/vtt; charset=utf-8", sourceTextLimit
	case "hdmv_pgs_subtitle":
		return "sup", "sup", "application/octet-stream", sourcePGSLimit
	case "mov_text", "text":
		return "webvtt", "vtt", "text/vtt; charset=utf-8", sourceTextLimit
	default:
		return "", "", "", 0
	}
}

func runSourceAsset(ctx context.Context, source string, index int, format string, writer io.Writer) error {
	ffmpeg, err := mediatools.Lookup("ffmpeg")
	if err != nil {
		return err
	}
	args := []string{"-hide_banner", "-loglevel", "error", "-nostdin"}
	if format == "font" {
		args = append(args, "-dump_attachment:"+strconv.Itoa(index), "pipe:1", "-i", source, "-map", "0:v:0?", "-t", "0", "-f", "null", "-")
	} else {
		codec := "copy"
		if format == "webvtt" {
			codec = "webvtt"
		}
		args = append(args, "-i", source, "-map", "0:"+strconv.Itoa(index), "-c:s", codec, "-f", format, "pipe:1")
	}
	command := exec.CommandContext(ctx, ffmpeg, args...)
	command.Stdout = writer
	// No source paths or media-derived diagnostic text is returned to clients.
	command.Stderr = io.Discard
	if err := command.Run(); err != nil {
		return fmt.Errorf("source media asset extraction failed: %w", err)
	}
	return nil
}

func servePreparedSourceAsset(w http.ResponseWriter, r *http.Request, d *data, file *files.FileInfo, index int, format, extension, mime string, limit int64) (int, error) {
	identity := mediaSourceIdentity(file)
	if expected := r.URL.Query().Get("identity"); expected == "" || expected != identity {
		return http.StatusConflict, fmt.Errorf("媒体版本已变化，请重新打开")
	}
	if d.mediaAssets == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("源媒体资源服务不可用")
	}
	key := sourceAssetKey(d, file, identity, format, index)
	asset, err := d.mediaAssets.prepare(r.Context(), key, limit, func(ctx context.Context, out io.Writer) error {
		return runSourceAsset(ctx, file.RealPath(), index, format, out)
	})
	if err != nil {
		return http.StatusServiceUnavailable, fmt.Errorf("暂时无法提取源媒体资源，请重试")
	}
	defer asset.Close()
	// A replaced source must never receive assets cached for its old identity.
	current, err := files.NewFileInfo(&files.FileOptions{Fs: d.user.Fs, Path: file.Path, Checker: d})
	if err != nil {
		return errToStatus(err), err
	}
	if mediaSourceIdentity(current) != identity {
		return http.StatusConflict, fmt.Errorf("媒体版本已变化，请重新打开")
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "private, no-cache")
	w.Header().Set("ETag", `"`+key+`"`)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "source."+extension, file.ModTime, asset)
	return 0, nil
}

func serveSourceSubtitle(w http.ResponseWriter, r *http.Request, d *data, file *files.FileInfo, probe mediaProbeResult, index int) (int, error) {
	for _, track := range probe.SubtitleTracks {
		if track.Index != index {
			continue
		}
		format, ext, mime, limit := sourceSubtitleFormat(track.Codec)
		if format == "" {
			break
		}
		return servePreparedSourceAsset(w, r, d, file, index, format, ext, mime, limit)
	}
	return http.StatusBadRequest, fmt.Errorf("这个轨道不支持源字幕提取")
}

var mediaAttachmentHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	value := r.URL.Query().Get("path")
	if wire := r.URL.Query().Get("wirePath"); wire != "" {
		decoded, err := decodeResourceWirePath(wire)
		if err != nil {
			return http.StatusBadRequest, err
		}
		value = decoded
	}
	file, _, status, err := playbackFile(d, value)
	if err != nil {
		return status, err
	}
	id := mux.Vars(r)["id"]
	if !strings.HasPrefix(id, "f") {
		return http.StatusBadRequest, fmt.Errorf("字体附件标识无效")
	}
	index, err := strconv.Atoi(strings.TrimPrefix(id, "f"))
	if err != nil || index < 0 {
		return http.StatusBadRequest, fmt.Errorf("字体附件标识无效")
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	probe, err := defaultMediaProbe(ctx, file.RealPath(), false)
	if err != nil {
		return http.StatusServiceUnavailable, fmt.Errorf("无法读取字体附件信息")
	}
	for _, attachment := range probe.Attachments {
		if attachment.ID == id {
			return servePreparedSourceAsset(w, r, d, file, index, "font", "font", attachment.Mime, sourceFontLimit)
		}
	}
	return http.StatusNotFound, fmt.Errorf("字体附件不存在")
})
