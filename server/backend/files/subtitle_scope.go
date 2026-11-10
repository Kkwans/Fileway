package files

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/spf13/afero"
)

var ErrSubtitleScopeUnavailable = errors.New("filesystem does not support safe subtitle access")

// SubtitleResource holds the exact object opened under the authenticated scope.
type SubtitleResource struct {
	File         *os.File
	watch        subtitleDirectoryWatch
	virtual      []fs.DirEntry
	virtualStamp string
	offset       int
}

type subtitleDirectoryWatch interface {
	changed() bool
	close()
}

func (r *SubtitleResource) Changed() bool { return r.watch != nil && r.watch.changed() }

func (r *SubtitleResource) Close() error {
	if r.watch != nil {
		r.watch.close()
	}
	if r.File != nil {
		return r.File.Close()
	}
	return nil
}

func (r *SubtitleResource) ReadDir(n int) ([]fs.DirEntry, error) {
	if r.File != nil {
		return r.File.ReadDir(n)
	}
	if r.offset == len(r.virtual) {
		return nil, io.EOF
	}
	end := min(r.offset+n, len(r.virtual))
	entries := r.virtual[r.offset:end]
	r.offset = end
	return entries, nil
}

// State returns an identity and change stamp from the open handle. A virtual
// root has a stamp derived from its current set of ready volumes.
func (r *SubtitleResource) State() (os.FileInfo, string, error) {
	if r.File == nil {
		return nil, r.virtualStamp, nil
	}
	info, err := r.File.Stat()
	if err != nil {
		return nil, "", err
	}
	stamp, err := subtitleResourceStamp(r.File, info)
	return info, stamp, err
}

func OpenSubtitleResource(filesystem afero.Fs, serverRoot, name string, directory, readData bool) (*SubtitleResource, error) {
	if !strings.HasPrefix(name, "/") || path.Clean(name) != name || strings.ContainsRune(name, 0) {
		return nil, fs.ErrInvalid
	}
	return openSubtitleResource(filesystem, serverRoot, name, directory, readData)
}

// Resolve the configured user filesystem relative to the administrator's
// server root. The native opener still verifies every component below it.
func subtitleBasePath(filesystem afero.Fs, serverRoot string) (root, scope string, err error) {
	if _, ok := filesystem.(*afero.BasePathFs); !ok {
		return "", "", ErrSubtitleScopeUnavailable
	}
	native, ok := osBackedPath(filesystem, "/")
	if !ok {
		return "", "", ErrSubtitleScopeUnavailable
	}
	root, err = filepath.Abs(serverRoot)
	if err != nil {
		return "", "", err
	}
	native, err = filepath.Abs(native)
	if err != nil {
		return "", "", err
	}
	rel, err := filepath.Rel(root, native)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", "", fs.ErrPermission
	}
	return root, filepath.ToSlash(rel), nil
}
