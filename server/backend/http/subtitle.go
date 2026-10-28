package fbhttp

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/asticode/go-astisub"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/mediatools"
)

var subtitleHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if !d.user.Perm.Download {
		if r.URL.Query().Get("format") == "source" {
			return http.StatusForbidden, fmt.Errorf("没有读取字幕的权限")
		}
		return http.StatusAccepted, nil
	}

	file, err := files.NewFileInfo(&files.FileOptions{
		Fs:            d.user.Fs,
		Path:          r.URL.Path,
		Modify:        d.user.Perm.Modify,
		Expand:        r.URL.Query().Has("streamIndex"),
		SkipSubtitles: true,
		ReadHeader:    d.server.TypeDetectionByHeader,
		Checker:       d,
	})
	if err != nil {
		return errToStatus(err), err
	}

	if file.IsDir {
		return http.StatusBadRequest, fmt.Errorf("不能预览目录的字幕")
	}
	if r.URL.Query().Has("streamIndex") {
		if file.Type != "video" {
			return http.StatusBadRequest, fmt.Errorf("内挂字幕需要视频文件")
		}
		index, parseErr := strconv.Atoi(r.URL.Query().Get("streamIndex"))
		if parseErr != nil || index < 0 {
			return http.StatusBadRequest, fmt.Errorf("内挂字幕轨道无效")
		}
		probeContext, cancelProbe := context.WithTimeout(r.Context(), 5*time.Second)
		probe, probeErr := defaultMediaProbe(probeContext, file.RealPath(), false)
		cancelProbe()
		if probeErr != nil {
			return http.StatusInternalServerError, probeErr
		}
		if r.URL.Query().Get("format") == "source" {
			return serveSourceSubtitle(w, r, d, file, probe, index)
		}
		if !validEmbeddedTextSubtitleStream(probe.SubtitleTracks, index) {
			return http.StatusBadRequest, fmt.Errorf("所选内挂文本字幕不可用")
		}
		convertContext, cancelConvert := context.WithTimeout(r.Context(), 2*time.Minute)
		defer cancelConvert()
		content, convertErr := extractEmbeddedSubtitleVTT(convertContext, file.RealPath(), index)
		if convertErr != nil {
			return http.StatusInternalServerError, convertErr
		}
		w.Header().Set("Content-Security-Policy", `script-src 'none';`)
		w.Header().Set("Cache-Control", "private")
		w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
		http.ServeContent(w, r, file.Name+".vtt", file.ModTime, bytes.NewReader(content))
		return 0, nil
	}

	if r.URL.Query().Get("format") == "source" {
		return http.StatusBadRequest, fmt.Errorf("源字幕提取需要内挂轨道")
	}
	return subtitleFileHandler(w, r, file)
})

func validEmbeddedTextSubtitleStream(tracks []mediaTrack, index int) bool {
	for _, track := range tracks {
		if track.Index != index {
			continue
		}
		switch track.Codec {
		case "subrip", "ass", "ssa", "webvtt", "mov_text", "text":
			return true
		}
	}
	return false
}

func extractEmbeddedSubtitleVTT(ctx context.Context, path string, index int) ([]byte, error) {
	ffmpegPath, err := mediatools.Lookup("ffmpeg")
	if err != nil {
		return nil, fmt.Errorf("FFmpeg 不可用: %w", err)
	}
	command := exec.CommandContext(ctx, ffmpegPath,
		"-hide_banner", "-loglevel", "error", "-nostdin",
		"-probesize", "1M", "-analyzeduration", "1M", "-i", path,
		"-map", fmt.Sprintf("0:%d", index), "-c:s", "webvtt", "-f", "webvtt", "pipe:1",
	)
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("创建字幕输出失败: %w", err)
	}
	var stderr bytes.Buffer
	command.Stderr = &stderr
	if err := command.Start(); err != nil {
		return nil, fmt.Errorf("提取内挂字幕失败: %w", err)
	}
	const maxSubtitleBytes = 16 << 20
	content, readErr := io.ReadAll(io.LimitReader(stdout, maxSubtitleBytes+1))
	if readErr != nil || len(content) > maxSubtitleBytes {
		_ = command.Process.Kill()
		_ = command.Wait()
		if readErr != nil {
			return nil, fmt.Errorf("读取内挂字幕失败: %w", readErr)
		}
		return nil, fmt.Errorf("内挂字幕超过 16 MiB")
	}
	if err := command.Wait(); err != nil {
		if ctx.Err() != nil {
			return nil, fmt.Errorf("提取内挂字幕超时或已取消: %w", ctx.Err())
		}
		return nil, fmt.Errorf("提取内挂字幕失败: %s", strings.TrimSpace(stderr.String()))
	}
	return content, nil
}

func subtitleFileHandler(w http.ResponseWriter, r *http.Request, file *files.FileInfo) (int, error) {
	// if its not a subtitle file, reject
	if !files.IsSupportedSubtitle(file.Name) {
		return http.StatusBadRequest, fmt.Errorf("不支持的字幕文件格式")
	}

	fd, err := file.Fs.Open(file.Path)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	defer func() { _ = fd.Close() }()

	// load subtitle for conversion to vtt
	var sub *astisub.Subtitles
	if strings.HasSuffix(file.Name, ".srt") {
		sub, err = astisub.ReadFromSRT(fd)
	} else if strings.HasSuffix(file.Name, ".ass") || strings.HasSuffix(file.Name, ".ssa") {
		sub, err = astisub.ReadFromSSA(fd)
	}
	if err != nil {
		return http.StatusInternalServerError, err
	}

	setContentDisposition(w, r, file)
	w.Header().Add("Content-Security-Policy", `script-src 'none';`)
	w.Header().Set("Cache-Control", "private")
	// force type to text/vtt
	w.Header().Set("Content-Type", "text/vtt")

	// serve vtt file directly
	if sub == nil {
		http.ServeContent(w, r, file.Name, file.ModTime, fd)
		return 0, nil
	}

	// convert others to vtt and serve from buffer
	var buf = &bytes.Buffer{}
	err = sub.WriteToWebVTT(buf)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	http.ServeContent(w, r, file.Name, file.ModTime, bytes.NewReader(buf.Bytes()))
	return 0, nil
}
