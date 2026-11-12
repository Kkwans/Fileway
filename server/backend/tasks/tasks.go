package tasks

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/events"
	"github.com/Kkwans/nas-file-browser/backend/hls"
)

var (
	ErrNotExist  = errors.New("task not found")
	ErrForbidden = errors.New("task access denied")
	ErrState     = errors.New("task state does not allow this operation")
)

type Type string

const (
	TypeFileCopy             Type = "file.copy"
	TypeFileMove             Type = "file.move"
	TypeFileDeletePermanent  Type = "file.delete.permanent"
	TypeTrashDeletePermanent Type = "trash.delete.permanent"
	TypeTrashClear           Type = "trash.clear"
	TypeTrashSize            Type = "trash.size"
	TypeDuplicateAnalysis    Type = "analysis.duplicates"
	TypeDuplicateCleanup     Type = "analysis.duplicates.cleanup"
	TypeStorageAnalysis      Type = "analysis.storage"
	TypeArchiveExtract       Type = "archive.extract"
	TypeMediaHLS             Type = "media.hls"
	TypeMediaTranscode       Type = "media.transcode"
)

type Status string

const (
	StatusQueued      Status = "queued"
	StatusRunning     Status = "running"
	StatusCompleted   Status = "completed"
	StatusFailed      Status = "failed"
	StatusCanceled    Status = "canceled"
	StatusInterrupted Status = "interrupted"
)

// Task is the durable task state exposed to the task center. Args contains
// internal replay data and must never be serialized to an API response.
type Task struct {
	ID             string          `json:"id" storm:"id"`
	UserID         uint            `json:"userId" storm:"index"`
	OwnerName      string          `json:"ownerName"`
	Type           Type            `json:"type" storm:"index"`
	Title          string          `json:"title"`
	Status         Status          `json:"status" storm:"index"`
	CreatedAt      int64           `json:"createdAt" storm:"index"`
	StartedAt      int64           `json:"startedAt,omitempty"`
	FinishedAt     int64           `json:"finishedAt,omitempty"`
	UndoUntil      int64           `json:"undoUntil,omitempty"`
	ArchivedAt     int64           `json:"archivedAt,omitempty" storm:"index"`
	TotalItems     int             `json:"totalItems"`
	ProcessedItems int             `json:"processedItems"`
	TotalBytes     int64           `json:"totalBytes"`
	ProcessedBytes int64           `json:"processedBytes"`
	Error          string          `json:"error,omitempty"`
	RetryOf        string          `json:"retryOf,omitempty"`
	Media          *hls.Progress   `json:"media,omitempty"`
	SourcePath     string          `json:"sourcePath,omitempty"`
	OutputPath     string          `json:"outputPath,omitempty"`
	Args           json.RawMessage `json:"-"`
	Result         json.RawMessage `json:"-"`
}

func (task Task) MarshalJSON() ([]byte, error) {
	type publicTask Task
	// Legacy copy/move records summed directory metadata sizes. Use item
	// progress on the wire while keeping the stored counters and replay data.
	if task.TotalBytes > 0 && (task.Type == TypeFileCopy || task.Type == TypeFileMove) {
		var args struct {
			Items []struct {
				IsDir bool `json:"isDir"`
			} `json:"items"`
		}
		if json.Unmarshal(task.Args, &args) == nil {
			for _, item := range args.Items {
				if item.IsDir {
					task.TotalBytes, task.ProcessedBytes = 0, 0
					break
				}
			}
		}
	}
	return json.Marshal(publicTask(task))
}

func (task *Task) Clone() *Task {
	if task == nil {
		return nil
	}
	clone := *task
	clone.Args = append(json.RawMessage(nil), task.Args...)
	clone.Result = append(json.RawMessage(nil), task.Result...)
	if task.Media != nil {
		media := *task.Media
		clone.Media = &media
	}
	return &clone
}

func (task *Task) CanCancel() bool {
	return task != nil && (task.Status == StatusQueued || task.Status == StatusRunning)
}

func (task *Task) CanRetry() bool {
	if task == nil || task.ArchivedAt != 0 {
		return false
	}
	if task.Type == TypeFileDeletePermanent || task.Type == TypeTrashDeletePermanent {
		return false
	}
	if task.Status == StatusFailed || task.Status == StatusInterrupted {
		return true
	}
	return task.Status == StatusCanceled && (task.Type == TypeFileCopy || task.Type == TypeFileMove || task.Type == TypeDuplicateCleanup || task.Type == TypeMediaHLS || task.Type == TypeMediaTranscode)
}

func (task *Task) CanArchive() bool {
	if task == nil || task.ArchivedAt != 0 {
		return false
	}
	return task.Status == StatusCompleted || task.Status == StatusFailed || task.Status == StatusCanceled || task.Status == StatusInterrupted
}

type StorageBackend interface {
	GetAll() ([]*Task, error)
	GetByID(id string) (*Task, error)
	Save(task *Task) error
	Update(task *Task) error
}

// RecentCursor is the stable keyset position used by history-like task lists.
// CreatedAt and ID together keep ordering deterministic when tasks share a
// millisecond timestamp.
type RecentCursor struct {
	CreatedAt int64
	ID        string
}

// RecentTaskBackend lets persistent stores answer a bounded, user-scoped
// query without loading the complete task table into memory. The fallback in
// Storage.ListRecent keeps lightweight test backends compatible.
type RecentTaskBackend interface {
	ListRecent(userID uint, taskType Type, limit int, after *RecentCursor) ([]*Task, error)
}

type Storage struct {
	back StorageBackend
}

func NewStorage(back StorageBackend) *Storage {
	return &Storage{back: back}
}

func (storage *Storage) New(userID uint, ownerName string, taskType Type, title string, args json.RawMessage, retryOf string) (*Task, error) {
	id, err := generateID()
	if err != nil {
		return nil, err
	}
	task := &Task{
		ID: id, UserID: userID, OwnerName: ownerName, Type: taskType,
		Title: title, Status: StatusQueued, CreatedAt: time.Now().UnixMilli(),
		RetryOf: retryOf, Args: append(json.RawMessage(nil), args...),
	}
	if err := storage.back.Save(task); err != nil {
		return nil, err
	}
	events.Default.PublishForUser(task.UserID, "task.changed", task)
	return task.Clone(), nil
}

func (storage *Storage) Save(task *Task) error {
	if err := storage.back.Save(task.Clone()); err != nil {
		return err
	}
	events.Default.PublishForUser(task.UserID, "task.changed", task)
	return nil
}

func (storage *Storage) Update(task *Task) error {
	if err := storage.back.Update(task.Clone()); err != nil {
		return err
	}
	events.Default.PublishForUser(task.UserID, "task.changed", task)
	return nil
}

func (storage *Storage) Get(userID uint, id string, admin bool) (*Task, error) {
	task, err := storage.back.GetByID(id)
	if err != nil {
		return nil, err
	}
	if !admin && task.UserID != userID {
		return nil, ErrForbidden
	}
	return task.Clone(), nil
}

func (storage *Storage) List(userID uint, admin bool) ([]*Task, error) {
	all, err := storage.back.GetAll()
	if err != nil {
		return nil, err
	}
	visible := make([]*Task, 0, len(all))
	for _, task := range all {
		if !admin && task.UserID != userID {
			continue
		}
		visible = append(visible, task.Clone())
	}
	sort.SliceStable(visible, func(left, right int) bool {
		if visible[left].CreatedAt == visible[right].CreatedAt {
			return visible[left].ID > visible[right].ID
		}
		return visible[left].CreatedAt > visible[right].CreatedAt
	})
	return visible, nil
}

// ListRecent returns active (non-archived) tasks in stable newest-first order.
// Persistent backends should implement RecentTaskBackend so limit+1 rows can
// be fetched directly for cursor pagination; the fallback is intentionally
// only for small in-memory/test stores.
func (storage *Storage) ListRecent(userID uint, taskType Type, limit int, after *RecentCursor) ([]*Task, error) {
	if limit < 1 {
		return []*Task{}, nil
	}
	if backend, ok := storage.back.(RecentTaskBackend); ok {
		return backend.ListRecent(userID, taskType, limit, after)
	}
	all, err := storage.List(userID, false)
	if err != nil {
		return nil, err
	}
	result := make([]*Task, 0, limit)
	for _, task := range all {
		if task.Type != taskType || task.ArchivedAt != 0 {
			continue
		}
		if after != nil && (task.CreatedAt > after.CreatedAt || task.CreatedAt == after.CreatedAt && task.ID >= after.ID) {
			continue
		}
		result = append(result, task)
		if len(result) == limit {
			break
		}
	}
	return result, nil
}

// InterruptActive is called once during process startup. Tasks are never
// silently resumed after a restart because destructive work requires an
// explicit user retry.
func (storage *Storage) InterruptActive() error {
	all, err := storage.back.GetAll()
	if err != nil {
		return err
	}
	now := time.Now().UnixMilli()
	for _, task := range all {
		if !task.CanCancel() {
			continue
		}
		task.Status = StatusInterrupted
		task.FinishedAt = now
		task.Error = "服务重启，任务已中断"
		if err := storage.back.Update(task); err != nil {
			return err
		}
		events.Default.PublishForUser(task.UserID, "task.changed", task)
	}
	return nil
}

func generateID() (string, error) {
	random := make([]byte, 8)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	return time.Now().UTC().Format("20060102T150405.000000000") + "-" + hex.EncodeToString(random), nil
}
