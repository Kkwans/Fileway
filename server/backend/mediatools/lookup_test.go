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
