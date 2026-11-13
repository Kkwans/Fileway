package files

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/spf13/afero"
)

func TestPrivateMediaNamespaceIsReservedWithoutEnabledCatalog(t *testing.T) {
	fs := afero.NewMemMapFs()
	for _, name := range []string{"/.fileway-media", "/somewhere/.fileway-media/catalog.db-wal", "/.FILEWAY-MEDIA/new"} {
		if !IsPrivateResourcePath(fs, name) {
			t.Fatal("reserved media path exposed", name)
		}
	}
	for _, name := range []string{"/media", "/.fileway-media-personal", "/catalog.db", "/"} {
		if IsPrivateResourcePath(fs, name) {
			t.Fatal("ordinary virtual path was hidden", name)
		}
	}
}

func TestPrivateMediaProtectsHardLinksAndRetainsRenamedDirectory(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, MediaLibraryDirectory)
	if err := os.Mkdir(directory, 0700); err != nil {
		t.Fatal(err)
	}
	release, err := ProtectMediaLibraryDatabase(filepath.Join(directory, "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = release() })
	fs := afero.NewBasePathFs(afero.NewOsFs(), root)
	// Create the family after registration: WAL/SHM may appear asynchronously.
	for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
		original := filepath.Join(directory, "catalog.db"+suffix)
		if err := os.WriteFile(original, []byte("private"), 0600); err != nil {
			t.Fatal(err)
		}
		alias := filepath.Join(root, "alias"+suffix)
		if err := os.Link(original, alias); err != nil {
			t.Fatal(err)
		}
		if !IsPrivateResourcePath(fs, "/alias"+suffix) {
			t.Fatal("hard link exposed", suffix)
		}
	}
	// Retained os.Root identity must survive moving the protected ancestor.
	moved := filepath.Join(root, "ordinary-name")
	prefix := "/ordinary-name"
	if err := os.Rename(directory, moved); err != nil {
		if runtime.GOOS != "windows" {
			t.Fatal(err)
		}
		// Windows' retained directory handle may prevent renaming outright.
		// That is a safe outcome, rather than a reason to release the guard.
		prefix = "/" + MediaLibraryDirectory
	}
	for _, name := range []string{prefix, prefix + "/catalog.db", prefix + "/not-created/child", "/alias-wal"} {
		if !IsPrivateResourcePath(fs, name) {
			t.Fatal("renaming private ancestor detached protection", name)
		}
	}
	if err := os.Mkdir(directory, 0700); err != nil && !os.IsExist(err) {
		t.Fatal(err)
	}
	if IsPrivateResourcePath(fs, "/ordinary-public/missing") {
		t.Fatal("unrelated future path hidden")
	}
}

func TestPrivateMediaRechecksCurrentSymbolicLinkTarget(t *testing.T) {
	root := t.TempDir()
	public := filepath.Join(root, "public")
	private := filepath.Join(root, MediaLibraryDirectory)
	for _, directory := range []string{public, private} {
		if err := os.Mkdir(directory, 0700); err != nil {
			t.Fatal(err)
		}
	}
	alias := filepath.Join(root, "alias")
	if err := os.Symlink(public, alias); err != nil {
		t.Skipf("native symlinks unavailable; system settings unchanged: %v", err)
	}
	fs := afero.NewBasePathFs(afero.NewOsFs(), root)
	if IsPrivateResourcePath(fs, "/alias/future") {
		t.Fatal("ordinary symbolic target hidden")
	}
	if err := os.Remove(alias); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(private, alias); err != nil {
		t.Fatal(err)
	}
	if !IsPrivateResourcePath(fs, "/alias/future") {
		t.Fatal("changed link target reused stale authorization")
	}
	// This verifies each fresh check, not a check/open TOCTOU guarantee.
}

func TestPrivateMediaOpenedIdentityRejectsChangedAlias(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, MediaLibraryDirectory)
	if err := os.Mkdir(directory, 0700); err != nil {
		t.Fatal(err)
	}
	database := filepath.Join(directory, "catalog.db")
	if err := os.WriteFile(database, []byte("private"), 0600); err != nil {
		t.Fatal(err)
	}
	release, err := ProtectMediaLibraryDatabase(database)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = release() })
	alias := filepath.Join(root, "ordinary.db")
	if err := os.WriteFile(alias, []byte("public"), 0600); err != nil {
		t.Fatal(err)
	}
	fs := afero.NewBasePathFs(afero.NewOsFs(), root)
	if IsPrivateResourcePath(fs, "/ordinary.db") {
		t.Fatal("ordinary file hidden")
	}
	// Deterministically change the target after path authorization. Consumers
	// must inspect the returned handle before reading, rather than trust Check.
	if err := os.Remove(alias); err != nil {
		t.Fatal(err)
	}
	if err := os.Link(database, alias); err != nil {
		t.Fatal(err)
	}
	opened, err := os.Open(alias)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = opened.Close() }()
	info, err := opened.Stat()
	if err != nil || !IsPrivateMediaFile(info) {
		t.Fatal("opened private object escaped identity guard", err)
	}
}

func TestPrivateMediaRejectsDirectoryAliasRegistration(t *testing.T) {
	root := t.TempDir()
	public := filepath.Join(root, "public")
	if err := os.Mkdir(public, 0700); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(root, MediaLibraryDirectory)
	if err := os.Symlink(public, alias); err != nil {
		t.Skipf("native symlinks unavailable; system settings unchanged: %v", err)
	}
	release, err := ProtectMediaLibraryDatabase(filepath.Join(alias, "catalog.db"))
	if err == nil {
		_ = release()
		t.Fatal("directory alias hid an arbitrary public parent")
	}
	if IsPrivateResourcePath(afero.NewBasePathFs(afero.NewOsFs(), root), "/public") {
		t.Fatal("rejected registration hid public directory")
	}
}
