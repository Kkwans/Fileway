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
	prepare := d.mediaAssets.prepare
	if format == "font" {
		prepare = d.mediaAssets.prepareFont
	}
	asset, err := prepare(r.Context(), key, limit, func(ctx context.Context, out io.Writer) error {
		return runSourceAsset(ctx, file.RealPath(), index, format, out)
	})
	if err != nil {
		return http.StatusServiceUnavailable, fmt.Errorf("暂时无法提取源媒体资源，请重试")
	}
	defer func() { _ = asset.Close() }()
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
