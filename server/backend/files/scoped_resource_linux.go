//go:build linux

package files

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"strconv"
	"strings"

	"github.com/spf13/afero"
	"golang.org/x/sys/unix"
)

type scopedDirectoryState struct {
	buffer     [32768]byte
	start, end int
	eof        bool
}

var scopedLinuxResolve = uint64(unix.RESOLVE_BENEATH | unix.RESOLVE_NO_SYMLINKS | unix.RESOLVE_NO_MAGICLINKS)

func duplicateScopedFile(file *os.File) (*os.File, error) {
	connection, err := file.SyscallConn()
	if err != nil {
		return nil, err
	}
	var fd int
	var duplicateErr error
	if err := connection.Control(func(raw uintptr) { fd, duplicateErr = unix.FcntlInt(raw, unix.F_DUPFD_CLOEXEC, 0) }); err != nil {
		return nil, err
	}
	if duplicateErr != nil {
		return nil, duplicateErr
	}
	return os.NewFile(uintptr(fd), "/proc/self/fd/"+strconv.Itoa(fd)), nil
}

func openScopedRootNative(ctx context.Context, filesystem afero.Fs, serverRoot, locator string) (*os.File, error) {
	root, scope, err := scopedBasePath(filesystem, serverRoot)
	if err != nil {
		return nil, err
	}
	var proc unix.Statfs_t
	if unix.Statfs("/proc/self/fd", &proc) != nil || proc.Type != unix.PROC_SUPER_MAGIC {
		return nil, ErrScopedUnavailable
	}
	anchor, err := unix.Open("/", unix.O_PATH|unix.O_DIRECTORY|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	defer func() { _ = unix.Close(anchor) }()
	relative := path.Join(strings.TrimPrefix(root, "/"), scope, strings.TrimPrefix(locator, "/"))
	if relative == "" {
		relative = "."
	}
	how := unix.OpenHow{Flags: unix.O_PATH | unix.O_DIRECTORY | unix.O_CLOEXEC, Resolve: scopedLinuxResolve}
	fd, err := unix.Openat2(anchor, relative, &how)
	if errors.Is(err, unix.ELOOP) || errors.Is(err, unix.EXDEV) {
		return nil, fs.ErrPermission
	}
	if errors.Is(err, unix.ENOSYS) {
		return nil, ErrScopedUnavailable
	}
	if err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		_ = unix.Close(fd)
		return nil, err
	}
	return os.NewFile(uintptr(fd), "/proc/self/fd/"+strconv.Itoa(fd)), nil
}

func openScopedChildNative(ctx context.Context, root *os.File, relative string, directory bool) (*os.File, error) {
	if relative == "" {
		relative = "."
	}
	how := unix.OpenHow{Flags: unix.O_PATH | unix.O_CLOEXEC, Resolve: scopedLinuxResolve | unix.RESOLVE_NO_XDEV}
	if directory {
		how.Flags = unix.O_RDONLY | unix.O_DIRECTORY | unix.O_CLOEXEC
	}
	fd, err := unix.Openat2(int(root.Fd()), relative, &how)
	if errors.Is(err, unix.EXDEV) {
		return nil, ErrScopedMountBoundary
	}
	if errors.Is(err, unix.ELOOP) {
		return nil, fs.ErrPermission
	}
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fd), "/proc/self/fd/"+strconv.Itoa(fd))
	info, err := file.Stat()
	if err != nil || directory && !info.IsDir() || !directory && !info.Mode().IsRegular() || IsPrivateMediaFile(info) {
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		return nil, fs.ErrPermission
	}
	if !directory {
		// The generated procfs path refers to the held O_PATH descriptor, not a
		// user pathname. Reopening cannot follow a swapped directory entry.
		opened, err := os.Open("/proc/self/fd/" + strconv.Itoa(fd))
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		current, err := opened.Stat()
		if err != nil || !os.SameFile(info, current) || IsPrivateMediaFile(current) {
			_ = opened.Close()
			return nil, fs.ErrPermission
		}
		file = opened
	}
	if err := ctx.Err(); err != nil {
		_ = file.Close()
		return nil, err
	}
	return file, nil
}

func scopedLinuxStatx(file *os.File, freshness ScopedFreshness) (unix.Statx_t, ScopedMountObservation, error) {
	var info unix.Statx_t
	var mount ScopedMountObservation
	flags := unix.AT_EMPTY_PATH
	switch freshness {
	case ScopedObserved:
		flags |= unix.AT_STATX_DONT_SYNC
	case ScopedForceSync:
		flags |= unix.AT_STATX_FORCE_SYNC
	default:
		return info, mount, fs.ErrInvalid
	}
	want := uint32(unix.STATX_BASIC_STATS | unix.STATX_BTIME | unix.STATX_MNT_ID_UNIQUE)
	err := unix.Statx(int(file.Fd()), "", flags, int(want), &info)
	if err == nil && info.Mask&unix.STATX_MNT_ID_UNIQUE != 0 && info.Mnt_id != 0 {
		mount.ID, mount.IDKind = info.Mnt_id, "unique_since_boot"
	} else {
		info = unix.Statx_t{}
		want = unix.STATX_BASIC_STATS | unix.STATX_BTIME | unix.STATX_MNT_ID
		err = unix.Statx(int(file.Fd()), "", flags, int(want), &info)
		if err == nil && info.Mask&unix.STATX_MNT_ID != 0 && info.Mnt_id != 0 {
			mount.ID, mount.IDKind = info.Mnt_id, "namespace_reusable"
		}
	}
	if err != nil {
		return info, mount, err
	}
	if mount.IDKind == "" {
		mount.IDKind = "unknown"
	}
	if mount.ID != 0 {
		mount.Namespace, _ = os.Readlink("/proc/self/ns/mnt")
		boot, err := os.ReadFile("/proc/sys/kernel/random/boot_id")
		if err == nil && len(boot) <= 128 {
			mount.BootID = strings.TrimSpace(string(boot))
		}
	}
	return info, mount, nil
}

func scopedCapabilities(file *os.File) SourceCapabilities {
	capabilities := SourceCapabilities{SafeHandleRead: true, Enumeration: "bounded_live", Notifications: "none", ObjectIdentity: "path_observed", FreshAbsence: "unknown", ChangeEvidence: "observed_only"}
	info, mount, err := scopedLinuxStatx(file, ScopedObserved)
	if err == nil && mount.ID != 0 && info.Mask&unix.STATX_INO != 0 && info.Ino != 0 {
		capabilities.ObjectIdentity = "mount_scoped"
	}
	// Local filesystem classification is only an evidence grade, never a
	// prerequisite for opening. A2 must still prove domain continuity; neither
	// inode/btime nor a mount ID is a content hash or a cross-remount proof.
	var filesystem unix.Statfs_t
	const required = unix.STATX_INO | unix.STATX_BTIME | unix.STATX_SIZE | unix.STATX_MTIME | unix.STATX_CTIME
	if err == nil && mount.IDKind == "unique_since_boot" && mount.Namespace != "" && mount.BootID != "" && info.Mask&required == required && info.Ino != 0 && (info.Btime.Sec != 0 || info.Btime.Nsec != 0) && unix.Fstatfs(int(file.Fd()), &filesystem) == nil && subtitleLinuxFilesystem(int64(filesystem.Type)) {
		capabilities.ObjectIdentity = "native_generation"
		capabilities.ChangeEvidence = "coherent_native"
	}
	return capabilities
}

func scopedFileState(ctx context.Context, file *os.File, freshness ScopedFreshness) (ScopedState, error) {
	if err := ctx.Err(); err != nil {
		return ScopedState{}, err
	}
	if freshness != ScopedObserved && freshness != ScopedForceSync {
		return ScopedState{}, fs.ErrInvalid
	}
	stat, err := file.Stat()
	if err != nil {
		return ScopedState{}, err
	}
	if IsPrivateMediaFile(stat) {
		return ScopedState{}, fs.ErrPermission
	}
	result := ScopedState{Info: stat, Size: stat.Size(), Mount: ScopedMountObservation{IDKind: "unknown"}}
	info, mount, statxErr := scopedLinuxStatx(file, freshness)
	if statxErr == nil {
		result.Mount = mount
		if info.Mask&unix.STATX_INO != 0 && info.Ino != 0 {
			result.ObjectEvidence = []byte(fmt.Sprintf("linux/observed/v1:%d:%d:%d:%d:%d:%d", mount.ID, info.Dev_major, info.Dev_minor, info.Ino, info.Btime.Sec, info.Btime.Nsec))
		}
		if info.Mask&(unix.STATX_SIZE|unix.STATX_MTIME|unix.STATX_CTIME) == unix.STATX_SIZE|unix.STATX_MTIME|unix.STATX_CTIME {
			result.ChangeStamp = []byte(fmt.Sprintf("linux/observed/v1:%d:%d:%d:%d:%d", info.Size, info.Mtime.Sec, info.Mtime.Nsec, info.Ctime.Sec, info.Ctime.Nsec))
		}
	} else if freshness == ScopedForceSync {
		return ScopedState{}, statxErr
	}
	if err := ctx.Err(); err != nil {
		return ScopedState{}, err
	}
	return result, nil
}

func readScopedDirectory(ctx context.Context, directory *ScopedDirectory, limit int) ([]ScopedEntry, bool, error) {
	held, err := duplicateScopedFile(directory.file)
	if err != nil {
		return nil, false, err
	}
	defer func() { _ = held.Close() }()
	state := &directory.platform
	if state.eof {
		return nil, true, nil
	}
	entries := make([]ScopedEntry, 0, limit)
	for len(entries) < limit {
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
		if state.start == state.end {
			n, err := unix.ReadDirent(int(held.Fd()), state.buffer[:])
			if err != nil {
				return nil, false, err
			}
			if n == 0 {
				state.eof = true
				return entries, true, nil
			}
			state.start, state.end = 0, n
		}
		consumed, _, names := unix.ParseDirent(state.buffer[state.start:state.end], limit-len(entries), nil)
		if consumed <= 0 || consumed > state.end-state.start {
			return nil, false, ErrScopedDirectoryChanged
		}
		state.start += consumed
		for _, name := range names {
			var stat unix.Stat_t
			if err := unix.Fstatat(int(held.Fd()), name, &stat, unix.AT_SYMLINK_NOFOLLOW); err != nil {
				if errors.Is(err, unix.ENOENT) {
					return nil, false, ErrScopedDirectoryChanged
				}
				return nil, false, err
			}
			var kind fs.FileMode
			switch stat.Mode & unix.S_IFMT {
			case unix.S_IFDIR:
				kind = fs.ModeDir
			case unix.S_IFLNK:
				kind = fs.ModeSymlink
			case unix.S_IFIFO:
				kind = fs.ModeNamedPipe
			case unix.S_IFSOCK:
				kind = fs.ModeSocket
			case unix.S_IFCHR:
				kind = fs.ModeDevice | fs.ModeCharDevice
			case unix.S_IFBLK:
				kind = fs.ModeDevice
			case unix.S_IFREG:
			default:
				kind = fs.ModeIrregular
			}
			entries = append(entries, ScopedEntry{Name: []byte(name), Type: kind})
		}
	}
	return entries, false, nil
}
