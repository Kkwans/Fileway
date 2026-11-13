package mediasource

import (
	"context"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/spf13/afero"
)

func TestLocalReadsOriginalBytesAndRechecksAuthority(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "media"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "media", "owned.bin"), []byte("0123456789"), 0600); err != nil {
		t.Fatal(err)
	}
	allowed := true
	calls := 0
	source, err := NewLocal("/media", func(context.Context) (LocalAuthority, error) {
		calls++
		return LocalAuthority{FS: afero.NewBasePathFs(afero.NewOsFs(), root), ServerRoot: root, CanRead: allowed, Check: func(string) bool { return true }}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	handle, err := source.Open(context.Background(), "owned.bin", false)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = handle.Close() }()
	if _, err := handle.File.Seek(3, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 4)
	if _, err := io.ReadFull(handle.File, b); err != nil || string(b) != "3456" {
		t.Fatalf("bytes %q: %v", b, err)
	}
	allowed = false
	if _, err := source.Open(context.Background(), "owned.bin", false); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("revocation: %v", err)
	}
	if calls != 2 {
		t.Fatalf("authority calls %d", calls)
	}
	badLocators := []string{"../owned.bin", "/owned.bin", "a/../owned.bin", "a\x00b"}
	if runtime.GOOS == "windows" {
		badLocators = append(badLocators, "a\\b")
	}
	for _, bad := range badLocators {
		if _, err := source.Open(context.Background(), bad, false); !errors.Is(err, fs.ErrInvalid) {
			t.Fatalf("locator %q: %v", bad, err)
		}
	}
	if calls != 2 {
		t.Fatal("invalid locators reached authority")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := source.Open(ctx, "owned.bin", false); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestOpenedObjectRejectsPrivateCatalogAliasesAndAllowsVirtualRoot(t *testing.T) {
	root := t.TempDir()
	privateDir := filepath.Join(root, files.MediaLibraryDirectory)
	if err := os.Mkdir(privateDir, 0o700); err != nil {
		t.Fatal(err)
	}
	database := filepath.Join(privateDir, "catalog.db")
	if err := os.WriteFile(database, []byte("catalog"), 0o600); err != nil {
		t.Fatal(err)
	}
	release, err := files.ProtectMediaLibraryDatabase(database)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := release(); err != nil {
			t.Error(err)
		}
	})
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
	directory, err := files.OpenSubtitleResource(filesystem, root, "/"+files.MediaLibraryDirectory, true, true)
	if err != nil {
		t.Fatal(err)
	}
	if err := checkOpenedResource(directory, true); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("private opened directory: %v", err)
	}
	if err := directory.Close(); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(root, "alias.mkv")
	if err := os.Link(database, alias); err != nil {
		t.Skipf("fixture filesystem cannot create hard link: %v", err)
	}
	file, err := files.OpenSubtitleResource(filesystem, root, "/alias.mkv", false, true)
	if err != nil {
		t.Fatal(err)
	}
	if err := checkOpenedResource(file, false); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("private opened file alias: %v", err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	virtual := &files.SubtitleResource{}
	if err := checkOpenedResource(virtual, true); err != nil {
		t.Fatalf("virtual directory root: %v", err)
	}
	if err := checkOpenedResource(virtual, false); !errors.Is(err, fs.ErrInvalid) {
		t.Fatalf("virtual root is not a regular file: %v", err)
	}
}
