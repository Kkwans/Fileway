package fbhttp

import (
	"context"
	"io"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestSourceAssetHotCacheBypassesColdExtraction(t *testing.T) {
	service := newMediaAssetService(t.TempDir())
	key := strings.Repeat("a", 64)
	file, err := service.prepare(context.Background(), key, 32, func(_ context.Context, w io.Writer) error {
		_, err := io.WriteString(w, "cached subtitle")
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	_ = file.Close()
	started, release, finished := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	go func() {
		file, err := service.prepare(context.Background(), strings.Repeat("b", 64), 32, func(_ context.Context, w io.Writer) error {
			close(started)
			<-release
			_, err := io.WriteString(w, "cold subtitle")
			return err
		})
		if file != nil {
			_ = file.Close()
		}
		finished <- err
	}()
	<-started
	defer func() {
		close(release)
		if err := <-finished; err != nil {
			t.Error(err)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	file, err = service.prepare(ctx, key, 32, func(context.Context, io.Writer) error {
		t.Error("hot subtitle was extracted again")
		return nil
	})
	if err != nil {
		t.Fatal("cached subtitle waited behind cold extraction:", err)
	}
	_ = file.Close()
}

func TestSourceFontDoesNotWaitForWholeSubtitleScan(t *testing.T) {
	service := newMediaAssetService(t.TempDir())
	started, release, finished := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	go func() {
		file, err := service.prepare(context.Background(), strings.Repeat("c", 64), 32, func(_ context.Context, w io.Writer) error {
			close(started)
			<-release
			_, err := io.WriteString(w, "subtitle")
			return err
		})
		if file != nil {
			_ = file.Close()
		}
		finished <- err
	}()
	<-started
	defer func() {
		close(release)
		if err := <-finished; err != nil {
			t.Error(err)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	file, err := service.prepareFont(ctx, strings.Repeat("d", 64), 32, func(_ context.Context, w io.Writer) error {
		_, err := io.WriteString(w, "font")
		return err
	})
	if err != nil {
		t.Fatal("font waited behind subtitle scan:", err)
	}
	_ = file.Close()
}

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
			defer func() { _ = file.Close() }()
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
		if err := f.Close(); err != nil {
			t.Fatal(err)
		}
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
