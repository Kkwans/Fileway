package hls

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
)

func TestReadProgressHandlesFFmpegBlocksAndInvalidValues(t *testing.T) {
	var reports []Progress
	input := "out_time_us=2500000\nout_time_ms=2500000\nfps=24.3\nspeed=1.25x\nprogress=continue\nout_time_us=-1\nspeed=N/A\nprogress=continue\nout_time_us=15000000\nspeed=NaN\nfps=Inf\nprogress=end\n"
	err := readProgress(strings.NewReader(input), 10, func(p Progress) error { reports = append(reports, p); return nil })
	if err != nil || len(reports) != 3 {
		t.Fatalf("reports=%+v err=%v", reports, err)
	}
	if reports[0].ProcessedSeconds != 2.5 || reports[0].Speed != 1.25 || reports[0].FPS != 24.3 {
		t.Fatalf("first block=%+v", reports[0])
	}
	if reports[1].ProcessedSeconds != 2.5 || reports[1].Speed != 0 {
		t.Fatalf("invalid block=%+v", reports[1])
	}
	if reports[2].ProcessedSeconds != 10 || reports[2].Phase != "finalizing" || reports[2].Speed != 0 || reports[2].FPS != 0 {
		t.Fatalf("final block=%+v", reports[2])
	}
}

func TestReadProgressPropagatesReporterFailure(t *testing.T) {
	want := errors.New("storage unavailable")
	err := readProgress(strings.NewReader("progress=continue\n"), 0, func(Progress) error { return want })
	if !errors.Is(err, want) {
		t.Fatal(err)
	}
}

func TestRunReportsQueueAndPreparation(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	job := reserveJobs(t, service, 1)[0]
	var phases []string
	if err := service.RunWithProgress(context.Background(), job, func(p Progress) error { phases = append(phases, p.Phase); return nil }); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(phases, []string{"queued", "preparing"}) {
		t.Fatal(phases)
	}
}

func TestPlaybackWindowUsesFastInputSeekAndDistinctCache(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	input := Input{UserID: 1, Path: "/film.mkv", SourcePath: "/source.mkv", Identity: "source", DurationSeconds: 600, StartSeconds: 300, WindowSeconds: 120, SessionID: "one"}
	var job Job
	first, _, err := service.Reserve(input, func(candidate Job) (string, error) { job = candidate; return "one", nil })
	if err != nil {
		t.Fatal(err)
	}
	if job.DurationSeconds != 120 || first.SourceDurationSeconds != 600 || first.StartSeconds != 300 {
		t.Fatalf("%+v %+v", job, first)
	}
	input.SessionID = "two"
	second, _, err := service.Reserve(input, func(Job) (string, error) { return "two", nil })
	if err != nil || first.ID == second.ID {
		t.Fatalf("cache not isolated: %+v %v", second, err)
	}
	joined := strings.Join(playbackWindowArgs([]string{"ffmpeg", "-i", "source", "-f", "webm", "output"}, job), " ")
	if !strings.Contains(joined, "-ss 300.000 -i source") || !strings.HasSuffix(joined, "-t 120.000 output") {
		t.Fatal(joined)
	}
}

func TestSoftwareThreadsBoundDecodeFiltersAndEncodeWithoutChangingCopy(t *testing.T) {
	args := []string{"ffmpeg", "-i", "source", "-c:v", "libx264", "-threads", "1", "-filter_threads", "1", "-filter_complex_threads", "1", "output"}
	got := strings.Join(softwareThreadArgs(args, 4), " ")
	if !strings.Contains(got, "-threads 4 -i source") || !strings.Contains(got, "-filter_threads 4") || !strings.Contains(got, "-filter_complex_threads 4") || !strings.Contains(got, "libx264 -threads 4") {
		t.Fatal(got)
	}
	copyArgs := []string{"ffmpeg", "-i", "source", "-c:v", "copy", "output"}
	if !reflect.DeepEqual(softwareThreadArgs(copyArgs, 4), copyArgs) {
		t.Fatal("stream copy must stay unchanged")
	}
}

func TestNonzeroPlaybackWindowCannotCopyPrecedingKeyframe(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	input := Input{UserID: 1, Path: "/owned.mp4", SourcePath: "/source.mp4", Identity: "owned", VideoCodec: "h264", VideoWidth: 1920, VideoHeight: 1080, DurationSeconds: 600, StartSeconds: 121, WindowSeconds: 120, SessionID: "aligned"}
	for _, profile := range []string{DefaultCopyProfile, DefaultAudioHLSProfile, DefaultMP4CopyProfile, DefaultMP4AudioProfile, DefaultWebMCopyProfile} {
		var job Job
		_, _, err := service.reserve(input, profile, func(candidate Job) (string, error) { job = candidate; return "aligned", nil })
		if err != nil {
			t.Fatal(err)
		}
		if IsCopyProfile(job.Profile) || IsMP4CopyProfile(job.Profile) || IsWebMCopyProfile(job.Profile) {
			t.Fatalf("nonzero window retained copy profile %s", job.Profile)
		}
		if job.StartSeconds != 121 {
			t.Fatalf("source offset changed: %+v", job)
		}
		// Distinct sessions prevent reuse from hiding the requested profile.
		input.SessionID += "x"
	}
}
