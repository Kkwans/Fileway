package mediascan

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io/fs"
	"path"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

type ReferenceState string

const (
	ReferenceAvailable  ReferenceState = "available"
	ReferenceOffline    ReferenceState = "offline"
	ReferenceChanged    ReferenceState = "changed"
	ReferenceUnverified ReferenceState = "unverified"
	// ReferenceReplaced is an internal authorization result, never a public
	// availability claim for an item tied to the earlier object.
	ReferenceReplaced ReferenceState = "replaced"
)

// CheckLocalReference revalidates a stored owner-workspace locator under its
// exact binding. Missing paths remain offline only after current owner scope and
// path rules passed. A native object is compared on the open handle; a path-only
// identity is explicitly unverified and cannot prove continuity of playback.
// An empty expectedKey checks a metadata sidecar's current path authority only.
func CheckLocalReference(ctx context.Context, root, locator, expectedKey []byte, expectedKind, expectedVersion string, resolve mediasource.ResolveLocalAuthority) (state ReferenceState, resultErr error) {
	relative, err := relativeLocalLocator(root, locator)
	if err != nil {
		return "", err
	}
	source, err := mediasource.NewLocal(string(root), resolve)
	if err != nil {
		return "", err
	}
	resource, err := source.Open(ctx, relative, false)
	if errors.Is(err, fs.ErrNotExist) {
		return ReferenceOffline, nil
	}
	if err != nil {
		return "", err
	}
	defer func() {
		if err := resource.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	info, _, err := resource.State()
	if err != nil || info == nil || resource.File == nil {
		if err != nil {
			return "", err
		}
		return "", fs.ErrInvalid
	}
	if len(expectedKey) == 0 {
		return ReferenceAvailable, nil
	}
	if expectedKind != string(IdentityNative) {
		return ReferenceUnverified, nil
	}
	currentKey, ok := nativeObjectKey(resource.File)
	if !ok || !bytes.Equal(currentKey, expectedKey) {
		return ReferenceReplaced, nil
	}
	version := fmt.Sprintf("v1:%d:%d", info.Size(), info.ModTime().UnixNano())
	if expectedVersion == "" || version != expectedVersion {
		return ReferenceChanged, nil
	}
	return ReferenceAvailable, nil
}

func relativeLocalLocator(root, locator []byte) (string, error) {
	prefix := bytes.Clone(root)
	if !bytes.Equal(root, []byte("/")) {
		prefix = append(prefix, '/')
	}
	if len(root) == 0 || !bytes.HasPrefix(locator, prefix) || len(locator) == len(prefix) {
		return "", fs.ErrPermission
	}
	relative := string(locator[len(prefix):])
	if relative == "" || path.Clean(relative) != relative || relative == ".." || strings.HasPrefix(relative, "../") || strings.HasPrefix(relative, "/") {
		return "", fs.ErrInvalid
	}
	return relative, nil
}
