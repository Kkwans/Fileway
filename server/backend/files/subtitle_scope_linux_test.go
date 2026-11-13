//go:build linux

package files

import (
	"encoding/binary"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/spf13/afero"
	"golang.org/x/sys/unix"
)

func TestSubtitleScopeRejectsLinksAndHoldsTheAuthorizedFile(t *testing.T) {
	if !SubtitleScopeAvailable() {
		t.Skip("kernel does not provide safe subtitle opens")
	}
	base := t.TempDir()
	server := filepath.Join(base, "server")
	owned := filepath.Join(server, "owned")
	outside := filepath.Join(base, "outside")
	for _, dir := range []string{filepath.Join(owned, "sub"), filepath.Join(outside, "nested")} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	file := filepath.Join(owned, "sub", "line\\%+#.srt")
	protected := filepath.Join(outside, "nested", "protected.srt")
	for name, contents := range map[string]string{file: "owned", protected: "protected"} {
		if err := os.WriteFile(name, []byte(contents), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for link, target := range map[string]string{
		filepath.Join(owned, "link"):        outside,
		filepath.Join(owned, "direct.srt"):  protected,
		filepath.Join(server, "scope-link"): outside,
	} {
		if err := os.Symlink(target, link); err != nil {
			t.Fatal(err)
		}
	}
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), owned)
	for _, test := range []struct {
		path string
		dir  bool
	}{{"/link/nested", true}, {"/link/nested/protected.srt", false}, {"/direct.srt", false}} {
		if _, err := OpenSubtitleResource(filesystem, server, test.path, test.dir, true); !errors.Is(err, fs.ErrPermission) {
			t.Fatalf("%s: %v", test.path, err)
		}
	}
	linkedScope := afero.NewBasePathFs(afero.NewOsFs(), filepath.Join(server, "scope-link", "nested"))
	if _, err := OpenSubtitleResource(linkedScope, server, "/protected.srt", false, true); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("intermediate link in configured scope: %v", err)
	}
	if err := unix.Mkfifo(filepath.Join(owned, "pipe.srt"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenSubtitleResource(filesystem, server, "/pipe.srt", false, true); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("pipe was not rejected: %v", err)
	}
	opened, err := OpenSubtitleResource(filesystem, server, "/sub/line\\%+#.srt", false, true)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = opened.Close() }()
	if err := os.Rename(file, file+".old"); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(protected, file); err != nil {
		t.Fatal(err)
	}
	contents, err := io.ReadAll(opened.File)
	if err != nil || string(contents) != "owned" {
		t.Fatalf("opened descriptor changed: %q %v", contents, err)
	}
}

func TestSubtitleScopeNotificationLossInvalidatesScan(t *testing.T) {
	if !SubtitleScopeAvailable() {
		t.Skip("kernel does not provide safe subtitle opens")
	}
	dir, err := os.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = dir.Close() }()
	watch, err := newSubtitleLinuxWatch(int(dir.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	defer watch.close()
	if watch.changed() {
		t.Fatal("new directory watch was already dirty")
	}
	wd, err := unix.InotifyAddWatch(watch.fd, "/proc/self/fd/"+strconv.FormatUint(uint64(dir.Fd()), 10), unix.IN_ATTRIB)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := unix.InotifyRmWatch(watch.fd, uint32(wd)); err != nil {
		t.Fatal(err)
	}
	firstChange, repeatedChange := watch.changed(), watch.changed()
	if !firstChange || !repeatedChange {
		t.Fatal("kernel watch removal must permanently invalidate the scan")
	}
	var pipe [2]int
	if err := unix.Pipe2(pipe[:], unix.O_CLOEXEC|unix.O_NONBLOCK); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = unix.Close(pipe[1]) }()
	overflow := &subtitleLinuxWatch{fd: pipe[0]}
	defer overflow.close()
	var event [unix.SizeofInotifyEvent]byte
	binary.NativeEndian.PutUint32(event[0:], ^uint32(0))
	binary.NativeEndian.PutUint32(event[4:], unix.IN_Q_OVERFLOW)
	if _, err := unix.Write(pipe[1], event[:]); err != nil {
		t.Fatal(err)
	}
	firstOverflow, repeatedOverflow := overflow.changed(), overflow.changed()
	if !firstOverflow || !repeatedOverflow {
		t.Fatal("overflow without named entries must permanently invalidate the scan")
	}
	for _, kind := range []int64{unix.NFS_SUPER_MAGIC, unix.FUSE_SUPER_MAGIC, unix.OVERLAYFS_SUPER_MAGIC, unix.V9FS_MAGIC} {
		if subtitleLinuxFilesystem(kind) {
			t.Fatalf("filesystem %#x was accepted without local notifications", kind)
		}
	}
}
