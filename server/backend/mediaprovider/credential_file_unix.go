//go:build !windows

package mediaprovider

import (
	"os"
	"syscall"
)

func privateKeyFile(_ *os.File, info os.FileInfo) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	return ok && int(stat.Uid) == os.Geteuid() && info.Mode().Perm()&0o077 == 0
}
