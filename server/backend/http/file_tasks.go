package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path"
	"strconv"
	"strings"
	"syscall"
	"unicode/utf8"

	fberrors "github.com/Kkwans/nas-file-browser/backend/errors"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/fileutils"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/spf13/afero"
)

type fileTransferItem struct {
	From         string `json:"from"`
	To           string `json:"to"`
	FromWirePath string `json:"fromWirePath,omitempty"`
	ToWirePath   string `json:"toWirePath,omitempty"`
	Name         string `json:"name,omitempty"`
	Size         int64  `json:"size,omitempty"`
	Modified     string `json:"modified,omitempty"`
	IsDir        bool   `json:"isDir,omitempty"`
	Overwrite    bool   `json:"overwrite,omitempty"`
	Rename       bool   `json:"rename,omitempty"`
}

type fileTransferRequest struct {
	Action string             `json:"action"`
	Items  []fileTransferItem `json:"items"`
}

func (item *fileTransferItem) UnmarshalJSON(encoded []byte) error {
	type payload fileTransferItem
	var saved payload
	if err := json.Unmarshal(encoded, &saved); err != nil {
		return err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &fields); err != nil {
		return err
	}
	for field := range fields {
		if (strings.EqualFold(field, "fromWirePath") && saved.FromWirePath == "") || (strings.EqualFold(field, "toWirePath") && saved.ToWirePath == "") {
			return fmt.Errorf("原始路径不能为空")
		}
	}
	*item = fileTransferItem(saved)
	return nil
}

type fileTransferTaskArgs struct {
	Items          []fileTransferItem               `json:"items"`
	Completed      map[string]bool                  `json:"completed,omitempty"`
	CompletedPaths []fileTransferResultItem         `json:"completedPaths,omitempty"`
	Directories    map[string]*directoryPublication `json:"directoryPublications,omitempty"`
}

type fileTransferResult struct {
	Directories map[string]*directoryPublication `json:"directoryPublications,omitempty"`
	Completed   []fileTransferResultItem         `json:"completed,omitempty"`
	Failed      []fileTransferFailure            `json:"failed,omitempty"`
}

type fileTransferResultItem struct {
	From string `json:"from"`
	To   string `json:"to"`
}

type fileTransferFailure struct {
	From  string `json:"from"`
	To    string `json:"to"`
	Error string `json:"error"`
}

type fileTransferCheckpoint struct {
	Directories map[string]*directoryPublication `json:"directoryPublications,omitempty"`
	Completed   map[string]bool                  `json:"completed,omitempty"`
}

// Task JSON contains display paths for readers and ASCII wire identities for
// replay. Transform before encoding/json can replace non-UTF8 filesystem bytes.
func (args fileTransferTaskArgs) MarshalJSON() ([]byte, error) {
	type payload fileTransferTaskArgs
	copy := payload(args)
	copy.Items = append([]fileTransferItem(nil), args.Items...)
	for index, item := range copy.Items {
		copy.Items[index].From, copy.Items[index].To = files.DisplayPath(item.From), files.DisplayPath(item.To)
		copy.Items[index].FromWirePath, copy.Items[index].ToWirePath = files.EncodeWirePath(item.From), files.EncodeWirePath(item.To)
	}
	copy.Completed = canonicalTransferCheckpoints(args.Completed)
	return json.Marshal(struct {
		payload
		PathEncoding string `json:"pathEncoding"`
	}{copy, taskWirePathEncoding})
}

func (args *fileTransferTaskArgs) UnmarshalJSON(encoded []byte) error {
	type payload fileTransferTaskArgs
	var saved struct {
		payload
		PathEncoding string `json:"pathEncoding"`
	}
	if err := json.Unmarshal(encoded, &saved); err != nil {
		return err
	}
	if saved.PathEncoding != "" && saved.PathEncoding != taskWirePathEncoding {
		return fmt.Errorf("不支持的任务路径编码，请重新创建")
	}
	for index, item := range saved.Items {
		from, err := restoreTaskPath(item.From, item.FromWirePath, saved.PathEncoding)
		if err != nil {
			return err
		}
		to, err := restoreTaskPath(item.To, item.ToWirePath, saved.PathEncoding)
		if err != nil {
			return err
		}
		saved.Items[index].From, saved.Items[index].To = from, to
	}
	saved.Completed = canonicalTransferCheckpoints(saved.Completed)
	for key, j := range saved.Directories {
		if err := validateDirectoryJournal(j); err != nil {
			return err
		}
		from, to, _, _ := directoryPaths(j)
		if key != directoryKey(fileTransferItem{From: from, To: to}) {
			return errDirectoryRecovery
		}
	}
	*args = fileTransferTaskArgs(saved.payload)
	return nil
}

func (item fileTransferResultItem) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		From         string `json:"from"`
		To           string `json:"to"`
		FromWirePath string `json:"fromWirePath"`
		ToWirePath   string `json:"toWirePath"`
		PathEncoding string `json:"pathEncoding"`
	}{files.DisplayPath(item.From), files.DisplayPath(item.To), files.EncodeWirePath(item.From), files.EncodeWirePath(item.To), taskWirePathEncoding})
}

func (item *fileTransferResultItem) UnmarshalJSON(encoded []byte) error {
	var saved struct{ From, To, FromWirePath, ToWirePath, PathEncoding string }
	if err := json.Unmarshal(encoded, &saved); err != nil {
		return err
	}
	from, err := restoreTaskPath(saved.From, saved.FromWirePath, saved.PathEncoding)
	if err != nil {
		return err
	}
	to, err := restoreTaskPath(saved.To, saved.ToWirePath, saved.PathEncoding)
	if err != nil {
		return err
	}
	item.From, item.To = from, to
	return nil
}

func (item fileTransferFailure) MarshalJSON() ([]byte, error) {
	type payload fileTransferFailure
	return json.Marshal(struct {
		payload
		From         string `json:"from"`
		To           string `json:"to"`
		FromWirePath string `json:"fromWirePath"`
		ToWirePath   string `json:"toWirePath"`
		PathEncoding string `json:"pathEncoding"`
	}{payload(item), files.DisplayPath(item.From), files.DisplayPath(item.To), files.EncodeWirePath(item.From), files.EncodeWirePath(item.To), taskWirePathEncoding})
}

func (item *fileTransferFailure) UnmarshalJSON(encoded []byte) error {
	var identity fileTransferResultItem
	if err := json.Unmarshal(encoded, &identity); err != nil {
		return err
	}
	var saved struct{ Error string }
	if err := json.Unmarshal(encoded, &saved); err != nil {
		return err
	}
	item.From, item.To, item.Error = identity.From, identity.To, saved.Error
	return nil
}

func (checkpoint fileTransferCheckpoint) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Completed    map[string]bool                  `json:"completed,omitempty"`
		PathEncoding string                           `json:"pathEncoding"`
		Directories  map[string]*directoryPublication `json:"directoryPublications,omitempty"`
	}{canonicalTransferCheckpoints(checkpoint.Completed), taskWirePathEncoding, checkpoint.Directories})
}

func (checkpoint *fileTransferCheckpoint) UnmarshalJSON(encoded []byte) error {
	var saved struct {
		Completed    map[string]bool
		PathEncoding string
		Directories  map[string]*directoryPublication `json:"directoryPublications,omitempty"`
	}
	if err := json.Unmarshal(encoded, &saved); err != nil {
		return err
	}
	if saved.PathEncoding != "" && saved.PathEncoding != taskWirePathEncoding {
		return fmt.Errorf("不支持的检查点路径编码")
	}
	checkpoint.Completed = canonicalTransferCheckpoints(saved.Completed)
	checkpoint.Directories = saved.Directories
	for _, j := range checkpoint.Directories {
		if err := validateDirectoryJournal(j); err != nil {
			return err
		}
	}
	return nil
}

func canonicalTransferCheckpoint(key string) (canonical, from, to string, valid bool) {
	wire := strings.HasPrefix(key, "wire:")
	if wire {
		key = strings.TrimPrefix(key, "wire:")
	}
	parts := strings.Split(key, "\x00")
	if len(parts) != 5 {
		return
	}
	size, sizeErr := strconv.ParseInt(parts[2], 10, 64)
	modified, modifiedErr := strconv.ParseInt(parts[3], 10, 64)
	if sizeErr != nil || modifiedErr != nil || size < 0 || (parts[4] != "true" && parts[4] != "false") {
		return
	}
	var err error
	if wire {
		from, err = decodeOperationWirePath(parts[0])
		if err != nil {
			return "", "", "", false
		}
		to, err = decodeOperationWirePath(parts[1])
	} else {
		if !utf8.ValidString(key) || strings.ContainsRune(key, '\ufffd') {
			return
		}
		from, err = literalOperationPath(parts[0])
		if err != nil {
			return "", "", "", false
		}
		to, err = literalOperationPath(parts[1])
	}
	if err != nil {
		return "", "", "", false
	}
	canonical = fmt.Sprintf("wire:%s\x00%s\x00%d\x00%d\x00%s", files.EncodeWirePath(from), files.EncodeWirePath(to), size, modified, parts[4])
	return canonical, from, to, true
}

func canonicalTransferCheckpoints(saved map[string]bool) map[string]bool {
	result := make(map[string]bool, len(saved))
	for key, complete := range saved {
		if canonical, _, _, ok := canonicalTransferCheckpoint(key); ok && complete {
			result[canonical] = true
		}
	}
	return result
}

var fileTransferGate = make(chan struct{}, 1)

var fileTransferTaskHandler = func(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var request fileTransferRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("无效的文件任务请求: %w", err)
		}
		if request.Action != "copy" && request.Action != "move" {
			return http.StatusBadRequest, fmt.Errorf("不支持的文件操作 %q", request.Action)
		}
		if len(request.Items) == 0 || len(request.Items) > 1000 {
			return http.StatusBadRequest, fmt.Errorf("文件任务项目数必须在 1 到 1000 之间")
		}
		if request.Action == "copy" && !d.user.Perm.Create {
			return http.StatusForbidden, fberrors.ErrPermissionDenied
		}
		if request.Action == "move" && (!d.user.Perm.Rename || !d.user.Perm.Create) {
			return http.StatusForbidden, fberrors.ErrPermissionDenied
		}

		args := fileTransferTaskArgs{Items: make([]fileTransferItem, 0, len(request.Items)), Completed: make(map[string]bool)}
		for index, item := range request.Items {
			if item.Overwrite && !d.user.Perm.Modify {
				return http.StatusForbidden, fberrors.ErrPermissionDenied
			}
			from, err := resourceOperationPath(item.From, item.FromWirePath, true)
			if err != nil {
				return http.StatusBadRequest, err
			}
			to, err := resourceOperationPath(item.To, item.ToWirePath, true)
			if err != nil {
				return http.StatusBadRequest, err
			}
			// Only explicit keep-both COPY may resolve its own source to a new
			// suffix below. MOVE and source overwrite must never pass this gate.
			if from == "/" || to == "/" || (from == to && (request.Action != "copy" || !item.Rename)) {
				return http.StatusBadRequest, fmt.Errorf("第 %d 项的源和目标路径无效", index+1)
			}
			if err := checkParent(from, to); err != nil {
				return http.StatusBadRequest, err
			}
			if !d.Check(from) || !d.Check(to) {
				return http.StatusForbidden, fmt.Errorf("没有权限访问第 %d 项的路径", index+1)
			}
			info, err := lstatResource(d.user.Fs, from)
			if err != nil {
				return errToStatus(err), err
			}
			if info.Mode()&os.ModeSymlink != 0 {
				return http.StatusBadRequest, fmt.Errorf("不支持复制符号链接: %s", from)
			}
			if _, err := d.user.Fs.Stat(to); err == nil {
				if item.Rename {
					to = addVersionSuffix(to, d.user.Fs)
				} else if !item.Overwrite {
					return http.StatusConflict, fmt.Errorf("文件冲突: %s", to)
				}
			}
			if to == from {
				return http.StatusBadRequest, fmt.Errorf("第 %d 项的源和目标路径相同", index+1)
			}
			if info.IsDir() {
				if err := nativeDirectoryOverlap(d.user.Fs, from, to); err != nil {
					return http.StatusBadRequest, err
				}
			}
			item.From, item.To, item.IsDir, item.Size = from, to, info.IsDir(), info.Size()
			args.Items = append(args.Items, item)
		}

		taskType := tasks.TypeFileCopy
		title := fmt.Sprintf("复制 %d 项", len(args.Items))
		if request.Action == "move" {
			taskType = tasks.TypeFileMove
			title = fmt.Sprintf("移动 %d 项", len(args.Items))
		}
		current, status, err := validateFileTransferEnqueue(r.Context(), d, &tasks.Task{UserID: d.user.ID, Type: taskType}, args)
		if err != nil {
			return status, err
		}
		encoded, err := json.Marshal(args)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		task, err := enqueueTask(runtime, current, current.user, taskType, title, encoded, "")
		if err != nil {
			return taskErrorStatus(err), err
		}
		recordHistory(d, "file."+request.Action, title, task.ID, history.StatusSubmitted)
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}

func normalizeTransferPath(value string) (string, error) {
	if value == "/files" || strings.HasPrefix(value, "/files/") {
		value = strings.TrimPrefix(value, "/files")
		if value == "" {
			value = "/"
		}
		parts := strings.Split(value, "/")
		for index, part := range parts {
			decoded, err := url.PathUnescape(part)
			if err != nil || strings.ContainsAny(decoded, "/\x00") {
				return "", fmt.Errorf("路径编码无效")
			}
			parts[index] = decoded
		}
		return literalOperationPath(strings.Join(parts, "/"))
	}
	return literalOperationPath(value)
}

func lstatResource(afs afero.Fs, name string) (os.FileInfo, error) {
	if lstater, ok := afs.(afero.Lstater); ok {
		info, _, err := lstater.LstatIfPossible(name)
		return info, err
	}
	return afs.Stat(name)
}

// This identity belongs to the current in-process attempt. Old persisted tasks
// do not contain a workspace identity; this does not guess one for historical retry.
type fileTransferWorkspace struct {
	scope, serverRoot, fsRoot string
	paths                     map[string]string
}

var errFileTransferWorkspaceChanged = errors.New("任务来源目录已变化，请重新创建任务")

func captureFileTransferWorkspace(d *data, args fileTransferTaskArgs) fileTransferWorkspace {
	identity := fileTransferWorkspace{scope: d.user.Scope, serverRoot: d.server.Root, fsRoot: d.user.FullPath("/"), paths: make(map[string]string)}
	for _, item := range args.Items {
		identity.paths[item.From], identity.paths[item.To] = d.user.FullPath(item.From), d.user.FullPath(item.To)
	}
	return identity
}

func (identity fileTransferWorkspace) authorize(ctx context.Context, d *data, task *tasks.Task, item fileTransferItem) (*data, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if d.server.Root != identity.serverRoot {
		return nil, errFileTransferWorkspaceChanged
	}
	actor, err := d.store.Users.Get(d.server.Root, task.UserID)
	if err != nil {
		return nil, fmt.Errorf("任务所有者已不可用，请重新创建任务: %w", err)
	}
	if actor.Scope != identity.scope || actor.FullPath("/") != identity.fsRoot || actor.FullPath(item.From) != identity.paths[item.From] || actor.FullPath(item.To) != identity.paths[item.To] {
		return nil, errFileTransferWorkspaceChanged
	}
	if !actor.Perm.Create || (task.Type == tasks.TypeFileMove && !actor.Perm.Rename) || (item.Overwrite && !actor.Perm.Modify) {
		return nil, fmt.Errorf("执行时没有文件操作权限，请检查权限后重试: %w", fberrors.ErrPermissionDenied)
	}
	policy, err := d.store.Settings.Get()
	if err != nil {
		return nil, fmt.Errorf("无法核验当前文件访问规则，请重试: %w", err)
	}
	current := *d
	current.user, current.settings = actor, policy
	if !current.Check(item.From) || !current.Check(item.To) {
		return nil, fmt.Errorf("任务路径已无权访问，请检查规则后重试: %w", fberrors.ErrPermissionDenied)
	}
	return &current, nil
}

// Initial submission and explicit file-task retry share the same current-owner
// authorization point before Store.New. This does not infer historical scope.
func validateFileTransferEnqueue(ctx context.Context, d *data, task *tasks.Task, args fileTransferTaskArgs) (*data, int, error) {
	if len(args.Items) == 0 || len(args.Items) > 1000 {
		return nil, http.StatusConflict, fmt.Errorf("文件任务参数无有效项目，请重新创建任务")
	}
	if err := directoryConflict(d, args); err != nil {
		return nil, http.StatusConflict, err
	}
	for _, j := range args.Directories {
		if err := validateDirectoryJournal(j); err != nil {
			return nil, http.StatusConflict, err
		}
		if j.UserID != task.UserID || j.Scope != directoryScope(d) {
			return nil, http.StatusConflict, errFileTransferWorkspaceChanged
		}
	}
	workspace := captureFileTransferWorkspace(d, args)
	current := d
	for _, item := range args.Items {
		if item.From == "/" || item.To == "/" || item.From == item.To {
			return nil, http.StatusConflict, fmt.Errorf("文件任务源或目标无效，请重新创建任务")
		}
		if item.IsDir {
			if err := directoryOverlap(item.From, item.To); err != nil {
				return nil, http.StatusConflict, err
			}
		}
		if err := checkParent(item.From, item.To); err != nil {
			return nil, http.StatusConflict, err
		}
		updated, err := workspace.authorize(ctx, d, task, item)
		if err != nil {
			status := http.StatusInternalServerError
			switch {
			case errors.Is(err, fberrors.ErrPermissionDenied):
				status = http.StatusForbidden
			case errors.Is(err, errFileTransferWorkspaceChanged), errors.Is(err, fberrors.ErrNotExist):
				status = http.StatusConflict
			case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
				status = http.StatusRequestTimeout
			}
			return nil, status, err
		}
		current = updated
	}
	return current, http.StatusAccepted, nil
}

func fileTransferRunner(d *data, task *tasks.Task, args fileTransferTaskArgs) tasks.Runner {
	if args.Completed == nil {
		args.Completed = make(map[string]bool)
	}
	args.Directories = nonNilDirectories(args.Directories)
	workspace := captureFileTransferWorkspace(d, args)
	return func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
		abandoned := func(err error) (json.RawMessage, error) {
			if len(args.Directories) > 0 {
				return marshalFileTransferResult(fileTransferResult{Directories: args.Directories}), fmt.Errorf("目录事务待恢复，未继续写入: %w", err)
			}
			return nil, err
		}
		select {
		case <-ctx.Done():
			return abandoned(ctx.Err())
		case fileTransferGate <- struct{}{}:
		}
		defer func() { <-fileTransferGate }()
		if err := ctx.Err(); err != nil {
			return abandoned(err)
		}

		result := fileTransferResult{Directories: args.Directories}
		if err := directoryConflict(d, args); err != nil {
			return marshalFileTransferResult(result), err
		}
		totalBytes := int64(0)
		for _, item := range args.Items {
			totalBytes += item.Size
		}
		processedItems := 0
		processedBytes := int64(0)
		checkpoint := fileTransferCheckpoint{Completed: args.Completed, Directories: args.Directories}
		checkpointJSON := func() json.RawMessage {
			encoded, _ := json.Marshal(checkpoint)
			return encoded
		}
		if err := report(tasks.Progress{TotalItems: len(args.Items), TotalBytes: totalBytes}); err != nil {
			return abandoned(err)
		}

		for index, item := range args.Items {
			if err := ctx.Err(); err != nil {
				return marshalFileTransferResult(result), err
			}
			current, err := workspace.authorize(ctx, d, task, item)
			if err != nil {
				result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
				return marshalFileTransferResult(result), err
			}
			// Keep temporary-file cleanup tied to the filesystem that created it,
			// even if a later permission/scope recheck rejects the current actor.
			itemFS := current.user.Fs
			authorizationFailed := false
			verify := func() error {
				updated, err := workspace.authorize(ctx, d, task, item)
				if err != nil {
					authorizationFailed = true
					return err
				}
				current = updated
				return nil
			}
			directoryRun := func() error {
				directoryBytes := processedBytes
				return runDirectoryPublication(ctx, current, task, item, args.Directories,
					func() error {
						return report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()})
					},
					func() (*data, error) {
						if err := verify(); err != nil {
							return nil, err
						}
						return current, nil
					},
					func(delta int64) error {
						directoryBytes += delta
						return report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: directoryBytes})
					})
			}
			if args.Directories[directoryKey(item)] != nil {
				if err := directoryRun(); err != nil {
					result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
					return marshalFileTransferResult(result), err
				}
				processedItems++
				processedBytes += item.Size
				result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
				if err := report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()}); err != nil {
					return marshalFileTransferResult(result), err
				}
				continue
			}
			if completedPath(args.CompletedPaths, item) || completedCheckpoint(itemFS, checkpoint.Completed, item) {
				if destinationMatchesItem(itemFS, item) {
					processedItems++
					processedBytes += item.Size
					_ = report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()})
					result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
					continue
				}
				for key := range checkpoint.Completed {
					if _, from, to, valid := canonicalTransferCheckpoint(key); valid && from == item.From && to == item.To {
						delete(checkpoint.Completed, key)
					}
				}
			}
			key, info, err := fileTransferIdentity(itemFS, item)
			if err != nil {
				result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
				continue
			}

			if info.IsDir() {
				if err := nativeDirectoryOverlap(itemFS, item.From, item.To); err != nil {
					return marshalFileTransferResult(result), err
				}
				if dst, dstErr := lstatResource(itemFS, item.To); dstErr == nil && item.Overwrite {
					if !dst.IsDir() {
						return marshalFileTransferResult(result), fmt.Errorf("目录目标类型已变化，未覆盖")
					}
					if err := directoryRun(); err != nil {
						result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
						return marshalFileTransferResult(result), err
					}
					processedItems++
					processedBytes += item.Size
					result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
					if err := report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()}); err != nil {
						return marshalFileTransferResult(result), err
					}
					continue
				} else if dstErr != nil && !errors.Is(dstErr, os.ErrNotExist) {
					return marshalFileTransferResult(result), dstErr
				}
			}

			if task.Type == tasks.TypeFileMove {
				// A same-filesystem move is an atomic rename. Only an explicit
				// cross-device error is allowed to fall through to the resumable
				// copy path below; permission and conflict errors must be reported.
				if _, destinationErr := lstatResource(itemFS, item.To); errors.Is(destinationErr, os.ErrNotExist) {
					if err := verify(); err != nil {
						result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
						return marshalFileTransferResult(result), err
					}
					var renamed bool
					var renameErr error
					if info.Mode().IsRegular() {
						// Stat does not reserve the target. Native no-replace also
						// protects a file created by another writer after that read.
						renameErr = files.PublishUpload(itemFS, item.From, item.To, false)
						renamed = renameErr == nil
						if errors.Is(renameErr, syscall.EXDEV) {
							renameErr = nil
						}
					} else {
						renamed, renameErr = tryFastMove(itemFS, item.From, item.To)
					}
					if renameErr != nil {
						result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: renameErr.Error()})
						continue
					}
					if renamed {
						if metadataErr := rewritePathMetadata(current, item.From, item.To); metadataErr != nil {
							rollbackErr := itemFS.Rename(item.To, item.From)
							if rollbackErr != nil {
								metadataErr = errors.Join(metadataErr, fmt.Errorf("元数据更新失败后的回滚失败: %w", rollbackErr))
							}
							result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: metadataErr.Error()})
							continue
						}
						processedItems++
						processedBytes += processedBytesForItem(info, item)
						checkpoint.Completed[key] = true
						result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
						if err := report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()}); err != nil {
							return marshalFileTransferResult(result), err
						}
						continue
					}
				}
			}

			temp := path.Join(path.Dir(item.To), ".nfb-transfer-"+task.ID+fmt.Sprintf("-%d", index))
			_ = itemFS.RemoveAll(temp)
			copyBytes := processedBytes
			err = fileutils.CopyContextProgress(ctx, itemFS, item.From, temp, current.settings.FileMode, current.settings.DirMode, func(delta int64) error {
				copyBytes += delta
				return report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: copyBytes})
			})
			if err == nil {
				if _, statErr := itemFS.Stat(item.To); statErr == nil {
					if !item.Overwrite {
						err = fmt.Errorf("文件冲突: %s", item.To)
					}
				}
			}
			// Keep authorization immediately before publication. Do not split
			// the authorized commit with another permission check inside it.
			if err == nil {
				err = verify()
			}
			if err == nil {
				if info.Mode().IsRegular() {
					// Publish the completed file without first unlinking the old
					// target; a failed native rename leaves its bytes available.
					err = files.PublishUpload(itemFS, temp, item.To, item.Overwrite)
				} else {
					err = files.PublishUpload(itemFS, temp, item.To, false)
				}
			}
			if err == nil && task.Type == tasks.TypeFileMove {
				// Update path metadata before deleting the source. A metadata
				// failure therefore leaves both paths available for inspection
				// instead of losing the user's original file.
				err = verify()
				if err == nil {
					err = rewritePathMetadata(current, item.From, item.To)
				}
				if err == nil {
					err = verify()
					if err == nil {
						err = itemFS.RemoveAll(item.From)
					}
				}
			}
			_ = itemFS.RemoveAll(temp)
			if err != nil {
				result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
				if errors.Is(err, context.Canceled) || errors.Is(ctx.Err(), context.Canceled) {
					return marshalFileTransferResult(result), ctx.Err()
				}
				if authorizationFailed {
					return marshalFileTransferResult(result), err
				}
				continue
			}

			processedItems++
			processedBytes = copyBytes
			if processedBytes < processedBytesForItem(info, item) {
				processedBytes = processedBytesForItem(info, item)
			}
			checkpoint.Completed[key] = true
			result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
			if err := report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()}); err != nil {
				return marshalFileTransferResult(result), err
			}
		}

		encoded := marshalFileTransferResult(result)
		if len(result.Failed) > 0 {
			return encoded, fmt.Errorf("%d 项文件操作失败", len(result.Failed))
		}
		return encoded, nil
	}
}

func tryFastMove(afs afero.Fs, from, to string) (bool, error) {
	err := afs.Rename(from, to)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, syscall.EXDEV) {
		return false, nil
	}
	return false, err
}

func fileTransferIdentity(afs afero.Fs, item fileTransferItem) (string, os.FileInfo, error) {
	info, err := lstatResource(afs, item.From)
	if err != nil {
		return "", nil, err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return "", nil, fmt.Errorf("不支持复制符号链接: %s", item.From)
	}
	return fmt.Sprintf("wire:%s\x00%s\x00%d\x00%d\x00%t", files.EncodeWirePath(item.From), files.EncodeWirePath(item.To), info.Size(), info.ModTime().UnixNano(), info.IsDir()), info, nil
}

func destinationMatchesItem(afs afero.Fs, item fileTransferItem) bool {
	info, err := lstatResource(afs, item.To)
	if err != nil {
		return false
	}
	return info.IsDir() == item.IsDir && info.Size() == item.Size
}

func completedPath(completed []fileTransferResultItem, item fileTransferItem) bool {
	for _, saved := range completed {
		if saved.From == item.From && saved.To == item.To {
			return true
		}
	}
	return false
}

func completedCheckpoint(afs afero.Fs, completed map[string]bool, item fileTransferItem) bool {
	if len(completed) == 0 {
		return false
	}
	if _, err := lstatResource(afs, item.From); err == nil {
		key, _, keyErr := fileTransferIdentity(afs, item)
		if keyErr != nil {
			return false
		}
		for saved, complete := range completed {
			canonical, _, _, valid := canonicalTransferCheckpoint(saved)
			if valid && complete && canonical == key {
				return true
			}
		}
		return false
	}
	for key, saved := range completed {
		_, from, to, valid := canonicalTransferCheckpoint(key)
		if saved && valid && from == item.From && to == item.To {
			return true
		}
	}
	return false
}

func processedBytesForItem(info os.FileInfo, item fileTransferItem) int64 {
	if info.IsDir() {
		return item.Size
	}
	return info.Size()
}

func marshalFileTransferResult(result fileTransferResult) json.RawMessage {
	encoded, _ := json.Marshal(result)
	return encoded
}

func resumeFileTransferArgs(original *tasks.Task) (json.RawMessage, error) {
	var args fileTransferTaskArgs
	if err := json.Unmarshal(original.Args, &args); err != nil {
		return nil, fmt.Errorf("任务参数损坏: %w", err)
	}
	if args.Completed == nil {
		args.Completed = make(map[string]bool)
	}
	if len(original.Result) > 0 {
		current, err := journalEnvelope(original.Result)
		if err != nil {
			return nil, err
		}
		args.Directories = nonNilDirectories(args.Directories)
		for key, j := range current {
			args.Directories[key] = j
		}
		var envelope struct{ Completed json.RawMessage }
		if err := json.Unmarshal(original.Result, &envelope); err != nil {
			return nil, fmt.Errorf("任务结果损坏: %w", err)
		}
		if completed := bytes.TrimSpace(envelope.Completed); len(completed) > 0 && completed[0] == '{' {
			var checkpoint fileTransferCheckpoint
			if err := json.Unmarshal(original.Result, &checkpoint); err != nil {
				return nil, err
			}
			for key, complete := range checkpoint.Completed {
				if complete {
					args.Completed[key] = true
				}
			}
		} else {
			var result fileTransferResult
			if err := json.Unmarshal(original.Result, &result); err != nil {
				return nil, fmt.Errorf("任务结果路径无法确认，请重新创建: %w", err)
			}
			args.CompletedPaths = append(args.CompletedPaths, result.Completed...)
		}
	}
	encoded, err := json.Marshal(args)
	if err != nil {
		return nil, fmt.Errorf("任务恢复参数生成失败: %w", err)
	}
	return encoded, nil
}
