//go:build windows

package mediascan

import (
	"errors"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

func TestWindowsVirtualComputerSelectsAnActualRoot(t *testing.T) {
	authority := mediasource.LocalAuthority{FS: files.NewDriveFs(), ServerRoot: files.VirtualComputerRoot,
		WorkspaceScope: []byte("/"), CanRead: true, Check: func(string) bool { return true }}
	if _, err := CaptureLocalBindingProof(t.Context(), 7, "fixture", []byte("/"), authority); !errors.Is(err, ErrBindingRootSelection) {
		t.Fatalf("virtual chooser treated as a physical root: %v", err)
	}
	selected := []byte(files.WindowsPathToVirtual(t.TempDir()))
	proof, err := CaptureLocalBindingProof(t.Context(), 7, "fixture", selected, authority)
	if err != nil || proof.RootIdentityKind != "native" {
		t.Fatalf("actual selected volume path rejected: %+v %v", proof, err)
	}
	if _, err := VerifyLocalBindingProof(t.Context(), 7, "fixture", selected, authority, proof); err != nil {
		t.Fatal(err)
	}
}
