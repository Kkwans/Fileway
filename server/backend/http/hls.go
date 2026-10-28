package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/hls"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

type mediaHLSStartRequest struct {
	Path                string  `json:"path"`
	WirePath            string  `json:"wirePath,omitempty"`
	Format              string  `json:"format,omitempty"`
	Quality             string  `json:"quality,omitempty"`
	SubtitleStreamIndex *int    `json:"subtitleStreamIndex,omitempty"`
	AudioStreamIndex    *int    `json:"audioStreamIndex,omitempty"`
	StartSeconds        float64 `json:"startSeconds,omitempty"`
	WindowSeconds       float64 `json:"windowSeconds,omitempty"`
	SessionID           string  `json:"sessionId,omitempty"`
}

type mediaHLSTaskArgs struct {
	Path                string  `json:"path"`
	CacheID             string  `json:"cacheId"`
	Identity            string  `json:"identity"`
	Format              string  `json:"format,omitempty"`
	Profile             string  `json:"profile,omitempty"`
	SubtitleStreamIndex *int    `json:"subtitleStreamIndex,omitempty"`
	AudioStreamIndex    *int    `json:"audioStreamIndex,omitempty"`
	StartSeconds        float64 `json:"startSeconds,omitempty"`
	WindowSeconds       float64 `json:"windowSeconds,omitempty"`
	SessionID           string  `json:"sessionId,omitempty"`
}

type mediaHLSResponse struct {
	Quality               string        `json:"quality,omitempty"`
	ID                    string        `json:"id"`
	TaskID                string        `json:"taskId,omitempty"`
	Path                  string        `json:"path"`
	Identity              string        `json:"identity"`
	Profile               string        `json:"profile"`
	SubtitleStreamIndex   *int          `json:"subtitleStreamIndex,omitempty"`
	AudioStreamIndex      *int          `json:"audioStreamIndex,omitempty"`
	HDRToneMapped         bool          `json:"hdrToneMapped,omitempty"`
	OutputDynamicRange    string        `json:"outputDynamicRange,omitempty"`
	State                 hls.State     `json:"state"`
	Error                 string        `json:"error,omitempty"`
	UpdatedAt             int64         `json:"updatedAt"`
	LastAccessAt          int64         `json:"lastAccessAt,omitempty"`
	SizeBytes             int64         `json:"sizeBytes,omitempty"`
	ProcessedSeconds      float64       `json:"processedSeconds,omitempty"`
	DurationSeconds       float64       `json:"durationSeconds,omitempty"`
	Progress              *hls.Progress `json:"progress,omitempty"`
	SourceDurationSeconds float64       `json:"sourceDurationSeconds,omitempty"`
	StartSeconds          float64       `json:"startSeconds,omitempty"`
	WindowSeconds         float64       `json:"windowSeconds,omitempty"`
	Format                string        `json:"format"`
	PlaylistURL           string        `json:"playlistUrl,omitempty"`
	SourceURL             string        `json:"sourceUrl,omitempty"`
}

const mediaHLSCodecProbeTimeout = 5 * time.Second

// reserveHLSForFormat keeps retries on the same artifact contract as the
// original compatibility task. In particular, an mp4-copy retry must remain a
// remux and must not silently fall back to a full HLS transcode.
func reserveHLSForFormat(service *hls.Service, format string) func(hls.Input, hls.StartFunc) (hls.Status, bool, error) {
	switch format {
	case "webm":
		return service.ReserveWebM
	case "webm-copy":
		return service.ReserveWebMCopy
	case "mp4-copy":
		return service.ReserveMP4Copy
	case "copy":
		return service.ReserveCopy
	default:
		return service.Reserve
	}
}

func mediaHLSStartHandler(service *hls.Service, runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var request mediaHLSStartRequest
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("兼容播放参数无效: %w", err)
		}
		request.Quality = strings.ToLower(strings.TrimSpace(request.Quality))
		if !mediaHLSQualityAllowed(request.Quality) {
			return http.StatusBadRequest, fmt.Errorf("不支持的兼容播放画质")
		}
		if request.WirePath != "" {
			decoded, err := decodeResourceWirePath(request.WirePath)
			if err != nil {
				return http.StatusBadRequest, err
			}
			request.Path = decoded
		}
		input, status, err := mediaHLSInputWithContext(r.Context(), d, d.user, request.Path, true)
		if err != nil {
			return status, err
		}
		if err := applyPlaybackWindow(&input, request.StartSeconds, request.WindowSeconds, request.SessionID); err != nil {
			return http.StatusBadRequest, err
		}
		if request.SubtitleStreamIndex != nil {
			if request.Format == "mp4" || !validPGSSubtitleStream(input.SubtitleStreams, *request.SubtitleStreamIndex) {
				return http.StatusBadRequest, fmt.Errorf("所选内挂字幕不可用于兼容播放")
			}
			input.SubtitleStream = request.SubtitleStreamIndex
		}
		if request.AudioStreamIndex != nil {
			if !validAudioStream(input.AudioStreams, *request.AudioStreamIndex) {
				return http.StatusBadRequest, fmt.Errorf("所选内挂音轨不可用于兼容播放")
			}
			input.AudioStream = request.AudioStreamIndex
		}
		var task *tasks.Task
		reserve := service.Reserve
		sourceQuality := request.Quality == "source"
		copySafe := mediaHLSFormatForInput(input) == "copy"
		if request.Format == "webm" {
			reserve = service.ReserveWebM
			if sourceQuality && !input.HDR && hls.CanCopyWebMMedia(input.VideoCodec, input.AudioCodec) {
				reserve = service.ReserveWebMCopy
			} else if mediaHLSExplicitQuality(request.Quality) || sourceQuality {
				profile := hls.WebMProfileForDimensions(request.Quality, input.VideoWidth, input.VideoHeight)
				reserve = func(source hls.Input, start hls.StartFunc) (hls.Status, bool, error) {
					return service.ReserveWithProfile(source, profile, start)
				}
			} else if !input.HDR && hls.CanCopyWebMMedia(input.VideoCodec, input.AudioCodec) {
				reserve = service.ReserveWebMCopy
			}
		} else if request.Format == "mp4" {
			// Prefer a seekable MP4 remux for H.264/AAC sources. If probing
			// cannot prove the streams are copy-safe, keep the HLS fallback so
			// HEVC/DTS and similar files still get a playable path.
			if !input.HDR && hls.CanCopyMediaWithDetails(input.VideoCodec, input.AudioCodec, input.VideoPixelFormat, input.VideoProfile, input.VideoBitDepth) {
				reserve = service.ReserveMP4Copy
			}
		} else if request.Format != "" && request.Format != "hls" {
			return http.StatusBadRequest, fmt.Errorf("不支持的兼容播放格式")
		} else if copySafe && !mediaHLSExplicitQuality(request.Quality) {
			reserve = service.ReserveCopy
		}
		if request.Format != "webm" && request.Format != "mp4" &&
			(mediaHLSExplicitQuality(request.Quality) || (sourceQuality && !copySafe)) {
			profile := hls.ProfileForDimensions(request.Quality, input.VideoWidth, input.VideoHeight)
			reserve = func(source hls.Input, start hls.StartFunc) (hls.Status, bool, error) {
				return service.ReserveWithProfile(source, profile, start)
			}
		}
		if input.SubtitleStream != nil || input.AudioStream != nil {
			quality := request.Quality
			if quality == "" || quality == "native" {
				quality = "1080p"
			}
			profile := hls.ProfileForDimensions(quality, input.VideoWidth, input.VideoHeight)
			if request.Format == "webm" {
				profile = hls.WebMProfileForDimensions(quality, input.VideoWidth, input.VideoHeight)
			}
			reserve = func(source hls.Input, start hls.StartFunc) (hls.Status, bool, error) {
				return service.ReserveWithProfile(source, profile, start)
			}
		}
		// Container/audio incompatibility does not require re-encoding a safe
		// video stream. This also applies to a selected alternate audio track.
		if request.Format != "webm" && input.SubtitleStream == nil && !input.HDR &&
			hls.CanCopyVideo(input) && hls.QualityPreservesSource(request.Quality, input.VideoWidth, input.VideoHeight) {
			audioCodec := input.AudioCodec
			if input.AudioStream != nil {
				for _, track := range input.AudioStreams {
					if track.Index == *input.AudioStream {
						audioCodec = track.Codec
					}
				}
			}
			profile := hls.DefaultCopyProfile
			if request.Format == "mp4" {
				profile = hls.DefaultMP4CopyProfile
			}
			if audioCodec != "" && audioCodec != "aac" {
				profile = hls.DefaultAudioHLSProfile
				if request.Format == "mp4" {
					profile = hls.DefaultMP4AudioProfile
				}
			}
			reserve = func(source hls.Input, start hls.StartFunc) (hls.Status, bool, error) {
				return service.ReserveWithProfile(source, profile, start)
			}
		}
		cached, created, err := reserve(input, func(job hls.Job) (string, error) {
			task, err = enqueueMediaHLSTask(runtime, d, d.user, service, job, "")
			if err != nil {
				return "", err
			}
			return task.ID, nil
		})
		if err != nil {
			return taskErrorStatus(err), err
		}
		if created && task != nil {
			recordHistory(d, "media.hls", input.Path, task.ID, history.StatusSubmitted)
			return renderJSONStatus(w, mediaHLSStatusResponse(d.server.BaseURL, cached), http.StatusAccepted)
		}
		return renderJSON(w, r, mediaHLSStatusResponse(d.server.BaseURL, cached))
	})
}

func mediaHLSGetHandler(service *hls.Service) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		status, err := service.Get(mux.Vars(r)["id"], d.user.ID)
		if err != nil {
			return mediaHLSErrorStatus(err), err
		}
		return renderJSON(w, r, mediaHLSStatusResponse(d.server.BaseURL, status))
	})
}

func mediaHLSCancelHandler(service *hls.Service, runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		status, err := service.Get(mux.Vars(r)["id"], d.user.ID)
		if err != nil {
			return mediaHLSErrorStatus(err), err
		}
		if status.State == hls.StateCompleted || status.State == hls.StateCanceled || status.State == hls.StateFailed {
			return renderJSON(w, r, mediaHLSStatusResponse(d.server.BaseURL, status))
		}
		if status.TaskID == "" || (status.State != hls.StateQueued && status.State != hls.StatePreparing && status.State != hls.StateStreamable) {
			return http.StatusConflict, hls.ErrState
		}
		if _, err := runtime.Cancel(d.user.ID, status.TaskID, false); err != nil {
			return taskErrorStatus(err), err
		}
		recordHistory(d, "task.cancel", "兼容播放 "+path.Base(status.Path), status.TaskID, history.StatusSuccess)
		latest, _ := service.Get(status.ID, d.user.ID)
		return renderJSONStatus(w, mediaHLSStatusResponse(d.server.BaseURL, latest), http.StatusAccepted)
	})
}

func mediaHLSAssetHandler(service *hls.Service) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		name := mux.Vars(r)["asset"]
		filename, state, err := service.Asset(mux.Vars(r)["id"], d.user.ID, name)
		if err != nil {
			return mediaHLSErrorStatus(err), err
		}
		file, err := os.Open(filename)
		if err != nil {
			return errToStatus(err), err
		}
		defer func() { _ = file.Close() }()
		info, err := file.Stat()
		if err != nil {
			return http.StatusInternalServerError, err
		}
		w.Header().Set("X-HLS-State", string(state))
		if strings.HasSuffix(name, ".m3u8") {
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			w.Header().Set("Cache-Control", "private, no-cache")
		} else if strings.HasSuffix(name, ".webm") {
			w.Header().Set("Content-Type", "video/webm")
			w.Header().Set("Cache-Control", "private, no-cache")
		} else if strings.HasSuffix(name, ".mp4") {
			w.Header().Set("Content-Type", "video/mp4")
			w.Header().Set("Cache-Control", "private, no-cache")
		} else {
			w.Header().Set("Content-Type", "video/mp2t")
			w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
		}
		http.ServeContent(w, r, name, info.ModTime(), file)
		return 0, nil
	})
}

func mediaHLSInputWithContext(ctx context.Context, d *data, owner *users.User, value string, probeCodecs bool) (hls.Input, int, error) {
	if owner == nil || !owner.Perm.Download {
		return hls.Input{}, http.StatusForbidden, fmt.Errorf("没有兼容播放该视频的权限")
	}
	if value == "" {
		return hls.Input{}, http.StatusBadRequest, fmt.Errorf("视频路径不能为空")
	}
	value = pathmeta.Clean(value)
	ownerData := *d
	ownerData.user = owner
	file, err := files.NewFileInfo(&files.FileOptions{
		Fs: owner.Fs, Path: value, Modify: owner.Perm.Modify,
		Expand: true, SkipSubtitles: true,
		ReadHeader: d.server.TypeDetectionByHeader, Checker: &ownerData,
	})
	if err != nil {
		return hls.Input{}, errToStatus(err), err
	}
	if file.IsDir || file.Type != "video" {
		return hls.Input{}, http.StatusBadRequest, fmt.Errorf("兼容播放仅适用于视频文件")
	}
	identity := "v1:" + strconv.FormatInt(file.Size, 10) + ":" + strconv.FormatInt(file.ModTime.UnixNano(), 10)
	input := hls.Input{
		UserID: owner.ID, Path: file.Path, Identity: identity, SourcePath: file.RealPath(),
	}
	// Compatibility playback is already an explicit user action. A short,
	// best-effort probe lets H.264/AAC MKV and MOV files take the remux-only
	// path; probe failures fall back to the existing transcode profile.
	if probeCodecs {
		probeCtx, cancel := context.WithTimeout(ctx, mediaHLSCodecProbeTimeout)
		probe, probeErr := defaultMediaProbe(probeCtx, input.SourcePath, false)
		cancel()
		if probeErr == nil {
			input.VideoCodec = probe.VideoCodec
			input.DynamicRange = mediaDynamicRange(probe)
			input.HDR = probe.VideoTransfer == "smpte2084" || probe.VideoTransfer == "arib-std-b67"
			input.AudioCodec = probe.AudioCodec
			input.VideoPixelFormat = probe.VideoPixelFormat
			input.VideoProfile = probe.VideoProfile
			input.VideoBitDepth = probe.VideoBitDepth
			input.VideoWidth = probe.Width
			input.VideoHeight = probe.Height
			for _, track := range probe.SubtitleTracks {
				input.SubtitleStreams = append(input.SubtitleStreams, hls.SubtitleStream{Index: track.Index, Codec: track.Codec})
			}
			for _, track := range probe.AudioTracks {
				input.AudioStreams = append(input.AudioStreams, hls.AudioStream{Index: track.Index, Codec: track.Codec})
			}
			input.DurationSeconds = probe.Duration
			log.Printf("media HLS codec probe video=%q audio=%q pix_fmt=%q profile=%q bit_depth=%d", input.VideoCodec, input.AudioCodec, input.VideoPixelFormat, input.VideoProfile, input.VideoBitDepth)
		} else {
			log.Printf("media HLS codec probe unavailable: %v", probeErr)
		}
	}
	return input, 0, nil
}

func validPGSSubtitleStream(streams []hls.SubtitleStream, index int) bool {
	for _, stream := range streams {
		if stream.Index == index && stream.Codec == "hdmv_pgs_subtitle" {
			return true
		}
	}
	return false
}

func validAudioStream(streams []hls.AudioStream, index int) bool {
	for _, stream := range streams {
		if stream.Index == index {
			return true
		}
	}
	return false
}

func mediaHLSExplicitQuality(quality string) bool {
	value := strings.ToLower(strings.TrimSpace(quality))
	switch value {
	case "4k", "2160p", "2k", "1440p", "1080", "1080p", "720", "720p", "480", "480p":
		return true
	default:
		return false
	}
}

func mediaHLSQualityAllowed(quality string) bool {
	value := strings.ToLower(strings.TrimSpace(quality))
	return value == "" || value == "source" || value == "native" || mediaHLSExplicitQuality(value)
}

func mediaHLSFormatForInput(input hls.Input) string {
	if input.HDR {
		return "hls"
	}
	if hls.CanCopyMediaWithDetails(input.VideoCodec, input.AudioCodec, input.VideoPixelFormat, input.VideoProfile, input.VideoBitDepth) {
		return "copy"
	}
	return "hls"
}

func enqueueMediaHLSTask(runtime *tasks.Runtime, d *data, owner *users.User, service *hls.Service, job hls.Job, retryOf string) (*tasks.Task, error) {
	format := "hls"
	if hls.IsWebMProfile(job.Profile) {
		format = "webm"
	} else if hls.IsWebMCopyProfile(job.Profile) {
		format = "webm-copy"
	} else if hls.IsMP4CopyProfile(job.Profile) {
		format = "mp4-copy"
	} else if hls.IsCopyProfile(job.Profile) {
		format = "copy"
	}
	args, err := json.Marshal(mediaHLSTaskArgs{Path: job.Path, CacheID: job.ID, Identity: job.Identity, Format: format, Profile: job.Profile, SubtitleStreamIndex: job.SubtitleStream, AudioStreamIndex: job.AudioStream, StartSeconds: job.StartSeconds, WindowSeconds: job.WindowSeconds, SessionID: job.SessionID})
	if err != nil {
		return nil, err
	}
	title := "兼容播放 " + path.Base(job.Path)
	if job.WindowSeconds > 0 {
		title = fmt.Sprintf("播放片段 %.0f–%.0f 秒 · %s", job.StartSeconds, job.StartSeconds+job.DurationSeconds, path.Base(job.Path))
	}
	task, err := d.store.Tasks.New(owner.ID, owner.Username, tasks.TypeMediaHLS, title, args, retryOf)
	if err != nil {
		return nil, err
	}
	runner := func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
		if err := service.RunWithProgress(ctx, job, func(progress hls.Progress) error {
			return report(tasks.Progress{Media: &progress})
		}); err != nil {
			return nil, err
		}
		return json.Marshal(struct {
			CacheID string `json:"cacheId"`
		}{CacheID: job.ID})
	}
	if err := runtime.StartExclusive(task, runner, "media.hls:"+job.ID); err != nil {
		task.Status = tasks.StatusFailed
		task.FinishedAt = time.Now().UnixMilli()
		task.Error = err.Error()
		_ = d.store.Tasks.Update(task)
		return nil, err
	}
	return task, nil
}

func mediaHLSStatusResponse(baseURL string, status hls.Status) mediaHLSResponse {
	response := mediaHLSResponse{
		ID: status.ID, TaskID: status.TaskID, Path: status.Path,
		Identity: status.Identity, Profile: status.Profile, State: status.State,
		SubtitleStreamIndex: status.SubtitleStream,
		AudioStreamIndex:    status.AudioStream,
		HDRToneMapped:       status.HDR,
		OutputDynamicRange:  status.OutputDynamicRange,
		Error:               status.Error, UpdatedAt: status.UpdatedAt,
		LastAccessAt: status.LastAccessAt, SizeBytes: status.SizeBytes,
		ProcessedSeconds:      status.ProcessedSeconds,
		DurationSeconds:       status.DurationSeconds,
		Progress:              status.Progress,
		SourceDurationSeconds: status.SourceDurationSeconds,
		StartSeconds:          status.StartSeconds,
		WindowSeconds:         status.WindowSeconds,
		Format:                "hls",
	}
	if hls.IsWebMProfile(status.Profile) {
		response.Format = "webm"
	} else if hls.IsWebMCopyProfile(status.Profile) {
		response.Format = "webm-copy"
	} else if hls.IsMP4CopyProfile(status.Profile) {
		response.Format = "mp4-copy"
	} else if hls.IsCopyProfile(status.Profile) {
		response.Format = "copy"
	}
	if status.State == hls.StateStreamable || status.State == hls.StateCompleted {
		base := strings.TrimSuffix(baseURL, "/") + "/api/media/hls/" + status.ID + "/"
		switch response.Format {
		case "webm", "webm-copy":
			response.SourceURL = base + "index.webm"
		case "mp4-copy":
			response.SourceURL = base + "index.mp4"
		default:
			response.PlaylistURL = base + "index.m3u8"
		}
	}
	return response
}

func mediaHLSErrorStatus(err error) int {
	switch {
	case errors.Is(err, hls.ErrNotFound):
		return http.StatusNotFound
	case errors.Is(err, hls.ErrForbidden):
		return http.StatusForbidden
	case errors.Is(err, hls.ErrState):
		return http.StatusConflict
	default:
		return http.StatusInternalServerError
	}
}
