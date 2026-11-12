package tasks

import (
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
)

func TestTaskJSONUsesItemProgressForLegacyDirectoryTransfers(t *testing.T) {
	for _, tc := range []struct {
		name       string
		kind       Type
		args       string
		total      int64
		processed  int64
		wantTotal  int64
		wantCopied int64
	}{
		{"legacy-copy", TypeFileCopy, `{"items":[{"from":"/private"},{"isDir":true}]}`, 4096, 7000, 0, 0},
		{"legacy-move", TypeFileMove, `{"items":[{"isDir":true}]}`, 4096, 4096, 0, 0},
		{"new-directory", TypeFileCopy, `{"items":[{"isDir":true}]}`, 0, 7000, 0, 7000},
		{"regular", TypeFileCopy, `{"items":[{"isDir":false}]}`, 10, 5, 10, 5},
	} {
		t.Run(tc.name, func(t *testing.T) {
			original := Task{
				Type: tc.kind, Args: json.RawMessage(tc.args), Result: json.RawMessage(`{"private":true}`),
				TotalBytes: tc.total, ProcessedBytes: tc.processed, TotalItems: 2, ProcessedItems: 1,
			}
			wire, err := json.Marshal(&original)
			if err != nil {
				t.Fatal(err)
			}
			var shown Task
			if err := json.Unmarshal(wire, &shown); err != nil {
				t.Fatal(err)
			}
			if shown.TotalBytes != tc.wantTotal || shown.ProcessedBytes != tc.wantCopied ||
				shown.TotalItems != 2 || shown.ProcessedItems != 1 {
				t.Fatalf("unexpected public progress: %s", wire)
			}
			if strings.Contains(string(wire), "private") || original.TotalBytes != tc.total ||
				original.ProcessedBytes != tc.processed || string(original.Args) != tc.args {
				t.Fatalf("public progress changed or exposed durable data: %s", wire)
			}
		})
	}
}

func TestStorageScopesTasksAndSortsNewestFirst(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	first := &Task{ID: "first", UserID: 1, CreatedAt: 10, Status: StatusCompleted}
	second := &Task{ID: "second", UserID: 2, CreatedAt: 20, Status: StatusRunning}
	if err := storage.Save(first); err != nil {
		t.Fatal(err)
	}
	if err := storage.Save(second); err != nil {
		t.Fatal(err)
	}

	visible, err := storage.List(1, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(visible) != 1 || visible[0].ID != first.ID {
		t.Fatalf("user tasks = %#v", visible)
	}
	if _, err := storage.Get(1, second.ID, false); !errors.Is(err, ErrForbidden) {
		t.Fatalf("cross-user get error = %v", err)
	}

	visible, err = storage.List(1, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(visible) != 2 || visible[0].ID != second.ID {
		t.Fatalf("admin tasks = %#v", visible)
	}
}

func TestInterruptActiveRequiresExplicitRetry(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	for _, task := range []*Task{
		{ID: "queued", Status: StatusQueued},
		{ID: "running", Status: StatusRunning},
		{ID: "completed", Status: StatusCompleted},
	} {
		if err := storage.Save(task); err != nil {
			t.Fatal(err)
		}
	}
	if err := storage.InterruptActive(); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"queued", "running"} {
		task, err := backend.GetByID(id)
		if err != nil {
			t.Fatal(err)
		}
		if task.Status != StatusInterrupted || !task.CanRetry() || task.Error == "" {
			t.Fatalf("interrupted task = %#v", task)
		}
	}
	completed, err := backend.GetByID("completed")
	if err != nil {
		t.Fatal(err)
	}
	if completed.Status != StatusCompleted {
		t.Fatalf("completed task changed to %q", completed.Status)
	}
}

func TestTaskCloneKeepsReplayArgsPrivateAndIndependent(t *testing.T) {
	task := &Task{ID: "task", Args: json.RawMessage(`{"all":true}`)}
	clone := task.Clone()
	clone.Args[0] = '['
	if string(task.Args) != `{"all":true}` {
		t.Fatalf("source args changed to %s", task.Args)
	}
	payload, err := json.Marshal(task)
	if err != nil {
		t.Fatal(err)
	}
	if string(payload) == "" || strings.Contains(string(payload), "all") {
		t.Fatalf("private args leaked: %s", payload)
	}
}

type memoryBackend struct {
	mu    sync.RWMutex
	items map[string]*Task
}

func newMemoryBackend() *memoryBackend {
	return &memoryBackend{items: make(map[string]*Task)}
}

func (backend *memoryBackend) GetAll() ([]*Task, error) {
	backend.mu.RLock()
	defer backend.mu.RUnlock()
	items := make([]*Task, 0, len(backend.items))
	for _, item := range backend.items {
		items = append(items, item.Clone())
	}
	return items, nil
}

func (backend *memoryBackend) GetByID(id string) (*Task, error) {
	backend.mu.RLock()
	defer backend.mu.RUnlock()
	item, ok := backend.items[id]
	if !ok {
		return nil, ErrNotExist
	}
	return item.Clone(), nil
}

func (backend *memoryBackend) Save(task *Task) error {
	backend.mu.Lock()
	defer backend.mu.Unlock()
	backend.items[task.ID] = task.Clone()
	return nil
}

func (backend *memoryBackend) Update(task *Task) error {
	backend.mu.Lock()
	defer backend.mu.Unlock()
	if _, ok := backend.items[task.ID]; !ok {
		return ErrNotExist
	}
	backend.items[task.ID] = task.Clone()
	return nil
}
