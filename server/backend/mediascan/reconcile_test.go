package mediascan

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

func TestMissingCandidateRequiresVerifiedAbsenceAndLiveAuthority(t *testing.T) {
	f := newScanFixture(t)
	f.write(t, "folder/present.mp4", "video")
	if err := os.Mkdir(filepath.Join(f.root, "media", "folder", "directory.mp4"), 0o700); err != nil {
		t.Fatal(err)
	}
	source, err := mediasource.NewLocal(string(f.binding.RootLocator), f.resolve)
	if err != nil {
		t.Fatal(err)
	}
	w := walker{source: source, binding: f.binding.Ref(), options: Options{ReadBatch: 1, MaxDepth: 8}}
	for _, name := range []string{"/media/folder/not-there.mp4", "/media/absent/subfolder/file.mp4"} {
		missing, err := w.missingCandidate(t.Context(), []byte(name))
		if err != nil || !missing {
			t.Fatalf("%q: missing=%t err=%v", name, missing, err)
		}
	}
	for _, name := range []string{"/media/folder/present.mp4", "/media/folder/directory.mp4"} {
		missing, _ := w.missingCandidate(t.Context(), []byte(name))
		if missing {
			t.Fatalf("existing path %q marked missing", name)
		}
	}
	if _, err := w.missingCandidate(t.Context(), []byte("/media2/not-there.mp4")); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("locator outside binding: %v", err)
	}
	f.mu.Lock()
	f.allowed = false
	f.mu.Unlock()
	if missing, err := w.missingCandidate(t.Context(), []byte("/media/folder/not-there.mp4")); missing || !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("revoked access: missing=%t err=%v", missing, err)
	}
}
