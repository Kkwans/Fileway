package hls

import (
	"context"
	"math"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// Compare decoded pixels with the authored source's non-keyframe timestamp.
// Metadata-only offset checks cannot catch an earlier stream-copy keyframe.
func TestPlaybackWindowDecodesRequestedSourceFrame(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("FFmpeg unavailable")
	}
	source, err := filepath.Abs(filepath.Join("..", "..", "..", "clients", "android-tv", "app", "src", "androidTest", "assets", "media", "tv-owned-source.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	service, err := New(Config{CacheDir: filepath.Join(t.TempDir(), "cache"), Workers: 1, FFmpegPath: ffmpeg, EncodeThreads: 1})
	if err != nil {
		t.Fatal(err)
	}
	var job Job
	status, _, err := service.ReserveCopy(Input{UserID: 1, Path: "/owned.mp4", SourcePath: source, Identity: "owned-source", VideoCodec: "h264", VideoWidth: 160, VideoHeight: 90, DurationSeconds: 150, StartSeconds: 121, WindowSeconds: 6, SessionID: "frame-proof", DynamicRange: "sdr"}, func(candidate Job) (string, error) { job = candidate; return "owned", nil })
	if err != nil {
		t.Fatal(err)
	}
	if status.OutputDynamicRange != "sdr" {
		t.Fatalf("output range=%s", status.OutputDynamicRange)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := service.Run(ctx, job); err != nil {
		t.Fatal(err)
	}
	artifact, _, err := service.Asset(job.ID, 1, "index.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	frame := func(input, start string) []byte {
		args := []string{"-hide_banner", "-loglevel", "error"}
		if start != "" {
			args = append(args, "-ss", start)
		}
		args = append(args, "-i", input, "-frames:v", "1", "-vf", "scale=160:90", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1")
		value, err := exec.CommandContext(ctx, ffmpeg, args...).Output()
		if err != nil {
			t.Fatal(err)
		}
		if len(value) != 160*90*3 {
			t.Fatalf("unexpected decoded frame length %d", len(value))
		}
		return value
	}
	actual := frame(artifact, "")
	expected := frame(source, "121")
	previous := frame(source, "120")
	distance := func(a, b []byte) float64 {
		sum := 0.0
		for i := range a {
			sum += math.Abs(float64(a[i]) - float64(b[i]))
		}
		return sum / float64(len(a))
	}
	aligned, wrong := distance(actual, expected), distance(actual, previous)
	t.Logf("decoded window error: requested frame %.3f; preceding keyframe %.3f", aligned, wrong)
	if aligned > 6 || aligned >= wrong*.65 {
		t.Fatalf("window does not start at requested source frame: requested=%f previous=%f", aligned, wrong)
	}
}
