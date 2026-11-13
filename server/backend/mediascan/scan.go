// Package mediascan discovers videos from an authorized local source. It does
// not grant access, infer work identities, write the source, or persist a second
// catalog. Consumers must commit observations in the media catalog transaction.
package mediascan

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"runtime"
	"strings"
	"unicode"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

var (
	ErrDirectoryChanged = errors.New("media scan directory changed during enumeration")
	ErrFileChanged      = errors.New("media scan file changed during observation")
	ErrDepthLimit       = errors.New("media scan directory depth limit reached")
)

// IdentityKind identifies the evidence behind ObjectKey. A path-only key can
// discover a file but cannot prove continuity across scans, replacement at the
// same path, moves, or permission changes. Consumers must not inherit watch
// state based solely on an unverified locator or a size/mtime source version.
type IdentityKind string

const (
	IdentityNative         IdentityKind = "native"
	IdentityPathUnverified IdentityKind = "path_unverified"
)

type NFOStatus string

const (
	NFOMissing     NFOStatus = "missing"
	NFOParsed      NFOStatus = "parsed"
	NFOInvalid     NFOStatus = "invalid"
	NFOUnavailable NFOStatus = "unavailable"
	NFOChanged     NFOStatus = "changed"
)

// Observation is evidence about a single safely opened file. Locator and the
// binding root are original workspace path bytes, not normalized display text.
// The consumer owns these values after Observe returns. Version is the legacy
// v1:size:mtimeNs marker; it is neither a content hash nor object identity.
type Observation struct {
	OwnerUserID      uint
	Binding          medialibrary.SourceBindingRef
	ObjectKey        []byte
	Identity         IdentityKind
	Locator          []byte
	ExpectedRevision int64
	SourceVersion    string
	SizeBytes        int64
	InitialTitle     string
	NFO              *medialibrary.MetadataObservation
	NFOStatus        NFOStatus
}

// Sink must validate the binding and generation at commit time. Observe must
// atomically observe the source file and create/attach an unmatched catalog
// entry if needed, update the persistent job checkpoint and publish its outbox.
// A binding ref is a consistency token, never a substitute for live authority.
type Sink interface {
	// LookupRevision must return zero only when this object does not exist.
	// Capture it before the final safe reopen; Observe must compare-and-swap it
	// so an older cross-binding scan cannot overwrite a newer observation.
	LookupRevision(context.Context, []byte) (int64, error)
	Observe(context.Context, Observation) error
}

type Options struct {
	// Zero uses the default. Values are capped to keep memory and open handles
	// bounded even for a directory tree controlled by an untrusted writer.
	ReadBatch int
	MaxDepth  int
}

type Result struct {
	Directories int
	Discovered  int
	Observed    int
	NFOFailed   int
}

// ScanLocal creates the adapter from the exact stored root, avoiding a caller
// accidentally pairing a binding with a different adapter root. resolve must
// load the owner's current account, scopes and server policy on every call.
// A nil error means this traversal reached the end without a detected error;
// it is not proof of a coherent whole-tree snapshot or permission to mark old
// objects missing. Missing candidates require separate live revalidation.
func ScanLocal(ctx context.Context, actor uint, binding *medialibrary.LibrarySource, resolve mediasource.ResolveLocalAuthority, sink Sink, options Options) (Result, error) {
	var result Result
	if binding == nil || actor == 0 || actor != binding.Source.OwnerUserID || binding.ID == "" || binding.ProofEpoch < 1 || binding.LibraryID == "" || binding.Source.ID == "" || binding.Revision < 1 || binding.Source.AuthorizationRevision < 1 || sink == nil {
		return result, fs.ErrInvalid
	}
	options, err := normalizeOptions(options)
	if err != nil {
		return result, err
	}
	source, err := mediasource.NewLocal(string(binding.RootLocator), resolve)
	if err != nil {
		return result, err
	}
	state := walker{source: source, binding: binding.Ref(), actor: actor, sink: sink, options: options, result: &result}
	err = state.directory(ctx, "", 0)
	return result, err
}

func normalizeOptions(options Options) (Options, error) {
	if options.ReadBatch == 0 {
		options.ReadBatch = 64
	}
	if options.MaxDepth == 0 {
		options.MaxDepth = 64
	}
	if options.ReadBatch < 1 || options.ReadBatch > 256 || options.MaxDepth < 1 || options.MaxDepth > 128 {
		return options, fs.ErrInvalid
	}
	return options, nil
}

type walker struct {
	source  *mediasource.Local
	binding medialibrary.SourceBindingRef
	actor   uint
	sink    Sink
	options Options
	result  *Result
}

func (w *walker) directory(ctx context.Context, locator string, depth int) (resultErr error) {
	if err := ctx.Err(); err != nil {
		return err
	}
	if depth > w.options.MaxDepth {
		return ErrDepthLimit
	}
	directory, err := w.source.Open(ctx, locator, true)
	if err != nil {
		return err
	}
	defer func() {
		if err := directory.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	initial, stamp, err := directory.State()
	if err != nil {
		return err
	}
	if initial != nil && files.IsPrivateMediaFile(initial) {
		return fs.ErrPermission
	}
	w.result.Directories++
	for {
		if err := w.checkDirectory(ctx, locator, directory, initial, stamp); err != nil {
			return err
		}
		entries, readErr := directory.ReadDir(w.options.ReadBatch)
		if len(entries) == 0 && readErr == nil {
			return io.ErrNoProgress
		}
		for _, entry := range entries {
			if err := ctx.Err(); err != nil {
				return err
			}
			name := entry.Name()
			if invalidEntryName(name) {
				return fs.ErrInvalid
			}
			if entry.Type()&os.ModeSymlink != 0 {
				continue
			}
			if entry.IsDir() && strings.EqualFold(name, files.MediaLibraryDirectory) {
				continue
			}
			child := appendLocator(locator, name)
			if entry.IsDir() {
				if err := w.directory(ctx, child, depth+1); err != nil {
					return err
				}
			} else if entry.Type().IsRegular() && videoName(name) {
				w.result.Discovered++
				if err := w.video(ctx, child, name); err != nil {
					return err
				}
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return readErr
			}
			return w.checkDirectory(ctx, locator, directory, initial, stamp)
		}
	}
}

// A held handle isn't a continuing authorization grant: re-resolve the owner
// before each directory batch and ensure the path still names that same object.
func (w *walker) checkDirectory(ctx context.Context, locator string, held *files.SubtitleResource, expected os.FileInfo, expectedStamp string) (resultErr error) {
	if err := ctx.Err(); err != nil {
		return err
	}
	if held.Changed() {
		return ErrDirectoryChanged
	}
	current, err := w.source.Open(ctx, locator, true)
	if err != nil {
		return err
	}
	defer func() {
		if err := current.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	info, stamp, err := current.State()
	if err != nil {
		return err
	}
	if info != nil && files.IsPrivateMediaFile(info) {
		return fs.ErrPermission
	}
	if stamp != expectedStamp || (expected == nil) != (info == nil) || expected != nil && !os.SameFile(expected, info) || held.Changed() {
		return ErrDirectoryChanged
	}
	return ctx.Err()
}

func (w *walker) video(ctx context.Context, locator, name string) (resultErr error) {
	file, err := w.source.Open(ctx, locator, false)
	if err != nil {
		return err
	}
	defer func() {
		if err := file.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	initial, stamp, err := file.State()
	if err != nil {
		return err
	}
	if initial == nil || file.File == nil || !initial.Mode().IsRegular() {
		return fs.ErrInvalid
	}
	if files.IsPrivateMediaFile(initial) {
		return fs.ErrPermission
	}
	abs := absoluteLocator(w.binding.RootLocator, locator)
	key, identity := objectKey(file.File, w.binding.BindingID, w.binding.ProofEpoch, abs)
	expectedRevision, err := w.sink.LookupRevision(ctx, key)
	if err != nil {
		return err
	}
	if expectedRevision < 0 {
		return fs.ErrInvalid
	}
	nfo, nfoStatus := w.readNFO(ctx, locator)
	if err := ctx.Err(); err != nil {
		return err
	}
	// Re-resolve live source authority after any sidecar read, and refuse to
	// publish an observation for a changed or replaced file.
	current, err := w.source.Open(ctx, locator, false)
	if err != nil {
		return err
	}
	defer func() {
		if err := current.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	now, nowStamp, err := current.State()
	if err != nil {
		return err
	}
	heldNow, heldStamp, err := file.State()
	if err != nil {
		return err
	}
	if now == nil || heldNow == nil || !os.SameFile(initial, now) || !os.SameFile(initial, heldNow) || stamp != nowStamp || stamp != heldStamp {
		return ErrFileChanged
	}
	if files.IsPrivateMediaFile(now) || files.IsPrivateMediaFile(heldNow) {
		return fs.ErrPermission
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	observation := Observation{
		OwnerUserID: w.actor, Binding: w.binding, ObjectKey: key, Identity: identity,
		Locator: abs, ExpectedRevision: expectedRevision, SourceVersion: fmt.Sprintf("v1:%d:%d", initial.Size(), initial.ModTime().UnixNano()),
		SizeBytes: initial.Size(), InitialTitle: displayTitle(name), NFO: nfo, NFOStatus: nfoStatus,
	}
	observation.Binding.RootLocator = append([]byte(nil), w.binding.RootLocator...)
	if err := w.sink.Observe(ctx, observation); err != nil {
		return err
	}
	w.result.Observed++
	if nfoStatus == NFOInvalid || nfoStatus == NFOUnavailable || nfoStatus == NFOChanged {
		w.result.NFOFailed++
	}
	return nil
}

func (w *walker) readNFO(ctx context.Context, video string) (*medialibrary.MetadataObservation, NFOStatus) {
	ext := path.Ext(video)
	base := strings.TrimSuffix(video, ext)
	directory := path.Dir(video)
	if directory == "." {
		directory = ""
	}
	movieBase := appendLocator(directory, "movie")
	type candidate struct {
		locator        string
		directoryLevel bool
	}
	candidates := []candidate{{base + ".nfo", strings.EqualFold(path.Base(base), "movie")}, {base + ".NFO", strings.EqualFold(path.Base(base), "movie")}}
	if base != movieBase {
		candidates = append(candidates, candidate{movieBase + ".nfo", true}, candidate{movieBase + ".NFO", true})
	}
	for _, candidate := range candidates {
		resource, err := w.source.Open(ctx, candidate.locator, false)
		if errors.Is(err, fs.ErrNotExist) {
			continue
		}
		if err != nil {
			return nil, NFOUnavailable
		}
		info, stamp, err := resource.State()
		if err != nil || info == nil || files.IsPrivateMediaFile(info) {
			_ = resource.Close()
			return nil, NFOUnavailable
		}
		digest := sha256.New()
		document, parseErr := mediametadata.ParseNFO(contextReader{ctx: ctx, reader: io.TeeReader(resource.File, digest)}, mediametadata.Limits{})
		heldAfter, after, stateErr := resource.State()
		if stateErr != nil {
			_ = resource.Close()
			return nil, NFOUnavailable
		}
		current, openErr := w.source.Open(ctx, candidate.locator, false)
		if openErr != nil {
			_ = resource.Close()
			if errors.Is(openErr, fs.ErrNotExist) {
				return nil, NFOChanged
			}
			return nil, NFOUnavailable
		}
		currentInfo, currentStamp, currentErr := current.State()
		closeErr := errors.Join(current.Close(), resource.Close())
		if currentErr != nil || closeErr != nil || currentInfo == nil || heldAfter == nil || files.IsPrivateMediaFile(currentInfo) {
			return nil, NFOUnavailable
		}
		if !os.SameFile(info, currentInfo) || !os.SameFile(info, heldAfter) || after != stamp || currentStamp != stamp {
			return nil, NFOChanged
		}
		if parseErr != nil {
			return nil, NFOInvalid
		}
		ambiguous := false
		if candidate.directoryLevel {
			ambiguous, err = w.ambiguousMovieDirectory(ctx, directory, path.Base(video))
			if err != nil {
				return nil, NFOChanged
			}
		}
		confirmed, err := w.source.Open(ctx, candidate.locator, false)
		if err != nil {
			return nil, NFOChanged
		}
		finalInfo, finalStamp, finalErr := confirmed.State()
		finalClose := confirmed.Close()
		if finalErr != nil || finalClose != nil || finalInfo == nil || !os.SameFile(info, finalInfo) || finalStamp != stamp || files.IsPrivateMediaFile(finalInfo) {
			return nil, NFOChanged
		}
		return &medialibrary.MetadataObservation{SourceKey: absoluteLocator(w.binding.RootLocator, candidate.locator), Digest: digest.Sum(nil), ParserVersion: 1, Document: *document, AssociationAmbiguous: ambiguous}, NFOParsed
	}
	return nil, NFOMissing
}

// A shared movie.nfo describes a directory, not evidence that all files in it
// are one work. Count only to two with bounded directory pages; the Store keeps
// an ambiguous association as a candidate without projecting its fields.
func (w *walker) ambiguousMovieDirectory(ctx context.Context, locator, expectedName string) (ambiguous bool, resultErr error) {
	directory, err := w.source.Open(ctx, locator, true)
	if err != nil {
		return false, err
	}
	defer func() {
		if err := directory.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	info, stamp, err := directory.State()
	if err != nil {
		return false, err
	}
	count := 0
	found := false
	for {
		if err := w.checkDirectory(ctx, locator, directory, info, stamp); err != nil {
			return false, err
		}
		entries, readErr := directory.ReadDir(w.options.ReadBatch)
		if len(entries) == 0 && readErr == nil {
			return false, io.ErrNoProgress
		}
		for _, entry := range entries {
			if entry.Type()&os.ModeSymlink != 0 || entry.IsDir() || !entry.Type().IsRegular() || !videoName(entry.Name()) {
				continue
			}
			count++
			found = found || entry.Name() == expectedName
			if count >= 2 {
				if err := w.checkDirectory(ctx, locator, directory, info, stamp); err != nil {
					return false, err
				}
				return true, nil
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return false, readErr
			}
			if err := w.checkDirectory(ctx, locator, directory, info, stamp); err != nil {
				return false, err
			}
			if !found {
				return false, ErrDirectoryChanged
			}
			return false, nil
		}
	}
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r contextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.reader.Read(p)
}

func invalidEntryName(name string) bool {
	return name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\x00") || runtime.GOOS == "windows" && strings.Contains(name, "\\")
}

func appendLocator(parent, name string) string {
	if parent == "" {
		return name
	}
	return parent + "/" + name
}

func absoluteLocator(root []byte, relative string) []byte {
	result := append([]byte(nil), root...)
	if len(result) != 1 || result[0] != '/' {
		result = append(result, '/')
	}
	return append(result, relative...)
}

func videoName(name string) bool {
	switch strings.ToLower(path.Ext(name)) {
	case ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm", ".ts", ".m2ts", ".mpg", ".mpeg", ".wmv", ".flv", ".vob":
		return true
	default:
		return false
	}
}

func displayTitle(name string) string {
	name = files.DisplayName(strings.TrimSuffix(name, path.Ext(name)))
	name = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, name)
	name = strings.TrimSpace(name)
	if name == "" {
		return "Untitled video"
	}
	return name
}

// DisplayFilename supplies a presentation-only fallback for an authorized
// video's basename. The caller must never pass a parent path as a filename.
func DisplayFilename(name string) string { return displayTitle(name) }
