//go:build !linux && !windows

package files

import (
	"os"

	"github.com/spf13/afero"
)

func SubtitleScopeAvailable() bool { return false }

func openSubtitleResource(afero.Fs, string, string, bool, bool) (*SubtitleResource, error) {
	return nil, ErrSubtitleScopeUnavailable
}

func subtitleResourceStamp(*os.File, os.FileInfo) (string, error) {
	return "", ErrSubtitleScopeUnavailable
}
