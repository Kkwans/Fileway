//go:build !linux && !windows

package files

import (
	"context"
	"os"

	"github.com/spf13/afero"
)

type scopedDirectoryState struct{}

func duplicateScopedFile(*os.File) (*os.File, error) { return nil, ErrScopedUnavailable }

func openScopedRootNative(context.Context, afero.Fs, string, string) (*os.File, error) {
	return nil, ErrScopedUnavailable
}
func openScopedChildNative(context.Context, *os.File, string, bool) (*os.File, error) {
	return nil, ErrScopedUnavailable
}
func scopedCapabilities(*os.File) SourceCapabilities { return SourceCapabilities{} }
func scopedFileState(context.Context, *os.File, ScopedFreshness) (ScopedState, error) {
	return ScopedState{}, ErrScopedUnavailable
}
func readScopedDirectory(context.Context, *ScopedDirectory, int) ([]ScopedEntry, bool, error) {
	return nil, false, ErrScopedUnavailable
}
