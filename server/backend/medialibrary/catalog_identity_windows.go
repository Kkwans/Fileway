//go:build windows

package medialibrary

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"golang.org/x/sys/windows"
)

func inspectCatalogHandle(file *os.File) (string, uint64, error) {
	info, err := file.Stat()
	if err != nil {
		return "", 0, err
	}
	if !info.Mode().IsRegular() {
		return "", 0, errors.New("catalog path is not a regular file")
	}
	var native windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(windows.Handle(file.Fd()), &native); err != nil {
		return "", 0, err
	}
	canonical, err := catalogHandleVolumePath(file)
	if err != nil {
		return "", 0, err
	}
	end := strings.Index(canonical, "}")
	if end < 0 {
		return "", 0, errors.New("invalid catalog volume identity")
	}
	return fmt.Sprintf("%s:%x:%x:%x", strings.ToLower(canonical[:end+1]), native.VolumeSerialNumber, native.FileIndexHigh, native.FileIndexLow), uint64(native.NumberOfLinks), nil
}

func catalogHandleVolumePath(file *os.File) (string, error) {
	// VOLUME_NAME_GUID gives the same absolute path for drive-letter aliases,
	// junctions and mounted volumes. A network path has no local volume GUID.
	buffer := make([]uint16, 32768)
	n, err := windows.GetFinalPathNameByHandle(windows.Handle(file.Fd()), &buffer[0], uint32(len(buffer)), 1)
	if err != nil {
		return "", fmt.Errorf("resolve catalog volume path: %w", err)
	}
	if n == 0 || n >= uint32(len(buffer)) {
		return "", errors.New("invalid catalog volume path")
	}
	canonical := windows.UTF16ToString(buffer[:n])
	if !strings.HasPrefix(strings.ToLower(canonical), `\\?\volume{`) {
		return "", errors.New("media catalog requires a local volume")
	}
	return canonical, nil
}

func inspectCatalogPath(path string) (canonical, key string, links uint64, resultErr error) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", 0, err
	}
	defer func() { resultErr = errors.Join(resultErr, f.Close()) }()
	key, links, err = inspectCatalogHandle(f)
	if err != nil {
		return "", "", 0, err
	}
	canonical, err = catalogHandleVolumePath(f)
	if err != nil {
		return "", "", 0, err
	}
	return canonical, key, links, nil
}

func rejectFileMountAlias(string, string) error { return nil }

func openCoordinatorFile(path string) (*os.File, error) {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateFile(name, windows.GENERIC_READ|windows.GENERIC_WRITE, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil, windows.OPEN_ALWAYS, windows.FILE_ATTRIBUTE_NORMAL|windows.FILE_FLAG_OPEN_REPARSE_POINT, 0)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(handle), path), nil
}

func tryLockCoordinatorFile(file *os.File) error {
	return windows.LockFileEx(windows.Handle(file.Fd()), windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &windows.Overlapped{})
}

func unlockCoordinatorFile(file *os.File) error {
	return windows.UnlockFileEx(windows.Handle(file.Fd()), 0, 1, 0, &windows.Overlapped{})
}
