package mediatools

import (
	"os"
	"path/filepath"
	"testing"
)

func TestExplicitMediaToolOverride(t *testing.T) {
	tool := filepath.Join(t.TempDir(), "ffmpeg.exe")
	if err := os.WriteFile(tool, []byte("test fixture"), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("FFMPEG_PATH", tool)
	resolved, err := Lookup("ffmpeg")
	if err != nil || resolved != tool {
		t.Fatalf("configured media tool = %q, %v", resolved, err)
	}
}

func TestParseProbeDurationRejectsUnreliableValues(t *testing.T) {
	for _, value := range []string{"", "N/A", "NaN", "+Inf", "-1", "0", "604800.001"} {
		if duration, err := ParseProbeDuration(value); err == nil || duration != 0 {
			t.Fatalf("invalid duration %q accepted: %d %v", value, duration, err)
		}
	}
	if duration, err := ParseProbeDuration("3599.999"); err != nil || duration != 3_599_999 {
		t.Fatalf("valid duration: %d %v", duration, err)
	}
}

func TestParseProbeMediaRetainsBoundedTechnicalEvidence(t *testing.T) {
	output := []byte(`{"format":{"duration":"89.999","tags":{"title":"本杰明·巴顿奇事.2008.BD1080p"}},"streams":[
		{"index":0,"codec_type":"video","codec_name":"hevc","pix_fmt":"yuv420p10le","color_transfer":"smpte2084","profile":"Main 10","bits_per_raw_sample":"10","width":3840,"height":2160,"side_data_list":[{"side_data_type":"Dolby Vision","dv_profile":5}]},
		{"index":1,"codec_type":"audio","codec_name":"eac3","channels":6,"sample_rate":"48000","tags":{"language":"eng","title":"English"},"disposition":{"default":1}},
		{"index":2,"codec_type":"subtitle","codec_name":"subrip","tags":{"language":"zho","title":"中文"},"disposition":{"forced":1}}
	]}`)
	observation, err := ParseProbeMedia(output)
	if err != nil || observation.DurationMs != 89_999 || observation.VideoCodec != "hevc" || observation.FormatTitle != "本杰明·巴顿奇事.2008.BD1080p" ||
		observation.VideoBitDepth != 10 || observation.Width != 3840 || observation.Height != 2160 ||
		observation.DynamicRange != "dolby-vision" || len(observation.AudioTracks) != 1 || len(observation.SubtitleTracks) != 1 {
		t.Fatalf("technical observation: %+v %v", observation, err)
	}
	if observation.AudioTracks[0].Language != "eng" || !observation.AudioTracks[0].Default || !observation.SubtitleTracks[0].Forced {
		t.Fatalf("track evidence lost: %+v", observation)
	}
}

func TestProbeDurationReadsRealMediaHeaderWhenAvailable(t *testing.T) {
	if _, err := Lookup("ffprobe"); err != nil {
		t.Skip("ffprobe unavailable")
	}
	file, err := os.Open(filepath.Join("..", "..", "..", "clients", "android", "app", "src", "androidTest", "assets", "media", "subtitle-fixture.mkv"))
	if err != nil {
		t.Skip(err)
	}
	defer file.Close()
	duration, err := ProbeDuration(t.Context(), file)
	if err != nil || duration <= 0 {
		t.Fatalf("real media duration: %d %v", duration, err)
	}
}

func TestInvalidOverrideDoesNotSilentlySelectAnotherTool(t *testing.T) {
	t.Setenv("FFMPEG_PATH", filepath.Join(t.TempDir(), "missing.exe"))
	if _, err := Lookup("ffmpeg"); err == nil {
		t.Fatal("missing configured tool unexpectedly accepted")
	}
	if _, err := Lookup("../filebrowser"); err == nil {
		t.Fatal("unknown tool accepted")
	}
}
