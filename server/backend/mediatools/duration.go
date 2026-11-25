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
	"time"
)

const maxProbeDurationMS int64 = 7 * 24 * 60 * 60 * 1000

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

// ProbeDuration reads only container headers from the already authorized file.
// Unix descendants inherit that exact handle; Windows reopens the same path and
// the scanner revalidates file identity before committing the observation.
func ProbeDuration(ctx context.Context, file *os.File) (int64, error) {
	if file == nil {
		return 0, errors.New("媒体文件无效")
	}
	ffprobePath, err := Lookup("ffprobe")
	if err != nil {
		return 0, err
	}
	offset, err := file.Seek(0, io.SeekCurrent)
	if err != nil {
		return 0, err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return 0, err
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
		"-show_entries", "format=duration", "-of", "json", input)
	command.ExtraFiles = extraFiles
	command.Stderr = io.Discard
	output, err := command.Output()
	if err != nil {
		if errors.Is(probeCtx.Err(), context.DeadlineExceeded) {
			return 0, errors.New("媒体时长探测超时")
		}
		return 0, errors.New("媒体时长探测失败")
	}
	if len(output) > 64<<10 {
		return 0, errors.New("媒体时长响应过大")
	}
	var document struct {
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
	}
	if err := json.Unmarshal(output, &document); err != nil {
		return 0, fmt.Errorf("媒体时长解析失败: %w", err)
	}
	return ParseProbeDuration(document.Format.Duration)
}
