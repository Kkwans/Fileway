//go:build windows

package files

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"
	"unsafe"

	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
)

type scopedDirectoryState struct{ eof bool }

const scopedWindowsMaxNameEntries = 16384
const scopedWindowsNameOpened = 8

func duplicateScopedFile(file *os.File) (*os.File, error) {
	connection, err := file.SyscallConn()
	if err != nil {
		return nil, err
	}
	var handle windows.Handle
	var duplicateErr error
	process := windows.CurrentProcess()
	if err := connection.Control(func(raw uintptr) {
		duplicateErr = windows.DuplicateHandle(process, windows.Handle(raw), process, &handle, 0, false, windows.DUPLICATE_SAME_ACCESS)
	}); err != nil {
		return nil, err
	}
	if duplicateErr != nil {
		return nil, duplicateErr
	}
	return os.NewFile(uintptr(handle), "scoped-handle"), nil
}

// Open the held directory itself through an empty relative object name. This
// creates an independent directory cursor without resolving a mutable path or
// using backup intent to bypass normal access checks. ReOpenFile returned
// ERROR_ACCESS_DENIED for directories on the tested Windows/NTFS host, even
// when a regular-file control and the same directory's relative open worked.
func scopedWindowsReopenDirectory(handle windows.Handle) (windows.Handle, error) {
	name, err := windows.NewNTUnicodeString("")
	if err != nil {
		return windows.InvalidHandle, err
	}
	attrs := windows.OBJECT_ATTRIBUTES{RootDirectory: handle, ObjectName: name, Attributes: windows.OBJ_CASE_INSENSITIVE}
	attrs.Length = uint32(unsafe.Sizeof(attrs))
	access := uint32(windows.FILE_READ_ATTRIBUTES | windows.FILE_TRAVERSE | windows.FILE_LIST_DIRECTORY | windows.SYNCHRONIZE)
	options := uint32(windows.FILE_DIRECTORY_FILE | windows.FILE_OPEN_REPARSE_POINT | windows.FILE_SYNCHRONOUS_IO_NONALERT)
	var opened windows.Handle
	err = windows.NtCreateFile(&opened, access, &attrs, &windows.IO_STATUS_BLOCK{}, nil, 0, subtitleWindowsShare, windows.FILE_OPEN, options, 0, 0)
	if status, ok := err.(windows.NTStatus); ok {
		err = status.Errno()
	}
	if err != nil {
		return windows.InvalidHandle, err
	}
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(opened, &info); err != nil || info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 || info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY == 0 {
		_ = windows.CloseHandle(opened)
		if err != nil {
			return windows.InvalidHandle, err
		}
		return windows.InvalidHandle, fs.ErrPermission
	}
	// The empty-name open is already anchored to the held handle. Where the
	// provider exposes IDs, reject any contradictory evidence; missing IDs stay
	// explicitly weak rather than being fabricated from a path or timestamp.
	before, beforeErr := subtitleWindowsFileID(handle)
	after, afterErr := subtitleWindowsFileID(opened)
	if beforeErr == nil && afterErr == nil && before.volume != 0 && after.volume != 0 && before != after {
		_ = windows.CloseHandle(opened)
		return windows.InvalidHandle, ErrScopedNameUnverified
	}
	return opened, nil
}

func scopedWindowsFinalPath(handle windows.Handle, flags uint32) (string, error) {
	var initial [512]uint16
	buffer := initial[:]
	n, err := windows.GetFinalPathNameByHandle(handle, &buffer[0], uint32(len(buffer)), flags)
	if err == nil && n >= uint32(len(buffer)) && n < 32768 {
		buffer = make([]uint16, n+1)
		n, err = windows.GetFinalPathNameByHandle(handle, &buffer[0], uint32(len(buffer)), flags)
	}
	if err != nil || n == 0 || n >= uint32(len(buffer)) {
		return "", ErrScopedNameUnverified
	}
	return windows.UTF16ToString(buffer[:n]), nil
}

func scopedWindowsValidSegment(segment string) bool {
	return segment != "" && utf8.ValidString(segment) && !strings.ContainsAny(segment, `\/:`) && strings.TrimRight(segment, ". ") == segment && filepath.IsLocal(segment)
}

// The fallback lists names using a separate handle to the actual parent, then
// compares a second opened child with the first by handle ID. FILE_NAME_OPENED
// alone merely echoes an alias and cannot establish the actual directory name.
func scopedWindowsVerifyName(ctx context.Context, parent, child windows.Handle, segment string, directory bool) error {
	handle, err := scopedWindowsReopenDirectory(parent)
	if err != nil {
		return ErrScopedNameUnverified
	}
	file := os.NewFile(uintptr(handle), "scoped-directory")
	defer func() { _ = file.Close() }()
	found := false
	count := 0
	for count < scopedWindowsMaxNameEntries {
		if err := ctx.Err(); err != nil {
			return err
		}
		names, readErr := file.Readdirnames(min(128, scopedWindowsMaxNameEntries-count))
		count += len(names)
		for _, name := range names {
			if name == segment {
				found = true
				break
			}
		}
		if found {
			break
		}
		if errors.Is(readErr, io.EOF) {
			return fs.ErrPermission
		}
		if readErr != nil {
			return ErrScopedNameUnverified
		}
		if len(names) == 0 {
			return ErrScopedNameUnverified
		}
	}
	if !found {
		return ErrScopedNameUnverified
	}
	actual, err := subtitleWindowsFileID(child)
	if err != nil || actual.volume == 0 {
		return ErrScopedNameUnverified
	}
	access := uint32(windows.FILE_READ_ATTRIBUTES | windows.SYNCHRONIZE)
	if directory {
		access |= windows.FILE_TRAVERSE
	}
	check, err := openSubtitleWindowsChild(parent, segment, access, directory)
	if err != nil {
		return ErrScopedNameUnverified
	}
	defer func() { _ = windows.CloseHandle(check) }()
	confirmed, err := subtitleWindowsFileID(check)
	if err != nil || confirmed.volume == 0 || actual != confirmed {
		return ErrScopedNameUnverified
	}
	return ctx.Err()
}

func scopedWindowsOpenSegments(ctx context.Context, handle windows.Handle, segments []string, strictFrom int, directory, readDirectory bool) (windows.Handle, error) {
	return scopedWindowsOpenSegmentsWithQuery(ctx, handle, segments, strictFrom, directory, readDirectory, scopedWindowsFinalPath)
}

func scopedWindowsOpenSegmentsWithQuery(ctx context.Context, handle windows.Handle, segments []string, strictFrom int, directory, readDirectory bool, query func(windows.Handle, uint32) (string, error)) (windows.Handle, error) {
	for i, segment := range segments {
		if err := ctx.Err(); err != nil {
			_ = windows.CloseHandle(handle)
			return windows.InvalidHandle, err
		}
		if !scopedWindowsValidSegment(segment) {
			_ = windows.CloseHandle(handle)
			return windows.InvalidHandle, fs.ErrPermission
		}
		last := i == len(segments)-1
		isDir := !last || directory
		access := uint32(windows.FILE_READ_ATTRIBUTES | windows.SYNCHRONIZE)
		if isDir {
			access |= windows.FILE_TRAVERSE
		}
		if last && !isDir {
			access |= windows.FILE_READ_DATA
		}
		if last && isDir && readDirectory {
			access |= windows.FILE_LIST_DIRECTORY
		}
		child, err := openSubtitleWindowsChild(handle, segment, access, isDir)
		if err != nil {
			_ = windows.CloseHandle(handle)
			return windows.InvalidHandle, err
		}
		parentName, parentErr := query(handle, 0)
		childName, childErr := query(child, 0)
		if parentErr == nil && childErr == nil {
			actualParent, actualName := filepath.Split(childName)
			if strings.TrimRight(actualParent, `\`) != strings.TrimRight(parentName, `\`) || i >= strictFrom && actualName != segment {
				err = fs.ErrPermission
			}
		} else if i >= strictFrom {
			// FILE_NAME_OPENED is only an additional consistency check. The
			// parent enumeration and same-object comparison enforce exact names.
			if opened, openedErr := query(child, scopedWindowsNameOpened); openedErr == nil {
				_, name := filepath.Split(opened)
				if name != segment {
					err = fs.ErrPermission
				}
			}
			if err == nil {
				err = scopedWindowsVerifyName(ctx, handle, child, segment, isDir)
			}
		}
		_ = windows.CloseHandle(handle)
		if err != nil {
			_ = windows.CloseHandle(child)
			return windows.InvalidHandle, err
		}
		handle = child
	}
	return handle, nil
}

func openScopedRootNative(ctx context.Context, filesystem afero.Fs, serverRoot, locator string) (*os.File, error) {
	requested := subtitleWindowsSegments(locator)
	var volume string
	var scope []string
	if drive, ok := filesystem.(*DriveFs); ok {
		if drive.globalRoot() {
			if len(requested) == 0 {
				return nil, ErrScopedMountBoundary
			}
			if !IsDriveSegment(requested[0]) || strings.ToUpper(requested[0]) != requested[0] {
				return nil, fs.ErrPermission
			}
			volume, requested = requested[0]+":", requested[1:]
		} else {
			scope = subtitleWindowsSegments(drive.scope)
			if len(scope) == 0 || !IsDriveSegment(scope[0]) {
				return nil, fs.ErrPermission
			}
			volume, scope = strings.ToUpper(scope[0])+":", scope[1:]
		}
	} else {
		base, relative, err := scopedBasePath(filesystem, serverRoot)
		if err != nil {
			return nil, err
		}
		volume = filepath.VolumeName(base)
		if volume == "" || !filepath.IsAbs(base) {
			return nil, ErrScopedUnavailable
		}
		rest := strings.TrimPrefix(base[len(volume):], `\`)
		if rest != "" {
			scope = append(scope, strings.Split(rest, `\`)...)
		}
		if relative != "." {
			scope = append(scope, strings.Split(relative, "/")...)
		}
	}
	if strings.HasPrefix(volume, `\\.\`) {
		return nil, fs.ErrPermission
	}
	rootName, err := windows.UTF16PtrFromString(volume + `\`)
	if err != nil {
		return nil, err
	}
	access := uint32(windows.FILE_TRAVERSE | windows.FILE_READ_ATTRIBUTES | windows.SYNCHRONIZE)
	handle, err := windows.CreateFile(rootName, access, subtitleWindowsShare, nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS|windows.FILE_FLAG_OPEN_REPARSE_POINT, 0)
	if err != nil {
		return nil, err
	}
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(handle, &info); err != nil || info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 || info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY == 0 {
		_ = windows.CloseHandle(handle)
		if err != nil {
			return nil, err
		}
		return nil, fs.ErrPermission
	}
	segments := append(append([]string(nil), scope...), requested...)
	// The root is for traversal, not enumeration; acquire listing access only
	// when opening a ScopedDirectory or checking an unavailable normalized name.
	handle, err = scopedWindowsOpenSegments(ctx, handle, segments, len(scope), true, false)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(handle), "scoped-root"), nil
}

func openScopedChildNative(ctx context.Context, root *os.File, relative string, directory bool) (*os.File, error) {
	if !utf8.ValidString(relative) || strings.ContainsRune(relative, '\\') {
		return nil, fs.ErrPermission
	}
	if relative == "" {
		if !directory {
			return nil, fs.ErrInvalid
		}
		handle, err := scopedWindowsReopenDirectory(windows.Handle(root.Fd()))
		if err != nil {
			return nil, err
		}
		if err := ctx.Err(); err != nil {
			_ = windows.CloseHandle(handle)
			return nil, err
		}
		return os.NewFile(uintptr(handle), "scoped-directory"), nil
	}
	process := windows.CurrentProcess()
	var handle windows.Handle
	if err := windows.DuplicateHandle(process, windows.Handle(root.Fd()), process, &handle, 0, false, windows.DUPLICATE_SAME_ACCESS); err != nil {
		return nil, err
	}
	handle, err := scopedWindowsOpenSegments(ctx, handle, strings.Split(relative, "/"), 0, directory, directory)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(handle), "scoped-child"), nil
}

func scopedCapabilities(file *os.File) SourceCapabilities {
	capabilities := SourceCapabilities{SafeHandleRead: true, Enumeration: "bounded_live", Notifications: "none", ObjectIdentity: "path_observed", FreshAbsence: "unknown", ChangeEvidence: "observed_only"}
	handle := windows.Handle(file.Fd())
	id, err := subtitleWindowsFileID(handle)
	if err == nil && id.volume != 0 {
		capabilities.ObjectIdentity = "mount_scoped"
	}
	// The local-only check grades evidence and never gates a safe open. A2 must
	// also fence mount/domain and file revisions before assuming continuity.
	var basic struct {
		created, accessed, modified, changed int64
		attrs, padding                       uint32
	}
	name, nameErr := scopedWindowsFinalPath(handle, 0)
	if err == nil && id.volume != 0 && nameErr == nil && subtitleWindowsVolume(handle, name) && windows.GetFileInformationByHandleEx(handle, windows.FileBasicInfo, (*byte)(unsafe.Pointer(&basic)), uint32(unsafe.Sizeof(basic))) == nil && basic.created != 0 && basic.changed != 0 {
		capabilities.ObjectIdentity = "native_generation"
		capabilities.ChangeEvidence = "coherent_native"
	}
	return capabilities
}

func scopedFileState(ctx context.Context, file *os.File, freshness ScopedFreshness) (ScopedState, error) {
	if err := ctx.Err(); err != nil {
		return ScopedState{}, err
	}
	if freshness == ScopedForceSync {
		return ScopedState{}, ErrScopedUnavailable
	}
	if freshness != ScopedObserved {
		return ScopedState{}, fs.ErrInvalid
	}
	info, err := file.Stat()
	if err != nil {
		return ScopedState{}, err
	}
	if IsPrivateMediaFile(info) {
		return ScopedState{}, fs.ErrPermission
	}
	result := ScopedState{Info: info, Size: info.Size(), Mount: ScopedMountObservation{IDKind: "unknown"}}
	handle := windows.Handle(file.Fd())
	id, idErr := subtitleWindowsFileID(handle)
	if idErr == nil && id.volume != 0 {
		result.ObjectEvidence = []byte(fmt.Sprintf("windows/observed/v1:%d:%x", id.volume, id.file))
	}
	var basic struct {
		created, accessed, modified, changed int64
		attrs, padding                       uint32
	}
	if windows.GetFileInformationByHandleEx(handle, windows.FileBasicInfo, (*byte)(unsafe.Pointer(&basic)), uint32(unsafe.Sizeof(basic))) == nil && basic.changed != 0 {
		result.ChangeStamp = []byte(fmt.Sprintf("windows/observed/v1:%d:%d:%d:%d:%d", info.Size(), basic.created, basic.modified, basic.changed, basic.attrs))
	}
	if err := ctx.Err(); err != nil {
		return ScopedState{}, err
	}
	return result, nil
}

func readScopedDirectory(ctx context.Context, directory *ScopedDirectory, limit int) ([]ScopedEntry, bool, error) {
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	if directory.platform.eof {
		return nil, true, nil
	}
	entries, err := directory.file.ReadDir(limit)
	eof := errors.Is(err, io.EOF)
	if err != nil && !eof {
		return nil, false, err
	}
	if eof {
		directory.platform.eof = true
	}
	result := make([]ScopedEntry, 0, len(entries))
	for _, entry := range entries {
		result = append(result, ScopedEntry{Name: []byte(entry.Name()), Type: entry.Type()})
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	return result, eof, nil
}
