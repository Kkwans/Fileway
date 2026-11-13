package fbhttp

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	goruntime "runtime"
	"syscall"
	"testing"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/analysis"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/runner"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestDuplicateCleanupMovesOnlyReportMembersToTrash(t *testing.T) {
	h, owner, filesystem := newPhysicalCleanupHarness(t)
	writeCleanupFile(t, filesystem, "/scan/keep.txt", "same-content")
	writeCleanupFile(t, filesystem, "/scan/remove-a.txt", "same-content")
	writeCleanupFile(t, filesystem, "/scan/remove-b.txt", "same-content")
	writeCleanupFile(t, filesystem, "/outside.txt", "same-content")

	reportTask, group := saveCleanupReport(t, h, owner, []string{"/scan/keep.txt", "/scan/remove-a.txt", "/scan/remove-b.txt"})
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	handler := cleanupStartTestHandler(runtime)
	body, _ := json.Marshal(duplicateCleanupRequest{Groups: []duplicateCleanupSelection{{SHA256: group.SHA256, KeepPath: "/scan/keep.txt"}}})
	response := h.request(t, owner.ID, handler, http.MethodPost, "/analysis/duplicates/"+reportTask.ID+"/cleanup", bytes.NewReader(body), map[string]string{"id": reportTask.ID})
	if response.Code != http.StatusAccepted {
		t.Fatalf("cleanup start status=%d body=%s", response.Code, response.Body.String())
	}
	var created tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	completed := waitForHTTPTask(t, h, created.ID, tasks.StatusCompleted)
	lookup := h.request(t, owner.ID, duplicateCleanupForReportHandler, http.MethodGet, "/cleanup", nil, map[string]string{"id": reportTask.ID})
	if lookup.Code != http.StatusOK || !bytes.Contains(lookup.Body.Bytes(), []byte(created.ID)) {
		t.Fatalf("cleanup lookup status=%d body=%s", lookup.Code, lookup.Body.String())
	}
	var result duplicateCleanupResult
	if err := json.Unmarshal(completed.Result, &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Groups) != 1 || len(result.Groups[0].Files) != 2 {
		t.Fatalf("cleanup result=%#v", result)
	}
	for _, item := range result.Groups[0].Files {
		if item.Status != "success" || item.TrashID == "" {
			t.Fatalf("cleanup file result=%#v", item)
		}
	}
	for _, path := range []string{"/scan/keep.txt", "/outside.txt"} {
		if exists, _ := afero.Exists(filesystem, path); !exists {
			t.Fatalf("preserved file %s is missing", path)
		}
	}
	for _, path := range []string{"/scan/remove-a.txt", "/scan/remove-b.txt"} {
		if exists, _ := afero.Exists(filesystem, path); exists {
			t.Fatalf("cleaned file %s still exists", path)
		}
	}
	items, err := h.storage.Trash.List(owner.ID, false)
	if err != nil || len(items) != 2 {
		t.Fatalf("trash items=%#v err=%v", items, err)
	}

	service := duplicateCleanupTrashService(h, owner)
	if _, err := service.Restore(owner.ID, result.Groups[0].Files[0].TrashID, false, trash.ConflictFail); err != nil {
		t.Fatal(err)
	}
	if restored, err := afero.ReadFile(filesystem, result.Groups[0].Files[0].Path); err != nil || string(restored) != "same-content" {
		t.Fatalf("restored content=%q err=%v", restored, err)
	}

	response = h.request(t, owner.ID, handler, http.MethodPost, "/analysis/duplicates/"+reportTask.ID+"/cleanup", bytes.NewReader(body), map[string]string{"id": reportTask.ID})
	if response.Code != http.StatusConflict {
		t.Fatalf("duplicate submission status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestDuplicateCleanupRejectsUntrustedStaleAndUnsafeSelections(t *testing.T) {
	t.Run("path outside saved report", func(t *testing.T) {
		h, owner, filesystem := newPhysicalCleanupHarness(t)
		writeCleanupFile(t, filesystem, "/scan/a.txt", "duplicate")
		writeCleanupFile(t, filesystem, "/scan/b.txt", "duplicate")
		writeCleanupFile(t, filesystem, "/outside.txt", "duplicate")
		reportTask, group := saveCleanupReport(t, h, owner, []string{"/scan/a.txt", "/scan/b.txt"})
		runtime, _ := tasks.NewRuntime(h.storage.Tasks)
		body, _ := json.Marshal(duplicateCleanupRequest{Groups: []duplicateCleanupSelection{{SHA256: group.SHA256, KeepPath: "/outside.txt"}}})
		response := h.request(t, owner.ID, cleanupStartTestHandler(runtime), http.MethodPost, "/cleanup", bytes.NewReader(body), map[string]string{"id": reportTask.ID})
		if response.Code != http.StatusBadRequest {
			t.Fatalf("outside keeper status=%d body=%s", response.Code, response.Body.String())
		}
		if exists, _ := afero.Exists(filesystem, "/outside.txt"); !exists {
			t.Fatal("untrusted outside path was modified")
		}
	})

	t.Run("content changed after report", func(t *testing.T) {
		h, owner, filesystem := newPhysicalCleanupHarness(t)
		writeCleanupFile(t, filesystem, "/scan/keep.txt", "duplicate")
		writeCleanupFile(t, filesystem, "/scan/remove.txt", "duplicate")
		reportTask, group := saveCleanupReport(t, h, owner, []string{"/scan/keep.txt", "/scan/remove.txt"})
		if err := afero.WriteFile(filesystem, "/scan/remove.txt", []byte("changed--"), 0o640); err != nil {
			t.Fatal(err)
		}
		runtime, _ := tasks.NewRuntime(h.storage.Tasks)
		body, _ := json.Marshal(duplicateCleanupRequest{Groups: []duplicateCleanupSelection{{SHA256: group.SHA256, KeepPath: "/scan/keep.txt"}}})
		response := h.request(t, owner.ID, cleanupStartTestHandler(runtime), http.MethodPost, "/cleanup", bytes.NewReader(body), map[string]string{"id": reportTask.ID})
		var created tasks.Task
		_ = json.Unmarshal(response.Body.Bytes(), &created)
		failed := waitForHTTPTask(t, h, created.ID, tasks.StatusFailed)
		var result duplicateCleanupResult
		if err := json.Unmarshal(failed.Result, &result); err != nil || result.Groups[0].Files[0].Status != "failed" {
			t.Fatalf("stale result=%#v err=%v", result, err)
		}
		if exists, _ := afero.Exists(filesystem, "/scan/remove.txt"); !exists {
			t.Fatal("stale file was moved")
		}
	})

	t.Run("hard link group", func(t *testing.T) {
		h, owner, filesystem := newPhysicalCleanupHarness(t)
		writeCleanupFile(t, filesystem, "/scan/a.txt", "duplicate")
		base := afero.FullBaseFsPath(filesystem.(*afero.BasePathFs), "/scan/a.txt")
		linked := afero.FullBaseFsPath(filesystem.(*afero.BasePathFs), "/scan/b.txt")
		if err := os.Link(base, linked); err != nil {
			t.Fatal(err)
		}
		reportTask, group := saveCleanupReport(t, h, owner, []string{"/scan/a.txt", "/scan/b.txt"})
		runtime, _ := tasks.NewRuntime(h.storage.Tasks)
		body, _ := json.Marshal(duplicateCleanupRequest{Groups: []duplicateCleanupSelection{{SHA256: group.SHA256, KeepPath: "/scan/a.txt"}}})
		response := h.request(t, owner.ID, cleanupStartTestHandler(runtime), http.MethodPost, "/cleanup", bytes.NewReader(body), map[string]string{"id": reportTask.ID})
		if response.Code != http.StatusBadRequest {
			t.Fatalf("hard link status=%d body=%s", response.Code, response.Body.String())
		}
	})

	t.Run("symbolic link group", func(t *testing.T) {
		h, owner, filesystem := newPhysicalCleanupHarness(t)
		writeCleanupFile(t, filesystem, "/scan/a.txt", "duplicate")
		base := afero.FullBaseFsPath(filesystem.(*afero.BasePathFs), "/scan/a.txt")
		linked := afero.FullBaseFsPath(filesystem.(*afero.BasePathFs), "/scan/link.txt")
		if err := os.Symlink(base, linked); err != nil {
			if goruntime.GOOS == "windows" && errors.Is(err, syscall.Errno(1314)) {
				t.Skip("native symbolic-link fixture requires Windows privilege; this security case remains unverified on this host")
			}
			t.Fatal(err)
		}
		reportTask, group := saveCleanupReport(t, h, owner, []string{"/scan/a.txt", "/scan/link.txt"})
		runtime, _ := tasks.NewRuntime(h.storage.Tasks)
		body, _ := json.Marshal(duplicateCleanupRequest{Groups: []duplicateCleanupSelection{{SHA256: group.SHA256, KeepPath: "/scan/a.txt"}}})
		response := h.request(t, owner.ID, cleanupStartTestHandler(runtime), http.MethodPost, "/cleanup", bytes.NewReader(body), map[string]string{"id": reportTask.ID})
		if response.Code != http.StatusBadRequest {
			t.Fatalf("symbolic link status=%d body=%s", response.Code, response.Body.String())
		}
	})
}

func TestDuplicateCleanupRetryMarksCompletedFilesSkipped(t *testing.T) {
	h, owner, filesystem := newPhysicalCleanupHarness(t)
	writeCleanupFile(t, filesystem, "/done.txt", "done")
	service := duplicateCleanupTrashService(h, owner)
	item, err := service.Move(owner.ID, owner.Username, "/done.txt")
	if err != nil {
		t.Fatal(err)
	}
	d := &data{store: h.storage, user: owner, settings: &settings.Settings{}}
	reconciled := reconcileCleanupResult(d, duplicateCleanupFileResult{Path: "/done.txt", Status: "success", TrashID: item.ID})
	if reconciled.Status != "skipped" || !completedCleanupOutcome(reconciled) {
		t.Fatalf("reconciled result=%#v", reconciled)
	}
}

func TestDuplicateCleanupCancellationKeepsCheckpointAndRemainingSource(t *testing.T) {
	h, owner, filesystem := newPhysicalCleanupHarness(t)
	for _, path := range []string{"/scan/keep.txt", "/scan/first.txt", "/scan/second.txt"} {
		writeCleanupFile(t, filesystem, path, "duplicate")
	}
	_, group := saveCleanupReport(t, h, owner, []string{"/scan/keep.txt", "/scan/first.txt", "/scan/second.txt"})
	ctx, cancel := context.WithCancel(context.Background())
	d := &data{
		Runner: &runner.Runner{}, store: h.storage, user: owner,
		settings: &settings.Settings{}, server: &settings.Server{}, fileCache: noopTrashFileCache{},
	}
	task := &tasks.Task{UserID: owner.ID}
	run := duplicateCleanupRunner(d, task, analysis.DuplicateReport{Groups: []analysis.DuplicateGroup{group}}, duplicateCleanupArgs{
		ReportID: "report", Groups: []duplicateCleanupSelection{{SHA256: group.SHA256, KeepPath: "/scan/keep.txt"}},
	})
	raw, err := run(ctx, func(progress tasks.Progress) error {
		if progress.ProcessedItems == 1 {
			cancel()
		}
		return nil
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("runner error=%v", err)
	}
	var result duplicateCleanupResult
	if err := json.Unmarshal(raw, &result); err != nil || len(result.Groups) != 1 || len(result.Groups[0].Files) != 1 || result.Groups[0].Files[0].Status != "success" {
		t.Fatalf("checkpoint=%#v err=%v", result, err)
	}
	if exists, _ := afero.Exists(filesystem, "/scan/first.txt"); exists {
		t.Fatal("first completed file was not moved")
	}
	if exists, _ := afero.Exists(filesystem, "/scan/second.txt"); !exists {
		t.Fatal("cancel removed the unprocessed source")
	}
}

func newPhysicalCleanupHarness(t *testing.T) (*trashHTTPHarness, *users.User, afero.Fs) {
	t.Helper()
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true, Delete: true, Modify: true}})
	owner := firstTrashHTTPUser(h)
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
	h.fs[owner.ID] = filesystem
	h.users[owner.ID].Fs = filesystem
	h.storage.Users.(*userFilesystemStore).filesystems[owner.ID] = filesystem
	return h, owner, filesystem
}

func writeCleanupFile(t *testing.T, filesystem afero.Fs, path, content string) {
	t.Helper()
	if err := filesystem.MkdirAll("/scan", 0o750); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, path, []byte(content), 0o640); err != nil {
		t.Fatal(err)
	}
}

func saveCleanupReport(t *testing.T, h *trashHTTPHarness, owner *users.User, paths []string) (*tasks.Task, analysis.DuplicateGroup) {
	t.Helper()
	content, err := afero.ReadFile(h.fs[owner.ID], paths[0])
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(content)
	group := analysis.DuplicateGroup{SHA256: hex.EncodeToString(digest[:]), TotalFiles: len(paths)}
	for _, path := range paths {
		info, err := h.fs[owner.ID].Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		identity := files.FileIdentity(h.fs[owner.ID], path)
		if identity == nil {
			t.Fatalf("identity unavailable for %s", path)
		}
		group.Files = append(group.Files, analysis.DuplicateFile{Path: path, Size: info.Size(), Modified: info.ModTime().UnixMilli(), Created: files.CreatedTime(h.fs[owner.ID], path), Identity: identity})
	}
	report := analysis.DuplicateReport{SchemaVersion: duplicateCleanupSchemaVersion, Groups: []analysis.DuplicateGroup{group}, DuplicateGroups: 1}
	raw, _ := json.Marshal(report)
	task, err := h.storage.Tasks.New(owner.ID, owner.Username, tasks.TypeDuplicateAnalysis, "report", json.RawMessage(`{}`), "")
	if err != nil {
		t.Fatal(err)
	}
	task.Status, task.Result, task.FinishedAt = tasks.StatusCompleted, raw, time.Now().UnixMilli()
	if err := h.storage.Tasks.Update(task); err != nil {
		t.Fatal(err)
	}
	return task, group
}

func cleanupStartTestHandler(runtime *tasks.Runtime) handleFunc {
	inner := duplicateCleanupStartHandler(runtime)
	return func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		d.fileCache = noopTrashFileCache{}
		d.taskRuntime = runtime
		return inner(w, r, d)
	}
}

func duplicateCleanupTrashService(h *trashHTTPHarness, owner *users.User) *trash.Service {
	return &trash.Service{Fs: owner.Fs, Records: h.storage.Trash, Favorites: h.storage.Favorites, Tags: h.storage.Tags, Recent: h.storage.Recent}
}
