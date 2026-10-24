package recent

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"path"
	"sort"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

const MaxEntriesPerUser = 100

var ErrNotExist = errors.New("recent entry not found")

// Entry is private to one authenticated user. UserID is persisted but never
// exposed by the HTTP representation.
type Entry struct {
	ID         string `json:"id" storm:"id"`
	UserID     uint   `json:"-" storm:"index"`
	Path       string `json:"path" storm:"index"`
	Name       string `json:"name"`
	IsDir      bool   `json:"isDir"`
	AccessedAt int64  `json:"accessedAt" storm:"index"`
	// A pre-byte-field row containing U+FFFD may have lost its original path.
	// Keep it for history, but never infer the identity of a Unicode sibling.
	PathUnverified bool `json:"-"`
}

// MarshalJSON mirrors resource metadata: path/name are display text while
// wirePath retains the original filesystem bytes, including non-UTF-8 names.
// Persistence continues to use the unmodified Entry fields.
func (entry *Entry) MarshalJSON() ([]byte, error) {
	type entryAlias Entry
	wirePath := ""
	if !entry.PathUnverified {
		wirePath = files.EncodeWirePath(entry.Path)
	}
	return json.Marshal(&struct {
		*entryAlias
		Path         string `json:"path"`
		Name         string `json:"name"`
		WirePath     string `json:"wirePath,omitempty"`
		PathVerified bool   `json:"pathVerified"`
	}{entryAlias: (*entryAlias)(entry), Path: files.DisplayPath(entry.Path),
		Name: files.DisplayName(entry.Name), WirePath: wirePath, PathVerified: !entry.PathUnverified})
}

func (entry *Entry) Clone() *Entry {
	if entry == nil {
		return nil
	}
	clone := *entry
	return &clone
}

type StorageBackend interface {
	GetAll() ([]*Entry, error)
	Save(entry *Entry) error
	Update(entry *Entry) error
	Delete(id string) error
}

type PathMutation struct {
	updated []Entry
	deleted []Entry
}

type Storage struct {
	back           StorageBackend
	mu             sync.Mutex
	lastAccessedAt int64
}

func NewStorage(back StorageBackend) *Storage {
	return &Storage{back: back}
}

func (storage *Storage) Record(userID uint, value string, name string, isDir bool) (*Entry, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	value = pathmeta.Clean(value)
	if name == "" {
		name = path.Base(value)
		if value == "/" {
			name = "根目录"
		}
	}
	all, err := storage.back.GetAll()
	if err != nil {
		return nil, err
	}
	accessedAt := time.Now().UnixMilli()
	if accessedAt <= storage.lastAccessedAt {
		accessedAt = storage.lastAccessedAt + 1
	}
	storage.lastAccessedAt = accessedAt

	var current *Entry
	duplicates := make([]*Entry, 0)
	for _, entry := range all {
		if entry.PathUnverified || entry.UserID != userID || pathmeta.Clean(entry.Path) != value {
			continue
		}
		if current == nil || entry.AccessedAt > current.AccessedAt {
			if current != nil {
				duplicates = append(duplicates, current)
			}
			current = entry
		} else {
			duplicates = append(duplicates, entry)
		}
	}

	if current == nil {
		id, err := generateID()
		if err != nil {
			return nil, err
		}
		current = &Entry{
			ID: id, UserID: userID, Path: value, Name: name,
			IsDir: isDir, AccessedAt: accessedAt,
		}
		if err := storage.back.Save(current); err != nil {
			return nil, err
		}
	} else {
		current.Path = value
		current.Name = name
		current.IsDir = isDir
		current.AccessedAt = accessedAt
		if err := storage.back.Update(current); err != nil {
			return nil, err
		}
	}
	for _, duplicate := range duplicates {
		if err := storage.back.Delete(duplicate.ID); err != nil {
			return nil, err
		}
	}
	if err := storage.prune(userID); err != nil {
		return nil, err
	}
	return current.Clone(), nil
}

func (storage *Storage) List(userID uint, limit int) ([]*Entry, error) {
	all, err := storage.back.GetAll()
	if err != nil {
		return nil, err
	}
	entries := make([]*Entry, 0)
	for _, entry := range all {
		if entry.UserID == userID {
			entries = append(entries, entry.Clone())
		}
	}
	sortEntries(entries)
	if limit <= 0 || limit > MaxEntriesPerUser {
		limit = MaxEntriesPerUser
	}
	if len(entries) > limit {
		entries = entries[:limit]
	}
	return entries, nil
}

func (storage *Storage) Remove(userID uint, id string) error {
	all, err := storage.back.GetAll()
	if err != nil {
		return err
	}
	for _, entry := range all {
		if entry.ID == id && entry.UserID == userID {
			return storage.back.Delete(id)
		}
	}
	return ErrNotExist
}

// DeleteSelected deletes only acknowledged IDs owned by this user; nil means clear all.
// Mutations share the same lock as recording/path rewrites and restore on failure.
func (storage *Storage) DeleteSelected(userID uint, ids []string) (int, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	all, err := storage.back.GetAll()
	if err != nil {
		return 0, err
	}
	chosen := make(map[string]bool, len(ids))
	for _, id := range ids {
		chosen[id] = true
	}
	deleted := make([]*Entry, 0)
	for _, entry := range all {
		if entry.UserID != userID || ids != nil && !chosen[entry.ID] {
			continue
		}
		if err := storage.back.Delete(entry.ID); err != nil {
			for _, old := range deleted {
				err = errors.Join(err, storage.back.Save(old))
			}
			return 0, err
		}
		deleted = append(deleted, entry.Clone())
	}
	return len(deleted), nil
}

func (storage *Storage) RewritePathPrefix(from, to string, mapper ...pathmeta.Mapper) (*PathMutation, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	all, err := storage.back.GetAll()
	if err != nil {
		return nil, err
	}
	mutation := &PathMutation{}
	for _, entry := range all {
		if entry.PathUnverified {
			continue
		}
		rewritten, matched := pathmeta.RewriteForUser(entry.UserID, entry.Path, from, to, mapper...)
		if !matched || rewritten == pathmeta.Clean(entry.Path) {
			continue
		}
		original := *entry
		if rewritten == "" {
			if err := storage.back.Delete(entry.ID); err != nil {
				return nil, errors.Join(err, storage.restorePathMutation(mutation))
			}
			mutation.deleted = append(mutation.deleted, original)
			continue
		}
		entry.Path = rewritten
		if entry.Name == path.Base(original.Path) {
			entry.Name = path.Base(rewritten)
		}
		if err := storage.back.Update(entry); err != nil {
			return nil, errors.Join(err, storage.restorePathMutation(mutation))
		}
		mutation.updated = append(mutation.updated, original)
	}
	return mutation, nil
}

func (storage *Storage) RemovePathPrefix(prefix string, mapper ...pathmeta.Mapper) (*PathMutation, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()

	all, err := storage.back.GetAll()
	if err != nil {
		return nil, err
	}
	mutation := &PathMutation{}
	for _, entry := range all {
		_, matched := pathmeta.RewriteForUser(entry.UserID, entry.Path, prefix, prefix, mapper...)
		if entry.PathUnverified || !matched {
			continue
		}
		original := *entry
		if err := storage.back.Delete(entry.ID); err != nil {
			return nil, errors.Join(err, storage.restorePathMutation(mutation))
		}
		mutation.deleted = append(mutation.deleted, original)
	}
	return mutation, nil
}

func (storage *Storage) RestorePathMutation(mutation *PathMutation) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	return storage.restorePathMutation(mutation)
}

func (storage *Storage) restorePathMutation(mutation *PathMutation) error {
	if mutation == nil {
		return nil
	}
	var restoreErr error
	for _, entry := range mutation.updated {
		copy := entry
		restoreErr = errors.Join(restoreErr, storage.back.Update(&copy))
	}
	for _, entry := range mutation.deleted {
		copy := entry
		restoreErr = errors.Join(restoreErr, storage.back.Save(&copy))
	}
	return restoreErr
}

func (storage *Storage) prune(userID uint) error {
	entries, err := storage.List(userID, MaxEntriesPerUser)
	if err != nil {
		return err
	}
	all, err := storage.back.GetAll()
	if err != nil {
		return err
	}
	keep := make(map[string]struct{}, len(entries))
	for _, entry := range entries {
		keep[entry.ID] = struct{}{}
	}
	for _, entry := range all {
		if entry.UserID != userID {
			continue
		}
		if _, exists := keep[entry.ID]; !exists {
			if err := storage.back.Delete(entry.ID); err != nil {
				return err
			}
		}
	}
	return nil
}

func sortEntries(entries []*Entry) {
	sort.SliceStable(entries, func(left, right int) bool {
		if entries[left].AccessedAt == entries[right].AccessedAt {
			return entries[left].ID > entries[right].ID
		}
		return entries[left].AccessedAt > entries[right].AccessedAt
	})
}

func generateID() (string, error) {
	random := make([]byte, 8)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	return time.Now().UTC().Format("20060102T150405.000000000") + "-" + hex.EncodeToString(random), nil
}
