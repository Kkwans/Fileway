package mediascan

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"path/filepath"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"github.com/spf13/afero"
)

var (
	ErrBindingUnverified    = errors.New("media source binding requires authorization")
	ErrBindingChanged       = errors.New("media source binding mapping or root changed")
	ErrBindingRootSelection = errors.New("select a physical media source root")
)

type BindingState string

const (
	BindingAvailable BindingState = "available"
	BindingOffline   BindingState = "offline"
)

type realPathFilesystem interface {
	RealPath(string) (string, error)
}

// mappingDigest identifies the effective owner workspace, not an arbitrary
// client-supplied path. Binary path components use JSON's []byte encoding so
// invalid UTF-8 filesystem bytes are not normalized or replaced.
func mappingDigest(owner uint, configuration string, root []byte, authority mediasource.LocalAuthority) ([]byte, error) {
	if owner == 0 || configuration == "" || authority.FS == nil || !authority.CanRead || authority.Check == nil {
		return nil, fs.ErrPermission
	}
	if _, err := mediasource.NewLocal(string(root), func(context.Context) (mediasource.LocalAuthority, error) { return authority, nil }); err != nil {
		return nil, err
	}
	mode := ""
	var workspace, selected, server []byte
	if files.IsVirtualComputerRoot(authority.ServerRoot) {
		server = []byte("virtual-computer")
	} else {
		absolute, err := filepath.Abs(authority.ServerRoot)
		if err != nil {
			return nil, err
		}
		server = []byte(filepath.Clean(absolute))
	}
	switch filesystem := authority.FS.(type) {
	case *afero.BasePathFs:
		mode = "base-path"
		base, err := filepath.Abs(afero.FullBaseFsPath(filesystem, "/"))
		if err != nil {
			return nil, err
		}
		mapped, err := filepath.Abs(afero.FullBaseFsPath(filesystem, string(root)))
		if err != nil {
			return nil, err
		}
		workspace, selected = []byte(filepath.Clean(base)), []byte(filepath.Clean(mapped))
	case realPathFilesystem:
		mode = "drive"
		mapped, err := filesystem.RealPath(string(root))
		if err != nil {
			if !files.IsVirtualComputerRoot(authority.ServerRoot) || !bytes.Equal(root, []byte("/")) {
				return nil, ErrBindingUnverified
			}
			mapped = "virtual-computer-root"
		}
		selected = []byte(mapped)
	default:
		return nil, ErrBindingUnverified
	}
	encoded, err := json.Marshal(struct {
		Version       int    `json:"version"`
		Owner         uint   `json:"owner"`
		Configuration string `json:"configuration"`
		Server        []byte `json:"server"`
		Scope         []byte `json:"scope"`
		Mode          string `json:"mode"`
		Workspace     []byte `json:"workspace"`
		Selected      []byte `json:"selected"`
		Root          []byte `json:"root"`
	}{1, owner, configuration, server, append([]byte{}, authority.WorkspaceScope...), mode, workspace, selected, root})
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(encoded)
	return sum[:], nil
}

func bindingRootIdentity(ctx context.Context, root []byte, authority mediasource.LocalAuthority) (kind string, identity []byte, resultErr error) {
	if err := ctx.Err(); err != nil {
		return "", nil, err
	}
	if authority.Check == nil || !authority.Check(string(root)) || files.IsPrivateResourcePath(authority.FS, string(root)) {
		return "", nil, fs.ErrPermission
	}
	if drive, ok := authority.FS.(*files.DriveFs); ok && files.IsVirtualComputerRoot(authority.ServerRoot) && bytes.Equal(root, []byte("/")) {
		if _, err := drive.RealPath("/"); err != nil {
			// The computer container is a chooser, not a single physical root.
			// Reject before enumerating drives; actual selected volumes still work.
			return "", nil, ErrBindingRootSelection
		}
	}
	resource, err := files.OpenSubtitleResource(authority.FS, authority.ServerRoot, string(root), true, false)
	if err != nil {
		return "", nil, err
	}
	defer func() {
		if err := resource.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	info, _, err := resource.State()
	if err != nil {
		return "", nil, err
	}
	if info == nil && resource.File == nil && files.IsVirtualComputerRoot(authority.ServerRoot) && bytes.Equal(root, []byte("/")) {
		return "", nil, ErrBindingRootSelection
	}
	if info == nil || !info.IsDir() || resource.File == nil || files.IsPrivateMediaFile(info) {
		return "", nil, fs.ErrPermission
	}
	key, ok := nativeDirectoryKey(resource.File)
	if !ok {
		return "", nil, ErrBindingUnverified
	}
	return "native", append([]byte("fileway/root/v1\x00"), key...), nil
}

// CaptureLocalBindingProof is used only after an explicit owner action. It
// captures a safe root identity; it never installs or updates a stored proof.
func CaptureLocalBindingProof(ctx context.Context, owner uint, configuration string, root []byte, authority mediasource.LocalAuthority) (*medialibrary.BindingProof, error) {
	digest, err := mappingDigest(owner, configuration, root, authority)
	if err != nil {
		return nil, err
	}
	kind, identity, err := bindingRootIdentity(ctx, root, authority)
	if err != nil {
		return nil, err
	}
	return &medialibrary.BindingProof{Version: 1, MappingDigest: digest, RootIdentityKind: kind, RootIdentity: identity, AttestedAt: time.Now().UnixMilli()}, nil
}

// VerifyLocalBindingProof always rechecks the mapping and current root rules.
// A proven mapping whose root is absent can be represented as offline; other
// open errors and a different live root never become an offline permission.
func VerifyLocalBindingProof(ctx context.Context, owner uint, configuration string, root []byte, authority mediasource.LocalAuthority, proof *medialibrary.BindingProof) (BindingState, error) {
	if proof == nil || proof.Version != 1 || len(proof.MappingDigest) != sha256.Size || len(proof.RootIdentity) == 0 {
		return "", ErrBindingUnverified
	}
	digest, err := mappingDigest(owner, configuration, root, authority)
	if err != nil {
		return "", err
	}
	if !bytes.Equal(digest, proof.MappingDigest) {
		return "", ErrBindingChanged
	}
	kind, identity, err := bindingRootIdentity(ctx, root, authority)
	if errors.Is(err, fs.ErrNotExist) {
		return BindingOffline, nil
	}
	if err != nil {
		return "", err
	}
	if kind != proof.RootIdentityKind || !bytes.Equal(identity, proof.RootIdentity) {
		return "", ErrBindingChanged
	}
	if err := ctx.Err(); err != nil {
		return "", fmt.Errorf("binding authorization: %w", err)
	}
	return BindingAvailable, nil
}
