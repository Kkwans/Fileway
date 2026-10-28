package fbhttp

import (
	"bytes"
	"context"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Uses the same repository-owned ASS/font/PGS fixture as Android's renderer
// tests. This proves source extraction, separately from TV rendering and HDR.
func TestSourceAssetsExtractOriginalASSFontAndPGS(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("FFmpeg unavailable for source extraction integration")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("FFprobe unavailable for source extraction integration")
	}
	source, err := filepath.Abs(filepath.Join("..", "..", "..", "clients", "android", "app", "src", "androidTest", "assets", "media", "subtitle-fixture.mkv"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	probe, err := defaultMediaProbe(ctx, source, false)
	if err != nil {
		t.Fatal(err)
	}
	var ass, pgs = -1, -1
	for _, track := range probe.SubtitleTracks {
		if track.Codec == "ass" {
			ass = track.Index
		}
		if track.Codec == "hdmv_pgs_subtitle" && pgs < 0 {
			pgs = track.Index
		}
	}
	if ass < 0 || pgs < 0 || len(probe.Attachments) == 0 {
		t.Fatalf("owned fixture lost ASS/PGS/font: %+v", probe)
	}
	var out bytes.Buffer
	if err := runSourceAsset(ctx, source, ass, "ass", &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "[V4+ Styles]") || !strings.Contains(out.String(), "NfbFixtureBars") {
		t.Fatal("authored styles/font names were lost")
	}
	out.Reset()
	fontIndex := -1
	for _, stream := range probe.Attachments {
		if stream.Name != "" {
			n, err := strconv.Atoi(strings.TrimPrefix(stream.ID, "f"))
			if err == nil {
				fontIndex = n
				break
			}
		}
	}
	if fontIndex < 0 {
		t.Fatal("no source font")
	}
	if err := runSourceAsset(ctx, source, fontIndex, "font", &out); err != nil {
		t.Fatal(err)
	}
	if out.Len() < 64 || (!bytes.Equal(out.Bytes()[:4], []byte{0, 1, 0, 0}) && string(out.Bytes()[:4]) != "OTTO") {
		t.Fatal("font attachment did not retain its OpenType data")
	}
	out.Reset()
	if err := runSourceAsset(ctx, source, pgs, "sup", &out); err != nil {
		t.Fatal(err)
	}
	if out.Len() < 16 || string(out.Bytes()[:2]) != "PG" {
		t.Fatal("bitmap subtitles were not extracted as SUP")
	}
}
