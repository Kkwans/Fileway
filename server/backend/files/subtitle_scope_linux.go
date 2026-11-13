//go:build linux

package files

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"strconv"
	"sync"
	"syscall"

	"github.com/spf13/afero"
	"golang.org/x/sys/unix"
)

var subtitleOpenHow = unix.OpenHow{Flags: unix.O_PATH | unix.O_CLOEXEC, Resolve: unix.RESOLVE_BENEATH | unix.RESOLVE_NO_SYMLINKS}

var subtitleLinuxAvailable = sync.OnceValue(func() bool {
	var proc unix.Statfs_t
	if unix.Statfs("/proc/self/fd", &proc) != nil || proc.Type != unix.PROC_SUPER_MAGIC {
		return false
	}
	fd, err := unix.Openat2(unix.AT_FDCWD, ".", &subtitleOpenHow)
	if err != nil {
		return false
	}
	_ = unix.Close(fd)
	watch, err := unix.InotifyInit1(unix.IN_CLOEXEC | unix.IN_NONBLOCK)
	if err != nil {
		return false
	}
	_ = unix.Close(watch)
	return true
})

func SubtitleScopeAvailable() bool { return subtitleLinuxAvailable() }

func openSubtitleResource(filesystem afero.Fs, serverRoot, name string, directory, readData bool) (*SubtitleResource, error) {
	if !SubtitleScopeAvailable() {
		return nil, ErrSubtitleScopeUnavailable
	}
	root, scope, err := subtitleBasePath(filesystem, serverRoot)
	if err != nil {
		return nil, err
	}
	rootFD, err := unix.Open(root, unix.O_PATH|unix.O_DIRECTORY|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	defer func() { _ = unix.Close(rootFD) }()
	how := subtitleOpenHow
	if directory && readData {
		how.Flags = unix.O_RDONLY | unix.O_DIRECTORY | unix.O_CLOEXEC
	}
	fd, err := unix.Openat2(rootFD, path.Join(scope, name), &how)
	if err != nil {
		if errors.Is(err, unix.ELOOP) || errors.Is(err, unix.EXDEV) {
			return nil, fs.ErrPermission
		}
		return nil, err
	}
	var filesystemState unix.Statfs_t
	if unix.Fstatfs(fd, &filesystemState) != nil || !subtitleLinuxFilesystem(int64(filesystemState.Type)) {
		_ = unix.Close(fd)
		return nil, ErrSubtitleScopeUnavailable
	}
	// os.File.ReadDir may resolve unknown dirent types with lstat relative
	// to File.Name. Keep that lookup tied to the held directory descriptor.
	file := os.NewFile(uintptr(fd), "/proc/self/fd/"+strconv.Itoa(fd))
	info, err := file.Stat()
	if err != nil || directory && !info.IsDir() || !directory && !info.Mode().IsRegular() {
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		return nil, fs.ErrNotExist
	}
	if !directory && readData {
		// Reopen the held regular object itself, avoiding device/FIFO opens or
		// a second lookup through a mutable user-controlled pathname.
		opened, err := os.Open("/proc/self/fd/" + strconv.Itoa(fd))
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		current, err := opened.Stat()
		if err != nil || !os.SameFile(info, current) {
			_ = opened.Close()
			return nil, fs.ErrPermission
		}
		file = opened
	}
	resource := &SubtitleResource{File: file}
	if directory && readData {
		watch, err := newSubtitleLinuxWatch(fd)
		if err != nil {
			_ = resource.Close()
			return nil, err
		}
		resource.watch = watch
	}
	return resource, nil
}

func subtitleLinuxFilesystem(kind int64) bool {
	// Remote writers on network filesystems and updates through an overlay's
	// lower/upper mounts do not reliably reach this directory's inotify queue.
	switch kind {
	case unix.EXT4_SUPER_MAGIC, unix.BTRFS_SUPER_MAGIC, unix.XFS_SUPER_MAGIC, unix.TMPFS_MAGIC:
		return true
	default:
		return false
	}
}

type subtitleLinuxWatch struct {
	fd    int
	dirty bool
}

func newSubtitleLinuxWatch(directoryFD int) (*subtitleLinuxWatch, error) {
	fd, err := unix.InotifyInit1(unix.IN_CLOEXEC | unix.IN_NONBLOCK)
	if err != nil {
		return nil, ErrSubtitleScopeUnavailable
	}
	const mask = unix.IN_ONLYDIR | unix.IN_CREATE | unix.IN_DELETE | unix.IN_MOVED_FROM | unix.IN_MOVED_TO |
		unix.IN_DELETE_SELF | unix.IN_MOVE_SELF | unix.IN_ATTRIB | unix.IN_UNMOUNT
	if _, err := unix.InotifyAddWatch(fd, "/proc/self/fd/"+strconv.Itoa(directoryFD), mask); err != nil {
		_ = unix.Close(fd)
		return nil, ErrSubtitleScopeUnavailable
	}
	return &subtitleLinuxWatch{fd: fd}, nil
}

func (w *subtitleLinuxWatch) changed() bool {
	if !w.dirty {
		var event [4096]byte
		n, err := unix.Read(w.fd, event[:])
		// Queue overflow, watch loss, and unexpected I/O errors all invalidate
		// the cursor. Only EAGAIN positively means the queue is empty.
		w.dirty = n > 0 || !errors.Is(err, unix.EAGAIN)
	}
	return w.dirty
}

func (w *subtitleLinuxWatch) close() { _ = unix.Close(w.fd) }

func subtitleResourceStamp(_ *os.File, info os.FileInfo) (string, error) {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Ino == 0 {
		return "", ErrSubtitleScopeUnavailable
	}
	return fmt.Sprintf("%d:%d:%d:%d:%d:%d:%d:%d", stat.Dev, stat.Ino, stat.Mode, stat.Size,
		stat.Mtim.Sec, stat.Mtim.Nsec, stat.Ctim.Sec, stat.Ctim.Nsec), nil
}
