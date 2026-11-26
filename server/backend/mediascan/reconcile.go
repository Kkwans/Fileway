package mediascan

import (
	"context"
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"slices"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

var ErrCandidateUncertain = errors.New("media scan cannot establish candidate absence")

// missingCandidate verifies that the exact owner-workspace path is absent. A
// failed regular-file open alone is not enough: the name could now designate a
// directory, a device, or an object rejected by the safe opener. Those cases
// must not be turned into a missing result. Every lookup rechecks live scope.
func (w *walker) missingCandidate(ctx context.Context, absolute []byte) (bool, error) {
	relative, err := relativeLocalLocator(w.binding.RootLocator, absolute)
	if err != nil {
		return false, err
	}
	resource, err := w.source.Open(ctx, relative, false)
	if err == nil {
		info, _, stateErr := resource.State()
		if stateErr != nil {
			_ = resource.Close()
			return false, stateErr
		}
		if info == nil || files.IsPrivateMediaFile(info) {
			_ = resource.Close()
			return false, fs.ErrPermission
		}
		if !info.Mode().IsRegular() {
			_ = resource.Close()
			return false, ErrCandidateUncertain
		}
		name := path.Base(relative)
		ignored := !videoName(name)
		if !ignored {
			parsed := mediametadata.ParseName(name)
			ignored = parsed.Title == "" && slices.Contains(parsed.Ambiguities, "promotional_filename")
		}
		if !ignored && strings.EqualFold(path.Ext(name), ".ts") && resource.File != nil {
			transport, transportErr := isMPEGTransportStream(resource.File, info.Size())
			if transportErr != nil {
				_ = resource.Close()
				return false, transportErr
			}
			ignored = !transport
		}
		closeErr := resource.Close()
		if closeErr != nil {
			return false, closeErr
		}
		if ignored {
			return true, nil
		}
		return false, ctx.Err()
	}
	if !errors.Is(err, fs.ErrNotExist) {
		return false, err
	}
	parts := strings.Split(relative, "/")
	if len(parts)-1 > w.options.MaxDepth {
		return false, ErrDepthLimit
	}
	parent := ""
	for index, part := range parts {
		if invalidEntryName(part) {
			return false, fs.ErrInvalid
		}
		entry, found, err := w.findEntry(ctx, parent, part)
		if err != nil {
			return false, err
		}
		if !found {
			return true, nil
		}
		if index == len(parts)-1 {
			return false, nil
		}
		if entry.Type()&os.ModeSymlink != 0 || !entry.IsDir() {
			return false, ErrCandidateUncertain
		}
		parent = appendLocator(parent, part)
	}
	return false, ErrCandidateUncertain
}

// findEntry streams a single directory and checks its change watch and identity
// before accepting either an absent or a present entry. The directory is never
// loaded in full and raw non-UTF-8 names retain byte-for-byte comparison.
func (w *walker) findEntry(ctx context.Context, parent, name string) (entry fs.DirEntry, found bool, resultErr error) {
	directory, err := w.source.Open(ctx, parent, true)
	if err != nil {
		return nil, false, err
	}
	defer func() {
		if err := directory.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	info, stamp, err := directory.State()
	if err != nil {
		return nil, false, err
	}
	if info != nil && files.IsPrivateMediaFile(info) {
		return nil, false, fs.ErrPermission
	}
	for {
		if err := w.checkDirectory(ctx, parent, directory, info, stamp); err != nil {
			return nil, false, err
		}
		entries, readErr := directory.ReadDir(w.options.ReadBatch)
		if len(entries) == 0 && readErr == nil {
			return nil, false, io.ErrNoProgress
		}
		for _, candidate := range entries {
			if candidate.Name() == name {
				if err := w.checkDirectory(ctx, parent, directory, info, stamp); err != nil {
					return nil, false, err
				}
				return candidate, true, nil
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return nil, false, readErr
			}
			if err := w.checkDirectory(ctx, parent, directory, info, stamp); err != nil {
				return nil, false, err
			}
			return nil, false, nil
		}
	}
}
