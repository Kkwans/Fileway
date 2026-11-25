package mediatools

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	maxProbeDurationMS int64 = 7 * 24 * 60 * 60 * 1000
	maxProbeTracks           = 64
	maxProbeOutput           = 64 << 10
)

type MediaTrack struct {
	Index    int    `json:"index"`
	Codec    string `json:"codec"`
	Language string `json:"language,omitempty"`
	Title    string `json:"title,omitempty"`
	Default  bool   `json:"default,omitempty"`
	Forced   bool   `json:"forced,omitempty"`
}

type MediaObservation struct {
	DurationMs     int64        `json:"durationMs"`
	VideoCodec     string       `json:"videoCodec,omitempty"`
	VideoProfile   string       `json:"videoProfile,omitempty"`
	PixelFormat    string       `json:"pixelFormat,omitempty"`
	VideoBitDepth  int          `json:"videoBitDepth,omitempty"`
	Width          int          `json:"width,omitempty"`
	Height         int          `json:"height,omitempty"`
	ColorTransfer  string       `json:"colorTransfer,omitempty"`
	DynamicRange   string       `json:"dynamicRange,omitempty"`
	AudioTracks    []MediaTrack `json:"audioTracks,omitempty"`
	SubtitleTracks []MediaTrack `json:"subtitleTracks,omitempty"`
}

func ParseProbeDuration(value string) (int64, error) {
	seconds, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds <= 0 || seconds > float64(maxProbeDurationMS)/1000 {
		return 0, errors.New("媒体时长证据无效")
	}
	milliseconds := int64(math.Round(seconds * 1000))
	if milliseconds <= 0 || milliseconds > maxProbeDurationMS {
		return 0, errors.New("媒体时长证据无效")
	}
	return milliseconds, nil
}

func ProbeDuration(ctx context.Context, file *os.File) (int64, error) {
	observation, err := ProbeMedia(ctx, file)
	return observation.DurationMs, err
}

// ProbeMedia reads bounded container headers from the already authorized file.
// Unix descendants inherit that exact handle; Windows reopens the same path and
// the scanner revalidates file identity before committing the observation.
func ProbeMedia(ctx context.Context, file *os.File) (MediaObservation, error) {
	if file == nil {
		return MediaObservation{}, errors.New("媒体文件无效")
	}
	ffprobePath, err := Lookup("ffprobe")
	if err != nil {
		return MediaObservation{}, err
	}
	offset, err := file.Seek(0, io.SeekCurrent)
	if err != nil {
		return MediaObservation{}, err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return MediaObservation{}, err
	}
	defer func() { _, _ = file.Seek(offset, io.SeekStart) }()
	input := file.Name()
	var extraFiles []*os.File
	if runtime.GOOS != "windows" {
		extraFiles = []*os.File{file}
		if runtime.GOOS == "linux" {
			input = "/proc/self/fd/3"
		} else {
			input = "/dev/fd/3"
		}
	}
	probeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	command := exec.CommandContext(probeCtx, ffprobePath,
		"-v", "error", "-probesize", "1M", "-analyzeduration", "1M",
		"-show_entries", "format=duration:stream=index,codec_type,codec_name,pix_fmt,color_transfer,profile,bits_per_raw_sample,width,height,channels,sample_rate:stream_tags=language,title:stream_disposition=default,forced:stream_side_data=side_data_type,dv_profile",
		"-of", "json", input)
	command.ExtraFiles = extraFiles
	command.Stderr = io.Discard
	output, err := command.Output()
	if err != nil {
		if errors.Is(probeCtx.Err(), context.DeadlineExceeded) {
			return MediaObservation{}, errors.New("媒体技术探测超时")
		}
		return MediaObservation{}, errors.New("媒体技术探测失败")
	}
	return ParseProbeMedia(output)
}

func ParseProbeMedia(output []byte) (MediaObservation, error) {
	if len(output) == 0 || len(output) > maxProbeOutput {
		return MediaObservation{}, errors.New("媒体技术响应大小无效")
	}
	var document struct {
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
		Streams []struct {
			Index            int               `json:"index"`
			CodecType        string            `json:"codec_type"`
			CodecName        string            `json:"codec_name"`
			PixelFormat      string            `json:"pix_fmt"`
			ColorTransfer    string            `json:"color_transfer"`
			Profile          string            `json:"profile"`
			BitsPerRawSample string            `json:"bits_per_raw_sample"`
			Width            int               `json:"width"`
			Height           int               `json:"height"`
			Tags             map[string]string `json:"tags"`
			SideData         []struct {
				Type         string `json:"side_data_type"`
				DolbyProfile int    `json:"dv_profile"`
			} `json:"side_data_list"`
			Disposition struct {
				Default int `json:"default"`
				Forced  int `json:"forced"`
			} `json:"disposition"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(output, &document); err != nil {
		return MediaObservation{}, fmt.Errorf("媒体技术解析失败: %w", err)
	}
	duration, err := ParseProbeDuration(document.Format.Duration)
	if err != nil {
		return MediaObservation{}, err
	}
	observation := MediaObservation{DurationMs: duration}
	for _, stream := range document.Streams {
		track := MediaTrack{Index: stream.Index, Codec: stream.CodecName,
			Language: stream.Tags["language"], Title: stream.Tags["title"],
			Default: stream.Disposition.Default != 0, Forced: stream.Disposition.Forced != 0}
		switch stream.CodecType {
		case "video":
			if observation.VideoCodec != "" {
				continue
			}
			observation.VideoCodec = stream.CodecName
			observation.VideoProfile = stream.Profile
			observation.PixelFormat = stream.PixelFormat
			observation.VideoBitDepth, _ = strconv.Atoi(stream.BitsPerRawSample)
			observation.Width, observation.Height = stream.Width, stream.Height
			observation.ColorTransfer = stream.ColorTransfer
			for _, side := range stream.SideData {
				if side.DolbyProfile > 0 {
					observation.DynamicRange = "dolby-vision"
					break
				}
			}
		case "audio":
			if len(observation.AudioTracks) >= maxProbeTracks {
				return MediaObservation{}, errors.New("音轨数量超出证据上限")
			}
			observation.AudioTracks = append(observation.AudioTracks, track)
		case "subtitle":
			if len(observation.SubtitleTracks) >= maxProbeTracks {
				return MediaObservation{}, errors.New("字幕轨数量超出证据上限")
			}
			observation.SubtitleTracks = append(observation.SubtitleTracks, track)
		}
	}
	if observation.DynamicRange == "" {
		observation.DynamicRange = classifyDynamicRange(observation.ColorTransfer)
	}
	if err := observation.Valid(); err != nil {
		return MediaObservation{}, err
	}
	return observation, nil
}

func classifyDynamicRange(transfer string) string {
	switch transfer {
	case "smpte2084":
		return "pq"
	case "arib-std-b67":
		return "hlg"
	case "bt709", "smpte170m", "smpte240m", "gamma22", "gamma28", "iec61966-2-1":
		return "sdr"
	default:
		return "unknown"
	}
}

func (observation MediaObservation) Valid() error {
	if observation.DurationMs <= 0 || observation.DurationMs > maxProbeDurationMS ||
		observation.VideoBitDepth < 0 || observation.VideoBitDepth > 16 ||
		observation.Width < 0 || observation.Width > 16384 || observation.Height < 0 || observation.Height > 16384 ||
		len(observation.AudioTracks) > maxProbeTracks || len(observation.SubtitleTracks) > maxProbeTracks {
		return errors.New("媒体技术证据超出有效范围")
	}
	switch observation.DynamicRange {
	case "", "unknown", "sdr", "pq", "hlg", "dolby-vision":
	default:
		return errors.New("媒体动态范围证据无效")
	}
	for _, value := range []string{observation.VideoCodec, observation.VideoProfile, observation.PixelFormat,
		observation.ColorTransfer, observation.DynamicRange} {
		if len(value) > 128 || strings.ContainsRune(value, 0) {
			return errors.New("媒体技术证据字段无效")
		}
	}
	for _, tracks := range [][]MediaTrack{observation.AudioTracks, observation.SubtitleTracks} {
		for _, track := range tracks {
			if track.Index < 0 || len(track.Codec) > 128 || len(track.Language) > 32 || len(track.Title) > 256 ||
				strings.ContainsRune(track.Codec, 0) || strings.ContainsRune(track.Language, 0) || strings.ContainsRune(track.Title, 0) {
				return errors.New("媒体轨道证据字段无效")
			}
		}
	}
	return nil
}
