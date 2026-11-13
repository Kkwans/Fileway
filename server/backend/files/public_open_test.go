package files

import (
	"errors"
	"github.com/spf13/afero"
	"io/fs"
	"os"
	"path/filepath"
	"testing"
)

// Simulate a filesystem alias swapping after its lexical/path check. The path
// looks public, but Open returns the private object that would leak to a reader.
type swappedPublicFS struct {
	afero.Fs
	target string
	opened afero.File
}

func (s *swappedPublicFS) Open(string) (afero.File, error) {
	f, err := s.Fs.Open(s.target)
	s.opened = f
	return f, err
}

func TestPublicOpenRejectsPrivateObjectAfterAliasSwap(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, MediaLibraryDirectory)
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	db := filepath.Join(dir, "catalog.db")
	if err := os.WriteFile(db, []byte("private-data"), 0600); err != nil {
		t.Fatal(err)
	}
	release, err := ProtectMediaLibraryDatabase(db)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	swapped := &swappedPublicFS{Fs: afero.NewOsFs(), target: db}
	if file, err := OpenPublicResource(swapped, "public-video.mkv"); !errors.Is(err, fs.ErrPermission) || file != nil {
		t.Fatalf("private object returned: %v %v", file, err)
	}
	if _, err := swapped.opened.Stat(); err == nil {
		t.Fatal("rejected handle was not closed")
	}
}

func TestPublicOpenPreservesOrdinaryAndRejectsReservedMemoryPaths(t *testing.T) {
	memory := afero.NewMemMapFs()
	if err := afero.WriteFile(memory, "/film.mkv", []byte("owned"), 0600); err != nil {
		t.Fatal(err)
	}
	file, err := OpenPublicResource(memory, "/film.mkv")
	if err != nil {
		t.Fatal(err)
	}
	_ = file.Close()
	if _, err := OpenPublicResource(memory, "/.fileway-media/catalog.db"); !errors.Is(err, fs.ErrPermission) {
		t.Fatal(err)
	}
}
