package playback

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

var ErrNotExist = errors.New("playback position not found")
var ErrRevisionChanged = errors.New("playback history revision changed")

// Entry stores one user's last position for one normalized video path. The
// file identity prevents a replacement file at the same path from inheriting
// stale progress.
type Entry struct {
	ID        string  `json:"-" storm:"id"`
	UserID    uint    `json:"-" storm:"index"`
	Path      string  `json:"path" storm:"index"`
	Identity  string  `json:"identity"`
	Position  float64 `json:"position"`
	Duration  float64 `json:"duration"`
	UpdatedAt int64   `json:"updatedAt" storm:"index"`
}

func (entry *Entry) Clone() *Entry {
	if entry == nil {
		return nil
	}
	clone := *entry
	return &clone
}

type StorageBackend interface {
	GetByID(id string) (*Entry, error)
	Save(entry *Entry) error
	Update(entry *Entry) error
	Delete(id string) error
}

type Storage struct {
	back StorageBackend
	mu   sync.Mutex
}

func NewStorage(back StorageBackend) *Storage {
	return &Storage{back: back}
}

// Recent is a read-only snapshot; existing position backends remain compatible.
func (storage *Storage) Recent(userID uint, limit int) ([]*Entry, error) {
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("invalid playback history limit")
	}
	lister, ok := storage.back.(interface {
		Recent(uint, int) ([]*Entry, error)
	})
	if !ok {
		return nil, fmt.Errorf("playback history listing unavailable")
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	rows, err := lister.Recent(userID, limit)
	if err != nil {
		return nil, err
	}
	result := make([]*Entry, 0, len(rows))
	for _, entry := range rows {
		if entry != nil && entry.UserID == userID {
			result = append(result, entry.Clone())
		}
	}
	return result, nil
}

func (storage *Storage) Get(userID uint, value, identity string) (*Entry, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	value = pathmeta.Clean(value)
	entry, err := storage.back.GetByID(entryID(userID, value))
	if err != nil {
		return nil, err
	}
	if entry.UserID != userID || pathmeta.Clean(entry.Path) != value {
		return nil, ErrNotExist
	}
	if entry.Identity != identity {
		if err := storage.back.Delete(entry.ID); err != nil && !errors.Is(err, ErrNotExist) {
			return nil, err
		}
		return nil, ErrNotExist
	}
	return entry.Clone(), nil
}

func (storage *Storage) Save(userID uint, value, identity string, position, duration float64) (*Entry, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	value = pathmeta.Clean(value)
	id := entryID(userID, value)
	entry, err := storage.back.GetByID(id)
	switch {
	case err == nil:
		entry.UserID = userID
		entry.Path = value
		entry.Identity = identity
		entry.Position = position
		entry.Duration = duration
		entry.UpdatedAt = time.Now().UnixMilli()
		if err := storage.back.Update(entry); err != nil {
			return nil, err
		}
	case errors.Is(err, ErrNotExist):
		entry = &Entry{
			ID: id, UserID: userID, Path: value, Identity: identity,
			Position: position, Duration: duration, UpdatedAt: time.Now().UnixMilli(),
		}
		if err := storage.back.Save(entry); err != nil {
			return nil, err
		}
	default:
		return nil, err
	}
	return entry.Clone(), nil
}

func (storage *Storage) Delete(userID uint, value string) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	value = pathmeta.Clean(value)
	id := entryID(userID, value)
	entry, err := storage.back.GetByID(id)
	if errors.Is(err, ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if entry.UserID != userID || pathmeta.Clean(entry.Path) != value {
		return ErrNotExist
	}
	if err := storage.back.Delete(id); err != nil && !errors.Is(err, ErrNotExist) {
		return err
	}
	return nil
}

func entryID(userID uint, value string) string {
	digest := sha256.Sum256([]byte(pathmeta.Clean(value)))
	return fmt.Sprintf("%d:%x", userID, digest[:])
}

// DeleteRef identifies the exact progress revision shown to the user.
type DeleteRef struct {
	Path, Identity string
	UpdatedAt      int64
}

func (storage *Storage) DeleteSelected(userID uint, refs []DeleteRef) (int, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	var entries []*Entry
	if refs == nil {
		lister, ok := storage.back.(interface{ All(uint) ([]*Entry, error) })
		if !ok {
			return 0, fmt.Errorf("playback history clearing unavailable")
		}
		var err error
		entries, err = lister.All(userID)
		if err != nil {
			return 0, err
		}
	} else {
		seen := map[string]bool{}
		for _, ref := range refs {
			id := entryID(userID, ref.Path)
			if seen[id] {
				continue
			}
			seen[id] = true
			entry, err := storage.back.GetByID(id)
			if errors.Is(err, ErrNotExist) {
				continue
			}
			if err != nil {
				return 0, err
			}
			if entry.UserID != userID || pathmeta.Clean(entry.Path) != pathmeta.Clean(ref.Path) || entry.Identity != ref.Identity || entry.UpdatedAt != ref.UpdatedAt {
				return 0, ErrRevisionChanged
			}
			entries = append(entries, entry)
		}
	}
	deleted := make([]*Entry, 0)
	for _, entry := range entries {
		if entry.UserID != userID {
			continue
		}
		if err := storage.back.Delete(entry.ID); err != nil && !errors.Is(err, ErrNotExist) {
			for _, old := range deleted {
				err = errors.Join(err, storage.back.Save(old))
			}
			return 0, err
		}
		deleted = append(deleted, entry.Clone())
	}
	return len(deleted), nil
}
