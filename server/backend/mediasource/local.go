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
	FS             afero.Fs
	ServerRoot     string
	WorkspaceScope []byte // from the same live account snapshot as FS and Check
	CanRead        bool
	Check          func(string) bool
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
	if err := checkOpenedResource(resource, directory); err != nil {
		_ = resource.Close()
		return nil, err
	}
	return resource, nil
}

// OpenFromVerifiedRoot opens a file below the exact root handle authenticated
// by the caller. Policy checks remain against the source owner's live rules.
// The caller must resolve the authority and attest root in the same operation.
func (source *Local) OpenFromVerifiedRoot(ctx context.Context, authority LocalAuthority, root *files.SubtitleResource, locator string) (*files.SubtitleResource, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if locator == "" || strings.HasPrefix(locator, "/") || path.Clean(locator) != locator || locator == ".." || strings.HasPrefix(locator, "../") || invalidLocalPath(locator) {
		return nil, fs.ErrInvalid
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
	resource, err := files.OpenSubtitleChild(root, locator)
	if err != nil {
		return nil, err
	}
	if err := checkOpenedResource(resource, false); err != nil {
		_ = resource.Close()
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		_ = resource.Close()
		return nil, err
	}
	return resource, nil
}

// The path policy can race with a rename or hard-link swap. Inspect the actual
// opened object as well, for both files and directories, before returning it.
// A nil info is only valid for the explicitly virtual computer directory.
func checkOpenedResource(resource *files.SubtitleResource, directory bool) error {
	info, _, err := resource.State()
	if err != nil {
		return err
	}
	if info == nil {
		if directory && resource.File == nil {
			return nil
		}
		return fs.ErrInvalid
	}
	if files.IsPrivateMediaFile(info) {
		return fs.ErrPermission
	}
	if directory && !info.IsDir() || !directory && !info.Mode().IsRegular() {
		return errors.New("media source has an unexpected object type")
	}
	return nil
}

func invalidLocalPath(value string) bool {
	return strings.ContainsRune(value, 0) || runtime.GOOS == "windows" && strings.ContainsRune(value, '\\')
}
