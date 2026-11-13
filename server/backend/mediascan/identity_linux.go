//go:build linux

package mediascan

import (
	"fmt"
	"os"

	"golang.org/x/sys/unix"
)

// Device/inode alone may be reused after deletion. A birth timestamp adds an
// independent piece of identity evidence. Missing btime uses the explicit
// unverified fallback; neither form is a content or timeline fingerprint.
func nativeObjectKey(file *os.File) ([]byte, bool) {
	return nativeKey(file, unix.S_IFREG)
}

func nativeDirectoryKey(file *os.File) ([]byte, bool) {
	return nativeKey(file, unix.S_IFDIR)
}

func nativeKey(file *os.File, mode uint16) ([]byte, bool) {
	if file == nil {
		return nil, false
	}
	var stat unix.Statx_t
	const required = unix.STATX_TYPE | unix.STATX_INO | unix.STATX_BTIME
	if err := unix.Statx(int(file.Fd()), "", unix.AT_EMPTY_PATH, required, &stat); err != nil || stat.Mask&required != required || stat.Ino == 0 || stat.Mode&unix.S_IFMT != mode || stat.Btime.Sec == 0 && stat.Btime.Nsec == 0 {
		return nil, false
	}
	return []byte(fmt.Sprintf("fileway/local/linux/v1:%d:%d:%d:%d:%d", stat.Dev_major, stat.Dev_minor, stat.Ino, stat.Btime.Sec, stat.Btime.Nsec)), true
}
