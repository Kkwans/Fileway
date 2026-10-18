package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path"
	"runtime"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/spf13/afero"
)

type publicationFailureFS struct {
	afero.Fs
	attempted, removedTarget bool
}

func (fs *publicationFailureFS) Rename(from, to string) error {
	if strings.HasPrefix(path.Base(from), ".nfb-transfer-") && to == "/target.txt" {
		fs.attempted = true
		return os.ErrPermission
	}
	return fs.Fs.Rename(from, to)
}

func (fs *publicationFailureFS) RemoveAll(name string) error {
	if name == "/target.txt" {
		fs.removedTarget = true
	}
	return fs.Fs.RemoveAll(name)
}

func assertPublicationFailedWithoutCheckpoint(t *testing.T, encoded json.RawMessage, err error, checkpoint json.RawMessage) {
	t.Helper()
	var result fileTransferResult
	if err == nil || json.Unmarshal(encoded, &result) != nil || len(result.Failed) != 1 || len(result.Completed) != 0 {
		t.Fatalf("failed publication must remain failed: err=%v result=%s", err, encoded)
	}
	var saved fileTransferCheckpoint
	if len(checkpoint) != 0 && (json.Unmarshal(checkpoint, &saved) != nil || len(saved.Completed) != 0) {
		t.Fatalf("failed publication checkpoint=%s", checkpoint)
	}
}

func TestFileTransferPublicationFailurePreservesSourceAndOldTarget(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		t.Run(action, func(t *testing.T) {
			h, d, task, args := permissionTransferFixture(t, action, true)
			wrapped := &publicationFailureFS{Fs: d.user.Fs}
			h.fs[d.user.ID], d.user.Fs = wrapped, wrapped
			var checkpoint json.RawMessage
			encoded, err := fileTransferRunner(d, task, args)(context.Background(), func(p tasks.Progress) error {
				if len(p.Checkpoint) != 0 {
					checkpoint = append(json.RawMessage(nil), p.Checkpoint...)
				}
				return nil
			})
			assertPublicationFailedWithoutCheckpoint(t, encoded, err, checkpoint)
			if !wrapped.attempted {
				t.Fatal("publication fault was not reached after real temp copy")
			}
			assertWireOwned(t, wrapped, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
			if wrapped.removedTarget {
				t.Fatal("regular-file publication removed the existing target before commit")
			}
			assertNoTransferTemp(t, wrapped)
		})
	}
}

// Return the observed absence, but create another writer's target immediately
// afterwards. This deterministically exercises the Stat-to-publication window.
type publicationRaceFS struct {
	afero.Fs
	injected bool
}

func (fs *publicationRaceFS) Stat(name string) (os.FileInfo, error) {
	info, err := fs.Fs.Stat(name)
	if name == "/target.txt" && errors.Is(err, os.ErrNotExist) && !fs.injected {
		fs.injected = true
		if writeErr := afero.WriteFile(fs.Fs, name, []byte("other-writer"), 0o600); writeErr != nil {
			return nil, writeErr
		}
	}
	return info, err
}

func TestFileTransferPublicationNoOverwriteRetainsConcurrentTarget(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		t.Run(action, func(t *testing.T) {
			h, d, task, args := permissionTransferFixture(t, action, false)
			wrapped := &publicationRaceFS{Fs: d.user.Fs}
			h.fs[d.user.ID], d.user.Fs = wrapped, wrapped
			var checkpoint json.RawMessage
			encoded, err := fileTransferRunner(d, task, args)(context.Background(), func(p tasks.Progress) error {
				if len(p.Checkpoint) != 0 {
					checkpoint = append(json.RawMessage(nil), p.Checkpoint...)
				}
				return nil
			})
			if !wrapped.injected {
				t.Fatal("Stat-to-publication race was not reached")
			}
			assertPublicationFailedWithoutCheckpoint(t, encoded, err, checkpoint)
			assertWireOwned(t, wrapped, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "other-writer"})
			assertNoTransferTemp(t, wrapped)
		})
	}
}

func TestFileTransferPublicationRegularFileCannotDestroyDirectoryTarget(t *testing.T) {
	h, d, task, args := permissionTransferFixture(t, "copy", false)
	args.Items[0].Overwrite = true
	if err := d.user.Fs.MkdirAll("/target.txt/nested", 0o700); err != nil {
		t.Fatal(err)
	}
	writeWireOwned(t, d.user.Fs, map[string]string{"/target.txt/nested/owned.txt": "owned-old-tree"})
	encoded, err := fileTransferRunner(d, task, args)(context.Background(), func(tasks.Progress) error { return nil })
	assertPublicationFailedWithoutCheckpoint(t, encoded, err, nil)
	assertWireOwned(t, h.fs[d.user.ID], map[string]string{"/source.txt": "owned-new-content", "/target.txt/nested/owned.txt": "owned-old-tree"})
	assertNoTransferTemp(t, d.user.Fs)
}

func TestFileTransferPublicationWindowsLockedTargetPreservesBothFiles(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Windows native sharing constraint; exercised by the native Windows gate")
	}
	_, d, task, args := permissionTransferFixture(t, "copy", true)
	// Go's Windows open handle shares reads/writes but not deletion. Keep it
	// open across the native publication to exercise a real sharing failure.
	handle, err := d.user.Fs.Open("/target.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = handle.Close() }()
	encoded, err := fileTransferRunner(d, task, args)(context.Background(), func(tasks.Progress) error { return nil })
	assertPublicationFailedWithoutCheckpoint(t, encoded, err, nil)
	assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
	assertNoTransferTemp(t, d.user.Fs)
}
