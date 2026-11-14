package fileutils

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/spf13/afero"
)

var errCopyInjected = errors.New("owned copy fault")

// The outer BasePathFs still proves actual native inodes to PublishUpload.
// Only file operations are faulted; publication uses the real OS primitive.
type copyFaultFS struct {
	afero.Fs
	fault      string
	source     string
	beforeSync func()
}

func (f *copyFaultFS) LstatIfPossible(name string) (os.FileInfo, bool, error) {
	return f.Fs.(afero.Lstater).LstatIfPossible(name)
}
func (f *copyFaultFS) Open(name string) (afero.File, error) {
	file, err := f.Fs.Open(name)
	if err != nil {
		return nil, err
	}
	return &copyFaultFile{File: file, fs: f, source: filepath.Clean(name) == f.source}, nil
}
func (f *copyFaultFS) OpenFile(name string, flags int, mode os.FileMode) (afero.File, error) {
	file, err := f.Fs.OpenFile(name, flags, mode)
	if err != nil {
		return nil, err
	}
	return &copyFaultFile{File: file, fs: f}, nil
}
func (f *copyFaultFS) Chmod(name string, mode os.FileMode) error {
	if f.fault == "chmod" {
		return errCopyInjected
	}
	return f.Fs.Chmod(name, mode)
}

type copyFaultFile struct {
	afero.File
	fs     *copyFaultFS
	source bool
}

func (f *copyFaultFile) Read(p []byte) (int, error) {
	if f.source && f.fs.fault == "read" {
		n, _ := f.File.Read(p[:min(len(p), 3)])
		return n, errCopyInjected
	}
	return f.File.Read(p)
}
func (f *copyFaultFile) Write(p []byte) (int, error) {
	if !f.source && f.fs.fault == "write" {
		n, _ := f.File.Write(p[:min(len(p), 3)])
		return n, errCopyInjected
	}
	return f.File.Write(p)
}
func (f *copyFaultFile) Sync() error {
	if f.fs.beforeSync != nil {
		f.fs.beforeSync()
	}
	if f.fs.fault == "sync" {
		return errCopyInjected
	}
	return f.File.Sync()
}
func (f *copyFaultFile) Close() error {
	err := f.File.Close()
	if f.source && f.fs.fault == "source-close" || !f.source && f.fs.fault == "destination-close" {
		return errors.Join(err, errCopyInjected)
	}
	return err
}

func copyNativeFixture(t *testing.T) (afero.Fs, *copyFaultFS, string) {
	t.Helper()
	root := t.TempDir()
	fault := &copyFaultFS{Fs: afero.NewOsFs(), source: filepath.Join(root, "source.txt")}
	scoped := afero.NewBasePathFs(fault, root)
	for name, contents := range map[string]string{"/source.txt": "new-source-bytes", "/target.txt": "old-target-bytes"} {
		if err := afero.WriteFile(scoped, name, []byte(contents), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	return scoped, fault, root
}
func assertCopyBytes(t *testing.T, afs afero.Fs, name, want string) {
	t.Helper()
	got, err := afero.ReadFile(afs, name)
	if err != nil || string(got) != want {
		t.Fatalf("%q bytes=%q error=%v, want %q", name, got, err, want)
	}
}
func assertNoCopyPart(t *testing.T, afs afero.Fs) {
	t.Helper()
	err := afero.Walk(afs, "/", func(name string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if files.IsUploadPartPath(name) {
			t.Errorf("owned temporary part leaked: %q", name)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestLegacyCopyPreservesTargetOnPreparationFailure(t *testing.T) {
	for _, kind := range []string{"read", "write", "chmod", "sync", "source-close", "destination-close"} {
		t.Run(kind, func(t *testing.T) {
			afs, fault, _ := copyNativeFixture(t)
			fault.fault = kind
			err := CopyFile(afs, "/source.txt", "/target.txt", 0o640, 0o750)
			fault.fault = ""
			if !errors.Is(err, errCopyInjected) {
				t.Errorf("copy error=%v, want injected %s", err, kind)
			}
			assertCopyBytes(t, afs, "/source.txt", "new-source-bytes")
			assertCopyBytes(t, afs, "/target.txt", "old-target-bytes")
			assertNoCopyPart(t, afs)
		})
	}
}

func TestLegacyCopyRejectsSameFileAndNativeAliases(t *testing.T) {
	afs, _, root := copyNativeFixture(t)
	for _, name := range []string{"/source.txt", "/alias.txt"} {
		if name == "/alias.txt" {
			if err := os.Link(filepath.Join(root, "source.txt"), filepath.Join(root, "alias.txt")); err != nil {
				t.Fatal(err)
			}
		}
		if err := CopyFile(afs, "/source.txt", name, 0o640, 0o750); !errors.Is(err, os.ErrInvalid) {
			t.Errorf("alias %q error=%v", name, err)
		}
		assertCopyBytes(t, afs, "/source.txt", "new-source-bytes")
	}
}

func TestLegacyDirectoryCopyFailurePreservesExistingChildren(t *testing.T) {
	afs, fault, _ := copyNativeFixture(t)
	for name, body := range map[string]string{"/source/child.txt": "new-child", "/target/child.txt": "old-child", "/target/unrelated.txt": "keep"} {
		if err := afs.MkdirAll(filepath.Dir(name), 0o750); err != nil {
			t.Fatal(err)
		}
		if err := afero.WriteFile(afs, name, []byte(body), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	fault.fault = "write"
	err := CopyDir(afs, "/source", "/target", 0o640, 0o750)
	fault.fault = ""
	if !errors.Is(err, errCopyInjected) {
		t.Fatalf("copy error=%v", err)
	}
	assertCopyBytes(t, afs, "/target/child.txt", "old-child")
	assertCopyBytes(t, afs, "/target/unrelated.txt", "keep")
	assertCopyBytes(t, afs, "/source/child.txt", "new-child")
	assertNoCopyPart(t, afs)
}

func TestLegacyCopySpecialNamesPreserveIdentity(t *testing.T) {
	afs, _, _ := copyNativeFixture(t)
	names := []string{"Unicode 空格[]'.txt", "%2F-literal.txt"}
	if runtime.GOOS != "windows" {
		names = append(names, " Unicode 空格[]' ", "raw-"+string([]byte{0xd6, 0xd0})+"\\name")
	}
	for _, name := range names {
		source := "/" + name
		target := "/copy-" + name
		if err := afero.WriteFile(afs, source, []byte("identity:"+name), 0o640); err != nil {
			t.Fatal(err)
		}
		if err := Copy(afs, source, target, 0o640, 0o750); err != nil {
			t.Fatal(err)
		}
		assertCopyBytes(t, afs, source, "identity:"+name)
		assertCopyBytes(t, afs, target, "identity:"+name)
	}
	assertNoCopyPart(t, afs)
}

func TestLegacyCopyExplicitOverwriteAndConcurrentTarget(t *testing.T) {
	for _, native := range []bool{false, true} {
		t.Run(fmt.Sprintf("native=%t", native), func(t *testing.T) {
			afs := afero.Fs(afero.NewMemMapFs())
			if native {
				afs = afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
			}
			for name, body := range map[string]string{"/source.txt": "new-source", "/target.txt": "old-target"} {
				if err := afero.WriteFile(afs, name, []byte(body), 0o640); err != nil {
					t.Fatal(err)
				}
			}
			if err := CopyWithOverwrite(afs, "/source.txt", "/target.txt", 0o640, 0o750, false); !errors.Is(err, os.ErrExist) {
				t.Fatalf("no replace error=%v", err)
			}
			assertCopyBytes(t, afs, "/target.txt", "old-target")
			if err := CopyWithOverwrite(afs, "/source.txt", "/target.txt", 0o640, 0o750, true); err != nil {
				t.Fatal(err)
			}
			assertCopyBytes(t, afs, "/target.txt", "new-source")
			assertCopyBytes(t, afs, "/source.txt", "new-source")
			assertNoCopyPart(t, afs)
		})
	}
	afs, fault, _ := copyNativeFixture(t)
	if err := afs.Remove("/target.txt"); err != nil {
		t.Fatal(err)
	}
	fault.beforeSync = func() {
		fault.beforeSync = nil
		if err := afero.WriteFile(afs, "/target.txt", []byte("concurrent-winner"), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	if err := CopyWithOverwrite(afs, "/source.txt", "/target.txt", 0o640, 0o750, false); !errors.Is(err, os.ErrExist) {
		t.Fatalf("race no-replace error=%v", err)
	}
	assertCopyBytes(t, afs, "/target.txt", "concurrent-winner")
	assertNoCopyPart(t, afs)
}

func TestLegacyDirectoryCopyMergesAndRejectsSelfDescendant(t *testing.T) {
	afs, _, _ := copyNativeFixture(t)
	for name, body := range map[string]string{"/source/child.txt": "new-child", "/source/nested/new.txt": "nested", "/target/child.txt": "old-child", "/target/unrelated.txt": "keep"} {
		if err := afs.MkdirAll(filepath.Dir(name), 0o750); err != nil {
			t.Fatal(err)
		}
		if err := afero.WriteFile(afs, name, []byte(body), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	if err := CopyWithOverwrite(afs, "/source", "/target", 0o640, 0o750, false); !errors.Is(err, os.ErrExist) {
		t.Fatalf("existing directory no-replace: %v", err)
	}
	assertCopyBytes(t, afs, "/target/child.txt", "old-child")
	if err := CopyWithOverwrite(afs, "/source", "/target", 0o640, 0o750, true); err != nil {
		t.Fatal(err)
	}
	assertCopyBytes(t, afs, "/target/child.txt", "new-child")
	assertCopyBytes(t, afs, "/target/nested/new.txt", "nested")
	assertCopyBytes(t, afs, "/target/unrelated.txt", "keep")
	if err := CopyWithOverwrite(afs, "/source", "/source/child", 0o640, 0o750, true); !errors.Is(err, os.ErrInvalid) {
		t.Fatalf("self-descendant error=%v", err)
	}
	if exists, err := afero.Exists(afs, "/source/child"); exists || err != nil {
		t.Fatalf("self-descendant created: %t %v", exists, err)
	}
	assertNoCopyPart(t, afs)
}

func TestLegacyCopyConcurrentNoReplacePublishesOneCompleteFile(t *testing.T) {
	afs := afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
	for _, name := range []string{"/one", "/two"} {
		if err := afero.WriteFile(afs, name, []byte(strings.Repeat(name, 65536)), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	start := make(chan struct{})
	results := make(chan error, 2)
	for _, name := range []string{"/one", "/two"} {
		go func() { <-start; results <- CopyWithOverwrite(afs, name, "/winner", 0o640, 0o750, false) }()
	}
	close(start)
	accepted, conflicts := 0, 0
	for range 2 {
		err := <-results
		switch {
		case err == nil:
			accepted++
		case errors.Is(err, os.ErrExist):
			conflicts++
		default:
			t.Errorf("unexpected error: %v", err)
		}
	}
	if accepted != 1 || conflicts != 1 {
		t.Fatalf("accepted=%d conflicts=%d", accepted, conflicts)
	}
	content, err := afero.ReadFile(afs, "/winner")
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != strings.Repeat("/one", 65536) && string(content) != strings.Repeat("/two", 65536) {
		t.Fatal("winner is not a complete source")
	}
	assertCopyBytes(t, afs, "/one", strings.Repeat("/one", 65536))
	assertCopyBytes(t, afs, "/two", strings.Repeat("/two", 65536))
	assertNoCopyPart(t, afs)
}

func TestLegacyDirectoryCopyRejectsSymlinkAliases(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows symlink creation requires a separately granted privilege; hardlink file aliases are tested on every OS")
	}
	afs, _, root := copyNativeFixture(t)
	if err := afs.MkdirAll("/source/sub", 0o750); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(afs, "/source/child", []byte("source-child"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(root, "source", "sub"), filepath.Join(root, "target-link")); err != nil {
		t.Fatal(err)
	}
	if err := CopyWithOverwrite(afs, "/source", "/target-link", 0o640, 0o750, true); !errors.Is(err, os.ErrInvalid) {
		t.Fatalf("target alias error=%v", err)
	}
	if err := os.Symlink(filepath.Join(root, "source"), filepath.Join(root, "source-link")); err != nil {
		t.Fatal(err)
	}
	if err := CopyWithOverwrite(afs, "/source-link", "/source/new", 0o640, 0o750, true); !errors.Is(err, os.ErrInvalid) {
		t.Fatalf("source alias error=%v", err)
	}
	assertCopyBytes(t, afs, "/source/child", "source-child")
	if exists, err := afero.Exists(afs, "/source/new"); exists || err != nil {
		t.Fatalf("alias mutated source: %t %v", exists, err)
	}
}

func TestLegacyCopyPublishFailureDoesNotRemoveSourceOrTarget(t *testing.T) {
	afs, _, _ := copyNativeFixture(t)
	if err := afs.Mkdir("/directory-target", 0o750); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(afs, "/directory-target/old", []byte("keep"), 0o640); err != nil {
		t.Fatal(err)
	}
	// Every native publisher rejects replacing a non-empty directory with a
	// regular file. The already closed, completed part is the only cleanup.
	if err := CopyWithOverwrite(afs, "/source.txt", "/directory-target", 0o640, 0o750, true); err == nil {
		t.Fatal("publication unexpectedly succeeded")
	}
	assertCopyBytes(t, afs, "/source.txt", "new-source-bytes")
	assertCopyBytes(t, afs, "/directory-target/old", "keep")
	assertNoCopyPart(t, afs)
}

// Simulate the native publisher's post-rename directory-sync failure through
// its afero fallback, using real OS files (and the supported memory adapter).
// The completed inode remains at dest while another writer reuses part's name.
type copyPostPublishErrorFS struct {
	afero.Fs
	part string
}

func (f *copyPostPublishErrorFS) LstatIfPossible(name string) (os.FileInfo, bool, error) {
	if fs, ok := f.Fs.(afero.Lstater); ok {
		return fs.LstatIfPossible(name)
	}
	info, err := f.Stat(name)
	return info, false, err
}
func (f *copyPostPublishErrorFS) Rename(from, to string) error {
	if err := f.Fs.Rename(from, to); err != nil {
		return err
	}
	f.part = from
	if err := afero.WriteFile(f.Fs, from, []byte("other-writer-recreated-part"), 0o640); err != nil {
		return err
	}
	return errCopyInjected
}
func TestLegacyCopyPostPublishErrorPreservesRecreatedPart(t *testing.T) {
	for _, native := range []bool{false, true} {
		t.Run(fmt.Sprintf("native=%t", native), func(t *testing.T) {
			afs := afero.Fs(afero.NewMemMapFs())
			if native {
				afs = afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
			}
			for name, body := range map[string]string{"/source.txt": "new-source", "/target.txt": "old-target"} {
				if err := afero.WriteFile(afs, name, []byte(body), 0o640); err != nil {
					t.Fatal(err)
				}
			}
			fault := &copyPostPublishErrorFS{Fs: afs}
			err := CopyWithOverwrite(fault, "/source.txt", "/target.txt", 0o640, 0o750, true)
			if !errors.Is(err, errCopyInjected) {
				t.Fatalf("publication error=%v", err)
			}
			if err == nil || !strings.Contains(err.Error(), "身份") {
				t.Errorf("replaced cleanup identity not reported: %v", err)
			}
			if !files.IsUploadPartPath(fault.part) {
				t.Fatalf("part is not reserved: %q", fault.part)
			}
			assertCopyBytes(t, afs, fault.part, "other-writer-recreated-part")
			assertCopyBytes(t, afs, "/target.txt", "new-source")
			assertCopyBytes(t, afs, "/source.txt", "new-source")
		})
	}
}

func TestLegacyCopyMemoryPreparationFailureCleansOwnedPart(t *testing.T) {
	afs := afero.NewMemMapFs()
	for name, body := range map[string]string{"/source.txt": "new-source", "/target.txt": "old-target"} {
		if err := afero.WriteFile(afs, name, []byte(body), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	fault := &copyFaultFS{Fs: afs, source: filepath.Clean("/source.txt"), fault: "read"}
	if err := CopyWithOverwrite(fault, "/source.txt", "/target.txt", 0o640, 0o750, true); !errors.Is(err, errCopyInjected) {
		t.Fatalf("preparation error=%v", err)
	}
	assertCopyBytes(t, afs, "/source.txt", "new-source")
	assertCopyBytes(t, afs, "/target.txt", "old-target")
	assertNoCopyPart(t, afs)
}

type copyUnknownInfo struct{ os.FileInfo }

func (copyUnknownInfo) Sys() any { return nil }

type copyUnknownFile struct{ afero.File }

func (f copyUnknownFile) Stat() (os.FileInfo, error) {
	info, err := f.File.Stat()
	if err != nil {
		return nil, err
	}
	return copyUnknownInfo{info}, nil
}

type copyUnknownIdentityFS struct {
	afero.Fs
	part               string
	removed            bool
	hideHandleIdentity bool
}

func (f *copyUnknownIdentityFS) OpenFile(name string, flags int, mode os.FileMode) (afero.File, error) {
	file, err := f.Fs.OpenFile(name, flags, mode)
	if err != nil {
		return nil, err
	}
	if files.IsUploadPartPath(name) {
		f.part = name
		if f.hideHandleIdentity {
			return copyUnknownFile{file}, nil
		}
		return file, nil
	}
	return file, nil
}
func (f *copyUnknownIdentityFS) Chmod(string, os.FileMode) error { return errCopyInjected }
func (f *copyUnknownIdentityFS) Remove(name string) error        { f.removed = true; return f.Fs.Remove(name) }
func TestLegacyCopyUnknownPartIdentityIsRetainedAndReported(t *testing.T) {
	for _, hideHandleIdentity := range []bool{false, true} {
		t.Run(fmt.Sprintf("hiddenHandleIdentity=%t", hideHandleIdentity), func(t *testing.T) {
			afs := afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
			for name, body := range map[string]string{"/source.txt": "new-source", "/target.txt": "old-target"} {
				if err := afero.WriteFile(afs, name, []byte(body), 0o640); err != nil {
					t.Fatal(err)
				}
			}
			fault := &copyUnknownIdentityFS{Fs: afs, hideHandleIdentity: hideHandleIdentity}
			err := CopyWithOverwrite(fault, "/source.txt", "/target.txt", 0o640, 0o750, true)
			if !errors.Is(err, errCopyInjected) || !strings.Contains(err.Error(), "身份") {
				t.Fatalf("unknown identity error=%v", err)
			}
			if fault.removed {
				t.Fatal("unknown object was passed to Remove")
			}
			assertCopyBytes(t, afs, fault.part, "new-source")
			assertCopyBytes(t, afs, "/source.txt", "new-source")
			assertCopyBytes(t, afs, "/target.txt", "old-target")
		})
	}
}
