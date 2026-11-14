package files

import (
	"context"
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/spf13/afero"
)

var (
	ErrScopedUnavailable      = errors.New("safe scoped handle access is unavailable")
	ErrScopedMountBoundary    = errors.New("scoped traversal reached another mount domain")
	ErrScopedNameUnverified   = errors.New("opened name cannot be independently verified")
	ErrScopedDirectoryChanged = errors.New("directory entry changed during enumeration")
)

// SourceCapabilities describes evidence, not a grant. A mount-scoped object ID
// is a current observation, not a permanent identity or a content hash.
type SourceCapabilities struct {
	SafeHandleRead bool
	Enumeration    string
	Notifications  string
	ObjectIdentity string
	FreshAbsence   string
	ChangeEvidence string
}

type ScopedFreshness string

const (
	ScopedObserved  ScopedFreshness = "observed"
	ScopedForceSync ScopedFreshness = "force_sync"
)

// ScopedMountObservation is ephemeral OS evidence. An ID may be reused and is
// not a persisted domain/epoch. Namespace and boot may be empty if unavailable.
type ScopedMountObservation struct {
	ID        uint64
	IDKind    string // unique_since_boot, namespace_reusable, or unknown
	Namespace string
	BootID    string
}

// ScopedState contains only evidence returned by the held handle. Nil evidence
// means unknown. A force-sync attribute query never proves fresh negative lookup.
type ScopedState struct {
	Info           fs.FileInfo
	ObjectEvidence []byte
	ChangeStamp    []byte
	Mount          ScopedMountObservation
	Size           int64
}

type ScopedAuthority struct {
	Filesystem afero.Fs
	ServerRoot string
	Check      func(string) bool
}

// ScopedRoot holds an actual, selected directory. Its authority is a snapshot:
// callers must re-resolve account, grants and domain evidence on every use.
type ScopedRoot struct {
	file         *os.File
	authority    ScopedAuthority
	locator      string
	capabilities SourceCapabilities
	mu           sync.RWMutex
	closed       bool
	done         chan struct{}
}

// ScopedResource has no write API and does not expose an OS path.
type ScopedResource struct {
	file         *os.File
	capabilities SourceCapabilities
}

// ScopedDirectory is a bounded live iterator, not a point-in-time snapshot.
type ScopedDirectory struct {
	file     *os.File
	root     *ScopedRoot
	relative string
	platform scopedDirectoryState
	batch    chan struct{}
	closed   atomic.Bool
	done     chan struct{}
}

type ScopedEntry struct {
	Name []byte      // original filesystem bytes; valid UTF-8 on Windows
	Type fs.FileMode // enumeration hint; opening must verify the actual object
}

type ScopedAbsenceEvidence struct {
	State     string
	Freshness string
}

func validScopedRootPath(name string) bool {
	return strings.HasPrefix(name, "/") && path.Clean(name) == name && !strings.ContainsRune(name, 0)
}

func validScopedRelative(name string, allowRoot bool) bool {
	if name == "" {
		return allowRoot
	}
	return !strings.HasPrefix(name, "/") && path.Clean(name) == name && name != "." && name != ".." && !strings.HasPrefix(name, "../") && !strings.ContainsRune(name, 0)
}

func (root *ScopedRoot) authorize(relative string, allowRoot bool) error {
	if !validScopedRelative(relative, allowRoot) {
		return fs.ErrInvalid
	}
	if root == nil || root.file == nil || root.authority.Filesystem == nil || root.authority.Check == nil {
		return fs.ErrPermission
	}
	root.mu.RLock()
	closed := root.closed
	root.mu.RUnlock()
	if closed {
		return fs.ErrClosed
	}
	target := root.locator
	if relative != "" {
		target = path.Join(target, relative)
	}
	for current := target; ; current = path.Dir(current) {
		if !root.authority.Check(current) || IsPrivateResourcePath(root.authority.Filesystem, current) {
			return fs.ErrPermission
		}
		if current == root.locator {
			return nil
		}
		if current == "/" {
			return fs.ErrPermission
		}
	}
}

// OpenScopedRoot accepts an explicit account mapping and selected workspace
// locator. The native implementation opens every selected component without
// following symlinks/reparse points; it does not install a binding proof.
func OpenScopedRoot(ctx context.Context, authority ScopedAuthority, locator string) (*ScopedRoot, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if !validScopedRootPath(locator) || authority.Filesystem == nil || authority.Check == nil {
		return nil, fs.ErrInvalid
	}
	if !authority.Check(locator) || IsPrivateResourcePath(authority.Filesystem, locator) {
		return nil, fs.ErrPermission
	}
	file, err := openScopedRootNative(ctx, authority.Filesystem, authority.ServerRoot, locator)
	if err != nil {
		return nil, err
	}
	info, err := file.Stat()
	if err != nil || !info.IsDir() || IsPrivateMediaFile(info) {
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		return nil, fs.ErrPermission
	}
	if err := ctx.Err(); err != nil {
		_ = file.Close()
		return nil, err
	}
	return &ScopedRoot{file: file, authority: authority, locator: locator, capabilities: scopedCapabilities(file), done: make(chan struct{})}, nil
}

func (root *ScopedRoot) Capabilities() SourceCapabilities { return root.capabilities }
func (root *ScopedRoot) Close() error {
	root.mu.Lock()
	defer root.mu.Unlock()
	if root.closed {
		return fs.ErrClosed
	}
	root.closed = true
	close(root.done)
	return root.file.Close()
}

// Duplicate the handle while it cannot be closed and recycled. Potentially
// blocking provider calls run after releasing the root's lifetime mutex.
func (root *ScopedRoot) duplicate() (*os.File, error) {
	root.mu.RLock()
	defer root.mu.RUnlock()
	if root.closed {
		return nil, fs.ErrClosed
	}
	return duplicateScopedFile(root.file)
}
func (root *ScopedRoot) State(ctx context.Context, freshness ScopedFreshness) (ScopedState, error) {
	file, err := root.duplicate()
	if err != nil {
		return ScopedState{}, err
	}
	defer func() { _ = file.Close() }()
	return scopedFileState(ctx, file, freshness)
}

func (root *ScopedRoot) OpenRegular(ctx context.Context, relative string) (*ScopedResource, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := root.authorize(relative, false); err != nil {
		return nil, err
	}
	held, err := root.duplicate()
	if err != nil {
		return nil, err
	}
	defer func() { _ = held.Close() }()
	file, err := openScopedChildNative(ctx, held, relative, false)
	if err != nil {
		return nil, err
	}
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || IsPrivateMediaFile(info) {
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		return nil, fs.ErrPermission
	}
	if err := ctx.Err(); err != nil {
		_ = file.Close()
		return nil, err
	}
	if err := root.authorize(relative, false); err != nil {
		_ = file.Close()
		return nil, err
	}
	return &ScopedResource{file: file, capabilities: scopedCapabilities(file)}, nil
}

func (root *ScopedRoot) OpenDirectory(ctx context.Context, relative string) (*ScopedDirectory, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := root.authorize(relative, true); err != nil {
		return nil, err
	}
	held, err := root.duplicate()
	if err != nil {
		return nil, err
	}
	defer func() { _ = held.Close() }()
	file, err := openScopedChildNative(ctx, held, relative, true)
	if err != nil {
		return nil, err
	}
	info, err := file.Stat()
	if err != nil || !info.IsDir() || IsPrivateMediaFile(info) {
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		return nil, fs.ErrPermission
	}
	if err := ctx.Err(); err != nil {
		_ = file.Close()
		return nil, err
	}
	if err := root.authorize(relative, true); err != nil {
		_ = file.Close()
		return nil, err
	}
	return &ScopedDirectory{file: file, root: root, relative: relative, batch: make(chan struct{}, 1), done: make(chan struct{})}, nil
}

func (resource *ScopedResource) ReadAt(p []byte, offset int64) (int, error) {
	return resource.file.ReadAt(p, offset)
}
func (resource *ScopedResource) ReadAtContext(ctx context.Context, p []byte, offset int64) (int, error) {
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	n, err := resource.file.ReadAt(p, offset)
	if canceled := ctx.Err(); canceled != nil {
		return 0, canceled
	}
	return n, err
}
func (resource *ScopedResource) Stat() (fs.FileInfo, error) { return resource.file.Stat() }
func (resource *ScopedResource) State(ctx context.Context, freshness ScopedFreshness) (ScopedState, error) {
	file, err := duplicateScopedFile(resource.file)
	if err != nil {
		return ScopedState{}, err
	}
	defer func() { _ = file.Close() }()
	return scopedFileState(ctx, file, freshness)
}
func (resource *ScopedResource) Capabilities() SourceCapabilities { return resource.capabilities }
func (resource *ScopedResource) Close() error                     { return resource.file.Close() }
func (directory *ScopedDirectory) State(ctx context.Context, freshness ScopedFreshness) (ScopedState, error) {
	if directory.closed.Load() {
		return ScopedState{}, fs.ErrClosed
	}
	if err := directory.root.authorize(directory.relative, true); err != nil {
		return ScopedState{}, err
	}
	file, err := duplicateScopedFile(directory.file)
	if err != nil {
		return ScopedState{}, err
	}
	defer func() { _ = file.Close() }()
	return scopedFileState(ctx, file, freshness)
}
func (directory *ScopedDirectory) Close() error {
	if directory.closed.Swap(true) {
		return fs.ErrClosed
	}
	close(directory.done)
	return directory.file.Close()
}

// ReadBatch inspects at most 256 directory entries per call. An empty page
// with eof=false means entries were filtered; callers may continue. Entry types
// are hints only and do not authorize a later child open.
func (directory *ScopedDirectory) ReadBatch(ctx context.Context, limit int) ([]ScopedEntry, bool, error) {
	select {
	case directory.batch <- struct{}{}:
		defer func() { <-directory.batch }()
	case <-ctx.Done():
		return nil, false, ctx.Err()
	case <-directory.done:
		return nil, false, fs.ErrClosed
	case <-directory.root.done:
		return nil, false, fs.ErrClosed
	}
	if directory.closed.Load() {
		return nil, false, fs.ErrClosed
	}
	if limit < 1 || limit > 256 {
		return nil, false, fs.ErrInvalid
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	if err := directory.root.authorize(directory.relative, true); err != nil {
		return nil, false, err
	}
	entries, eof, err := readScopedDirectory(ctx, directory, limit)
	if err != nil {
		return nil, false, err
	}
	visible := make([]ScopedEntry, 0, len(entries))
	for _, entry := range entries {
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
		name := string(entry.Name)
		if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\x00") {
			return nil, false, fs.ErrInvalid
		}
		relative := name
		if directory.relative != "" {
			relative = directory.relative + "/" + name
		}
		if err := directory.root.authorize(relative, false); errors.Is(err, fs.ErrPermission) {
			continue
		} else if err != nil {
			return nil, false, err
		}
		visible = append(visible, entry)
	}
	if directory.closed.Load() {
		return nil, false, fs.ErrClosed
	}
	if err := directory.root.authorize(directory.relative, true); err != nil {
		return nil, false, err
	}
	return visible, eof, nil
}

// ConfirmAbsence deliberately reports unknown freshness until A2 supplies a
// domain/provider-specific revalidation proof. ENOENT alone is not a remote
// server confirmation and is never a deletion authorization.
func (root *ScopedRoot) ConfirmAbsence(ctx context.Context, relative string) (ScopedAbsenceEvidence, error) {
	if err := root.authorize(relative, false); err != nil {
		return ScopedAbsenceEvidence{}, err
	}
	held, err := root.duplicate()
	if err != nil {
		return ScopedAbsenceEvidence{}, err
	}
	defer func() { _ = held.Close() }()
	file, err := openScopedChildNative(ctx, held, relative, false)
	if canceled := ctx.Err(); canceled != nil {
		if file != nil {
			_ = file.Close()
		}
		return ScopedAbsenceEvidence{}, canceled
	}
	if policyErr := root.authorize(relative, false); policyErr != nil {
		if file != nil {
			_ = file.Close()
		}
		return ScopedAbsenceEvidence{}, policyErr
	}
	if err == nil {
		_ = file.Close()
		return ScopedAbsenceEvidence{State: "present", Freshness: "unknown"}, nil
	}
	if errors.Is(err, fs.ErrNotExist) {
		return ScopedAbsenceEvidence{State: "suspected_absent", Freshness: "unknown"}, nil
	}
	return ScopedAbsenceEvidence{}, err
}

// scopedBasePath additionally verifies that BasePathFs opens an actual OS file.
// It does not infer a mount's capabilities from os.SameFile or FS type.
func scopedBasePath(filesystem afero.Fs, serverRoot string) (root, scope string, err error) {
	base, ok := filesystem.(*afero.BasePathFs)
	if !ok {
		return "", "", ErrScopedUnavailable
	}
	opened, err := base.Open("/")
	if err != nil {
		return "", "", err
	}
	defer func() { _ = opened.Close() }()
	inner := opened
	for {
		wrapper, ok := inner.(*afero.BasePathFile)
		if !ok {
			break
		}
		inner = wrapper.File
	}
	nativeFile, ok := inner.(*os.File)
	if !ok {
		return "", "", ErrScopedUnavailable
	}
	native, err := filepath.Abs(afero.FullBaseFsPath(base, "/"))
	if err != nil {
		return "", "", err
	}
	openedName, err := filepath.Abs(nativeFile.Name())
	if err != nil || filepath.Clean(openedName) != filepath.Clean(native) {
		return "", "", ErrScopedUnavailable
	}
	root, err = filepath.Abs(serverRoot)
	if err != nil {
		return "", "", err
	}
	relative, err := filepath.Rel(root, native)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", "", fs.ErrPermission
	}
	return root, filepath.ToSlash(relative), nil
}

var _ io.ReaderAt = (*ScopedResource)(nil)
