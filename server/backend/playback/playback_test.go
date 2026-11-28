package playback

import (
	"errors"
	"sync"
	"testing"
)

type memoryBackend struct {
	entries map[string]*Entry
}

func newMemoryBackend() *memoryBackend {
	return &memoryBackend{entries: make(map[string]*Entry)}
}

func (backend *memoryBackend) GetByID(id string) (*Entry, error) {
	entry := backend.entries[id]
	if entry == nil {
		return nil, ErrNotExist
	}
	return entry.Clone(), nil
}

func (backend *memoryBackend) Save(entry *Entry) error {
	if backend.entries[entry.ID] != nil {
		return errors.New("duplicate")
	}
	backend.entries[entry.ID] = entry.Clone()
	return nil
}

func (backend *memoryBackend) Update(entry *Entry) error {
	if backend.entries[entry.ID] == nil {
		return ErrNotExist
	}
	backend.entries[entry.ID] = entry.Clone()
	return nil
}

func (backend *memoryBackend) FindByIdentity(userID uint, identity string) ([]*Entry, error) {
	var result []*Entry
	for _, entry := range backend.entries {
		if entry.UserID == userID && entry.Identity == identity {
			result = append(result, entry.Clone())
		}
	}
	return result, nil
}

func (backend *memoryBackend) Delete(id string) error {
	if backend.entries[id] == nil {
		return ErrNotExist
	}
	delete(backend.entries, id)
	return nil
}

func TestStorageKeepsExactPositionAndSeparatesUsers(t *testing.T) {
	storage := NewStorage(newMemoryBackend())
	entry, err := storage.Save(7, "videos/film.mp4", "v1:100:200", 99.875, 100)
	if err != nil {
		t.Fatal(err)
	}
	if entry.Path != "/videos/film.mp4" || entry.Position != 99.875 {
		t.Fatalf("entry = %#v", entry)
	}
	loaded, err := storage.Get(7, "/videos/film.mp4", "v1:100:200")
	if err != nil || loaded.Position != 99.875 {
		t.Fatalf("loaded = %#v, err = %v", loaded, err)
	}
	if _, err := storage.Get(8, "/videos/film.mp4", "v1:100:200"); !errors.Is(err, ErrNotExist) {
		t.Fatalf("other user err = %v", err)
	}
}

func TestStorageInvalidatesReplacedFileAndSupportsExplicitClear(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	if _, err := storage.Save(3, "/video.mkv", "v1:10:20", 12.5, 90); err != nil {
		t.Fatal(err)
	}
	if _, err := storage.Get(3, "/video.mkv", "v1:11:21"); !errors.Is(err, ErrNotExist) {
		t.Fatalf("replacement err = %v", err)
	}
	if len(backend.entries) != 0 {
		t.Fatalf("stale entries = %d", len(backend.entries))
	}
	if _, err := storage.Save(3, "/video.mkv", "v1:11:21", 0, 90); err != nil {
		t.Fatal(err)
	}
	if err := storage.Delete(3, "video.mkv"); err != nil {
		t.Fatal(err)
	}
	if err := storage.Delete(3, "video.mkv"); err != nil {
		t.Fatalf("idempotent delete = %v", err)
	}
}

func TestStorageMergesPathAliasesByFileIdentity(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	if _, err := storage.Save(7, "/home/Kkwans/TV/Loki.EP02.mkv", "v1:same", 10, 100); err != nil {
		t.Fatal(err)
	}
	entry, err := storage.Save(7, "/volume1/@home/Kkwans/TV/Loki.EP02.mkv", "v1:same", 20, 100)
	if err != nil {
		t.Fatal(err)
	}
	if entry.Path != "/home/Kkwans/TV/Loki.EP02.mkv" || entry.Position != 20 || len(backend.entries) != 1 {
		t.Fatalf("alias save = %#v entries=%d", entry, len(backend.entries))
	}
	for _, alias := range []string{"/home/Kkwans/TV/Loki.EP02.mkv", "/volume1/@home/Kkwans/TV/Loki.EP02.mkv"} {
		loaded, err := storage.Get(7, alias, "v1:same")
		if err != nil || loaded.Position != 20 {
			t.Fatalf("Get(%q) = %#v, %v", alias, loaded, err)
		}
	}
}

func TestStorageDoesNotMergeDifferentFilesWithSameIdentity(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	if _, err := storage.Save(7, "/one/film.mkv", "v1:same", 10, 100); err != nil {
		t.Fatal(err)
	}
	if _, err := storage.Save(7, "/two/other.mkv", "v1:same", 20, 100); err != nil {
		t.Fatal(err)
	}
	if len(backend.entries) != 2 {
		t.Fatalf("different files were merged: %d", len(backend.entries))
	}
}

func TestStorageSerializesConcurrentSaveAndDelete(t *testing.T) {
	storage := NewStorage(newMemoryBackend())
	const operations = 64
	errCh := make(chan error, operations*2)
	var wg sync.WaitGroup
	for i := 0; i < operations; i++ {
		wg.Add(2)
		go func(position float64) {
			defer wg.Done()
			_, err := storage.Save(9, "/race.mp4", "v1:1:1", position, 120)
			errCh <- err
		}(float64(i))
		go func() {
			defer wg.Done()
			errCh <- storage.Delete(9, "/race.mp4")
		}()
	}
	wg.Wait()
	close(errCh)
	for err := range errCh {
		if err != nil {
			t.Fatalf("concurrent playback mutation failed: %v", err)
		}
	}
}
