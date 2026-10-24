package history

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"sync"
	"time"
)

const MaxEntriesPerUser = 500

var ErrNotExist = errors.New("history entry not found")

type Status string

const (
	StatusSuccess   Status = "success"
	StatusFailed    Status = "failed"
	StatusSubmitted Status = "submitted"
)

// Entry intentionally hides UserID from JSON. History is always scoped to the
// authenticated user and is not an administrator-wide audit log.
type Entry struct {
	ID        string `json:"id" storm:"id"`
	UserID    uint   `json:"-" storm:"index"`
	Action    string `json:"action" storm:"index"`
	Target    string `json:"target"`
	Detail    string `json:"detail,omitempty"`
	Status    Status `json:"status" storm:"index"`
	CreatedAt int64  `json:"createdAt" storm:"index"`
}

func (entry *Entry) Clone() *Entry {
	if entry == nil {
		return nil
	}
	clone := *entry
	return &clone
}

type StorageBackend interface {
	GetByUser(userID uint) ([]*Entry, error)
	Save(entry *Entry) error
	Delete(id string) error
}

type Storage struct {
	back          StorageBackend
	mu            sync.Mutex
	lastCreatedAt int64
}

func NewStorage(back StorageBackend) *Storage {
	return &Storage{back: back}
}

func (storage *Storage) Record(userID uint, action, target, detail string, status Status) (*Entry, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	id, err := generateID()
	if err != nil {
		return nil, err
	}
	createdAt := time.Now().UnixMilli()
	if createdAt <= storage.lastCreatedAt {
		createdAt = storage.lastCreatedAt + 1
	}
	storage.lastCreatedAt = createdAt
	entry := &Entry{
		ID: id, UserID: userID, Action: action, Target: target,
		Detail: detail, Status: status, CreatedAt: createdAt,
	}
	if err := storage.back.Save(entry); err != nil {
		return nil, err
	}
	if err := storage.prune(userID); err != nil {
		return nil, err
	}
	return entry.Clone(), nil
}

func (storage *Storage) List(userID uint, limit int) ([]*Entry, error) {
	entries, err := storage.back.GetByUser(userID)
	if err != nil {
		return nil, err
	}
	sortEntries(entries)
	if limit <= 0 || limit > MaxEntriesPerUser {
		limit = MaxEntriesPerUser
	}
	if len(entries) > limit {
		entries = entries[:limit]
	}
	result := make([]*Entry, len(entries))
	for index, entry := range entries {
		result[index] = entry.Clone()
	}
	return result, nil
}

// DeleteAll removes the current user's history entries. The operation is
// intentionally scoped by user here rather than relying on the HTTP layer so
// callers cannot accidentally clear another user's activity timeline.
func (storage *Storage) DeleteAll(userID uint) (int, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	entries, err := storage.back.GetByUser(userID)
	if err != nil {
		return 0, err
	}
	deleted := 0
	for _, entry := range entries {
		if err := storage.back.Delete(entry.ID); err != nil {
			return deleted, err
		}
		deleted++
	}
	return deleted, nil
}

// DeleteSelected is scoped in storage as well as HTTP: IDs belonging to other
// users are never looked up or removed, even when the caller is an admin.
func (storage *Storage) DeleteSelected(userID uint, ids []string) (int, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	wanted := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		wanted[id] = struct{}{}
	}
	entries, err := storage.back.GetByUser(userID)
	if err != nil {
		return 0, err
	}
	deleted := 0
	for _, entry := range entries {
		if _, ok := wanted[entry.ID]; !ok {
			continue
		}
		if err := storage.back.Delete(entry.ID); err != nil {
			return deleted, err
		}
		deleted++
	}
	return deleted, nil
}

func (storage *Storage) prune(userID uint) error {
	entries, err := storage.back.GetByUser(userID)
	if err != nil {
		return err
	}
	sortEntries(entries)
	for _, entry := range entries[minimum(len(entries), MaxEntriesPerUser):] {
		if err := storage.back.Delete(entry.ID); err != nil {
			return err
		}
	}
	return nil
}

func sortEntries(entries []*Entry) {
	sort.SliceStable(entries, func(left, right int) bool {
		if entries[left].CreatedAt == entries[right].CreatedAt {
			return entries[left].ID > entries[right].ID
		}
		return entries[left].CreatedAt > entries[right].CreatedAt
	})
}

func minimum(left, right int) int {
	if left < right {
		return left
	}
	return right
}

func generateID() (string, error) {
	random := make([]byte, 8)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	return time.Now().UTC().Format("20060102T150405.000000000") + "-" + hex.EncodeToString(random), nil
}
