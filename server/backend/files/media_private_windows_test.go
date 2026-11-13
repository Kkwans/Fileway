//go:build windows

package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
)

func TestPrivateMediaWindowsShortAliasStaysPrivateWhenDisabled(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, MediaLibraryDirectory)
	if err := os.Mkdir(directory, 0700); err != nil {
		t.Fatal(err)
	}
	input, err := windows.UTF16PtrFromString(directory)
	if err != nil {
		t.Fatal(err)
	}
	buffer := make([]uint16, 32768)
	written, err := windows.GetShortPathName(input, &buffer[0], uint32(len(buffer)))
	if err != nil || written == 0 || written >= uint32(len(buffer)) {
		t.Skipf("existing filesystem supplies no short alias: %v", err)
	}
	short := filepath.Base(windows.UTF16ToString(buffer))
	if strings.EqualFold(short, MediaLibraryDirectory) {
		t.Skip("no distinct short alias generated on the owned fixture")
	}
	fs := afero.NewBasePathFs(afero.NewOsFs(), root)
	if !IsPrivateResourcePath(fs, "/"+short+"/future.db-wal") {
		t.Fatal("disabled catalog exposed via a native short alias")
	}
	if !IsPrivateResourcePath(NewDriveFs(), WindowsPathToVirtual(filepath.Join(directory, "future.db"))) {
		t.Fatal("computer root exposed the reserved catalog directory")
	}
}
