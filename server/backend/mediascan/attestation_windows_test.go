//go:build windows

package mediascan

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"golang.org/x/sys/windows"
)

func TestWindowsVirtualComputerSelectsAnActualRoot(t *testing.T) {
	authority := mediasource.LocalAuthority{FS: files.NewDriveFs(), ServerRoot: files.VirtualComputerRoot,
		WorkspaceScope: []byte("/"), CanRead: true, Check: func(string) bool { return true }}
	if _, err := CaptureLocalBindingProof(t.Context(), 7, "fixture", []byte("/"), authority); !errors.Is(err, ErrBindingRootSelection) {
		t.Fatalf("virtual chooser treated as a physical root: %v", err)
	}
	// The directory chooser returns native names, whereas TEMP (and thus
	// t.TempDir) can contain a case or 8.3 alias. Resolve the test fixture as
	// an opened directory so the test supplies the same canonical path that a
	// chooser would select. Do not relax the scoped opener's alias checks.
	fixture := filepath.Join(t.TempDir(), "MediaRootCase")
	if err := os.Mkdir(fixture, 0o700); err != nil {
		t.Fatal(err)
	}
	dir, err := os.Open(fixture)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = dir.Close() }()
	buffer := make([]uint16, 32768)
	n, err := windows.GetFinalPathNameByHandle(windows.Handle(dir.Fd()), &buffer[0], uint32(len(buffer)), 0)
	if err != nil || n == 0 || n >= uint32(len(buffer)) {
		t.Fatalf("canonical fixture path: %d %v", n, err)
	}
	canonical := strings.TrimPrefix(windows.UTF16ToString(buffer[:n]), `\\?\`)
	selected := []byte(files.WindowsPathToVirtual(canonical))
	alias := filepath.Join(filepath.Dir(canonical), strings.ToLower(filepath.Base(canonical)))
	if _, err := CaptureLocalBindingProof(t.Context(), 7, "fixture", []byte(files.WindowsPathToVirtual(alias)), authority); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("case alias bypassed scoped opener: %v", err)
	}
	proof, err := CaptureLocalBindingProof(t.Context(), 7, "fixture", selected, authority)
	if err != nil || proof.RootIdentityKind != "native" {
		t.Fatalf("actual selected volume path rejected: %+v %v", proof, err)
	}
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", selected, authority, proof); err != nil {
		t.Fatal(err)
	}
}
