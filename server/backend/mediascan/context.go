package mediascan

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"io"
	"io/fs"
	"os"
	"sort"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

// ContextSink extends the existing sink without changing standalone scanner
// consumers. A persistent implementation commits context and checkpoint under
// one lease; a returned ref is an optimistic revision fence, not a read grant.
type ContextSink interface {
	ObserveDirectory(context.Context, medialibrary.DirectoryContextObservation) (medialibrary.DirectoryContextRef, error)
}

const maxContextSidecars = 32

// readDirectoryContext performs a bounded first pass before any child or video
// is observed. This makes enumeration order irrelevant, including when a
// filesystem permits multiple case variants of the same special NFO name.
func (w *walker) readDirectoryContext(ctx context.Context, locator string, parent medialibrary.DirectoryContextRef) (observation medialibrary.DirectoryContextObservation, resultErr error) {
	observation = medialibrary.DirectoryContextObservation{
		Locator: absoluteLocator(w.binding.RootLocator, locator),
		Parent:  parent,
		TVShow:  medialibrary.DirectorySidecar{Status: "missing"},
		Season:  medialibrary.DirectorySidecar{Status: "missing"},
	}
	dir, err := w.source.Open(ctx, locator, true)
	if err != nil {
		return observation, err
	}
	defer func() {
		if err := dir.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	info, stamp, err := dir.State()
	if err != nil {
		return observation, err
	}
	var tvNames, seasonNames []string
	var tvOverflow, seasonOverflow bool
	for {
		if err := w.checkDirectory(ctx, locator, dir, info, stamp); err != nil {
			return observation, err
		}
		entries, readErr := dir.ReadDir(w.options.ReadBatch)
		if len(entries) == 0 && readErr == nil {
			return observation, io.ErrNoProgress
		}
		for _, entry := range entries {
			name := entry.Name()
			if invalidEntryName(name) {
				return observation, fs.ErrInvalid
			}
			switch {
			case strings.EqualFold(name, "tvshow.nfo"):
				if len(tvNames) < maxContextSidecars {
					tvNames = append(tvNames, name)
				} else {
					tvOverflow = true
				}
			case strings.EqualFold(name, "season.nfo"):
				if len(seasonNames) < maxContextSidecars {
					seasonNames = append(seasonNames, name)
				} else {
					seasonOverflow = true
				}
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return observation, readErr
			}
			break
		}
	}
	if err := w.checkDirectory(ctx, locator, dir, info, stamp); err != nil {
		return observation, err
	}
	observation.TVShow = w.readDirectorySidecars(ctx, locator, tvNames, tvOverflow, mediametadata.TVShow)
	observation.Season = w.readDirectorySidecars(ctx, locator, seasonNames, seasonOverflow, mediametadata.Season)
	if err := ctx.Err(); err != nil {
		return observation, err
	}
	if err := w.checkDirectory(ctx, locator, dir, info, stamp); err != nil {
		return observation, err
	}
	return observation, nil
}

func (w *walker) readDirectorySidecars(ctx context.Context, directory string, names []string, overflow bool, expected mediametadata.Kind) medialibrary.DirectorySidecar {
	if overflow {
		return medialibrary.DirectorySidecar{Status: "conflict"}
	}
	if len(names) == 0 {
		return medialibrary.DirectorySidecar{Status: "missing"}
	}
	sort.Strings(names)
	var chosen *medialibrary.MetadataObservation
	for _, name := range names {
		current, status := w.readContextNFO(ctx, appendLocator(directory, name))
		if status != "parsed" {
			return medialibrary.DirectorySidecar{Status: status}
		}
		if len(current.Document.Items) != 1 || current.Document.Items[0].Kind != expected {
			return medialibrary.DirectorySidecar{Status: "invalid"}
		}
		if chosen != nil && !bytes.Equal(chosen.Digest, current.Digest) {
			return medialibrary.DirectorySidecar{Status: "conflict"}
		}
		if chosen == nil {
			chosen = current
		}
	}
	return medialibrary.DirectorySidecar{Status: "parsed", NFO: chosen}
}

// readContextNFO uses the same safe opener, limits, digest and before/after
// identity checks as a file sidecar. No caller may persist a partial read.
func (w *walker) readContextNFO(ctx context.Context, locator string) (*medialibrary.MetadataObservation, string) {
	resource, err := w.source.Open(ctx, locator, false)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, "changed"
		}
		return nil, "unavailable"
	}
	info, stamp, err := resource.State()
	if err != nil || info == nil || resource.File == nil || !info.Mode().IsRegular() || files.IsPrivateMediaFile(info) {
		_ = resource.Close()
		return nil, "unavailable"
	}
	digest := sha256.New()
	document, parseErr := mediametadata.ParseNFO(contextReader{ctx: ctx, reader: io.TeeReader(resource.File, digest)}, mediametadata.Limits{})
	held, heldStamp, stateErr := resource.State()
	current, openErr := w.source.Open(ctx, locator, false)
	if stateErr != nil || openErr != nil {
		_ = resource.Close()
		if current != nil {
			_ = current.Close()
		}
		if errors.Is(openErr, fs.ErrNotExist) {
			return nil, "changed"
		}
		return nil, "unavailable"
	}
	now, nowStamp, stateErr := current.State()
	closeErr := errors.Join(current.Close(), resource.Close())
	if stateErr != nil || closeErr != nil || now == nil || held == nil || files.IsPrivateMediaFile(now) || files.IsPrivateMediaFile(held) {
		return nil, "unavailable"
	}
	if !os.SameFile(info, now) || !os.SameFile(info, held) || stamp != heldStamp || stamp != nowStamp {
		return nil, "changed"
	}
	if parseErr != nil {
		return nil, "invalid"
	}
	return &medialibrary.MetadataObservation{SourceKey: absoluteLocator(w.binding.RootLocator, locator), Digest: digest.Sum(nil), ParserVersion: 1, Document: *document}, "parsed"
}
