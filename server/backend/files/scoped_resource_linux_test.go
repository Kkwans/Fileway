//go:build linux

package files

import (
	"bytes"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"
	"golang.org/x/sys/unix"
)

func TestLinuxScopedResourceRejectsLinksAndPreservesOpaqueNames(t *testing.T) {
	base, _, root := scopedFixture(t)
	if err := os.Symlink(filepath.Join(base, "outside"), filepath.Join(base, "media", "link")); err != nil {
		t.Fatal(err)
	}
	if err := unix.Mkfifo(filepath.Join(base, "media", "pipe"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, target := range []string{"link/private.mp4", "pipe"} {
		if file, err := root.OpenRegular(t.Context(), target); err == nil {
			_ = file.Close()
			t.Fatalf("link or special file accepted: %q", target)
		}
	}
	name := string([]byte{'x', 0xff, '%', '+', '#', '.', 'm', 'p', '4'})
	if err := os.WriteFile(filepath.Join(base, "media", name), []byte("opaque"), 0o600); err != nil {
		t.Fatal(err)
	}
	file, err := root.OpenRegular(t.Context(), name)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = file.Close() }()
	buf := make([]byte, 6)
	if _, err := file.ReadAt(buf, 0); err != nil || string(buf) != "opaque" {
		t.Fatalf("opaque path: %q %v", buf, err)
	}
	dir, err := root.OpenDirectory(t.Context(), "")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = dir.Close() }()
	entries, _, err := dir.ReadBatch(t.Context(), 256)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, entry := range entries {
		if bytes.Equal(entry.Name, []byte(name)) {
			found = true
		}
	}
	if !found {
		t.Fatalf("original filename bytes missing: %+v", entries)
	}
	if _, err := root.OpenDirectory(t.Context(), "link"); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("symlink directory: %v", err)
	}
}

func TestLinuxScopedResourceReportsMountBoundaryWithoutWatch(t *testing.T) {
	authority := ScopedAuthority{Filesystem: afero.NewBasePathFs(afero.NewOsFs(), "/"), ServerRoot: "/", Check: func(string) bool { return true }}
	root, err := OpenScopedRoot(t.Context(), authority, "/")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = root.Close() }()
	var rootStat, procStat unix.Stat_t
	if err := unix.Stat("/", &rootStat); err != nil {
		t.Fatal(err)
	}
	if err := unix.Stat("/proc", &procStat); err != nil {
		t.Fatal(err)
	}
	if rootStat.Dev == procStat.Dev {
		t.Skip("this environment does not expose /proc as a separate mount")
	}
	if dir, err := root.OpenDirectory(t.Context(), "proc"); !errors.Is(err, ErrScopedMountBoundary) {
		if dir != nil {
			_ = dir.Close()
		}
		t.Fatalf("submount was not fenced: %v", err)
	}
	state, err := root.State(t.Context(), ScopedObserved)
	if err != nil {
		t.Fatal(err)
	}
	if state.Mount.ID == 0 && state.Mount.IDKind != "unknown" {
		t.Fatalf("unavailable mount ID claimed: %+v", state.Mount)
	}
	if root.Capabilities().Notifications != "none" {
		t.Fatal("absence of a watch was presented as a snapshot")
	}
}
