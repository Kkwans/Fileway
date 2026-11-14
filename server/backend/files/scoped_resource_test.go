package files

import (
	"context"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/spf13/afero"
)

func scopedFixture(t *testing.T) (string, ScopedAuthority, *ScopedRoot) {
	t.Helper()
	base := t.TempDir()
	for _, name := range []string{"media", "media/nested", "outside"} {
		if err := os.MkdirAll(filepath.Join(base, filepath.FromSlash(name)), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	for name, content := range map[string]string{"media/one.mp4": "0123456789", "media/nested/two.mp4": "nested", "media/denied.mp4": "secret", "outside/private.mp4": "outside"} {
		if err := os.WriteFile(filepath.Join(base, filepath.FromSlash(name)), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	authority := ScopedAuthority{Filesystem: afero.NewBasePathFs(afero.NewOsFs(), base), ServerRoot: base, Check: func(value string) bool { return value != "/media/denied.mp4" }}
	root, err := OpenScopedRoot(t.Context(), authority, "/media")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = root.Close() })
	return base, authority, root
}

func TestScopedResourceAuthorizedReadsAndBoundedLiveEnumeration(t *testing.T) {
	_, _, root := scopedFixture(t)
	caps := root.Capabilities()
	if !caps.SafeHandleRead || caps.Enumeration != "bounded_live" || caps.Notifications != "none" || caps.FreshAbsence != "unknown" || caps.ChangeEvidence != "observed_only" && caps.ChangeEvidence != "coherent_native" || caps.ObjectIdentity != "native_generation" && caps.ObjectIdentity != "mount_scoped" && caps.ObjectIdentity != "path_observed" || caps.ChangeEvidence == "coherent_native" && caps.ObjectIdentity != "native_generation" {
		t.Fatalf("capability overstated: %+v", caps)
	}
	file, err := root.OpenRegular(t.Context(), "nested/two.mp4")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = file.Close() }()
	buffer := make([]byte, 3)
	n, err := file.ReadAt(buffer, 2)
	if err != nil || n != 3 || string(buffer) != "ste" {
		t.Fatalf("ReadAt: %q %d %v", buffer, n, err)
	}
	state, err := file.State(t.Context(), ScopedObserved)
	if err != nil || state.Info == nil || state.Size != 6 {
		t.Fatalf("state: %+v %v", state, err)
	}
	if _, err := file.State(t.Context(), "unrecognized"); !errors.Is(err, fs.ErrInvalid) {
		t.Fatalf("invalid freshness: %v", err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := file.ReadAtContext(ctx, buffer, 0); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled read: %v", err)
	}
	for _, name := range []string{"../outside/private.mp4", "/one.mp4", "nested/../one.mp4", "denied.mp4", "missing\x00.mp4", ".fileway-media/catalog.db"} {
		if value, err := root.OpenRegular(t.Context(), name); err == nil {
			_ = value.Close()
			t.Fatalf("unsafe child accepted: %q", name)
		}
	}
	dir, err := root.OpenDirectory(t.Context(), "")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = dir.Close() }()
	var names []string
	for i := 0; i < 20; i++ {
		page, eof, err := dir.ReadBatch(t.Context(), 1)
		if err != nil {
			t.Fatal(err)
		}
		for _, item := range page {
			names = append(names, string(item.Name))
		}
		if eof {
			break
		}
		if i == 19 {
			t.Fatal("directory did not reach EOF")
		}
	}
	sort.Strings(names)
	if len(names) != 2 || names[0] != "nested" || names[1] != "one.mp4" {
		t.Fatalf("unfiltered directory entries: %q", names)
	}
	second, err := root.OpenDirectory(t.Context(), "")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = second.Close() }()
	page, eof, err := second.ReadBatch(t.Context(), 256)
	if err != nil || eof && len(page) == 0 || len(page) != 2 {
		t.Fatalf("independent cursor: %+v %v %v", page, eof, err)
	}
	if _, _, err := second.ReadBatch(t.Context(), 257); !errors.Is(err, fs.ErrInvalid) {
		t.Fatalf("unbounded batch accepted: %v", err)
	}
	second.batch <- struct{}{}
	if _, _, err := second.ReadBatch(ctx, 1); !errors.Is(err, context.Canceled) {
		t.Fatalf("blocked directory page ignored cancellation: %v", err)
	}
	<-second.batch
	if absence, err := root.ConfirmAbsence(t.Context(), "gone.mp4"); err != nil || absence.State != "suspected_absent" || absence.Freshness != "unknown" {
		t.Fatalf("missing certainty: %+v %v", absence, err)
	}
	if presence, err := root.ConfirmAbsence(t.Context(), "one.mp4"); err != nil || presence.State != "present" || presence.Freshness != "unknown" {
		t.Fatalf("present: %+v %v", presence, err)
	}
}

func TestScopedRootHandleSurvivesRenameButDoesNotRetarget(t *testing.T) {
	base, _, root := scopedFixture(t)
	if err := os.Rename(filepath.Join(base, "media"), filepath.Join(base, "old-media")); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(base, "media"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(base, "media", "one.mp4"), []byte("replacement"), 0o600); err != nil {
		t.Fatal(err)
	}
	file, err := root.OpenRegular(t.Context(), "one.mp4")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = file.Close() }()
	data, err := io.ReadAll(io.NewSectionReader(file, 0, 10))
	if err != nil || string(data) != "0123456789" {
		t.Fatalf("root silently retargeted: %q %v", data, err)
	}
	dir, err := root.OpenDirectory(t.Context(), "")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = dir.Close() }()
	dir.batch <- struct{}{}
	// A held root is not proof the current path still names it. A2 must perform
	// binding/domain revalidation before publishing data from this API.
	if err := root.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := dir.ReadBatch(t.Context(), 1); !errors.Is(err, fs.ErrClosed) {
		t.Fatalf("closed root did not fence queued directory read: %v", err)
	}
	<-dir.batch
	if _, err := root.OpenRegular(t.Context(), "one.mp4"); !errors.Is(err, fs.ErrClosed) {
		t.Fatalf("closed root usable: %v", err)
	}
}

func TestScopedResourceRejectsPrivateDatabaseHardLink(t *testing.T) {
	base, _, root := scopedFixture(t)
	private := filepath.Join(base, MediaLibraryDirectory)
	if err := os.Mkdir(private, 0o700); err != nil {
		t.Fatal(err)
	}
	database := filepath.Join(private, "catalog.db")
	if err := os.WriteFile(database, []byte("private"), 0o600); err != nil {
		t.Fatal(err)
	}
	release, err := ProtectMediaLibraryDatabase(database)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = release() }()
	if err := os.Link(database, filepath.Join(base, "media", "alias.mp4")); err != nil {
		t.Fatal(err)
	}
	if file, err := root.OpenRegular(t.Context(), "alias.mp4"); err == nil {
		_ = file.Close()
		t.Fatal("private hard link opened")
	}
}
