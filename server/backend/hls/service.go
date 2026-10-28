package hls

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/mediatools"
)

const (
	DefaultMaxBytes        = int64(10 << 30)
	DefaultProfile         = "h264-main-720p-aac-hls4-v1"
	DefaultCopyProfile     = "h264-copy-hls-v1"
	DefaultMP4CopyProfile  = "h264-aac-mp4-copy-v1"
	DefaultAudioHLSProfile = "h264-copy-aac-hls-v1"
	DefaultMP4AudioProfile = "h264-copy-aac-mp4-v1"
	DefaultWebMProfile     = "vp9-720p-opus-webm-v1"
	transcodeScaleVersion  = "bounded-width-height-v2"
	DefaultWebMCopyProfile = "vp9-opus-webm-copy-v1"
	playingLease           = 2 * time.Minute
	maxFFmpegError         = 8 * 1024
)

var (
	ErrNotFound  = errors.New("HLS cache not found")
	ErrForbidden = errors.New("HLS cache access denied")
	ErrState     = errors.New("HLS cache state does not allow this operation")
	cacheIDRE    = regexp.MustCompile(`^[a-f0-9]{64}$`)
	assetNameRE  = regexp.MustCompile(`^(index\.m3u8|index\.mp4|index\.webm|segment-[0-9]{6}\.ts)$`)
)

type State string

const (
	StateQueued     State = "queued"
	StatePreparing  State = "preparing"
	StateStreamable State = "streamable"
	StateCompleted  State = "completed"
	StateFailed     State = "failed"
	StateCanceled   State = "canceled"
)

type Config struct {
	CacheDir      string
	MaxBytes      int64
	Workers       int
	FFmpegPath    string
	Profile       string
	EncodeThreads int
	Accelerator   string
}

type Input struct {
	UserID     uint
	Path       string
	Identity   string
	SourcePath string
	VideoCodec string
	AudioCodec string
	// VideoPixelFormat, VideoProfile and VideoBitDepth come from ffprobe. They
	// keep a stream that merely names itself H.264 from being treated as
	// browser-safe when it is actually 10-bit or 4:2:2/4:4:4.
	VideoPixelFormat string
	VideoProfile     string
	VideoBitDepth    int
	VideoWidth       int
	VideoHeight      int
	HDR              bool
	DynamicRange     string
	SubtitleStreams  []SubtitleStream
	SubtitleStream   *int
	AudioStreams     []AudioStream
	AudioStream      *int
	// DurationSeconds is the probed source duration used to render truthful
	// compatibility progress while a WebM artifact is being generated.
	DurationSeconds float64
	StartSeconds    float64
	WindowSeconds   float64
	SessionID       string
}

type SubtitleStream struct {
	Index int
	Codec string
}

type AudioStream struct {
	Index int
	Codec string
}

type Job struct {
	ID                    string
	UserID                uint
	Path                  string
	Identity              string
	SourcePath            string
	Profile               string
	DurationSeconds       float64
	SourceDurationSeconds float64
	StartSeconds          float64
	WindowSeconds         float64
	SessionID             string
	SubtitleStream        *int
	AudioStream           *int
	HDR                   bool
	VideoWidth            int
	VideoHeight           int
	VideoCodec            string
	report                func(Progress) error
}

// IsWebMProfile reports whether a compatibility artifact is a complete WebM
// file rather than an HLS playlist.  The profile is part of the cache key so
// browsers with and without H.264 MSE support do not share incompatible data.
func IsWebMProfile(profile string) bool {
	return profile == DefaultWebMProfile ||
		(strings.HasPrefix(profile, "vp9-") && strings.HasSuffix(profile, "-opus-webm-v1"))
}

// IsWebMCopyProfile reports a WebM artifact that only remuxes streams already
// supported by Chromium, rather than decoding and re-encoding them.
func IsWebMCopyProfile(profile string) bool {
	return profile == DefaultWebMCopyProfile
}

// IsCopyProfile reports whether the HLS artifact only remuxes streams that
// Chromium can consume.  It avoids a full video encode for MKV/MOV files that
// already contain H.264 video and AAC (or no) audio.
func IsCopyProfile(profile string) bool {
	return profile == DefaultCopyProfile || profile == DefaultAudioHLSProfile
}

// IsMP4CopyProfile reports a complete MP4 artifact containing the original
// browser-compatible H.264/AAC streams. The source is remuxed, never encoded.
func IsMP4CopyProfile(profile string) bool {
	return profile == DefaultMP4CopyProfile || profile == DefaultMP4AudioProfile
}

func CanCopyVideo(input Input) bool {
	return CanCopyMediaWithDetails(input.VideoCodec, "", input.VideoPixelFormat, input.VideoProfile, input.VideoBitDepth)
}

// QualityPreservesSource allows explicit quality choices to use stream copy
// when they would not resize the source. Unknown dimensions stay conservative.
func QualityPreservesSource(quality string, width, height int) bool {
	if quality == "" || quality == "source" {
		return true
	}
	if width <= 0 || height <= 0 {
		return false
	}
	limits := map[string][2]int{"4k": {3840, 2160}, "2k": {2560, 1440}, "1080p": {1920, 1080}, "720p": {1280, 720}, "480p": {854, 480}}
	limit, ok := limits[quality]
	return ok && width <= limit[0] && height <= limit[1]
}

// CanCopyMedia is deliberately conservative: copying an unsupported audio
// stream would produce a fast but unusable compatibility artifact.
func CanCopyMedia(videoCodec, audioCodec string) bool {
	return CanCopyMediaWithDetails(videoCodec, audioCodec, "", "", 0)
}

// CanCopyMediaWithDetails is deliberately conservative. Chromium's broadly
// available MP4 decoder expects 8-bit 4:2:0 H.264; a container carrying H.264
// 10-bit, 4:2:2 or 4:4:4 still needs a compatibility encode even though the
// codec name itself is "h264".
func CanCopyMediaWithDetails(videoCodec, audioCodec, pixelFormat, profile string, bitDepth int) bool {
	videoCodec = strings.ToLower(strings.TrimSpace(videoCodec))
	audioCodec = strings.ToLower(strings.TrimSpace(audioCodec))
	if videoCodec != "h264" || (audioCodec != "" && audioCodec != "aac") {
		return false
	}
	if bitDepth > 8 {
		return false
	}
	pixelFormat = strings.ToLower(strings.TrimSpace(pixelFormat))
	if pixelFormat != "" && pixelFormat != "yuv420p" && pixelFormat != "yuvj420p" {
		return false
	}
	profile = strings.ToLower(strings.TrimSpace(profile))
	for _, unsupported := range []string{"10", "12", "4:2:2", "4:4:4"} {
		if strings.Contains(profile, unsupported) {
			return false
		}
	}
	return true
}

// CanCopyWebMMedia accepts browser-native WebM codec families so MKV/MOV
// files carrying VP8/VP9/AV1 + Opus/Vorbis do not need a compatibility encode.
func CanCopyWebMMedia(videoCodec, audioCodec string) bool {
	videoCodec = strings.ToLower(strings.TrimSpace(videoCodec))
	audioCodec = strings.ToLower(strings.TrimSpace(audioCodec))
	videoOK := videoCodec == "vp8" || videoCodec == "vp9" || videoCodec == "av1"
	audioOK := audioCodec == "" || audioCodec == "opus" || audioCodec == "vorbis"
	return videoOK && audioOK
}

type Status struct {
	ID                 string `json:"id"`
	TaskID             string `json:"taskId,omitempty"`
	Path               string `json:"path"`
	Identity           string `json:"identity"`
	Profile            string `json:"profile"`
	SubtitleStream     *int   `json:"subtitleStreamIndex,omitempty"`
	AudioStream        *int   `json:"audioStreamIndex,omitempty"`
	HDR                bool   `json:"hdrToneMapped,omitempty"`
	OutputDynamicRange string `json:"outputDynamicRange,omitempty"`
	State              State  `json:"state"`
	Error              string `json:"error,omitempty"`
	UpdatedAt          int64  `json:"updatedAt"`
	LastAccessAt       int64  `json:"lastAccessAt,omitempty"`
	SizeBytes          int64  `json:"sizeBytes,omitempty"`
	// ProcessedSeconds is reported for compatibility transcodes that cannot
	// expose a playable artifact until the complete file is ready.  It is
	// deliberately a duration, not a guessed percentage.
	ProcessedSeconds float64 `json:"processedSeconds,omitempty"`
	// DurationSeconds is the source duration paired with ProcessedSeconds.
	// It may be zero when codec probing was unavailable.
	DurationSeconds       float64   `json:"durationSeconds,omitempty"`
	UserID                uint      `json:"userId"`
	Progress              *Progress `json:"progress,omitempty"`
	SourceDurationSeconds float64   `json:"sourceDurationSeconds,omitempty"`
	StartSeconds          float64   `json:"startSeconds,omitempty"`
	WindowSeconds         float64   `json:"windowSeconds,omitempty"`
}

type entry struct {
	Status
	sourcePath string
	leaseUntil time.Time
}

type StartFunc func(job Job) (taskID string, err error)

type Service struct {
	cacheDir        string
	maxBytes        int64
	ffmpegPath      string
	profile         string
	encodeThreads   int
	accelerator     string
	workers         chan struct{}
	playbackWorkers chan struct{}

	mu      sync.Mutex
	entries map[string]*entry
}

type cappedBuffer struct {
	bytes.Buffer
	limit int
}

func (buffer *cappedBuffer) Write(payload []byte) (int, error) {
	written := len(payload)
	remaining := buffer.limit - buffer.Len()
	if remaining > 0 {
		if remaining > len(payload) {
			remaining = len(payload)
		}
		_, _ = buffer.Buffer.Write(payload[:remaining])
	}
	return written, nil
}

func New(config Config) (*Service, error) {
	if config.CacheDir == "" {
		return nil, fmt.Errorf("HLS cache directory is required")
	}
	if config.MaxBytes <= 0 {
		config.MaxBytes = DefaultMaxBytes
	}
	if config.Workers < 1 || config.Workers > 2 {
		return nil, fmt.Errorf("HLS processors count must be between 1 and 2")
	}
	if config.FFmpegPath == "" {
		config.FFmpegPath = "ffmpeg"
		if resolved, err := mediatools.Lookup("ffmpeg"); err == nil {
			config.FFmpegPath = resolved
		}
	}
	if config.Profile == "" {
		config.Profile = DefaultProfile
	}
	if config.EncodeThreads == 0 {
		config.EncodeThreads = min(4, runtime.NumCPU())
	}
	if config.EncodeThreads < 1 || config.EncodeThreads > 4 {
		return nil, fmt.Errorf("video encode threads must be between 1 and 4")
	}
	if config.Accelerator != "" && config.Accelerator != "software" && config.Accelerator != "rkmpp" {
		return nil, fmt.Errorf("video accelerator must be software or rkmpp")
	}
	if err := os.MkdirAll(config.CacheDir, 0o700); err != nil {
		return nil, fmt.Errorf("create HLS cache directory: %w", err)
	}
	service := &Service{
		cacheDir: config.CacheDir, maxBytes: config.MaxBytes,
		ffmpegPath: config.FFmpegPath, profile: config.Profile,
		encodeThreads: config.EncodeThreads,
		accelerator:   config.Accelerator,
		workers:       make(chan struct{}, config.Workers), entries: make(map[string]*entry),
		playbackWorkers: make(chan struct{}, 1),
	}
	if err := service.loadCompleted(); err != nil {
		return nil, err
	}
	service.mu.Lock()
	service.cleanupLocked("")
	service.mu.Unlock()
	return service, nil
}

func (service *Service) Reserve(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, service.profile, start)
}

// ReserveWithProfile keeps the selected quality in the cache identity.
func (service *Service) ReserveWithProfile(input Input, profile string, start StartFunc) (Status, bool, error) {
	if profile == "" {
		profile = service.profile
	}
	return service.reserve(input, profile, start)
}

// ProfileForQuality maps the public quality token to a bounded encode profile.
// A source smaller than the requested profile is never upscaled.
func ProfileForQuality(quality string, sourceHeight int) string {
	requested := map[string]int{
		"4k": 2160, "2160p": 2160,
		"2k": 1440, "1440p": 1440,
		"1080": 1080, "1080p": 1080,
		"720": 720, "720p": 720,
		"480": 480, "480p": 480,
	}[strings.ToLower(strings.TrimSpace(quality))]
	if requested == 0 {
		requested = sourceHeight
	}
	if sourceHeight > 0 && (requested == 0 || sourceHeight < requested) {
		requested = sourceHeight
	}
	return encodeProfileForHeight(requested)
}

// ProfileForDimensions keeps cinema-width 4K sources at their source class
// even when the cropped frame is shorter than 2160 pixels.
func ProfileForDimensions(quality string, sourceWidth, sourceHeight int) string {
	if quality == "source" && sourceWidth > 0 && sourceHeight > 0 {
		return fmt.Sprintf("h264-main-source-%dx%d-aac-hls4-v1", sourceWidth, sourceHeight)
	}
	classHeight := sourceHeight
	if widthHeight := (sourceWidth*9 + 8) / 16; widthHeight > classHeight {
		classHeight = widthHeight
	}
	return ProfileForQuality(quality, classHeight)
}

func WebMProfileForDimensions(quality string, sourceWidth, sourceHeight int) string {
	profile := ProfileForDimensions(quality, sourceWidth, sourceHeight)
	profile = strings.Replace(profile, "h264-main-", "vp9-", 1)
	return strings.Replace(profile, "-aac-hls4-v1", "-opus-webm-v1", 1)
}

func WebMProfileForQuality(quality string, sourceHeight int) string {
	profile := ProfileForQuality(quality, sourceHeight)
	profile = strings.Replace(profile, "h264-main-", "vp9-", 1)
	return strings.Replace(profile, "-aac-hls4-v1", "-opus-webm-v1", 1)
}

func encodeProfileForHeight(height int) string {
	switch {
	case height >= 1800:
		return "h264-main-2160p-aac-hls4-v1"
	case height >= 1200:
		return "h264-main-1440p-aac-hls4-v1"
	case height >= 900:
		return "h264-main-1080p-aac-hls4-v1"
	case height >= 600:
		return "h264-main-720p-aac-hls4-v1"
	default:
		return "h264-main-480p-aac-hls4-v1"
	}
}

func profileMaxWidth(profile string) int {
	if width, _ := sourceProfileDimensions(profile); width > 0 {
		return width
	}
	switch {
	case strings.Contains(profile, "2160p"):
		return 3840
	case strings.Contains(profile, "1440p"):
		return 2560
	case strings.Contains(profile, "1080p"):
		return 1920
	case strings.Contains(profile, "480p"):
		return 854
	default:
		return 1280
	}
}

func profileMaxHeight(profile string) int {
	if _, height := sourceProfileDimensions(profile); height > 0 {
		return height
	}
	switch {
	case strings.Contains(profile, "2160p"):
		return 2160
	case strings.Contains(profile, "1440p"):
		return 1440
	case strings.Contains(profile, "1080p"):
		return 1080
	case strings.Contains(profile, "480p"):
		return 480
	default:
		return 720
	}
}

func sourceProfileDimensions(profile string) (int, int) {
	_, rest, ok := strings.Cut(profile, "-source-")
	if !ok {
		return 0, 0
	}
	dimensions, _, _ := strings.Cut(rest, "-")
	widthText, heightText, ok := strings.Cut(dimensions, "x")
	if !ok {
		return 0, 0
	}
	width, _ := strconv.Atoi(widthText)
	height, _ := strconv.Atoi(heightText)
	return width, height
}

// ReserveCopy creates an HLS playlist by copying already browser-compatible
// H.264/AAC streams.  The container is remuxed, not re-encoded.
func (service *Service) ReserveCopy(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultCopyProfile, start)
}

// ReserveMP4Copy creates a seekable MP4 artifact by copying compatible
// H.264/AAC streams into a browser-friendly container.
func (service *Service) ReserveMP4Copy(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultMP4CopyProfile, start)
}

// ReserveWebM creates a compatibility artifact that can be played by
// Chromium builds without proprietary H.264/MSE decoders.  It intentionally
// waits until the complete WebM file is available before exposing it, which
// gives the browser a normal seekable resource instead of a broken HLS source.
func (service *Service) ReserveWebM(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultWebMProfile, start)
}

// ReserveWebMCopy remuxes browser-native WebM streams without re-encoding.
func (service *Service) ReserveWebMCopy(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultWebMCopyProfile, start)
}

func (service *Service) reserve(input Input, profile string, start StartFunc) (Status, bool, error) {
	if input.UserID == 0 || input.Path == "" || input.Identity == "" || input.SourcePath == "" {
		return Status{}, false, fmt.Errorf("invalid HLS source")
	}
	if start == nil {
		return Status{}, false, fmt.Errorf("HLS task starter is required")
	}
	// Input-side stream copy starts at the preceding keyframe. A bounded
	// nonzero playback window promises source-time alignment, so decode/discard
	// to the requested start instead of reporting a misleading requested offset.
	if input.HDR || input.WindowSeconds > 0 && input.StartSeconds > 0 {
		if IsWebMCopyProfile(profile) {
			profile = WebMProfileForDimensions("source", input.VideoWidth, input.VideoHeight)
		} else if IsCopyProfile(profile) || IsMP4CopyProfile(profile) {
			profile = ProfileForDimensions("source", input.VideoWidth, input.VideoHeight)
		}
	}
	cacheIdentity := input.Identity
	if input.WindowSeconds > 0 {
		cacheIdentity += fmt.Sprintf("\x00window-v1:%.3f:%.3f:%s", input.StartSeconds, input.WindowSeconds, input.SessionID)
	}
	if input.SubtitleStream != nil {
		cacheIdentity += "\x00subtitle=" + strconv.Itoa(*input.SubtitleStream) + "\x00pgs-overlay-shortest-v1"
	}
	if input.AudioStream != nil {
		cacheIdentity += "\x00audio=" + strconv.Itoa(*input.AudioStream)
	}
	if input.HDR {
		cacheIdentity += "\x00hdr10-sdr-v1"
	}
	if service.accelerator == "rkmpp" && !IsCopyProfile(profile) && !IsMP4CopyProfile(profile) && !IsWebMCopyProfile(profile) {
		cacheIdentity += "\x00rkmpp-rga-opencl-cqp18-v1"
	}
	job := Job{
		ID:     cacheKey(input.UserID, input.Path, cacheIdentity, profile),
		UserID: input.UserID, Path: input.Path, Identity: input.Identity,
		SourcePath: input.SourcePath, Profile: profile,
		DurationSeconds:       input.DurationSeconds,
		SourceDurationSeconds: input.DurationSeconds,
		StartSeconds:          input.StartSeconds,
		WindowSeconds:         input.WindowSeconds,
		SessionID:             input.SessionID,
		SubtitleStream:        input.SubtitleStream,
		AudioStream:           input.AudioStream,
		HDR:                   input.HDR,
		VideoWidth:            input.VideoWidth, VideoHeight: input.VideoHeight, VideoCodec: input.VideoCodec,
	}
	if input.WindowSeconds > 0 {
		job.DurationSeconds = min(input.WindowSeconds, input.DurationSeconds-input.StartSeconds)
	}

	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[job.ID]; current != nil &&
		(current.State == StateQueued || current.State == StatePreparing ||
			current.State == StateStreamable || current.State == StateCompleted) {
		return current.Status, false, nil
	}
	outputRange := input.DynamicRange
	if input.HDR && !IsCopyProfile(profile) && !IsMP4CopyProfile(profile) && !IsWebMCopyProfile(profile) {
		outputRange = "sdr"
	}
	if outputRange == "" {
		outputRange = "unknown"
	}
	now := time.Now().UnixMilli()
	current := &entry{Status: Status{
		ID: job.ID, UserID: job.UserID, Path: job.Path, Identity: job.Identity,
		Profile: job.Profile, State: StateQueued, UpdatedAt: now,
		DurationSeconds:       job.DurationSeconds,
		SourceDurationSeconds: job.SourceDurationSeconds,
		StartSeconds:          job.StartSeconds,
		WindowSeconds:         job.WindowSeconds,
		SubtitleStream:        job.SubtitleStream,
		AudioStream:           job.AudioStream,
		HDR:                   job.HDR,
		OutputDynamicRange:    outputRange,
	}, sourcePath: job.SourcePath}
	service.entries[job.ID] = current
	taskID, err := start(job)
	if err != nil {
		current.State = StateFailed
		current.Error = err.Error()
		current.UpdatedAt = time.Now().UnixMilli()
		return current.Status, true, err
	}
	current.TaskID = taskID
	current.UpdatedAt = time.Now().UnixMilli()
	return current.Status, true, nil
}

func (service *Service) Get(id string, userID uint) (Status, error) {
	service.mu.Lock()
	defer service.mu.Unlock()
	current := service.entries[id]
	if current == nil {
		return Status{}, ErrNotFound
	}
	if current.UserID != userID {
		return Status{}, ErrForbidden
	}
	return current.Status, nil
}

func (service *Service) Run(ctx context.Context, job Job) error {
	if err := service.reportPhase(job, "queued"); err != nil {
		return err
	}
	workers := service.workers
	// Short interactive windows have a reserved slot so a long background
	// export cannot make seeking wait for an entire movie to finish.
	if job.WindowSeconds > 0 && job.SessionID != "" {
		workers = service.playbackWorkers
	}
	select {
	case workers <- struct{}{}:
		defer func() { <-workers }()
	case <-ctx.Done():
		service.finish(job.ID, StateCanceled, "任务已取消", 0)
		return ctx.Err()
	}

	service.mu.Lock()
	current := service.entries[job.ID]
	if current == nil || current.UserID != job.UserID || current.Identity != job.Identity {
		service.mu.Unlock()
		return ErrState
	}
	current.State = StatePreparing
	current.Error = ""
	current.ProcessedSeconds = 0
	current.UpdatedAt = time.Now().UnixMilli()
	service.mu.Unlock()
	if err := service.reportPhase(job, "preparing"); err != nil {
		return err
	}

	directory := service.entryDir(job.ID)
	if err := os.RemoveAll(directory); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("reset HLS work directory: %w", err)
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("create HLS work directory: %w", err)
	}
	if IsWebMProfile(job.Profile) {
		return service.runWebM(ctx, job, directory)
	}
	if IsWebMCopyProfile(job.Profile) {
		return service.runWebMCopy(ctx, job, directory)
	}
	if IsMP4CopyProfile(job.Profile) {
		return service.runMP4Copy(ctx, job, directory)
	}

	playlist := filepath.Join(directory, "index.m3u8")
	segmentPattern := filepath.Join(directory, "segment-%06d.ts")
	args := ffmpegArgs(job.SourcePath, segmentPattern, playlist, profileMaxWidth(job.Profile), profileMaxHeight(job.Profile))
	if job.SubtitleStream != nil || job.AudioStream != nil || job.HDR {
		args = ffmpegTrackArgs(job.SourcePath, segmentPattern, playlist, profileMaxWidth(job.Profile), profileMaxHeight(job.Profile), job.SubtitleStream, job.AudioStream, job.HDR)
	}
	if IsCopyProfile(job.Profile) {
		args = copyFFmpegArgs(job.SourcePath, segmentPattern, playlist)
		args = copyTrackArgs(args, job)
	}
	command := exec.CommandContext(ctx, service.ffmpegPath, args...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	progressDone, err := service.startProgress(command, job)
	if err != nil {
		message := fmt.Sprintf("FFmpeg HLS 转码启动失败: %v", err)
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}

	wait := make(chan error, 1)
	go func() {
		progressErr := <-progressDone
		err := command.Wait()
		if progressErr != nil {
			err = progressErr
		}
		wait <- err
	}()
	ticker := time.NewTicker(120 * time.Millisecond)
	defer ticker.Stop()
	streamable := false
	var runErr error
	finished := false
	for !finished {
		select {
		case runErr = <-wait:
			finished = true
		case <-ticker.C:
			if !streamable && readyToStream(directory) {
				streamable = true
				service.setState(job.ID, StateStreamable, "")
			}
		case <-ctx.Done():
			runErr = <-wait
			if runErr == nil {
				runErr = ctx.Err()
			}
			finished = true
		}
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		service.finish(job.ID, StateCanceled, "任务已取消", 0)
		_ = os.RemoveAll(directory)
		return ctx.Err()
	}
	if runErr != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = runErr.Error()
		}
		message = "FFmpeg HLS 转码失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if !readyToStream(directory) {
		message := "FFmpeg HLS 转码未生成可播放分段"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}

	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure HLS cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist HLS cache metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) runWebM(ctx context.Context, job Job, directory string) error {
	temporary := filepath.Join(directory, "index.webm.tmp")
	output := filepath.Join(directory, "index.webm")
	args := webMArgs(job.SourcePath, temporary, profileMaxWidth(job.Profile), profileMaxHeight(job.Profile))
	if job.SubtitleStream != nil || job.AudioStream != nil || job.HDR {
		args = webMTrackArgs(job.SourcePath, temporary, profileMaxWidth(job.Profile), profileMaxHeight(job.Profile), job.SubtitleStream, job.AudioStream, job.HDR)
	}
	command := exec.CommandContext(ctx, service.ffmpegPath, args...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	runErr := service.runProgress(command, job)
	if errors.Is(ctx.Err(), context.Canceled) {
		service.finish(job.ID, StateCanceled, "任务已取消", 0)
		_ = os.RemoveAll(directory)
		return ctx.Err()
	}
	if runErr != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = runErr.Error()
		}
		message = "FFmpeg WebM 转码失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if err := os.Rename(temporary, output); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		_ = os.RemoveAll(directory)
		return fmt.Errorf("publish WebM compatibility artifact: %w", err)
	}
	if !readyForProfile(directory, job.Profile) {
		message := "FFmpeg WebM 转码未生成可播放文件"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}

	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure WebM cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist WebM cache metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) runWebMCopy(ctx context.Context, job Job, directory string) error {
	temporary := filepath.Join(directory, "index.webm.tmp")
	output := filepath.Join(directory, "index.webm")
	command := exec.CommandContext(ctx, service.ffmpegPath, webMCopyArgs(job.SourcePath, temporary)...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	if err := service.runProgress(command, job); err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			service.finish(job.ID, StateCanceled, "任务已取消", 0)
			_ = os.RemoveAll(directory)
			return ctx.Err()
		}
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		message = "FFmpeg WebM 重新封装失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if err := os.Rename(temporary, output); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		_ = os.RemoveAll(directory)
		return fmt.Errorf("publish WebM remux artifact: %w", err)
	}
	if !readyForProfile(directory, job.Profile) {
		message := "FFmpeg WebM 重新封装未生成可播放文件"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure WebM remux cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist WebM remux metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) runMP4Copy(ctx context.Context, job Job, directory string) error {
	temporary := filepath.Join(directory, "index.mp4.tmp")
	output := filepath.Join(directory, "index.mp4")
	command := exec.CommandContext(ctx, service.ffmpegPath, copyTrackArgs(mp4CopyArgs(job.SourcePath, temporary), job)...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	if err := service.runProgress(command, job); err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			service.finish(job.ID, StateCanceled, "任务已取消", 0)
			_ = os.RemoveAll(directory)
			return ctx.Err()
		}
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		message = "MP4 兼容封装失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if err := os.Rename(temporary, output); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		_ = os.RemoveAll(directory)
		return fmt.Errorf("publish MP4 remux artifact: %w", err)
	}
	if !readyForProfile(directory, job.Profile) {
		message := "MP4 兼容封装未生成可播放文件"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure MP4 cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist MP4 cache metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) Asset(id string, userID uint, name string) (string, State, error) {
	if !assetNameRE.MatchString(name) {
		return "", "", ErrNotFound
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	current := service.entries[id]
	if current == nil {
		return "", "", ErrNotFound
	}
	if current.UserID != userID {
		return "", "", ErrForbidden
	}
	if current.State != StateStreamable && current.State != StateCompleted {
		return "", current.State, ErrState
	}
	if (name == "index.webm" || name == "index.mp4") && current.State != StateCompleted {
		return "", current.State, ErrState
	}
	path := filepath.Join(service.entryDir(id), name)
	if info, err := os.Stat(path); err != nil || info.IsDir() {
		return "", current.State, ErrNotFound
	}
	now := time.Now()
	current.LastAccessAt = now.UnixMilli()
	current.leaseUntil = now.Add(playingLease)
	return path, current.State, nil
}

func (service *Service) setState(id string, state State, message string) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[id]; current != nil {
		current.State = state
		current.Error = message
		current.UpdatedAt = time.Now().UnixMilli()
	}
}

func (service *Service) finish(id string, state State, message string, size int64) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[id]; current != nil {
		current.State = state
		current.Error = message
		current.SizeBytes = size
		current.UpdatedAt = time.Now().UnixMilli()
	}
}

func (service *Service) persist(id string) error {
	service.mu.Lock()
	current := service.entries[id]
	if current == nil || current.State != StateCompleted {
		service.mu.Unlock()
		return ErrState
	}
	status := current.Status
	service.mu.Unlock()

	payload, err := json.Marshal(status)
	if err != nil {
		return err
	}
	directory := service.entryDir(id)
	temporary, err := os.CreateTemp(directory, ".meta-*.tmp")
	if err != nil {
		return err
	}
	temporaryName := temporary.Name()
	defer func() { _ = os.Remove(temporaryName) }()
	if err := temporary.Chmod(0o600); err != nil {
		_ = temporary.Close()
		return err
	}
	if _, err := temporary.Write(payload); err != nil {
		_ = temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryName, filepath.Join(directory, "meta.json"))
}

func (service *Service) loadCompleted() error {
	prefixes, err := os.ReadDir(service.cacheDir)
	if err != nil {
		return fmt.Errorf("read HLS cache directory: %w", err)
	}
	for _, prefix := range prefixes {
		if !prefix.IsDir() || len(prefix.Name()) != 2 {
			continue
		}
		children, readErr := os.ReadDir(filepath.Join(service.cacheDir, prefix.Name()))
		if readErr != nil {
			return fmt.Errorf("read HLS cache prefix: %w", readErr)
		}
		for _, child := range children {
			if !child.IsDir() || !cacheIDRE.MatchString(child.Name()) || !strings.HasPrefix(child.Name(), prefix.Name()) {
				continue
			}
			directory := filepath.Join(service.cacheDir, prefix.Name(), child.Name())
			payload, readErr := os.ReadFile(filepath.Join(directory, "meta.json"))
			var status Status
			if readErr != nil || json.Unmarshal(payload, &status) != nil ||
				status.ID != child.Name() || status.State != StateCompleted || !readyForProfile(directory, status.Profile) {
				_ = os.RemoveAll(directory)
				continue
			}
			if status.SizeBytes <= 0 {
				status.SizeBytes, _ = directorySize(directory)
			}
			service.entries[status.ID] = &entry{Status: status}
		}
	}
	return nil
}

func (service *Service) cleanupLocked(protectID string) {
	total := int64(0)
	candidates := make([]*entry, 0, len(service.entries))
	now := time.Now()
	for _, current := range service.entries {
		if current.State != StateCompleted {
			continue
		}
		total += current.SizeBytes
		if current.ID != protectID && !current.leaseUntil.After(now) {
			candidates = append(candidates, current)
		}
	}
	if total <= service.maxBytes {
		return
	}
	sort.SliceStable(candidates, func(left, right int) bool {
		leftTime := candidates[left].LastAccessAt
		if leftTime == 0 {
			leftTime = candidates[left].UpdatedAt
		}
		rightTime := candidates[right].LastAccessAt
		if rightTime == 0 {
			rightTime = candidates[right].UpdatedAt
		}
		return leftTime < rightTime
	})
	for _, candidate := range candidates {
		if total <= service.maxBytes {
			break
		}
		if err := os.RemoveAll(service.entryDir(candidate.ID)); err != nil {
			continue
		}
		total -= candidate.SizeBytes
		delete(service.entries, candidate.ID)
	}
}

func (service *Service) entryDir(id string) string {
	return filepath.Join(service.cacheDir, id[:2], id)
}

func cacheKey(userID uint, path, identity, profile string) string {
	version := ""
	if !IsCopyProfile(profile) && !IsMP4CopyProfile(profile) && !IsWebMCopyProfile(profile) {
		version = "\x00" + transcodeScaleVersion
	}
	digest := sha256.Sum256([]byte(strconv.FormatUint(uint64(userID), 10) + "\x00" + path + "\x00" + identity + "\x00" + profile + version))
	return hex.EncodeToString(digest[:])
}

func boundedVideoScale(maxWidth, maxHeight int) string {
	if maxWidth <= 0 {
		maxWidth = 1280
	}
	if maxHeight <= 0 {
		maxHeight = 720
	}
	return fmt.Sprintf("scale=w='trunc(min(%d,iw)/2)*2':h='trunc(min(%d,ih)/2)*2':force_original_aspect_ratio=decrease,pad=ceil(iw/2)*2:ceil(ih/2)*2", maxWidth, maxHeight)
}

func ffmpegArgs(source, segmentPattern, playlist string, maxWidth, maxHeight int) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?",
		"-vf", boundedVideoScale(maxWidth, maxHeight),
		"-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-pix_fmt", "yuv420p",
		"-threads", "1", "-filter_threads", "1",
		"-c:a", "aac", "-b:a", "128k", "-ac", "2",
		"-force_key_frames", "expr:gte(t,n_forced*4)",
		"-f", "hls", "-hls_time", "4", "-hls_list_size", "0", "-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+temp_file",
		"-hls_segment_filename", segmentPattern, playlist,
	}
}

func ffmpegSubtitleArgs(source, segmentPattern, playlist string, maxWidth, maxHeight, streamIndex int) []string {
	return ffmpegTrackArgs(source, segmentPattern, playlist, maxWidth, maxHeight, &streamIndex, nil, false)
}

func selectedAudioMap(streamIndex *int) string {
	if streamIndex == nil {
		return "0:a:0?"
	}
	return fmt.Sprintf("0:%d", *streamIndex)
}

func videoTrackArgs(maxWidth, maxHeight int, subtitleStream *int, hdr bool) []string {
	filter := boundedVideoScale(maxWidth, maxHeight)
	if hdr {
		filter += ",format=yuv420p10le,zscale=t=linear:npl=100,format=gbrpf32le,tonemap=tonemap=hable:desat=0,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p"
	}
	if subtitleStream == nil {
		return []string{"-map", "0:v:0", "-vf", filter}
	}
	// PGS decoding emits a terminal canvas frame; it must not extend the
	// encoded timeline beyond the main video's EOF.
	graph := fmt.Sprintf("[0:v:0][0:%d]overlay=shortest=1,%s[v]", *subtitleStream, filter)
	return []string{"-filter_complex", graph, "-map", "[v]", "-filter_complex_threads", "1"}
}

func ffmpegTrackArgs(source, segmentPattern, playlist string, maxWidth, maxHeight int, subtitleStream, audioStream *int, hdr bool) []string {
	args := []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
	}
	args = append(args, videoTrackArgs(maxWidth, maxHeight, subtitleStream, hdr)...)
	args = append(args, "-map", selectedAudioMap(audioStream))
	args = append(args,
		"-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-pix_fmt", "yuv420p",
		"-threads", "1", "-filter_threads", "1",
		"-c:a", "aac", "-b:a", "128k", "-ac", "2",
		"-force_key_frames", "expr:gte(t,n_forced*4)",
	)
	if hdr {
		args = append(args, "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709")
	}
	return append(args,
		"-f", "hls", "-hls_time", "4", "-hls_list_size", "0", "-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+temp_file",
		"-hls_segment_filename", segmentPattern, playlist,
	)
}

func copyFFmpegArgs(source, segmentPattern, playlist string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?",
		"-c:v", "copy", "-c:a", "copy",
		"-f", "hls", "-hls_time", "4", "-hls_list_size", "0", "-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+temp_file",
		"-hls_segment_filename", segmentPattern, playlist,
	}
}

func webMArgs(source, output string, maxWidth, maxHeight int) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?",
		"-vf", boundedVideoScale(maxWidth, maxHeight),
		"-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1.5M",
		// Keep the compatibility queue globally serial, but let the single
		// active VP9 encode use two codec threads. On the NAS ARM host this
		// materially shortens the first-play wait without increasing the
		// number of simultaneous FFmpeg jobs.
		"-row-mt", "1", "-threads", "2", "-pix_fmt", "yuv420p",
		"-c:a", "libopus", "-b:a", "128k", "-ac", "2",
		"-progress", "pipe:1",
		"-f", "webm", output,
	}
}

func webMSubtitleArgs(source, output string, maxWidth, maxHeight, streamIndex int) []string {
	return webMTrackArgs(source, output, maxWidth, maxHeight, &streamIndex, nil, false)
}

func webMTrackArgs(source, output string, maxWidth, maxHeight int, subtitleStream, audioStream *int, hdr bool) []string {
	args := []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
	}
	args = append(args, videoTrackArgs(maxWidth, maxHeight, subtitleStream, hdr)...)
	args = append(args, "-map", selectedAudioMap(audioStream))
	args = append(args,
		"-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1.5M",
		"-row-mt", "1", "-threads", "2", "-pix_fmt", "yuv420p",
		"-c:a", "libopus", "-b:a", "128k", "-ac", "2",
		"-progress", "pipe:1",
	)
	if hdr {
		args = append(args, "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709")
	}
	return append(args, "-f", "webm", output)
}

func webMCopyArgs(source, output string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-f", "webm", output,
	}
}

func mp4CopyArgs(source, output string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart",
		"-f", "mp4", output,
	}
}

func parseFFmpegProgressLine(line string) (float64, bool) {
	key, value, found := strings.Cut(strings.TrimSpace(line), "=")
	if !found || key != "out_time_ms" {
		return 0, false
	}
	microseconds, err := strconv.ParseInt(value, 10, 64)
	if err != nil || microseconds < 0 {
		return 0, false
	}
	return float64(microseconds) / 1_000_000, true
}

func readyToStream(directory string) bool {
	playlist, err := os.Stat(filepath.Join(directory, "index.m3u8"))
	if err != nil || playlist.Size() == 0 {
		return false
	}
	segment, err := os.Stat(filepath.Join(directory, "segment-000000.ts"))
	return err == nil && segment.Size() > 0
}

func readyForProfile(directory, profile string) bool {
	if IsWebMProfile(profile) || IsWebMCopyProfile(profile) {
		info, err := os.Stat(filepath.Join(directory, "index.webm"))
		return err == nil && !info.IsDir() && info.Size() > 0
	}
	if IsMP4CopyProfile(profile) {
		info, err := os.Stat(filepath.Join(directory, "index.mp4"))
		return err == nil && !info.IsDir() && info.Size() > 0
	}
	return readyToStream(directory)
}

func directorySize(directory string) (int64, error) {
	var total int64
	err := filepath.WalkDir(directory, func(_ string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		total += info.Size()
		return nil
	})
	return total, err
}

// SourceAssetDirectory keeps auxiliary media assets in the configured private cache.
func (service *Service) SourceAssetDirectory() string {
	return filepath.Join(service.cacheDir, "source-assets")
}
