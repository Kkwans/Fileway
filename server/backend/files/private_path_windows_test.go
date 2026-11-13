//go:build windows

package files

import (
	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWindowsPrivateResourceUsesCurrentPhysicalRootAndExistingAncestors(t *testing.T) {
	root, err := os.MkdirTemp(os.TempDir(), "private-txn-")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = os.RemoveAll(root) }()
	private := UploadPartPrefix + strings.Repeat("a", 32) + ".part"
	part := filepath.Join(root, private)
	if err := os.MkdirAll(filepath.Join(part, "old"), 0o700); err != nil {
		t.Fatal(err)
	}
	fs := afero.NewBasePathFs(afero.NewOsFs(), root)
	scoped := afero.NewBasePathFs(afero.NewOsFs(), part)
	if !IsPrivateResourcePath(scoped, "/old/missing.txt") {
		t.Fatal("scoped root into a private part bypassed ancestor check")
	}
	if err := os.WriteFile(filepath.Join(root, "ordinary.txt"), []byte("owned"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, ordinary := range []string{"/ordinary.txt", "/", "/ordinary-dir/new/missing.txt"} {
		if IsPrivateResourcePath(fs, ordinary) {
			t.Fatalf("ordinary native path rejected: %s", ordinary)
		}
	}
	if IsPrivateResourcePath(fs, "/ordinary-missing.txt") {
		t.Fatal("ordinary missing leaf rejected")
	}
	t.Run("existing-reparse-alias", func(t *testing.T) {
		alias := filepath.Join(root, "ordinary-alias")
		if err := os.Symlink(part, alias); err != nil {
			t.Skipf("Native reparse creation unavailable; settings untouched: %v", err)
		}
		if !IsPrivateResourcePath(fs, "/ordinary-alias/old/missing.txt") {
			t.Fatal("private reparse alias bypassed metadata guard")
		}
	})
	t.Run("actual-8dot3-alias", func(t *testing.T) {
		input, err := windows.UTF16PtrFromString(part)
		if err != nil {
			t.Fatal(err)
		}
		buffer := make([]uint16, 32768)
		written, err := windows.GetShortPathName(input, &buffer[0], uint32(len(buffer)))
		if err != nil || written == 0 {
			t.Skipf("Existing filesystem supplies no queryable short alias: %v", err)
		}
		short := filepath.Base(windows.UTF16ToString(buffer))
		if strings.EqualFold(short, private) || IsUploadPartPath(short) {
			t.Skip("No distinct NTFS short alias generated on this owned fixture")
		}
		if !IsPrivateResourcePath(fs, "/"+short+"/old/missing.txt") {
			t.Fatalf("actual short alias exposed private directory: %s", short)
		}
		aliasScope := afero.NewBasePathFs(afero.NewOsFs(), filepath.Join(root, short, "not-created"))
		if !IsPrivateResourcePath(aliasScope, "/future.txt") {
			t.Fatal("missing scoped root under an existing private alias bypassed the guard")
		}
	})
}
