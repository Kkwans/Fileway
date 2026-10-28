package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/mediatools"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

type mediaAttachment struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Mime string `json:"mime"`
}

type mediaInfoResponse struct {
	Identity       string                 `json:"identity"`
	WirePath       string                 `json:"wirePath"`
	DynamicRange   string                 `json:"dynamicRange"`
	VideoTransfer  string                 `json:"videoTransfer,omitempty"`
	Attachments    []mediaAttachment      `json:"attachments,omitempty"`
	Path           string                 `json:"path"`
	Name           string                 `json:"name"`
	Type           string                 `json:"type"`
	Extension      string                 `json:"extension"`
	Size           int64                  `json:"size"`
	Modified       time.Time              `json:"modified"`
	Resolution     *files.ImageResolution `json:"resolution,omitempty"`
	Format         string                 `json:"format,omitempty"`
	Duration       float64                `json:"duration,omitempty"`
	BitRate        int64                  `json:"bitRate,omitempty"`
	VideoCodec     string                 `json:"videoCodec,omitempty"`
	VideoBitDepth  int                    `json:"videoBitDepth,omitempty"`
	HDR            bool                   `json:"hdr,omitempty"`
	AudioCodec     string                 `json:"audioCodec,omitempty"`
	AudioTracks    []mediaTrack           `json:"audioTracks,omitempty"`
	SubtitleTracks []mediaTrack           `json:"subtitleTracks,omitempty"`
	Channels       int                    `json:"channels,omitempty"`
	SampleRate     int                    `json:"sampleRate,omitempty"`
	Title          string                 `json:"title,omitempty"`
	Artist         string                 `json:"artist,omitempty"`
	Album          string                 `json:"album,omitempty"`
	Date           string                 `json:"date,omitempty"`
	Location       string                 `json:"location,omitempty"`
	TechnicalError string                 `json:"technicalError,omitempty"`
}

type mediaTrack struct {
	Index    int    `json:"index"`
	Codec    string `json:"codec"`
	Language string `json:"language,omitempty"`
	Title    string `json:"title,omitempty"`
	Default  bool   `json:"default,omitempty"`
	Forced   bool   `json:"forced,omitempty"`
}

type mediaProbeResult struct {
	Format           string
	Duration         float64
	BitRate          int64
	VideoCodec       string
	Attachments      []mediaAttachment
	DolbyVision      bool
	VideoTransfer    string
	AudioCodec       string
	AudioTracks      []mediaTrack
	SubtitleTracks   []mediaTrack
	VideoPixelFormat string
	VideoProfile     string
	VideoBitDepth    int
	Width            int
	Height           int
	Channels         int
	SampleRate       int
	Title            string
	Artist           string
	Album            string
	Date             string
	Location         string
}

type mediaProbe func(ctx context.Context, path string, includeLocation bool) (mediaProbeResult, error)

func mediaInfoHandler(probe mediaProbe) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Download {
			return http.StatusForbidden, fmt.Errorf("没有读取媒体信息的权限")
		}
		value := r.URL.Query().Get("path")
		if wire := r.URL.Query().Get("wirePath"); wire != "" {
			decoded, err := decodeResourceWirePath(wire)
			if err != nil {
				return http.StatusBadRequest, err
			}
			value = decoded
		}
		if value == "" {
			return http.StatusBadRequest, fmt.Errorf("媒体路径不能为空")
		}
		value = pathmeta.Clean(value)
		file, err := files.NewFileInfo(&files.FileOptions{
			Fs: d.user.Fs, Path: value, Modify: d.user.Perm.Modify,
			Expand: true, SkipSubtitles: true,
			ReadHeader: d.server.TypeDetectionByHeader,
			CalcImgRes: true, Checker: d,
		})
		if err != nil {
			return errToStatus(err), err
		}
		if file.IsDir || (file.Type != "image" && file.Type != "video" && file.Type != "audio") {
			return http.StatusBadRequest, fmt.Errorf("该文件不是可探测的媒体")
		}

		response := mediaInfoResponse{
			Identity: mediaSourceIdentity(file), WirePath: files.EncodeWirePath(file.Path), DynamicRange: "unknown",
			Path: file.Path, Name: file.Name, Type: file.Type,
			Extension: file.Extension, Size: file.Size, Modified: file.ModTime,
			Resolution: file.Resolution,
		}
		if file.Type == "video" || file.Type == "audio" {
			includeLocation := r.URL.Query().Get("includeLocation") == "true"
			ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
			defer cancel()
			technical, probeErr := probe(ctx, file.RealPath(), includeLocation)
			if probeErr != nil {
				response.TechnicalError = probeErr.Error()
			} else {
				response.applyProbe(technical, includeLocation)
			}
		}
		return renderJSON(w, r, response)
	})
}

func (response *mediaInfoResponse) applyProbe(probe mediaProbeResult, includeLocation bool) {
	response.Format = probe.Format
	response.Duration = probe.Duration
	response.BitRate = probe.BitRate
	response.VideoCodec = probe.VideoCodec
	response.VideoBitDepth = probe.VideoBitDepth
	if response.VideoBitDepth == 0 {
		switch {
		case strings.Contains(probe.VideoPixelFormat, "12"):
			response.VideoBitDepth = 12
		case strings.Contains(probe.VideoPixelFormat, "10"):
			response.VideoBitDepth = 10
		case probe.VideoPixelFormat != "":
			response.VideoBitDepth = 8
		}
	}
	response.DynamicRange = mediaDynamicRange(probe)
	response.VideoTransfer = probe.VideoTransfer
	response.HDR = response.DynamicRange != "sdr" && response.DynamicRange != "unknown"
	response.Attachments = probe.Attachments
	response.AudioCodec = probe.AudioCodec
	response.AudioTracks = probe.AudioTracks
	response.SubtitleTracks = probe.SubtitleTracks
	response.Channels = probe.Channels
	response.SampleRate = probe.SampleRate
	response.Title = probe.Title
	response.Artist = probe.Artist
	response.Album = probe.Album
	response.Date = probe.Date
	if response.Resolution == nil && probe.Width > 0 && probe.Height > 0 {
		response.Resolution = &files.ImageResolution{Width: probe.Width, Height: probe.Height}
	}
	if includeLocation {
		response.Location = probe.Location
	}
}

type ffprobeStream struct {
	Index            int               `json:"index"`
	CodecType        string            `json:"codec_type"`
	CodecName        string            `json:"codec_name"`
	PixelFormat      string            `json:"pix_fmt"`
	ColorTransfer    string            `json:"color_transfer"`
	Profile          string            `json:"profile"`
	BitsPerRawSample string            `json:"bits_per_raw_sample"`
	Width            int               `json:"width"`
	Height           int               `json:"height"`
	Channels         int               `json:"channels"`
	SampleRate       string            `json:"sample_rate"`
	Tags             map[string]string `json:"tags"`
	SideData         []struct {
		Type         string `json:"side_data_type"`
		DolbyProfile int    `json:"dv_profile"`
	} `json:"side_data_list"`
	Disposition struct {
		Default int `json:"default"`
		Forced  int `json:"forced"`
	} `json:"disposition"`
}

type ffprobeDocument struct {
	Streams []ffprobeStream `json:"streams"`
	Format  struct {
		FormatName string            `json:"format_name"`
		Duration   string            `json:"duration"`
		BitRate    string            `json:"bit_rate"`
		Tags       map[string]string `json:"tags"`
	} `json:"format"`
}

func defaultMediaProbe(ctx context.Context, path string, includeLocation bool) (mediaProbeResult, error) {
	ffprobePath, err := mediatools.Lookup("ffprobe")
	if err != nil {
		return mediaProbeResult{}, fmt.Errorf("FFprobe 不可用: %w", err)
	}
	entries := "format=format_name,duration,bit_rate:format_tags=title,artist,album,date:stream=index,codec_type,codec_name,pix_fmt,color_transfer,profile,bits_per_raw_sample,width,height,channels,sample_rate:stream_tags=language,title,filename,mimetype:stream_disposition=default,forced:stream_side_data=side_data_type,dv_profile"
	if includeLocation {
		// Location is deliberately requested only after an explicit user action.
		entries = "format:stream=index,codec_type,codec_name,pix_fmt,color_transfer,profile,bits_per_raw_sample,width,height,channels,sample_rate:stream_tags=language,title,filename,mimetype:stream_disposition=default,forced:stream_side_data=side_data_type,dv_profile"
	}
	command := exec.CommandContext(ctx, ffprobePath,
		"-v", "error",
		// Container and stream headers are enough for the UI's codec decision.
		// Bound probing so opening a video does not scan a large NAS file before
		// the player can show its first useful state.
		"-probesize", "1M", "-analyzeduration", "1M",
		"-show_entries", entries, "-of", "json", path,
	)
	output, err := command.Output()
	if err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return mediaProbeResult{}, fmt.Errorf("媒体信息探测超时")
		}
		return mediaProbeResult{}, fmt.Errorf("媒体信息探测失败: %w", err)
	}
	var document ffprobeDocument
	if err := json.Unmarshal(output, &document); err != nil {
		return mediaProbeResult{}, fmt.Errorf("媒体信息解析失败: %w", err)
	}
	return summarizeFFprobe(document, includeLocation), nil
}

func summarizeFFprobe(document ffprobeDocument, includeLocation bool) mediaProbeResult {
	result := mediaProbeResult{
		Format: document.Format.FormatName,
		Title:  tagValue(document.Format.Tags, "title"),
		Artist: tagValue(document.Format.Tags, "artist"),
		Album:  tagValue(document.Format.Tags, "album"),
		Date:   tagValue(document.Format.Tags, "date"),
	}
	result.Duration, _ = strconv.ParseFloat(document.Format.Duration, 64)
	result.BitRate, _ = strconv.ParseInt(document.Format.BitRate, 10, 64)
	if includeLocation {
		result.Location = firstTagValue(document.Format.Tags,
			"location", "location-eng", "com.apple.quicktime.location.iso6709",
		)
	}
	for _, stream := range document.Streams {
		track := mediaTrack{
			Index: stream.Index, Codec: stream.CodecName,
			Language: tagValue(stream.Tags, "language"),
			Title:    tagValue(stream.Tags, "title"),
			Default:  stream.Disposition.Default != 0,
			Forced:   stream.Disposition.Forced != 0,
		}
		switch stream.CodecType {
		case "video":
			if result.VideoCodec == "" {
				result.VideoCodec = stream.CodecName
				result.VideoTransfer = stream.ColorTransfer
				for _, side := range stream.SideData {
					if side.DolbyProfile > 0 {
						result.DolbyVision = true
					}
				}
				result.VideoPixelFormat = stream.PixelFormat
				result.VideoProfile = stream.Profile
				result.VideoBitDepth, _ = strconv.Atoi(stream.BitsPerRawSample)
				result.Width = stream.Width
				result.Height = stream.Height
			}
		case "audio":
			result.AudioTracks = append(result.AudioTracks, track)
			if result.AudioCodec == "" {
				result.AudioCodec = stream.CodecName
				result.Channels = stream.Channels
				result.SampleRate, _ = strconv.Atoi(stream.SampleRate)
			}
		case "subtitle":
			result.SubtitleTracks = append(result.SubtitleTracks, track)
		case "attachment":
			mime := tagValue(stream.Tags, "mimetype")
			if sourceFontAllowed(stream.CodecName, mime) && len(result.Attachments) < 64 {
				result.Attachments = append(result.Attachments, mediaAttachment{ID: "f" + strconv.Itoa(stream.Index), Name: tagValue(stream.Tags, "filename"), Mime: mime})
			}
		}
	}
	return result
}

func firstTagValue(tags map[string]string, keys ...string) string {
	for _, key := range keys {
		if value := tagValue(tags, key); value != "" {
			return value
		}
	}
	return ""
}

func tagValue(tags map[string]string, key string) string {
	for candidate, value := range tags {
		if strings.EqualFold(candidate, key) {
			return value
		}
	}
	return ""
}

// Source identity is identical to the existing playback-progress contract.
func mediaSourceIdentity(file *files.FileInfo) string {
	return "v1:" + strconv.FormatInt(file.Size, 10) + ":" + strconv.FormatInt(file.ModTime.UnixNano(), 10)
}
func mediaDynamicRange(probe mediaProbeResult) string {
	if probe.DolbyVision {
		return "dolby-vision"
	}
	switch probe.VideoTransfer {
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
func sourceFontAllowed(codec, mime string) bool {
	return (codec == "ttf" || codec == "otf") && (mime == "application/x-truetype-font" || mime == "application/x-font-ttf" || mime == "application/vnd.ms-opentype" || mime == "font/ttf" || mime == "font/otf")
}
