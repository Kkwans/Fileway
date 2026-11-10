//go:build windows

package files

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"unicode/utf8"
	"unsafe"

	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
)

func SubtitleScopeAvailable() bool { return true }

func openSubtitleResource(filesystem afero.Fs, serverRoot, name string, directory, readData bool) (*SubtitleResource, error) {
	requested := subtitleWindowsSegments(name)
	for _, segment := range requested {
		if !utf8.ValidString(segment) || strings.ContainsAny(segment, `\:`) || strings.TrimRight(segment, ". ") != segment || !filepath.IsLocal(segment) {
			return nil, fs.ErrPermission
		}
	}
	var root string
	var scope []string
	if drive, ok := filesystem.(*DriveFs); ok {
		if drive.globalRoot() {
			if len(requested) == 0 {
				if !directory {
					return nil, fs.ErrNotExist
				}
				return openSubtitleComputerRoot()
			}
			if !IsDriveSegment(requested[0]) || strings.ToUpper(requested[0]) != requested[0] {
				return nil, fs.ErrNotExist
			}
			root, requested = requested[0]+`:\`, requested[1:]
		} else {
			scope = subtitleWindowsSegments(drive.scope)
			if len(scope) == 0 || !IsDriveSegment(scope[0]) {
				return nil, fs.ErrPermission
			}
			root, scope = strings.ToUpper(scope[0])+`:\`, scope[1:]
		}
	} else {
		base, relative, err := subtitleBasePath(filesystem, serverRoot)
		if err != nil {
			return nil, err
		}
		root = base
		if relative != "." {
			scope = strings.Split(relative, "/")
		}
	}
	return openSubtitleWindows(root, scope, requested, directory, readData)
}

func subtitleWindowsSegments(name string) []string {
	if name == "/" {
		return nil
	}
	return strings.Split(strings.TrimPrefix(name, "/"), "/")
}

const subtitleWindowsShare = windows.FILE_SHARE_READ | windows.FILE_SHARE_WRITE | windows.FILE_SHARE_DELETE

func openSubtitleWindows(root string, scope, requested []string, directory, readData bool) (*SubtitleResource, error) {
	segments := append(scope, requested...)
	if len(segments) == 0 && !directory {
		return nil, fs.ErrNotExist
	}
	rootName, err := windows.UTF16PtrFromString(root)
	if err != nil {
		return nil, err
	}
	access := uint32(windows.FILE_TRAVERSE | windows.FILE_READ_ATTRIBUTES | windows.SYNCHRONIZE)
	if len(segments) == 0 && readData {
		access |= windows.FILE_LIST_DIRECTORY
	}
	handle, err := windows.CreateFile(rootName, access, subtitleWindowsShare, nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS, 0)
	if err != nil {
		return nil, err
	}
	defer func() { _ = windows.CloseHandle(handle) }()
	canonical, err := subtitleWindowsFinalPath(handle)
	if err != nil {
		return nil, err
	}
	for i, segment := range segments {
		if !filepath.IsLocal(segment) || strings.ContainsAny(segment, `\/:`) {
			return nil, fs.ErrPermission
		}
		last := i == len(segments)-1
		isDir := !last || directory
		access = windows.FILE_READ_ATTRIBUTES | windows.SYNCHRONIZE
		if !last {
			access |= windows.FILE_TRAVERSE
		} else if readData {
			// FILE_READ_DATA and FILE_LIST_DIRECTORY use the same bit.
			access |= windows.FILE_READ_DATA
		}
		child, err := openSubtitleWindowsChild(handle, segment, access, isDir)
		if err != nil {
			return nil, err
		}
		_ = windows.CloseHandle(handle)
		handle = child
		actual, err := subtitleWindowsFinalPath(handle)
		if err != nil {
			return nil, err
		}
		parent := strings.TrimRight(canonical, `\`)
		actualParent, actualName := filepath.Split(actual)
		if strings.TrimRight(actualParent, `\`) != parent || i >= len(scope) && actualName != segment {
			return nil, fs.ErrPermission
		}
		canonical = actual
	}
	if !subtitleWindowsVolume(handle, canonical) {
		return nil, ErrSubtitleScopeUnavailable
	}
	file := os.NewFile(uintptr(handle), canonical)
	handle = windows.InvalidHandle
	resource := &SubtitleResource{File: file}
	if directory && readData {
		watch, err := newSubtitleWindowsWatch(file, canonical)
		if err != nil {
			_ = resource.Close()
			return nil, err
		}
		resource.watch = watch
	}
	return resource, nil
}

func subtitleWindowsVolume(handle windows.Handle, name string) bool {
	volume := strings.TrimPrefix(filepath.VolumeName(name), `\\?\`)
	if len(volume) != 2 || !IsDriveSegment(volume[:1]) || volume[1] != ':' {
		return false
	}
	root, err := windows.UTF16PtrFromString(volume + `\`)
	if err != nil {
		return false
	}
	// Restrict the first implementation to local volumes. Remote providers
	// can lose notifications when writers use other hosts or mount paths.
	switch windows.GetDriveType(root) {
	case windows.DRIVE_FIXED, windows.DRIVE_REMOVABLE, windows.DRIVE_RAMDISK:
	default:
		return false
	}
	var filesystem [32]uint16
	if err := windows.GetVolumeInformationByHandle(handle, nil, 0, nil, nil, nil, &filesystem[0], uint32(len(filesystem))); err != nil {
		return false
	}
	name = windows.UTF16ToString(filesystem[:])
	return name == "NTFS" || name == "ReFS"
}

func openSubtitleWindowsChild(parent windows.Handle, name string, access uint32, directory bool) (windows.Handle, error) {
	part, err := windows.NewNTUnicodeString(name)
	if err != nil {
		return windows.InvalidHandle, err
	}
	attrs := windows.OBJECT_ATTRIBUTES{RootDirectory: parent, ObjectName: part, Attributes: windows.OBJ_CASE_INSENSITIVE}
	attrs.Length = uint32(unsafe.Sizeof(attrs))
	options := uint32(windows.FILE_OPEN_REPARSE_POINT | windows.FILE_OPEN_FOR_BACKUP_INTENT | windows.FILE_SYNCHRONOUS_IO_NONALERT)
	if directory {
		options |= windows.FILE_DIRECTORY_FILE
	} else {
		options |= windows.FILE_NON_DIRECTORY_FILE
	}
	var handle windows.Handle
	err = windows.NtCreateFile(&handle, access, &attrs, &windows.IO_STATUS_BLOCK{}, nil, 0, subtitleWindowsShare, windows.FILE_OPEN, options, 0, 0)
	if status, ok := err.(windows.NTStatus); ok {
		err = status.Errno()
	}
	if err != nil {
		return windows.InvalidHandle, err
	}
	var info windows.ByHandleFileInformation
	err = windows.GetFileInformationByHandle(handle, &info)
	if err == nil && info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		err = fs.ErrPermission
	}
	if err != nil {
		_ = windows.CloseHandle(handle)
		return windows.InvalidHandle, err
	}
	return handle, nil
}

func subtitleWindowsFinalPath(handle windows.Handle) (string, error) {
	var initial [512]uint16
	buffer := initial[:]
	n, err := windows.GetFinalPathNameByHandle(handle, &buffer[0], uint32(len(buffer)), 0)
	if err == nil && n >= uint32(len(buffer)) && n < 32768 {
		buffer = make([]uint16, n+1)
		n, err = windows.GetFinalPathNameByHandle(handle, &buffer[0], uint32(len(buffer)), 0)
	}
	if err != nil || n >= uint32(len(buffer)) || n == 0 {
		return "", ErrSubtitleScopeUnavailable
	}
	return windows.UTF16ToString(buffer[:n]), nil
}

type subtitleWindowsID struct {
	volume uint64
	file   [16]byte
}

func subtitleWindowsFileID(handle windows.Handle) (subtitleWindowsID, error) {
	var id subtitleWindowsID
	if windows.GetFileInformationByHandleEx(handle, windows.FileIdInfo, (*byte)(unsafe.Pointer(&id)), uint32(unsafe.Sizeof(id))) != nil || id.file == [16]byte{} {
		return id, ErrSubtitleScopeUnavailable
	}
	return id, nil
}

type subtitleWindowsWatch struct {
	handle  windows.Handle
	overlap windows.Overlapped
	buffer  [1024]uint32
	pins    runtime.Pinner
}

func newSubtitleWindowsWatch(file *os.File, name string) (*subtitleWindowsWatch, error) {
	path, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateFile(path, windows.FILE_LIST_DIRECTORY|windows.FILE_READ_ATTRIBUTES, subtitleWindowsShare, nil,
		windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS|windows.FILE_FLAG_OPEN_REPARSE_POINT|windows.FILE_FLAG_OVERLAPPED, 0)
	if err != nil {
		return nil, ErrSubtitleScopeUnavailable
	}
	// The notification handle never supplies entries or file content. It
	// must identify the exact directory already opened through the scope.
	watched, watchedErr := subtitleWindowsFileID(handle)
	actual, actualErr := subtitleWindowsFileID(windows.Handle(file.Fd()))
	if watchedErr != nil || actualErr != nil || watched != actual {
		_ = windows.CloseHandle(handle)
		return nil, ErrSubtitleScopeUnavailable
	}
	event, err := windows.CreateEvent(nil, 1, 0, nil)
	if err != nil {
		_ = windows.CloseHandle(handle)
		return nil, ErrSubtitleScopeUnavailable
	}
	watch := &subtitleWindowsWatch{handle: handle, overlap: windows.Overlapped{HEvent: event}}
	watch.pins.Pin(&watch.overlap)
	watch.pins.Pin(&watch.buffer[0])
	const mask = windows.FILE_NOTIFY_CHANGE_FILE_NAME | windows.FILE_NOTIFY_CHANGE_DIR_NAME | windows.FILE_NOTIFY_CHANGE_ATTRIBUTES
	err = windows.ReadDirectoryChanges(handle, (*byte)(unsafe.Pointer(&watch.buffer[0])), uint32(unsafe.Sizeof(watch.buffer)), false, mask, nil, &watch.overlap, 0)
	if err != nil && !errors.Is(err, windows.ERROR_IO_PENDING) {
		watch.pins.Unpin()
		_ = windows.CloseHandle(event)
		_ = windows.CloseHandle(handle)
		return nil, ErrSubtitleScopeUnavailable
	}
	return watch, nil
}

func (w *subtitleWindowsWatch) changed() bool {
	var n uint32
	err := windows.GetOverlappedResult(w.handle, &w.overlap, &n, false)
	return !errors.Is(err, windows.ERROR_IO_INCOMPLETE)
}

func (w *subtitleWindowsWatch) close() {
	_ = windows.CancelIoEx(w.handle, &w.overlap)
	var n uint32
	_ = windows.GetOverlappedResult(w.handle, &w.overlap, &n, true)
	w.pins.Unpin()
	_ = windows.CloseHandle(w.overlap.HEvent)
	_ = windows.CloseHandle(w.handle)
}

func subtitleResourceStamp(file *os.File, info os.FileInfo) (string, error) {
	handle := windows.Handle(file.Fd())
	var basic struct {
		created, accessed, modified, changed int64
		attrs, padding                       uint32
	}
	id, err := subtitleWindowsFileID(handle)
	if windows.GetFileInformationByHandleEx(handle, windows.FileBasicInfo, (*byte)(unsafe.Pointer(&basic)), uint32(unsafe.Sizeof(basic))) != nil ||
		err != nil || basic.changed == 0 {
		return "", ErrSubtitleScopeUnavailable
	}
	if basic.attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 || !info.IsDir() && !info.Mode().IsRegular() {
		return "", fs.ErrPermission
	}
	return fmt.Sprintf("%d:%x:%d:%d:%d:%d:%d", id.volume, id.file, basic.attrs, info.Size(), basic.created, basic.modified, basic.changed), nil
}

func openSubtitleComputerRoot() (*SubtitleResource, error) {
	drives, err := ListLogicalDrives()
	if err != nil {
		return nil, err
	}
	result := &SubtitleResource{virtualStamp: "computer"}
	for _, drive := range drives {
		resource, err := openSubtitleWindows(drive.Root, nil, nil, true, false)
		if err != nil {
			continue
		}
		info, stamp, err := resource.State()
		_ = resource.Close()
		if errors.Is(err, ErrSubtitleScopeUnavailable) {
			continue
		}
		if err != nil {
			return nil, err
		}
		result.virtual = append(result.virtual, fs.FileInfoToDirEntry(&driveRootInfo{FileInfo: info, name: drive.Letter}))
		// Volume identity changes also invalidate cursors across drive remounts.
		result.virtualStamp += "|" + drive.Letter + ":" + stamp
	}
	if len(result.virtual) == 0 {
		return nil, ErrSubtitleScopeUnavailable
	}
	return result, nil
}
