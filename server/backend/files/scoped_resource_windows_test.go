//go:build windows

package files

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/sys/windows"
)

func TestWindowsScopedResourceRejectsJunctionAndAlternateNames(t *testing.T) {
	base, _, root := scopedFixture(t)
	if output, err := exec.Command("cmd.exe", "/C", "mklink", "/J", filepath.Join(base, "media", "jump"), filepath.Join(base, "outside")).CombinedOutput(); err != nil {
		t.Fatalf("junction fixture: %s %v", output, err)
	}
	for _, name := range []string{"jump/private.mp4", "one.MP4", "one.mp4:stream", "one.mp4.", `nested\two.mp4`} {
		if file, err := root.OpenRegular(t.Context(), name); err == nil {
			_ = file.Close()
			t.Fatalf("alias or reparse accepted: %q", name)
		}
	}
	if dir, err := root.OpenDirectory(t.Context(), "jump"); err == nil {
		_ = dir.Close()
		t.Fatal("junction directory accepted")
	}
}

func TestWindowsScopedReopenedDirectoriesHaveIndependentKernelCursors(t *testing.T) {
	base, _, root := scopedFixture(t)
	for i := range 100 {
		name := fmt.Sprintf("cursor-entry-%04d-abcdefghijklmnopqrstuvwxyz.txt", i)
		if err := os.WriteFile(filepath.Join(base, "media", name), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	first, err := scopedWindowsReopenDirectory(windows.Handle(root.file.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = windows.CloseHandle(first) }()
	second, err := scopedWindowsReopenDirectory(windows.Handle(root.file.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = windows.CloseHandle(second) }()
	read := func(handle windows.Handle, class uint32) []byte {
		t.Helper()
		buffer := make([]byte, 1024)
		if err := windows.GetFileInformationByHandleEx(handle, class, &buffer[0], uint32(len(buffer))); err != nil {
			t.Fatal(err)
		}
		return buffer
	}
	firstPage := read(first, windows.FileFullDirectoryRestartInfo)
	secondPage := read(second, windows.FileFullDirectoryRestartInfo)
	if !bytes.Equal(firstPage, secondPage) {
		t.Fatal("separate iterators started at different positions")
	}
	firstNext := read(first, windows.FileFullDirectoryInfo)
	secondNext := read(second, windows.FileFullDirectoryInfo)
	if !bytes.Equal(firstNext, secondNext) || bytes.Equal(firstNext, firstPage) {
		t.Fatal("separate iterators shared or failed to advance their kernel cursor")
	}
}

func TestWindowsScopedReopenHonorsDirectoryListDenial(t *testing.T) {
	base, _, root := scopedFixture(t)
	directory := filepath.Join(base, "media")
	const worldSID = "*S-1-1-0"
	if output, err := exec.Command("icacls.exe", directory, "/deny", worldSID+":(RD)").CombinedOutput(); err != nil {
		t.Fatalf("fixture list deny: %s %v", output, err)
	}
	t.Cleanup(func() {
		if output, err := exec.Command("icacls.exe", directory, "/remove:d", worldSID).CombinedOutput(); err != nil {
			t.Errorf("restore fixture ACL: %s %v", output, err)
		}
	})
	if handle, err := scopedWindowsReopenDirectory(windows.Handle(root.file.Fd())); !errors.Is(err, fs.ErrPermission) {
		if handle != windows.InvalidHandle {
			_ = windows.CloseHandle(handle)
		}
		t.Fatalf("native directory reopen bypassed list denial: %v", err)
	}
	if dir, err := root.OpenDirectory(t.Context(), ""); !errors.Is(err, fs.ErrPermission) {
		if dir != nil {
			_ = dir.Close()
		}
		t.Fatalf("denied directory listing was permitted: %v", err)
	}
}

func TestWindowsScopedFallbackChecksActualNameAndOpenedIdentity(t *testing.T) {
	base, _, root := scopedFixture(t)
	const actual = "VeryLongCanonicalName.mp4"
	filename := filepath.Join(base, "media", actual)
	if err := os.WriteFile(filename, []byte("original"), 0o600); err != nil {
		t.Fatal(err)
	}
	query := func(handle windows.Handle, flags uint32) (string, error) {
		if flags == 0 {
			return "", ErrScopedNameUnverified
		}
		return scopedWindowsFinalPath(handle, flags)
	}
	open := func(name string) (windows.Handle, error) {
		process := windows.CurrentProcess()
		var handle windows.Handle
		if err := windows.DuplicateHandle(process, windows.Handle(root.file.Fd()), process, &handle, 0, false, windows.DUPLICATE_SAME_ACCESS); err != nil {
			return windows.InvalidHandle, err
		}
		return scopedWindowsOpenSegmentsWithQuery(t.Context(), handle, []string{name}, 0, false, false, query)
	}
	child, err := open(actual)
	if err != nil {
		t.Fatalf("opened-name fallback refused exact name: %v", err)
	}
	defer func() { _ = windows.CloseHandle(child) }()
	if alias, err := open(strings.ToLower(actual)); !errors.Is(err, fs.ErrPermission) {
		if alias != windows.InvalidHandle {
			_ = windows.CloseHandle(alias)
		}
		t.Fatalf("case alias accepted: %v", err)
	}
	if err := os.Rename(filename, filename+".old"); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filename, []byte("replacement"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := scopedWindowsVerifyName(context.Background(), windows.Handle(root.file.Fd()), child, actual, false); !errors.Is(err, ErrScopedNameUnverified) {
		t.Fatalf("renamed object accepted: %v", err)
	}
	ptr, err := windows.UTF16PtrFromString(filename)
	if err != nil {
		t.Fatal(err)
	}
	var short [32768]uint16
	n, err := windows.GetShortPathName(ptr, &short[0], uint32(len(short)))
	if err == nil && n > 0 && n < uint32(len(short)) {
		alias := filepath.Base(windows.UTF16ToString(short[:n]))
		if alias != actual {
			if handle, err := open(alias); !errors.Is(err, fs.ErrPermission) {
				if handle != windows.InvalidHandle {
					_ = windows.CloseHandle(handle)
				}
				t.Fatalf("8.3 alias accepted: %q %v", alias, err)
			}
		}
	}
}
