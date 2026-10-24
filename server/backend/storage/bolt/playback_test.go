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

func TestPlaybackClearIncludesMoreThanRecentPageAndKeepsOtherOwner(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "playback-clear.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
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
