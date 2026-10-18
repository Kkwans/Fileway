package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage/bolt"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/asdine/storm/v3"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/spf13/afero"
)

func directoryTransferFixture(t *testing.T, action string) (*trashHTTPHarness, *data, *tasks.Task, fileTransferTaskArgs) {
	t.Helper()
	h, d, task, args := permissionTransferFixture(t, action, false)
	if err := d.user.Fs.Remove("/source.txt"); err != nil {
		t.Fatal(err)
	}
	for _, dir := range []string{"/source.txt/nested", "/target.txt/nested"} {
		if err := d.user.Fs.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	writeWireOwned(t, d.user.Fs, map[string]string{"/source.txt/nested/new.txt": "new-tree", "/target.txt/nested/old.txt": "old-tree"})
	args.Items[0].Overwrite, args.Items[0].IsDir = true, true
	return h, d, task, args
}

type directoryRenameFailureFS struct{ afero.Fs }

func (fs *directoryRenameFailureFS) Rename(from, to string) error {
	if strings.HasPrefix(path.Base(from), ".nfb-transfer-") && to == "/target.txt" {
		return os.ErrPermission
	}
	return fs.Fs.Rename(from, to)
}

func TestDirectoryTransferFailedPublicationKeepsOldTree(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		t.Run(action, func(t *testing.T) {
			h, d, task, args := directoryTransferFixture(t, action)
			wrapped := &directoryRenameFailureFS{Fs: d.user.Fs}
			h.fs[d.user.ID], d.user.Fs = wrapped, wrapped
			result, err := fileTransferRunner(d, task, args)(context.Background(), func(tasks.Progress) error { return nil })
			if err == nil {
				t.Fatalf("publication fault was accepted: %s", result)
			}
			assertWireOwned(t, wrapped, map[string]string{"/source.txt/nested/new.txt": "new-tree", "/target.txt/nested/old.txt": "old-tree"})
		})
	}
}

func TestDirectoryTransferOverwriteReplacesWholeTree(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		t.Run(action, func(t *testing.T) {
			_, d, task, args := directoryTransferFixture(t, action)
			result, err := fileTransferRunner(d, task, args)(context.Background(), func(tasks.Progress) error { return nil })
			if err != nil {
				t.Fatalf("directory overwrite failed: %v %s", err, result)
			}
			var decoded fileTransferResult
			if json.Unmarshal(result, &decoded) != nil || len(decoded.Completed) != 1 {
				t.Fatalf("result=%s", result)
			}
			assertWireOwned(t, d.user.Fs, map[string]string{"/target.txt/nested/new.txt": "new-tree"})
			if exists, _ := afero.Exists(d.user.Fs, "/target.txt/nested/old.txt"); exists {
				t.Fatal("directory overwrite silently merged old children")
			}
			if action == "copy" {
				assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt/nested/new.txt": "new-tree"})
			} else if exists, _ := afero.Exists(d.user.Fs, "/source.txt"); exists {
				t.Fatal("completed directory MOVE retained source")
			}
		})
	}
}

type directoryRestartFixture struct {
	h            *trashHTTPHarness
	d            *data
	db           *storm.DB
	dbPath, root string
	userID       uint
	task         *tasks.Task
	args         fileTransferTaskArgs
}

func newDirectoryRestartFixture(t *testing.T, action string) *directoryRestartFixture {
	t.Helper()
	base, err := os.MkdirTemp(os.TempDir(), "dir-txn-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(base) })
	f := &directoryRestartFixture{dbPath: filepath.Join(base, "tasks.db"), root: filepath.Join(base, "workspace")}
	if err := os.Mkdir(f.root, 0o700); err != nil {
		t.Fatal(err)
	}
	f.open(t, true)
	for _, dir := range []string{"/source/nested", "/target/nested"} {
		if err := f.d.user.Fs.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	writeWireOwned(t, f.d.user.Fs, map[string]string{"/source/nested/new.txt": "new-tree", "/target/nested/old.txt": "old-tree"})
	item := fileTransferItem{From: "/source", To: "/target", IsDir: true, Overwrite: true, Size: 8}
	f.args = fileTransferTaskArgs{Items: []fileTransferItem{item}}
	encoded, _ := json.Marshal(f.args)
	kind := tasks.TypeFileCopy
	if action == "move" {
		kind = tasks.TypeFileMove
	}
	f.task, err = f.h.storage.Tasks.New(f.userID, "owner", kind, "directory transaction", encoded, "")
	if err != nil {
		t.Fatal(err)
	}
	body := `{"path":"/source/nested/new.txt","wirePath":"/source/nested/new.txt","name":"owned"}`
	response := f.h.request(t, f.userID, favoritesPostHandler, http.MethodPost, "/favorites", strings.NewReader(body), nil)
	if response.Code != 200 {
		t.Fatalf("seed favorite %d %s", response.Code, response.Body.String())
	}
	t.Cleanup(func() {
		if f.db != nil {
			_ = f.db.Close()
		}
		directoryRegistries.Lock()
		delete(directoryRegistries.stores, f.h.storage)
		directoryRegistries.Unlock()
	})
	return f
}
func (f *directoryRestartFixture) open(t *testing.T, create bool) {
	t.Helper()
	var err error
	f.db, err = storm.Open(f.dbPath)
	if err != nil {
		t.Fatal(err)
	}
	store, err := bolt.NewStorage(f.db)
	if err != nil {
		t.Fatal(err)
	}
	if create {
		if err := store.Settings.Save(&settings.Settings{Key: trashHTTPKey}); err != nil {
			t.Fatal(err)
		}
		owner := &users.User{Username: "owner", Password: "fixture-only", Scope: f.root, Perm: users.Permissions{Create: true, Modify: true, Rename: true, Delete: true, Download: true}}
		if err := store.Users.Save(owner); err != nil {
			t.Fatal(err)
		}
		f.userID = owner.ID
	}
	fs := afero.NewBasePathFs(afero.NewOsFs(), f.root)
	filesystemMap := map[uint]afero.Fs{f.userID: fs}
	store.Users = &userFilesystemStore{Store: store.Users, filesystems: filesystemMap}
	owner, err := store.Users.Get("", f.userID)
	if err != nil {
		t.Fatal(err)
	}
	policy, err := store.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	f.h = &trashHTTPHarness{storage: store, server: &settings.Server{}, users: map[uint]*users.User{f.userID: owner}, fs: filesystemMap}
	f.d = &data{store: store, server: f.h.server, user: owner, settings: policy, fileCache: noopTrashFileCache{}}
}
func (f *directoryRestartFixture) reopen(t *testing.T) *tasks.Runtime {
	t.Helper()
	old := f.h.storage
	if err := f.db.Close(); err != nil {
		t.Fatal(err)
	}
	directoryRegistries.Lock()
	delete(directoryRegistries.stores, old)
	directoryRegistries.Unlock()
	f.open(t, false)
	runtime, err := tasks.NewRuntime(f.h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	if err := initializeDirectoryPublications(f.h.storage); err != nil {
		t.Fatal(err)
	}
	f.task, err = f.h.storage.Tasks.Get(f.userID, f.task.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	return runtime
}

type directoryCrash struct{}

func (f *directoryRestartFixture) crash(t *testing.T, point string) {
	t.Helper()
	f.task.Status = tasks.StatusRunning
	if err := f.h.storage.Tasks.Update(f.task); err != nil {
		t.Fatal(err)
	}
	stopped := false
	func() {
		defer func() {
			if value := recover(); value != nil {
				if _, ok := value.(directoryCrash); !ok {
					panic(value)
				}
				stopped = true
			}
		}()
		_, err := fileTransferRunner(f.d, f.task, f.args)(context.Background(), func(progress tasks.Progress) error {
			phase := ""
			if len(progress.Checkpoint) > 0 {
				var c fileTransferCheckpoint
				if err := json.Unmarshal(progress.Checkpoint, &c); err != nil {
					t.Fatal(err)
				}
				for _, j := range c.Directories {
					phase = j.Phase
				}
			}
			if point == "copy-bytes" && len(progress.Checkpoint) == 0 && progress.ProcessedBytes > 0 {
				panic(directoryCrash{})
			}
			if phase != "" && point == "before:"+phase {
				panic(directoryCrash{})
			}
			if len(progress.Checkpoint) > 0 {
				f.task.Result = append(json.RawMessage(nil), progress.Checkpoint...)
				if err := f.h.storage.Tasks.Update(f.task); err != nil {
					t.Fatal(err)
				}
			}
			if phase != "" && point == phase {
				panic(directoryCrash{})
			}
			return nil
		})
		t.Fatalf("exit point %s not reached: %v", point, err)
	}()
	if !stopped {
		t.Fatal("injected process-stop was not observed")
	}
}
func (f *directoryRestartFixture) journal(t *testing.T) *directoryPublication {
	t.Helper()
	m, err := journalEnvelope(f.task.Result)
	if err != nil {
		t.Fatal(err)
	}
	if len(m) != 1 {
		t.Fatalf("journal missing: %s", f.task.Result)
	}
	for _, j := range m {
		return j
	}
	return nil
}
func (f *directoryRestartFixture) retry(t *testing.T, runtime *tasks.Runtime, want tasks.Status) *tasks.Task {
	t.Helper()
	next, status, err := retryExistingTask(runtime, f.d, f.task)
	if err != nil || status != 202 {
		t.Fatalf("retry status=%d err=%v", status, err)
	}
	done := waitForHTTPTask(t, f.h, next.ID, want)
	f.task = done
	return done
}
func (f *directoryRestartFixture) favorite(t *testing.T, want string) {
	t.Helper()
	response := f.h.request(t, f.userID, favoritesGetHandler, http.MethodGet, "/favorites", nil, nil)
	var rows []struct {
		Path string `json:"path"`
	}
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &rows) != nil || len(rows) != 1 || rows[0].Path != want {
		t.Fatalf("favorite want=%s: %d %s", want, response.Code, response.Body.String())
	}
}
func TestDirectoryPublicationRestartReconcilesNativeTreesAndDurableJournal(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		for _, point := range []string{"copying", "copy-bytes", "prepared", "backupIntent", "before:backedUp", "backedUp", "publishIntent", "before:installed", "installed", "logicalDone", "cleanupIntent", "before:clean", "metadataIntent", "metadataApplied", "sourceRemoveIntent", "before:sourceRemoved", "sourceRemoved"} {
			if action == "copy" && (strings.Contains(point, "metadata") || strings.Contains(point, "sourceRemove")) {
				continue
			}
			t.Run(action+"/"+point, func(t *testing.T) {
				f := newDirectoryRestartFixture(t, action)
				f.crash(t, point)
				// Reopen the actual Bolt DB and native filesystem, as a new process does.
				beforeTree := directoryOwnedSnapshot(t, f.d.user.Fs)
				runtime := f.reopen(t)
				if !reflect.DeepEqual(beforeTree, directoryOwnedSnapshot(t, f.d.user.Fs)) {
					t.Fatal("startup automatically mutated directory exchange")
				}
				if f.task.Status != tasks.StatusInterrupted {
					t.Fatalf("startup silently resumed: %s", f.task.Status)
				}
				before := f.journal(t)
				work, err := decodeOperationWirePath(before.Work)
				if err != nil {
					t.Fatal(err)
				}
				installed := strings.Contains(point, "installed") || point == "logicalDone" || point == "cleanupIntent" || point == "before:clean" || strings.Contains(point, "metadata") || strings.Contains(point, "sourceRemove")
				want := tasks.StatusFailed
				if installed {
					want = tasks.StatusCompleted
				}
				done := f.retry(t, runtime, want)
				after := f.journal(t)
				if installed {
					if after.Phase != "clean" || !after.Done {
						t.Fatalf("journal did not finish: %#v", after)
					}
					assertWireOwned(t, f.d.user.Fs, map[string]string{"/target/nested/new.txt": "new-tree"})
					if exists, _ := afero.Exists(f.d.user.Fs, "/target/nested/old.txt"); exists {
						t.Fatal("installed overwrite merged old tree")
					}
					if action == "move" {
						if exists, _ := afero.Exists(f.d.user.Fs, "/source"); exists {
							t.Fatal("MOVE source not removed")
						}
						f.favorite(t, "/target/nested/new.txt")
					} else {
						assertWireOwned(t, f.d.user.Fs, map[string]string{"/source/nested/new.txt": "new-tree"})
						f.favorite(t, "/source/nested/new.txt")
					}
				} else {
					if after.Phase != "restored" || after.Done {
						t.Fatalf("rollback confused with completed copy: %#v", after)
					}
					assertWireOwned(t, f.d.user.Fs, map[string]string{"/source/nested/new.txt": "new-tree", "/target/nested/old.txt": "old-tree"})
					f.favorite(t, "/source/nested/new.txt")
					if _, status, err := retryExistingTask(runtime, f.d, done); err == nil || status != 409 {
						t.Fatalf("restored snapshot was implicitly replayed: %d %v", status, err)
					}
				}
				if exists, _ := afero.Exists(f.d.user.Fs, work); exists {
					t.Fatal("known finished transaction container retained")
				}
			})
		}
	}
}
func TestDirectoryPublicationConcurrentTargetAndUnknownOwnershipStayUntouched(t *testing.T) {
	for _, mode := range []string{"target", "manifest", "extra-child", "backup-replaced", "scope"} {
		t.Run(mode, func(t *testing.T) {
			f := newDirectoryRestartFixture(t, "copy")
			f.crash(t, "publishIntent")
			runtime := f.reopen(t)
			j := f.journal(t)
			_, _, work, _ := directoryPaths(j)
			switch mode {
			case "target":
				if err := f.d.user.Fs.Mkdir("/target", 0o700); err != nil {
					t.Fatal(err)
				}
				writeWireOwned(t, f.d.user.Fs, map[string]string{"/target/foreign.txt": "other-writer"})
			case "manifest":
				if err := afero.WriteFile(f.d.user.Fs, path.Join(work, "manifest.json"), []byte("foreign-owner"), 0o600); err != nil {
					t.Fatal(err)
				}
			case "extra-child":
				writeWireOwned(t, f.d.user.Fs, map[string]string{path.Join(work, "unknown.txt"): "foreign-object"})
			case "backup-replaced":
				if err := f.d.user.Fs.Rename(path.Join(work, "old"), "/retained-old"); err != nil {
					t.Fatal(err)
				}
				if err := f.d.user.Fs.Mkdir(path.Join(work, "old"), 0o700); err != nil {
					t.Fatal(err)
				}
				writeWireOwned(t, f.d.user.Fs, map[string]string{path.Join(work, "old/foreign.txt"): "foreign-backup"})
			case "scope":
				updateTransferActor(t, f.h, f.userID, func(u *users.User) { u.Scope = filepath.Join(f.root, "different") })
			}
			if mode == "scope" {
				if _, status, err := retryExistingTask(runtime, f.d, f.task); err == nil || status != 409 {
					t.Fatalf("scope rebound: %d %v", status, err)
				}
			} else {
				f.retry(t, runtime, tasks.StatusFailed)
			}
			if mode == "backup-replaced" {
				assertWireOwned(t, f.d.user.Fs, map[string]string{"/retained-old/nested/old.txt": "old-tree", path.Join(work, "old/foreign.txt"): "foreign-backup"})
			} else {
				assertWireOwned(t, f.d.user.Fs, map[string]string{path.Join(work, "old/nested/old.txt"): "old-tree"})
			}
			assertWireOwned(t, f.d.user.Fs, map[string]string{path.Join(work, "new/nested/new.txt"): "new-tree", "/source/nested/new.txt": "new-tree"})
			if mode == "target" {
				assertWireOwned(t, f.d.user.Fs, map[string]string{"/target/foreign.txt": "other-writer"})
			}
		})
	}
}
func TestDirectoryPublicationCancelAndRevocationRespectCommitBoundary(t *testing.T) {
	for _, phase := range []string{"backupIntent", "backedUp", "installed", "cleanupIntent"} {
		for _, mode := range []string{"cancel", "revoke"} {
			t.Run(phase+"/"+mode, func(t *testing.T) {
				f := newDirectoryRestartFixture(t, "move")
				runtime, err := tasks.NewRuntime(f.h.storage.Tasks)
				if err != nil {
					t.Fatal(err)
				}
				triggered := false
				runner := fileTransferRunner(f.d, f.task, f.args)
				if err := runtime.Start(f.task, func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
					return runner(ctx, func(progress tasks.Progress) error {
						if err := report(progress); err != nil {
							return err
						}
						var c fileTransferCheckpoint
						if len(progress.Checkpoint) > 0 {
							if err := json.Unmarshal(progress.Checkpoint, &c); err != nil {
								return err
							}
							for _, j := range c.Directories {
								if j.Phase == phase && !triggered {
									triggered = true
									if mode == "cancel" {
										_, err := runtime.Cancel(f.userID, f.task.ID, false)
										return err
									}
									updateTransferActor(t, f.h, f.userID, func(u *users.User) { u.Perm.Modify = false })
								}
							}
						}
						return nil
					})
				}); err != nil {
					t.Fatal(err)
				}
				want := tasks.StatusFailed
				if mode == "cancel" {
					want = tasks.StatusCanceled
				}
				f.task = waitForHTTPTask(t, f.h, f.task.ID, want)
				if !triggered {
					t.Fatal("commit-boundary hook not reached")
				}
				j := f.journal(t)
				if phase == "backupIntent" || phase == "backedUp" {
					if j.Phase != "restored" {
						t.Fatalf("pre-publication cancellation stranded old tree: %s", j.Phase)
					}
					assertWireOwned(t, f.d.user.Fs, map[string]string{"/source/nested/new.txt": "new-tree", "/target/nested/old.txt": "old-tree"})
				} else {
					if !j.pending() || !strings.Contains(f.task.Error, "备份") {
						t.Fatalf("published cancellation lost recovery detail: %s %s", j.Phase, f.task.Error)
					}
					assertWireOwned(t, f.d.user.Fs, map[string]string{"/target/nested/new.txt": "new-tree"})
					if phase == "installed" {
						assertWireOwned(t, f.d.user.Fs, map[string]string{"/source/nested/new.txt": "new-tree"})
					}
					response := f.h.request(t, f.userID, taskArchiveHandler(true), http.MethodPost, "/tasks/"+f.task.ID+"/archive", nil, map[string]string{"id": f.task.ID})
					if response.Code != 409 {
						t.Fatalf("pending journal archived: %d", response.Code)
					}
				}
			})
		}
	}
}
func TestDirectoryPublicationTwoRetriesShareOriginalTransaction(t *testing.T) {
	f := newDirectoryRestartFixture(t, "copy")
	f.crash(t, "publishIntent")
	runtime := f.reopen(t)
	// Occupy the existing global transfer gate, keeping the first explicit retry
	// queued inside its worker while a second request targets the same journal.
	fileTransferGate <- struct{}{}
	released := false
	defer func() {
		if !released {
			<-fileTransferGate
		}
	}()
	first, status, err := retryExistingTask(runtime, f.d, f.task)
	if err != nil || status != 202 {
		t.Fatalf("first retry %d %v", status, err)
	}
	before, _ := f.h.storage.Tasks.List(f.userID, false)
	if _, status, err := retryExistingTask(runtime, f.d, f.task); err == nil || status != 409 {
		t.Fatalf("second retry raced journal: %d %v", status, err)
	}
	after, _ := f.h.storage.Tasks.List(f.userID, false)
	if len(before) != len(after) {
		t.Fatal("rejected retry created another task")
	}
	<-fileTransferGate
	released = true
	done := waitForHTTPTask(t, f.h, first.ID, tasks.StatusFailed)
	m, err := journalEnvelope(done.Result)
	if err != nil {
		t.Fatal(err)
	}
	for _, j := range m {
		if j.Origin != f.task.ID || j.Phase != "restored" {
			t.Fatalf("retry rebound original container: %#v", j)
		}
	}
}
func TestDirectoryPublicationAncestorOverwriteRejectedBeforeTaskCreation(t *testing.T) {
	f := newDirectoryRestartFixture(t, "copy")
	for _, to := range []string{"/source/nested/child", "/source"} {
		from := "/source"
		if to == "/source" {
			from = "/source/nested"
		}
		body, _ := json.Marshal(map[string]any{"action": "copy", "items": []any{map[string]any{"fromWirePath": from, "toWirePath": to, "overwrite": true}}})
		before, _ := f.h.storage.Tasks.List(f.userID, false)
		runtime, err := tasks.NewRuntime(f.h.storage.Tasks)
		if err != nil {
			t.Fatal(err)
		}
		response := f.h.request(t, f.userID, fileTransferTaskHandler(runtime), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
		if response.Code != 400 {
			t.Fatalf("overlapping directory accepted %d %s", response.Code, response.Body.String())
		}
		after, _ := f.h.storage.Tasks.List(f.userID, false)
		if len(before) != len(after) {
			t.Fatal("overlap created a task")
		}
	}
	assertWireOwned(t, f.d.user.Fs, map[string]string{"/source/nested/new.txt": "new-tree", "/target/nested/old.txt": "old-tree"})
}
func TestDirectoryPublicationWindowsCleanupFailureRetainsBackupForRetry(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Native Windows file-sharing failure")
	}
	f := newDirectoryRestartFixture(t, "copy")
	var handle afero.File
	result, err := fileTransferRunner(f.d, f.task, f.args)(context.Background(), func(progress tasks.Progress) error {
		if len(progress.Checkpoint) > 0 {
			f.task.Result = progress.Checkpoint
			if err := f.h.storage.Tasks.Update(f.task); err != nil {
				return err
			}
			var c fileTransferCheckpoint
			if err := json.Unmarshal(progress.Checkpoint, &c); err != nil {
				return err
			}
			for _, j := range c.Directories {
				if j.Phase == "cleanupIntent" && handle == nil {
					_, _, work, _ := directoryPaths(j)
					var err error
					handle, err = f.d.user.Fs.Open(path.Join(work, "old/nested/old.txt"))
					if err != nil {
						return err
					}
				}
			}
		}
		return nil
	})
	if handle == nil {
		t.Fatal("native cleanup lock not acquired")
	}
	defer func() { _ = handle.Close() }()
	if err == nil {
		t.Fatalf("Windows locked cleanup falsely completed: %s", result)
	}
	f.task.Result = result
	f.task.Status = tasks.StatusFailed
	if err := f.h.storage.Tasks.Update(f.task); err != nil {
		t.Fatal(err)
	}
	assertWireOwned(t, f.d.user.Fs, map[string]string{"/target/nested/new.txt": "new-tree", "/source/nested/new.txt": "new-tree"})
	if err := handle.Close(); err != nil {
		t.Fatal(err)
	}
	runtime := f.reopen(t)
	f.retry(t, runtime, tasks.StatusCompleted)
}

func directoryOwnedSnapshot(t *testing.T, fs afero.Fs) map[string]string {
	t.Helper()
	result := make(map[string]string)
	var walk func(string)
	walk = func(parent string) {
		entries, err := afero.ReadDir(fs, parent)
		if err != nil {
			t.Fatal(err)
		}
		for _, info := range entries {
			child := path.Join(parent, info.Name())
			if info.IsDir() {
				walk(child)
			} else {
				value, err := afero.ReadFile(fs, child)
				if err != nil {
					t.Fatal(err)
				}
				result[child] = string(value)
			}
		}
	}
	walk("/")
	return result
}
func TestDirectoryPublicationCleanupRestartDoesNotRecopyChangedSource(t *testing.T) {
	for _, point := range []string{"partial-backup", "manifest-removed"} {
		t.Run(point, func(t *testing.T) {
			f := newDirectoryRestartFixture(t, "copy")
			f.crash(t, "cleanupIntent")
			j := f.journal(t)
			_, _, work, _ := directoryPaths(j)
			if err := f.d.user.Fs.RemoveAll(path.Join(work, "old/nested")); err != nil {
				t.Fatal(err)
			}
			if point == "manifest-removed" {
				if err := f.d.user.Fs.Remove(path.Join(work, "old")); err != nil {
					t.Fatal(err)
				}
				if err := f.d.user.Fs.Remove(path.Join(work, "manifest.json")); err != nil {
					t.Fatal(err)
				}
			}
			writeWireOwned(t, f.d.user.Fs, map[string]string{"/source/nested/new.txt": "source-changed-after-publication"})
			runtime := f.reopen(t)
			f.retry(t, runtime, tasks.StatusCompleted)
			assertWireOwned(t, f.d.user.Fs, map[string]string{"/target/nested/new.txt": "new-tree", "/source/nested/new.txt": "source-changed-after-publication"})
			if exists, _ := afero.Exists(f.d.user.Fs, work); exists {
				t.Fatal("cleanup recovery kept owned transaction container")
			}
		})
	}
}
func TestDirectoryPublicationPendingJournalPreventsBatchArchiveAndCrossScopeWrites(t *testing.T) {
	f := newDirectoryRestartFixture(t, "copy")
	f.crash(t, "publishIntent")
	runtime := f.reopen(t)
	ordinary, err := f.h.storage.Tasks.New(f.userID, "owner", tasks.TypeFileCopy, "ordinary", json.RawMessage(`{"items":[]}`), "")
	if err != nil {
		t.Fatal(err)
	}
	ordinary.Status = tasks.StatusCompleted
	if err := f.h.storage.Tasks.Update(ordinary); err != nil {
		t.Fatal(err)
	}
	response := f.h.request(t, f.userID, taskDeleteRecordsHandler, http.MethodDelete, "/tasks?category=file", nil, nil)
	if response.Code != 409 {
		t.Fatalf("clear archived pending journal: %d %s", response.Code, response.Body.String())
	}
	kept, _ := f.h.storage.Tasks.Get(f.userID, ordinary.ID, false)
	if kept.ArchivedAt != 0 {
		t.Fatal("clear partially archived preceding task before rejecting journal")
	}
	shared := &users.User{Username: "shared", Password: "fixture-only", Scope: f.root, Perm: users.Permissions{Create: true, Modify: true, Rename: true, Download: true}}
	if err := f.h.storage.Users.Save(shared); err != nil {
		t.Fatal(err)
	}
	f.h.fs[shared.ID] = f.d.user.Fs
	body := `{"action":"copy","items":[{"fromWirePath":"/source","toWirePath":"/other","overwrite":false}]}`
	response = f.h.request(t, shared.ID, fileTransferTaskHandler(runtime), http.MethodPost, "/resources/transfer", strings.NewReader(body), nil)
	if response.Code != 409 {
		t.Fatalf("different account bypassed physical journal conflict: %d %s", response.Code, response.Body.String())
	}
	otherRoot := f.root + "-other"
	if err := os.MkdirAll(filepath.Join(otherRoot, "source"), 0o700); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = os.RemoveAll(otherRoot) }()
	independent := &users.User{Username: "independent", Password: "fixture-only", Scope: otherRoot, Perm: shared.Perm}
	if err := f.h.storage.Users.Save(independent); err != nil {
		t.Fatal(err)
	}
	otherFs := afero.NewBasePathFs(afero.NewOsFs(), otherRoot)
	f.h.fs[independent.ID] = otherFs
	writeWireOwned(t, otherFs, map[string]string{"/source/file.txt": "other-workspace"})
	response = f.h.request(t, independent.ID, fileTransferTaskHandler(runtime), http.MethodPost, "/resources/transfer", strings.NewReader(body), nil)
	if response.Code != 202 {
		t.Fatalf("relative path in independent workspace falsely blocked: %d %s", response.Code, response.Body.String())
	}
	var accepted tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
		t.Fatal(err)
	}
	waitForHTTPTask(t, f.h, accepted.ID, tasks.StatusCompleted)
	assertWireOwned(t, otherFs, map[string]string{"/source/file.txt": "other-workspace", "/other/file.txt": "other-workspace"})
}
func TestDirectoryPublicationTusExpiryCannotClaimTransactionPart(t *testing.T) {
	f := newDirectoryRestartFixture(t, "copy")
	f.crash(t, "publishIntent")
	j := f.journal(t)
	_, _, work, _ := directoryPaths(j)
	target, id := "/expiring-upload.bin", "owned-expiring-tus"
	expectTus(t, tusRequest(t, f.h, f.userID, "POST", target, id, 8, 0, nil), 201)
	expectTus(t, tusRequest(t, f.h, f.userID, "PATCH", target, id, 8, 0, []byte("abcd")), 204)
	row := ownedTusRow(t, f.h, target, id)
	part := mustUploadPath(row.PartWire)
	row.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	if err := f.h.storage.Uploads.Save(row); err != nil {
		t.Fatal(err)
	}
	due, err := f.h.storage.Uploads.Due(time.Now())
	if err != nil || len(due) != 1 || due[0].ID != id {
		t.Fatalf("due rows mixed directory transactions: %#v %v", due, err)
	}
	expectTus(t, tusRequest(t, f.h, f.userID, "HEAD", target, id, 8, 0, nil), 410)
	if exists, _ := afero.Exists(f.d.user.Fs, part); exists {
		t.Fatal("owned expired upload part not cleaned")
	}
	assertWireOwned(t, f.d.user.Fs, map[string]string{path.Join(work, "old/nested/old.txt"): "old-tree", path.Join(work, "new/nested/new.txt"): "new-tree"})
}
func TestDirectoryPublicationOpaqueWireSiblingRemainsIndependent(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Non-UTF8 directory names require byte-preserving OS filesystem")
	}
	f := newDirectoryRestartFixture(t, "copy")
	for _, dir := range []string{"/\xd6\xd0\xce\xc4", "/中文"} {
		if err := f.d.user.Fs.Mkdir(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	writeWireOwned(t, f.d.user.Fs, map[string]string{"/\xd6\xd0\xce\xc4/old.txt": "opaque-old", "/中文/old.txt": "unicode-old"})
	f.args.Items[0].To = "/\xd6\xd0\xce\xc4"
	encoded, _ := json.Marshal(f.args)
	f.task.Args = encoded
	if err := f.h.storage.Tasks.Update(f.task); err != nil {
		t.Fatal(err)
	}
	f.crash(t, "before:installed")
	runtime := f.reopen(t)
	f.retry(t, runtime, tasks.StatusCompleted)
	assertWireOwned(t, f.d.user.Fs, map[string]string{"/\xd6\xd0\xce\xc4/nested/new.txt": "new-tree", "/中文/old.txt": "unicode-old", "/source/nested/new.txt": "new-tree"})
}

func TestDirectoryPublicationNilOrUnknownJournalNeverPanicsOrClaimsOwnership(t *testing.T) {
	for _, raw := range []string{`{"directoryPublications":{"x":null}}`, `{"directoryPublications":{"x":{"version":999}}}`} {
		if _, err := journalEnvelope(json.RawMessage(raw)); err == nil {
			t.Fatalf("unknown journal accepted: %s", raw)
		}
		var checkpoint fileTransferCheckpoint
		if err := json.Unmarshal([]byte(raw), &checkpoint); err == nil {
			t.Fatalf("unknown checkpoint accepted: %s", raw)
		}
	}
}
func TestDirectoryPublicationPublicTaskRepliesHideJournalWithoutMutatingStore(t *testing.T) {
	for _, mode := range []string{"retry", "batch"} {
		t.Run(mode, func(t *testing.T) {
			f := newDirectoryRestartFixture(t, "copy")
			f.crash(t, "publishIntent")
			runtime := f.reopen(t)
			j := f.journal(t)
			original := append(json.RawMessage(nil), f.task.Result...)
			assertPrivate := func(body []byte) {
				t.Helper()
				for _, secret := range []string{"directoryPublications", j.Nonce, j.PhysicalFrom, j.PhysicalTo, j.PhysicalWork, f.root} {
					if bytes.Contains(body, []byte(secret)) {
						t.Fatalf("task reply exposed private journal: %s", body)
					}
				}
			}
			response := f.h.request(t, f.userID, taskGetHandler, http.MethodGet, "/tasks/"+f.task.ID, nil, map[string]string{"id": f.task.ID})
			if response.Code != 200 {
				t.Fatal(response.Code)
			}
			assertPrivate(response.Body.Bytes())
			response = f.h.request(t, f.userID, taskListHandler, http.MethodGet, "/tasks", nil, nil)
			if response.Code != 200 {
				t.Fatal(response.Code)
			}
			assertPrivate(response.Body.Bytes())
			fileTransferGate <- struct{}{}
			released := false
			defer func() {
				if !released {
					<-fileTransferGate
				}
			}()
			var childID string
			if mode == "retry" {
				response = f.h.request(t, f.userID, taskRetryHandler(runtime), http.MethodPost, "/tasks/"+f.task.ID+"/retry", nil, map[string]string{"id": f.task.ID})
				if response.Code != 202 {
					t.Fatalf("retry=%d %s", response.Code, response.Body.String())
				}
				var child tasks.Task
				if err := json.Unmarshal(response.Body.Bytes(), &child); err != nil {
					t.Fatal(err)
				}
				childID = child.ID
			} else {
				body, _ := json.Marshal(map[string]any{"action": "retry", "expectedCount": 1, "filters": map[string]any{"text": f.task.ID}})
				response = f.h.request(t, f.userID, taskBatchHandler(runtime), http.MethodPost, "/tasks/batch", bytes.NewReader(body), nil)
				if response.Code != 200 {
					t.Fatalf("batch=%d %s", response.Code, response.Body.String())
				}
				var batch taskBatchResponse
				if err := json.Unmarshal(response.Body.Bytes(), &batch); err != nil || len(batch.Created) != 1 {
					t.Fatalf("batch=%s err=%v", response.Body.String(), err)
				}
				childID = batch.Created[0].ID
			}
			assertPrivate(response.Body.Bytes())
			response = f.h.request(t, f.userID, taskCancelHandler(runtime), http.MethodPost, "/tasks/"+childID+"/cancel", nil, map[string]string{"id": childID})
			if response.Code != 202 {
				t.Fatalf("cancel=%d %s", response.Code, response.Body.String())
			}
			assertPrivate(response.Body.Bytes())
			<-fileTransferGate
			released = true
			canceled := waitForHTTPTask(t, f.h, childID, tasks.StatusCanceled)
			if !bytes.Contains(canceled.Result, []byte("directoryPublications")) {
				t.Fatal("queued cancellation discarded terminal journal")
			}
			response = f.h.request(t, f.userID, taskGetHandler, http.MethodGet, "/tasks/"+childID, nil, map[string]string{"id": childID})
			assertPrivate(response.Body.Bytes())
			stored, err := f.h.storage.Tasks.Get(f.userID, f.task.ID, false)
			if err != nil || !bytes.Equal(stored.Result, original) {
				t.Fatal("public projection mutated stored recovery journal")
			}
		})
	}
}
func TestDirectoryPublicationDoesNotBlockUnrelatedSiblingFileTask(t *testing.T) {
	f := newDirectoryRestartFixture(t, "copy")
	f.crash(t, "publishIntent")
	runtime := f.reopen(t)
	writeWireOwned(t, f.d.user.Fs, map[string]string{"/unrelated.txt": "independent-bytes"})
	body := `{"action":"copy","items":[{"fromWirePath":"/unrelated.txt","toWirePath":"/unrelated-copy.txt"}]}`
	response := f.h.request(t, f.userID, fileTransferTaskHandler(runtime), http.MethodPost, "/resources/transfer", strings.NewReader(body), nil)
	if response.Code != 202 {
		t.Fatalf("unrelated sibling task blocked: %d %s", response.Code, response.Body.String())
	}
	var accepted tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
		t.Fatal(err)
	}
	waitForHTTPTask(t, f.h, accepted.ID, tasks.StatusCompleted)
	assertWireOwned(t, f.d.user.Fs, map[string]string{"/unrelated.txt": "independent-bytes", "/unrelated-copy.txt": "independent-bytes"})
	_, _, work, _ := directoryPaths(f.journal(t))
	assertWireOwned(t, f.d.user.Fs, map[string]string{path.Join(work, "old/nested/old.txt"): "old-tree", path.Join(work, "new/nested/new.txt"): "new-tree"})
}
