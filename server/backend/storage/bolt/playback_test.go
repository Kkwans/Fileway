package bolt

import (
	"fmt"
	"path/filepath"
	"testing"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/playback"
)

func TestPlaybackBackendUpdatesZeroPositionAndDurationInOneRecord(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "playback.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := playbackBackend{db: db}
	entry := &playback.Entry{
		ID: "playback", UserID: 7, Path: "/video.mp4", Identity: "v1:10:20",
		Position: 42.5, Duration: 90, UpdatedAt: 1,
	}
	if err := backend.Save(entry); err != nil {
		t.Fatal(err)
	}
	entry.Identity = "v1:10:21"
	entry.Position = 0
	entry.Duration = 0
	entry.UpdatedAt = 2
	if err := backend.Update(entry); err != nil {
		t.Fatal(err)
	}

	loaded, err := backend.GetByID(entry.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Identity != entry.Identity || loaded.Position != 0 || loaded.Duration != 0 || loaded.UpdatedAt != 2 {
		t.Fatalf("updated playback = %#v", loaded)
	}
}

func TestPlaybackStorageMergesMountAliasesByFileIdentity(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "playback-alias.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = db.Close() }()
	storage := playback.NewStorage(playbackBackend{db: db})
	if _, err := storage.Save(7, "/home/Kkwans/TV/Loki.EP02.mkv", "v1:same", 10, 100); err != nil {
		t.Fatal(err)
	}
	entry, err := storage.Save(7, "/volume1/@home/Kkwans/TV/Loki.EP02.mkv", "v1:same", 20, 100)
	if err != nil {
		t.Fatal(err)
	}
	if entry.Path != "/home/Kkwans/TV/Loki.EP02.mkv" || entry.Position != 20 {
		t.Fatalf("alias entry = %#v", entry)
	}
	recent, err := storage.Recent(7, 10)
	if err != nil || len(recent) != 1 || recent[0].Position != 20 {
		t.Fatalf("recent = %#v, %v", recent, err)
	}
	loaded, err := storage.Get(7, "/volume1/@home/Kkwans/TV/Loki.EP02.mkv", "v1:same")
	if err != nil || loaded.Position != 20 {
		t.Fatalf("alias get = %#v, %v", loaded, err)
	}
}

func TestPlaybackRecentDeduplicatesExistingMountAliases(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "playback-recent-alias.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = db.Close() }()
	backend := playbackBackend{db: db}
	for _, entry := range []*playback.Entry{
		{ID: "older", UserID: 7, Path: "/home/Kkwans/TV/Loki.EP02.mkv", Identity: "v1:same", Position: 10, UpdatedAt: 1},
		{ID: "newer", UserID: 7, Path: "/volume1/@home/Kkwans/TV/Loki.EP02.mkv", Identity: "v1:same", Position: 20, UpdatedAt: 2},
	} {
		if err := backend.Save(entry); err != nil {
			t.Fatal(err)
		}
	}
	recent, err := playback.NewStorage(backend).Recent(7, 10)
	if err != nil || len(recent) != 1 || recent[0].Position != 20 {
		t.Fatalf("recent = %#v, %v", recent, err)
	}
}

func TestPlaybackClearIncludesMoreThanRecentPageAndKeepsOtherOwner(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "playback-clear.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = db.Close() }()
	storage := playback.NewStorage(playbackBackend{db: db})
	for i := 0; i < 105; i++ {
		if _, err := storage.Save(7, fmt.Sprintf("/%d.mp4", i), "identity", 0, 1); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := storage.Save(8, "/private.mp4", "identity", 0, 1); err != nil {
		t.Fatal(err)
	}
	count, err := storage.DeleteSelected(7, nil)
	if err != nil || count != 105 {
		t.Fatal(count, err)
	}
	if _, err := storage.Get(8, "/private.mp4", "identity"); err != nil {
		t.Fatal(err)
	}
}
