//go:build windows

package files

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/spf13/afero"
)

func TestWindowsSubtitleScopeRejectsJunctionsAndAlternateNames(t *testing.T) {
	root := t.TempDir()
	owned := filepath.Join(root, "owned")
	outside := filepath.Join(root, "outside")
	for _, dir := range []string{owned, filepath.Join(outside, "nested")} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	for name, content := range map[string]string{
		filepath.Join(owned, "长名字 %+.srt"):            "owned",
		filepath.Join(outside, "nested", "other.srt"): "outside",
	} {
		if err := os.WriteFile(name, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	junction := filepath.Join(owned, "jump")
	if output, err := exec.Command("cmd.exe", "/C", "mklink", "/J", junction, outside).CombinedOutput(); err != nil {
		t.Fatalf("could not create junction fixture: %s %v", output, err)
	}
	defer os.Remove(junction)
	for _, filesystem := range []afero.Fs{afero.NewBasePathFs(afero.NewOsFs(), owned), NewScopedDriveFs(WindowsPathToVirtual(owned))} {
		opened, err := OpenSubtitleResource(filesystem, root, "/长名字 %+.srt", false, true)
		if err != nil {
			t.Fatal(err)
		}
		content, err := io.ReadAll(opened.File)
		_, stamp, stampErr := opened.State()
		_ = opened.Close()
		if err != nil || stampErr != nil || string(content) != "owned" || stamp == "" {
			t.Fatalf("scoped file: %q %q %v %v", content, stamp, err, stampErr)
		}
		for _, raw := range []string{"/jump/nested/other.srt", "/长名字 %+.SRT", "/长名字 %+.srt:stream", "/长名字 %+.srt."} {
			if _, err := OpenSubtitleResource(filesystem, root, raw, false, true); !errors.Is(err, fs.ErrPermission) {
				t.Fatalf("%s: %v", raw, err)
			}
		}
	}
	directory, err := OpenSubtitleResource(NewScopedDriveFs(WindowsPathToVirtual(owned)), "computer", "/", true, true)
	if err != nil {
		t.Fatal(err)
	}
	defer directory.Close()
	if directory.Changed() {
		t.Fatal("new directory watch was already dirty")
	}
	if err := os.WriteFile(filepath.Join(owned, "changed.srt"), []byte("new"), 0o600); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for !directory.Changed() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !directory.Changed() {
		t.Fatal("directory watch did not observe the new file")
	}
	global, err := OpenSubtitleResource(NewDriveFs(), "computer", "/", true, true)
	if err != nil {
		t.Fatal(err)
	}
	defer global.Close()
	entries, err := global.ReadDir(26)
	if err != nil || len(entries) == 0 || !entries[0].IsDir() {
		t.Fatalf("drive root entries: %v %v", entries, err)
	}
}
