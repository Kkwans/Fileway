package fbhttp

import (
	"context"
	"io"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestSourceAssetCacheDeduplicatesAndRejectsPartialOutputs(t *testing.T) {
	service := newMediaAssetService(t.TempDir())
	key := strings.Repeat("a", 64)
	var calls atomic.Int32
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			file, err := service.prepare(context.Background(), key, 32, func(_ context.Context, w io.Writer) error {
				calls.Add(1)
				_, err := io.WriteString(w, "complete subtitle")
				return err
			})
			if err != nil {
				t.Error(err)
				return
			}
			defer file.Close()
			b, _ := io.ReadAll(file)
			if string(b) != "complete subtitle" {
				t.Error("partial cache hit")
			}
		}()
	}
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatalf("duplicate extractions: %d", calls.Load())
	}
	bad := strings.Repeat("b", 64)
	if f, err := service.prepare(context.Background(), bad, 3, func(_ context.Context, w io.Writer) error { _, err := io.WriteString(w, "oversize"); return err }); err == nil {
		f.Close()
		t.Fatal("oversized asset accepted")
	}
	entries, _ := os.ReadDir(service.directory)
	for _, entry := range entries {
		if entry.Name() != key {
			t.Fatal("partial asset retained", entry.Name())
		}
	}
}

func TestSourceSubtitleFormatsPreserveAuthoredASSAndPGS(t *testing.T) {
	for _, codec := range []string{"ass", "ssa"} {
		format, ext, _, limit := sourceSubtitleFormat(codec)
		if format != "ass" || ext != "ass" || limit != sourceTextLimit {
			t.Fatal(codec)
		}
	}
	format, ext, _, limit := sourceSubtitleFormat("hdmv_pgs_subtitle")
	if format != "sup" || ext != "sup" || limit != sourcePGSLimit {
		t.Fatal("PGS was converted to text")
	}
	if f, _, _, _ := sourceSubtitleFormat("dvd_subtitle"); f != "" {
		t.Fatal("unsupported subtitle silently converted")
	}
}

func TestMediaDynamicRangeSeparatesSDRPQHLGAndUnknown(t *testing.T) {
	for transfer, want := range map[string]string{"bt709": "sdr", "smpte2084": "pq", "arib-std-b67": "hlg", "": "unknown"} {
		if got := mediaDynamicRange(mediaProbeResult{VideoTransfer: transfer}); got != want {
			t.Fatalf("%s: %s", transfer, got)
		}
	}
	if got := mediaDynamicRange(mediaProbeResult{DolbyVision: true, VideoTransfer: "smpte2084"}); got != "dolby-vision" {
		t.Fatal(got)
	}
}
