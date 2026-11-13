//go:build windows

package files

import (
	"errors"
	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
	"os"
	"path/filepath"
	"strings"
)

func resolvedPrivateResourcePath(filesystem afero.Fs, name string) bool {
	var native string
	switch fs := filesystem.(type) {
	case *afero.BasePathFs:
		var err error
		native, err = fs.RealPath(name)
		if err != nil {
			return true
		}
		// MemMapFs and other virtual filesystems have no native short aliases.
		_, supported, statErr := fs.LstatIfPossible(name)
		if !supported {
			return false
		}
		if statErr != nil && !os.IsNotExist(statErr) {
			return true
		}
	case *DriveFs:
		var err error
		native, err = fs.RealPath(name)
		if errors.Is(err, errVirtualRoot) {
			return false
		}
		if err != nil {
			return true
		}
	case *afero.OsFs:
		var err error
		native, err = filepath.Abs(name)
		if err != nil {
			return true
		}
	default:
		return false
	}
	// The leaf may not exist yet (POST/PATCH destination). Expand the nearest
	// existing native ancestor, using the current authoritative Fs mapping only.
	for candidate := filepath.Clean(native); ; candidate = filepath.Dir(candidate) {
		extended := candidate
		if !strings.HasPrefix(extended, "\\\\?\\") {
			if strings.HasPrefix(extended, "\\\\") {
				extended = "\\\\?\\UNC\\" + strings.TrimPrefix(extended, "\\\\")
			} else {
				extended = "\\\\?\\" + extended
			}
		}
		input, err := windows.UTF16PtrFromString(extended)
		if err != nil {
			return true
		}
		count, err := windows.GetLongPathName(input, nil, 0)
		if err == nil && count > 0 && count <= 32768 {
			buffer := make([]uint16, count+1)
			written, err := windows.GetLongPathName(input, &buffer[0], uint32(len(buffer)))
			if err != nil || written == 0 || written >= uint32(len(buffer)) {
				return true
			}
			if IsUploadPartPath(windows.UTF16ToString(buffer)) || isPrivateMediaNativePath(windows.UTF16ToString(buffer)) {
				return true
			}
			// A reparse/junction alias can name the same private ancestor without
			// containing either its long or short filename. Inspect metadata only.
			handle, openErr := windows.CreateFile(input, 0, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS, 0)
			if openErr != nil {
				return true
			}
			final := make([]uint16, 32768)
			size, finalErr := windows.GetFinalPathNameByHandle(handle, &final[0], uint32(len(final)), 0)
			closeErr := windows.CloseHandle(handle)
			if finalErr != nil || closeErr != nil || size == 0 || size >= uint32(len(final)) {
				return true
			}
			return IsUploadPartPath(windows.UTF16ToString(final)) || isPrivateMediaNativePath(windows.UTF16ToString(final))
		}
		if err == nil {
			return true
		}
		if !errors.Is(err, windows.ERROR_FILE_NOT_FOUND) && !errors.Is(err, windows.ERROR_PATH_NOT_FOUND) {
			return true
		}
		if filepath.Dir(candidate) == candidate {
			return false
		}
	}
}
