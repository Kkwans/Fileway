package mediascan

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"github.com/spf13/afero"
)

type observationSink struct {
	observations []Observation
	after        func(Observation)
}

func (*observationSink) LookupRevision(context.Context, []byte) (int64, error) { return 0, nil }

func (s *observationSink) Observe(_ context.Context, observation Observation) error {
	s.observations = append(s.observations, observation)
	if s.after != nil {
		s.after(observation)
	}
	return nil
}

type scanFixture struct {
	root    string
	binding *medialibrary.LibrarySource
	mu      sync.Mutex
	allowed bool
	calls   int
}

func newScanFixture(t *testing.T) *scanFixture {
	t.Helper()
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "media"), 0o700); err != nil {
		t.Fatal(err)
	}
	return &scanFixture{root: root, allowed: true, binding: &medialibrary.LibrarySource{
		LibraryID: "library", Source: medialibrary.LocalSource{ID: "source", OwnerUserID: 7, AuthorizationRevision: 1}, RootLocator: []byte("/media"), Revision: 1,
	}}
}

func (f *scanFixture) resolve(context.Context) (mediasource.LocalAuthority, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	return mediasource.LocalAuthority{
		FS: afero.NewBasePathFs(afero.NewOsFs(), f.root), ServerRoot: f.root, CanRead: f.allowed,
		Check: func(string) bool { return true },
	}, nil
}

func (f *scanFixture) write(t *testing.T, relative string, content string) {
	t.Helper()
	name := filepath.Join(f.root, "media", relative)
	if err := os.MkdirAll(filepath.Dir(name), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(name, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestScanLocalEnumeratesBoundedPagesAndRecordsNFOErrorsPerItem(t *testing.T) {
	f := newScanFixture(t)
	f.write(t, "folder/good.mp4", "video")
	f.write(t, "folder/good.nfo", "<movie><title>Local title</title></movie>")
	f.write(t, "folder/bad.MKV", "video")
	f.write(t, "folder/bad.nfo", "<movie><title>")
	f.write(t, "folder/ignored.txt", "no media")
	f.write(t, "folder/external.strm", "https://example.invalid")
	f.write(t, ".fileway-media/internal.mp4", "private namespace")
	for index := range 70 {
		f.write(t, fmt.Sprintf("many/%03d.mkv", index), "video")
	}
	sink := &observationSink{}
	result, err := ScanLocal(t.Context(), 7, f.binding, f.resolve, sink, Options{ReadBatch: 7})
	if err != nil {
		t.Fatal(err)
	}
	if result.Directories != 3 || result.Discovered != 72 || result.Observed != 72 || result.NFOFailed != 1 || len(sink.observations) != 72 {
		t.Fatalf("unexpected counts: %+v", result)
	}
	found := map[string]Observation{}
	for _, observation := range sink.observations {
		found[string(observation.Locator)] = observation
		if observation.OwnerUserID != 7 || observation.Binding.SourceID != "source" || !bytes.Equal(observation.Binding.RootLocator, []byte("/media")) || len(observation.ObjectKey) == 0 || observation.SourceVersion == "" {
			t.Fatalf("incomplete observation: %+v", observation)
		}
	}
	good := found["/media/folder/good.mp4"]
	if good.NFOStatus != NFOParsed || good.NFO == nil || len(good.NFO.Items) != 1 || good.NFO.Items[0].Fields["title"][0] != "Local title" {
		t.Fatalf("good sidecar: %+v", good)
	}
	bad := found["/media/folder/bad.MKV"]
	if bad.NFOStatus != NFOInvalid || bad.NFO != nil {
		t.Fatalf("bad sidecar: %+v", bad)
	}
	if f.calls < result.Observed*2 {
		t.Fatalf("source authority was not resolved for each file: %d", f.calls)
	}
}

func TestScanLocalCancellationAndRevocationStopBeforeNextObservation(t *testing.T) {
	for _, mode := range []string{"cancel", "revoke"} {
		t.Run(mode, func(t *testing.T) {
			f := newScanFixture(t)
			for index := range 5 {
				f.write(t, fmt.Sprintf("%d.mp4", index), "video")
			}
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			sink := &observationSink{}
			sink.after = func(Observation) {
				if mode == "cancel" {
					cancel()
				} else {
					f.mu.Lock()
					f.allowed = false
					f.mu.Unlock()
				}
			}
			result, err := ScanLocal(ctx, 7, f.binding, f.resolve, sink, Options{ReadBatch: 1})
			want := error(context.Canceled)
			if mode == "revoke" {
				want = fs.ErrPermission
			}
			if !errors.Is(err, want) || result.Observed != 1 || len(sink.observations) != 1 {
				t.Fatalf("result=%+v observations=%d error=%v", result, len(sink.observations), err)
			}
		})
	}
}

func TestScanLocalPreservesNativeIdentityAcrossRepeatAndRejectsReplacementIdentity(t *testing.T) {
	f := newScanFixture(t)
	f.write(t, "same.mp4", "original")
	first := &observationSink{}
	if _, err := ScanLocal(t.Context(), 7, f.binding, f.resolve, first, Options{}); err != nil {
		t.Fatal(err)
	}
	second := &observationSink{}
	if _, err := ScanLocal(t.Context(), 7, f.binding, f.resolve, second, Options{}); err != nil {
		t.Fatal(err)
	}
	if len(first.observations) != 1 || len(second.observations) != 1 || !bytes.Equal(first.observations[0].ObjectKey, second.observations[0].ObjectKey) {
		t.Fatal("unchanged source did not retain object evidence")
	}
	if first.observations[0].Identity != IdentityNative {
		t.Skip("fixture filesystem does not expose birth-qualified native identity")
	}
	// Prepare a different object before removing the original, so inode reuse
	// cannot make this test a function of filesystem allocation policy.
	f.write(t, "replacement.tmp", "new bytes")
	if err := os.Remove(filepath.Join(f.root, "media", "same.mp4")); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(f.root, "media", "replacement.tmp"), filepath.Join(f.root, "media", "same.mp4")); err != nil {
		t.Fatal(err)
	}
	replaced := &observationSink{}
	if _, err := ScanLocal(t.Context(), 7, f.binding, f.resolve, replaced, Options{}); err != nil {
		t.Fatal(err)
	}
	if len(replaced.observations) != 1 || bytes.Equal(first.observations[0].ObjectKey, replaced.observations[0].ObjectKey) {
		t.Fatal("replacement inherited native identity")
	}
}

func TestScanLocalChangedDirectoryDoesNotComplete(t *testing.T) {
	f := newScanFixture(t)
	f.write(t, "one.mp4", "video")
	sink := &observationSink{after: func(Observation) { f.write(t, "late.txt", "changed") }}
	_, err := ScanLocal(t.Context(), 7, f.binding, f.resolve, sink, Options{ReadBatch: 1})
	if !errors.Is(err, ErrDirectoryChanged) {
		t.Fatalf("changed directory should abort: %v", err)
	}
}

func TestScanLocalRejectsWrongOwnerBeforeResolvingAuthority(t *testing.T) {
	f := newScanFixture(t)
	_, err := ScanLocal(t.Context(), 8, f.binding, f.resolve, &observationSink{}, Options{})
	if !errors.Is(err, fs.ErrInvalid) || f.calls != 0 {
		t.Fatalf("owner mismatch: %v, authority calls=%d", err, f.calls)
	}
}

func TestScanLocalRechecksPerFileScope(t *testing.T) {
	f := newScanFixture(t)
	f.write(t, "denied.mp4", "video")
	resolve := func(ctx context.Context) (mediasource.LocalAuthority, error) {
		authority, err := f.resolve(ctx)
		authority.Check = func(locator string) bool { return locator != "/media/denied.mp4" }
		return authority, err
	}
	sink := &observationSink{}
	_, err := ScanLocal(t.Context(), 7, f.binding, resolve, sink, Options{})
	if !errors.Is(err, fs.ErrPermission) || len(sink.observations) != 0 {
		t.Fatalf("denied file entered catalog: observations=%d error=%v", len(sink.observations), err)
	}
}

func TestScanLocalRejectsPrivateCatalogHardLink(t *testing.T) {
	f := newScanFixture(t)
	f.write(t, ".fileway-media/catalog.db", "private")
	database := filepath.Join(f.root, "media", ".fileway-media", "catalog.db")
	release, err := files.ProtectMediaLibraryDatabase(database)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := release(); err != nil {
			t.Error(err)
		}
	})
	if err := os.Link(database, filepath.Join(f.root, "media", "alias.mp4")); err != nil {
		t.Skipf("fixture filesystem cannot create hard link: %v", err)
	}
	sink := &observationSink{}
	_, err = ScanLocal(t.Context(), 7, f.binding, f.resolve, sink, Options{})
	if !errors.Is(err, fs.ErrPermission) || len(sink.observations) != 0 {
		t.Fatalf("private alias entered catalog: observations=%d error=%v", len(sink.observations), err)
	}
}
