// Package mediasource provides read-only media inputs. Library/viewer grants
// remain the caller's responsibility; source-owner authority is checked here.
package mediasource

import (
	"context"
	"errors"
	"io/fs"
	"path"
	"runtime"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/spf13/afero"
)

// LocalAuthority is resolved afresh for every open. Never capture a long-lived
// user Fs or permission snapshot in a source connection.
type LocalAuthority struct {
	FS         afero.Fs
	ServerRoot string
	CanRead    bool
	Check      func(string) bool
}

type ResolveLocalAuthority func(context.Context) (LocalAuthority, error)

// Local is a source root expressed in its owner's existing file workspace.
// It does not expose filesystem mutation or accept arbitrary absolute locators.
type Local struct {
	root    string
	resolve ResolveLocalAuthority
}

func NewLocal(root string, resolve ResolveLocalAuthority) (*Local, error) {
	if resolve == nil || !strings.HasPrefix(root, "/") || path.Clean(root) != root || invalidLocalPath(root) {
		return nil, fs.ErrInvalid
	}
	return &Local{root: root, resolve: resolve}, nil
}

// Open validates current owner authority and uses the existing native scoped
// opener. The returned handle supports original-byte seeking; it never emulates
// ranges by reading and discarding a remote full response. Callers must close it
// and cancel existing streams when their library/source grant is revoked.
func (source *Local) Open(ctx context.Context, locator string, directory bool) (*files.SubtitleResource, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if locator != "" && (strings.HasPrefix(locator, "/") || path.Clean(locator) != locator || locator == ".." || strings.HasPrefix(locator, "../") || invalidLocalPath(locator)) {
		return nil, fs.ErrInvalid
	}
	if locator == "" && !directory {
		return nil, fs.ErrInvalid
	}
	authority, err := source.resolve(ctx)
	if err != nil {
		return nil, err
	}
	if authority.FS == nil || !authority.CanRead || authority.Check == nil {
		return nil, fs.ErrPermission
	}
	target := path.Join(source.root, locator)
	for current := target; ; current = path.Dir(current) {
		if !authority.Check(current) || files.IsPrivateResourcePath(authority.FS, current) {
			return nil, fs.ErrPermission
		}
		if current == source.root {
			break
		}
		if current == "/" {
			return nil, fs.ErrPermission
		}
	}
	resource, err := files.OpenSubtitleResource(authority.FS, authority.ServerRoot, target, directory, true)
	if err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		_ = resource.Close()
		return nil, err
	}
	if !directory {
		info, _, err := resource.State()
		if err != nil || info == nil || !info.Mode().IsRegular() {
			_ = resource.Close()
			if err != nil {
				return nil, err
			}
			return nil, errors.New("media source is not a regular file")
		}
	}
	return resource, nil
}

func invalidLocalPath(value string) bool {
	return strings.ContainsRune(value, 0) || runtime.GOOS == "windows" && strings.ContainsRune(value, '\\')
}
