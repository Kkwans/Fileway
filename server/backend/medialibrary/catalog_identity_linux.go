//go:build linux

package medialibrary

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"golang.org/x/sys/unix"
)

func linuxIdentity(info os.FileInfo) (string, uint64, error) {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.Mode().IsRegular() {
		return "", 0, errors.New("catalog path is not a regular file")
	}
	return fmt.Sprintf("%x:%x", uint64(stat.Dev), stat.Ino), uint64(stat.Nlink), nil
}

func inspectCatalogPath(path string) (string, string, uint64, error) {
	canonical, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", "", 0, err
	}
	canonical, err = filepath.Abs(canonical)
	if err != nil {
		return "", "", 0, err
	}
	info, err := os.Stat(canonical)
	if err != nil {
		return "", "", 0, err
	}
	key, links, err := linuxIdentity(info)
	return canonical, key, links, err
}

func inspectCatalogHandle(file *os.File) (string, uint64, error) {
	info, err := file.Stat()
	if err != nil {
		return "", 0, err
	}
	return linuxIdentity(info)
}

// A file-only bind mount can expose the same inode with a different sibling
// lock file. Inspect mountinfo and reject such aliases; directory mounts and
// media roots remain supported. Every process performs this before locking.
func rejectFileMountAlias(path, key string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return ErrCoordinatorUnavailable
	}
	device := fmt.Sprintf("%d:%d", unix.Major(uint64(stat.Dev)), unix.Minor(uint64(stat.Dev)))
	f, err := os.Open("/proc/self/mountinfo")
	if err != nil {
		return fmt.Errorf("inspect catalog mounts: %w", err)
	}
	defer func() { _ = f.Close() }()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 6 {
			return errors.New("invalid mountinfo entry")
		}
		if fields[2] != device {
			continue
		}
		mountpoint := strings.NewReplacer(`\040`, " ", `\011`, "\t", `\012`, "\n", `\134`, `\`).Replace(fields[4])
		mountInfo, err := os.Stat(mountpoint)
		if err != nil {
			return fmt.Errorf("inspect catalog mountpoint: %w", err)
		}
		if !mountInfo.Mode().IsRegular() {
			continue
		}
		mountKey, _, err := linuxIdentity(mountInfo)
		if err != nil {
			return err
		}
		if mountKey == key {
			return errors.New("file-level bind mount of media catalog is unsupported")
		}
	}
	return scanner.Err()
}

func openCoordinatorFile(path string) (*os.File, error) {
	fd, err := unix.Open(path, unix.O_CREAT|unix.O_RDWR|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(fd), path), nil
}

func tryLockCoordinatorFile(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_EX|unix.LOCK_NB)
}
func unlockCoordinatorFile(file *os.File) error { return unix.Flock(int(file.Fd()), unix.LOCK_UN) }
