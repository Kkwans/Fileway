package fbhttp

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"strings"
	"sync"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/fileutils"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/spf13/afero"
)

var errDirectoryRecovery = errors.New("目录交换需要恢复，备份已保留")
var errDirectoryRestored = errors.New("原目录已恢复，未重做复制或移动；请重新选择并执行")

type directoryPublication struct {
	Version         int               `json:"version"`
	Origin          string            `json:"origin"`
	Nonce           string            `json:"nonce"`
	UserID          uint              `json:"userId"`
	Scope           string            `json:"scope"`
	From            string            `json:"fromWirePath"`
	To              string            `json:"toWirePath"`
	Work            string            `json:"workWirePath"`
	PhysicalFrom    string            `json:"physicalFrom"`
	PhysicalTo      string            `json:"physicalTo"`
	PhysicalWork    string            `json:"physicalWork"`
	Ancestors       []*files.Identity `json:"ancestors"`
	SourceID        *files.Identity   `json:"sourceIdentity"`
	OldID           *files.Identity   `json:"oldIdentity"`
	NewID           *files.Identity   `json:"newIdentity"`
	WorkID          *files.Identity   `json:"workIdentity"`
	ParentID        *files.Identity   `json:"parentIdentity"`
	Phase           string            `json:"phase"`
	MetadataApplied bool              `json:"metadataApplied,omitempty"`
	SourceRemoved   bool              `json:"sourceRemoved,omitempty"`
	Done            bool              `json:"done,omitempty"`
}

type directoryOwner struct {
	Version                              int
	Origin, Nonce, Scope, From, To, Work string
	UserID                               uint
	WorkID, ParentID                     *files.Identity
}

func directoryKey(item fileTransferItem) string {
	return files.EncodeWirePath(item.From) + "\x00" + files.EncodeWirePath(item.To)
}
func (j *directoryPublication) key() string   { return j.Origin + ":" + j.Nonce }
func (j *directoryPublication) pending() bool { return j.Phase != "clean" && j.Phase != "restored" }
func (j *directoryPublication) owner() directoryOwner {
	return directoryOwner{j.Version, j.Origin, j.Nonce, j.Scope, j.From, j.To, j.Work, j.UserID, j.WorkID, j.ParentID}
}
func directoryScope(d *data) string {
	sum := sha256.Sum256([]byte(d.server.Root + "\x00" + d.user.Scope + "\x00" + d.user.FullPath("/")))
	return hex.EncodeToString(sum[:])
}
func stableDirectory(a, b *files.Identity) bool {
	return a != nil && b != nil && a.DeviceMajor == b.DeviceMajor && a.DeviceMinor == b.DeviceMinor && a.Inode == b.Inode && a.Mode&0o170000 == 0o040000 && b.Mode&0o170000 == 0o040000
}
func sameDirectoryDevice(a, b *files.Identity) bool {
	return a != nil && b != nil && a.DeviceMajor == b.DeviceMajor && a.DeviceMinor == b.DeviceMinor
}
func directoryPaths(j *directoryPublication) (from, to, work string, err error) {
	for _, v := range []string{j.From, j.To, j.Work} {
		if _, e := decodeOperationWirePath(v); e != nil {
			return "", "", "", e
		}
	}
	from, _ = decodeOperationWirePath(j.From)
	to, _ = decodeOperationWirePath(j.To)
	work, _ = decodeOperationWirePath(j.Work)
	return
}
func validateDirectoryJournal(j *directoryPublication) error {
	if j == nil || j.Version != 1 || j.Origin == "" || len(j.Nonce) != 32 || j.UserID == 0 || j.Scope == "" {
		return errDirectoryRecovery
	}
	if _, err := hex.DecodeString(j.Nonce); err != nil {
		return errDirectoryRecovery
	}
	from, to, work, err := directoryPaths(j)
	if err != nil {
		return errDirectoryRecovery
	}
	if from == "/" || to == "/" || directoryOverlap(from, to) != nil || work != path.Join(path.Dir(to), files.UploadPartPrefix+j.Nonce+".part") {
		return errDirectoryRecovery
	}
	switch j.Phase {
	case "copying", "prepared", "backupIntent", "backedUp", "publishIntent", "installed", "metadataIntent", "metadataApplied", "sourceRemoveIntent", "sourceRemoved", "logicalDone", "cleanupIntent", "discardIntent", "restoreIntent", "restored", "clean":
	default:
		return errDirectoryRecovery
	}
	if j.WorkID == nil || j.ParentID == nil || j.NewID == nil || j.SourceID == nil || j.OldID == nil {
		return errDirectoryRecovery
	}
	for _, v := range []string{j.PhysicalFrom, j.PhysicalTo, j.PhysicalWork} {
		b, e := base64.StdEncoding.DecodeString(v)
		if e != nil || !filepath.IsAbs(string(b)) {
			return errDirectoryRecovery
		}
	}
	return nil
}
func directoryOverlap(from, to string) error {
	if from == to || checkParent(from, to) != nil || checkParent(to, from) != nil {
		return fmt.Errorf("目录源和目标不能相同或互为祖先")
	}
	return nil
}
func nativeDirectoryOverlap(fs afero.Fs, from, to string) error {
	if err := directoryOverlap(from, to); err != nil {
		return err
	}
	source, target := files.FileIdentity(fs, from), files.FileIdentity(fs, to)
	if stableDirectory(source, target) {
		return fmt.Errorf("目录源和目标指向同一对象")
	}
	for _, pair := range []struct {
		root  *files.Identity
		child string
	}{{source, to}, {target, from}} {
		if pair.root == nil {
			continue
		}
		for parent := path.Dir(pair.child); ; parent = path.Dir(parent) {
			if stableDirectory(pair.root, files.FileIdentity(fs, parent)) {
				return fmt.Errorf("目录源和目标存在实际祖先重叠")
			}
			if parent == "/" || parent == "." {
				break
			}
		}
	}
	return nil
}

func cloneDirectoryJournal(j *directoryPublication) *directoryPublication {
	b, _ := json.Marshal(j)
	var c directoryPublication
	_ = json.Unmarshal(b, &c)
	return &c
}
func journalEnvelope(raw json.RawMessage) (map[string]*directoryPublication, error) {
	if len(raw) == 0 || !strings.Contains(string(raw), `"directoryPublications"`) {
		return nil, nil
	}
	var envelope struct {
		Directories map[string]*directoryPublication `json:"directoryPublications"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, err
	}
	for key, j := range envelope.Directories {
		if err := validateDirectoryJournal(j); err != nil {
			return nil, err
		}
		from, to, _, err := directoryPaths(j)
		if err != nil || key != directoryKey(fileTransferItem{From: from, To: to}) {
			return nil, errDirectoryRecovery
		}
	}
	return envelope.Directories, nil
}

type directoryRegistry struct {
	mu     sync.Mutex
	latest map[string]*directoryPublication
	busy   map[string]string
}

var directoryRegistries = struct {
	sync.Mutex
	stores map[*storage.Storage]*directoryRegistry
}{stores: make(map[*storage.Storage]*directoryRegistry)}

func initializeDirectoryPublications(store *storage.Storage) error {
	directoryRegistries.Lock()
	defer directoryRegistries.Unlock()
	registry, err := loadDirectoryRegistry(store)
	if err != nil {
		return err
	}
	directoryRegistries.stores[store] = registry
	return nil
}
func loadDirectoryRegistry(store *storage.Storage) (*directoryRegistry, error) {
	registry := &directoryRegistry{latest: make(map[string]*directoryPublication), busy: make(map[string]string)}
	all, err := store.Tasks.List(0, true)
	if err != nil {
		return nil, err
	}
	// Storage lists newest first. Result supersedes this attempt's inherited Args.
	for _, task := range all {
		if task.Type != tasks.TypeFileCopy && task.Type != tasks.TypeFileMove {
			continue
		}
		inherited, e := journalEnvelope(task.Args)
		if e != nil {
			return nil, e
		}
		current, e := journalEnvelope(task.Result)
		if e != nil {
			return nil, e
		}
		for key, j := range current {
			inherited = nonNilDirectories(inherited)
			inherited[key] = j
		}
		for _, j := range inherited {
			if _, exists := registry.latest[j.key()]; !exists {
				registry.latest[j.key()] = cloneDirectoryJournal(j)
			}
		}
	}
	return registry, nil
}
func nonNilDirectories(m map[string]*directoryPublication) map[string]*directoryPublication {
	if m == nil {
		return make(map[string]*directoryPublication)
	}
	return m
}
func getDirectoryRegistry(store *storage.Storage) (*directoryRegistry, error) {
	directoryRegistries.Lock()
	defer directoryRegistries.Unlock()
	if registry := directoryRegistries.stores[store]; registry != nil {
		return registry, nil
	}
	registry, err := loadDirectoryRegistry(store)
	if err != nil {
		return nil, err
	}
	directoryRegistries.stores[store] = registry
	return registry, nil
}
func recordDirectoryJournal(d *data, j *directoryPublication) error {
	r, err := getDirectoryRegistry(d.store)
	if err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.latest[j.key()] = cloneDirectoryJournal(j)
	return nil
}
func refreshDirectoryArgs(d *data, args *fileTransferTaskArgs) error {
	r, err := getDirectoryRegistry(d.store)
	if err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	for key, j := range args.Directories {
		if latest := r.latest[j.key()]; latest != nil {
			args.Directories[key] = cloneDirectoryJournal(latest)
		}
		if args.Directories[key].Phase == "restored" {
			return errDirectoryRestored
		}
	}
	return nil
}
func claimDirectoryRetry(d *data, args fileTransferTaskArgs) (func(), error) {
	r, err := getDirectoryRegistry(d.store)
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	keys := make([]string, 0, len(args.Directories))
	tokenBytes := make([]byte, 16)
	if _, err := rand.Read(tokenBytes); err != nil {
		return nil, err
	}
	token := hex.EncodeToString(tokenBytes)
	for _, j := range args.Directories {
		if r.busy[j.key()] != "" {
			return nil, tasks.ErrState
		}
		keys = append(keys, j.key())
	}
	for _, key := range keys {
		r.busy[key] = token
	}
	var once sync.Once
	return func() {
		once.Do(func() {
			r.mu.Lock()
			defer r.mu.Unlock()
			for _, key := range keys {
				if r.busy[key] == token {
					delete(r.busy, key)
				}
			}
		})
	}, nil
}
func directoryTaskNeedsRecovery(d *data, task *tasks.Task) bool {
	if task.Type != tasks.TypeFileCopy && task.Type != tasks.TypeFileMove {
		return false
	}
	inherited, err := journalEnvelope(task.Args)
	if err != nil {
		return true
	}
	current, err := journalEnvelope(task.Result)
	if err != nil {
		return true
	}
	for key, j := range current {
		inherited = nonNilDirectories(inherited)
		inherited[key] = j
	}
	r, err := getDirectoryRegistry(d.store)
	if err != nil {
		return true
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, j := range inherited {
		if latest := r.latest[j.key()]; latest != nil {
			j = latest
		}
		if j.pending() {
			return true
		}
	}
	return false
}
func physicalOverlap(a, b string) bool {
	if runtime.GOOS == "windows" {
		a, b = strings.ToLower(a), strings.ToLower(b)
	}
	_, one := metadataDescendant(a, b)
	_, two := metadataDescendant(b, a)
	return one || two
}
func directoryConflict(d *data, args fileTransferTaskArgs) error {
	r, err := getDirectoryRegistry(d.store)
	if err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	own := make(map[string]bool)
	for _, j := range args.Directories {
		own[j.key()] = true
	}
	for _, j := range r.latest {
		if !j.pending() || own[j.key()] {
			continue
		}
		for _, item := range args.Items {
			for _, candidate := range []string{item.From, item.To} {
				real := metadataRealPath(d.user, candidate)
				if real == "" {
					return errDirectoryRecovery
				}
				for _, encoded := range []string{j.PhysicalFrom, j.PhysicalTo, j.PhysicalWork} {
					b, e := base64.StdEncoding.DecodeString(encoded)
					if e != nil || physicalOverlap(real, string(b)) {
						return errDirectoryRecovery
					}
				}
				// Parent object IDs also close Windows case/short-name aliases and scopes
				// which expose the same real directory under different resource paths.
				direct := files.FileIdentity(d.user.Fs, candidate)
				for _, ancestor := range j.Ancestors {
					if stableDirectory(direct, ancestor) {
						return errDirectoryRecovery
					}
				}
				for parent := path.Dir(candidate); ; parent = path.Dir(parent) {
					parentID := files.FileIdentity(d.user.Fs, parent)
					for _, critical := range []*files.Identity{j.SourceID, j.OldID, j.NewID, j.WorkID} {
						if stableDirectory(parentID, critical) {
							return errDirectoryRecovery
						}
					}
					if parent == "/" || parent == "." {
						break
					}
				}
			}
		}
	}
	return nil
}

// The directory protocol is intentionally private to file tasks. Its journal
// is carried in the existing task checkpoint/result, not a new database table.
type directoryPublisher struct {
	ctx       context.Context
	d         *data
	task      *tasks.Task
	item      fileTransferItem
	j         *directoryPublication
	journals  map[string]*directoryPublication
	persist   func() error
	authorize func() (*data, error)
	copied    func(int64) error
	fs        afero.Fs
}

func (p *directoryPublisher) stage(phase string) error {
	p.j.Phase = phase
	if err := recordDirectoryJournal(p.d, p.j); err != nil {
		return err
	}
	return p.persist()
}
func (p *directoryPublisher) paths() (string, string, string) {
	from, to, work, _ := directoryPaths(p.j)
	return from, to, work
}
func (p *directoryPublisher) identity(name string) *files.Identity {
	return files.FileIdentity(p.fs, name)
}
func (p *directoryPublisher) matches(id *files.Identity, name string) bool {
	return stableDirectory(id, p.identity(name))
}
func (p *directoryPublisher) absent(name string) bool {
	_, err := lstatResource(p.fs, name)
	return errors.Is(err, os.ErrNotExist)
}
func (p *directoryPublisher) auth() (*data, error) {
	current, err := p.authorize()
	if err != nil {
		return nil, err
	}
	if current.user.ID != p.j.UserID || directoryScope(current) != p.j.Scope {
		return nil, errFileTransferWorkspaceChanged
	}
	if !p.matches(p.j.ParentID, path.Dir(p.item.To)) {
		return nil, errDirectoryRecovery
	}
	return current, nil
}
func (p *directoryPublisher) owned() error {
	_, to, work := p.paths()
	if !p.matches(p.j.ParentID, path.Dir(to)) || !p.matches(p.j.WorkID, work) {
		return errDirectoryRecovery
	}
	manifestPath := path.Join(work, "manifest.json")
	info, statErr := lstatResource(p.fs, manifestPath)
	if statErr != nil {
		if errors.Is(statErr, os.ErrNotExist) && (p.j.Phase == "cleanupIntent" || p.j.Phase == "discardIntent") {
			entries, e := afero.ReadDir(p.fs, work)
			if e == nil && len(entries) == 0 {
				return nil
			}
		}
		return errDirectoryRecovery
	}
	if !info.Mode().IsRegular() || info.Size() > 16*1024 {
		return errDirectoryRecovery
	}
	manifest, err := p.fs.Open(manifestPath)
	if err != nil {
		return errDirectoryRecovery
	}
	raw, readErr := io.ReadAll(io.LimitReader(manifest, 16*1024+1))
	closeErr := manifest.Close()
	if readErr != nil || closeErr != nil || len(raw) > 16*1024 {
		return errDirectoryRecovery
	}
	expected, _ := json.Marshal(p.j.owner())
	if !bytesEqual(raw, expected) {
		return errDirectoryRecovery
	}
	dir, err := p.fs.Open(work)
	if err != nil {
		return err
	}
	defer func() { _ = dir.Close() }()
	names, err := dir.Readdirnames(4)
	if err != nil && err != io.EOF {
		return err
	}
	for _, name := range names {
		if name != "manifest.json" && name != "new" && name != "old" {
			return errDirectoryRecovery
		}
	}
	return nil
}
func bytesEqual(a, b []byte) bool { return string(a) == string(b) }
func (p *directoryPublisher) removeWork() error {
	_, _, work := p.paths()
	if err := p.owned(); err != nil {
		return err
	}
	for _, name := range []string{"new", "old"} {
		if !p.absent(path.Join(work, name)) {
			return errDirectoryRecovery
		}
	}
	if err := p.fs.Remove(path.Join(work, "manifest.json")); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return p.fs.Remove(work) // Never recursively remove an unverified container.
}
func (p *directoryPublisher) discard() error {
	_, to, work := p.paths()
	if !p.matches(p.j.OldID, to) || !p.absent(path.Join(work, "old")) {
		return errDirectoryRecovery
	}
	if p.absent(work) {
		return p.stage("restored")
	}
	if err := p.owned(); err != nil {
		return err
	}
	if err := p.stage("discardIntent"); err != nil {
		return err
	}
	newPath := path.Join(work, "new")
	if !p.absent(newPath) {
		if !p.matches(p.j.NewID, newPath) {
			return errDirectoryRecovery
		}
		if err := p.fs.RemoveAll(newPath); err != nil {
			return err
		}
	}
	if err := p.removeWork(); err != nil {
		return err
	}
	return p.stage("restored")
}
func (p *directoryPublisher) rollback() error {
	_, to, work := p.paths()
	backup := path.Join(work, "old")
	if err := p.owned(); err != nil {
		return err
	}
	if !p.matches(p.j.OldID, backup) || !p.absent(to) || !p.matches(p.j.NewID, path.Join(work, "new")) {
		return errDirectoryRecovery
	}
	// Compensation uses the originally captured filesystem, never a new scope.
	// No-replace refuses a target filled by another writer, even after cancel.
	if err := p.stage("restoreIntent"); err != nil {
		return err
	}
	moveErr := files.PublishUpload(p.fs, backup, to, false)
	if !p.matches(p.j.OldID, to) || !p.absent(backup) {
		if moveErr != nil {
			return moveErr
		}
		return errDirectoryRecovery
	}
	return p.discard()
}
func (p *directoryPublisher) failure(err error) error {
	if err == nil {
		return nil
	}
	_, to, work := p.paths()
	if p.matches(p.j.OldID, path.Join(work, "old")) && p.absent(to) && p.matches(p.j.NewID, path.Join(work, "new")) {
		if recoveryErr := p.rollback(); recoveryErr != nil {
			return fmt.Errorf("%w：原目录备份待恢复: %w", errDirectoryRecovery, errors.Join(err, recoveryErr))
		}
		return errors.Join(errDirectoryRestored, err)
	}
	if p.matches(p.j.OldID, to) && p.absent(path.Join(work, "old")) {
		if recoveryErr := p.discard(); recoveryErr == nil {
			return errors.Join(errDirectoryRestored, err)
		}
	}
	return fmt.Errorf("%w；新目录可能已发布，源/旧备份未继续清除: %w", errDirectoryRecovery, err)
}
func (p *directoryPublisher) prepare() error {
	if err := nativeDirectoryOverlap(p.fs, p.item.From, p.item.To); err != nil {
		return err
	}
	parent := path.Dir(p.item.To)
	sourceID, oldID, parentID := p.identity(p.item.From), p.identity(p.item.To), p.identity(parent)
	if sourceID == nil || oldID == nil || parentID == nil || !stableDirectory(sourceID, sourceID) || !stableDirectory(oldID, oldID) || stableDirectory(sourceID, oldID) || !sameDirectoryDevice(oldID, parentID) {
		return fmt.Errorf("目录身份或同卷发布能力无法确认，原目标未改变")
	}
	// Reject reparse/symlink ancestors before allocating the private container.
	for _, candidate := range []string{p.item.From, p.item.To} {
		for candidate != "/" && candidate != "." {
			info, err := lstatResource(p.fs, candidate)
			if err != nil || info.Mode()&os.ModeSymlink != 0 {
				return errDirectoryRecovery
			}
			candidate = path.Dir(candidate)
		}
	}
	nonceBytes := make([]byte, 16)
	if _, err := rand.Read(nonceBytes); err != nil {
		return err
	}
	nonce := hex.EncodeToString(nonceBytes)
	work := path.Join(parent, files.UploadPartPrefix+nonce+".part")
	if err := p.fs.Mkdir(work, 0o700); err != nil {
		return err
	}
	workID := p.identity(work)
	if workID == nil {
		return errDirectoryRecovery
	}
	j := &directoryPublication{Version: 1, Origin: p.task.ID, Nonce: nonce, UserID: p.task.UserID, Scope: directoryScope(p.d), From: files.EncodeWirePath(p.item.From), To: files.EncodeWirePath(p.item.To), Work: files.EncodeWirePath(work), SourceID: sourceID, OldID: oldID, ParentID: parentID, WorkID: workID, Phase: "copying"}
	for encoded, raw := range map[*string]string{&j.PhysicalFrom: metadataRealPath(p.d.user, p.item.From), &j.PhysicalTo: metadataRealPath(p.d.user, p.item.To), &j.PhysicalWork: metadataRealPath(p.d.user, work)} {
		if raw == "" {
			return errDirectoryRecovery
		}
		*encoded = base64.StdEncoding.EncodeToString([]byte(raw))
	}
	for _, resourceParent := range []string{parent, path.Dir(p.item.From)} {
		for native := metadataRealPath(p.d.user, resourceParent); native != ""; native = filepath.Dir(native) {
			if id := files.FileIdentity(afero.NewOsFs(), native); id != nil {
				j.Ancestors = append(j.Ancestors, id)
			}
			if filepath.Dir(native) == native {
				break
			}
		}
	}
	p.j = j
	raw, _ := json.Marshal(j.owner())
	manifest, err := p.fs.OpenFile(path.Join(work, "manifest.json"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	_, writeErr := manifest.Write(raw)
	syncErr := manifest.Sync()
	closeErr := manifest.Close()
	if err := errors.Join(writeErr, syncErr, closeErr); err != nil {
		return err
	}
	newPath := path.Join(work, "new")
	if err := p.fs.Mkdir(newPath, 0o700); err != nil {
		return err
	}
	j.NewID = p.identity(newPath)
	if j.NewID == nil {
		return errDirectoryRecovery
	}
	p.journals[directoryKey(p.item)] = j
	if err := p.stage("copying"); err != nil {
		return err
	}
	probe := path.Join(work, "probe-a")
	if err := p.fs.Mkdir(probe, 0o700); err != nil {
		return err
	}
	probeTo := path.Join(work, "probe-b")
	probeID := p.identity(probe)
	if probeID == nil {
		return errDirectoryRecovery
	}
	err = files.PublishUpload(p.fs, probe, probeTo, false)
	if p.matches(probeID, probe) {
		if removeErr := p.fs.Remove(probe); removeErr != nil {
			return removeErr
		}
	}
	if p.matches(probeID, probeTo) {
		if removeErr := p.fs.Remove(probeTo); removeErr != nil {
			return removeErr
		}
	}
	if err != nil {
		return fmt.Errorf("文件系统不支持安全目录 no-replace 发布: %w", err)
	}
	if !p.absent(probe) || !p.absent(probeTo) {
		return errDirectoryRecovery
	}
	if err := fileutils.CopyContextProgress(p.ctx, p.fs, p.item.From, newPath, p.d.settings.FileMode, p.d.settings.DirMode, p.copied); err != nil {
		return err
	}
	info, err := p.fs.Stat(p.item.From)
	if err != nil {
		return err
	}
	if err := p.fs.Chmod(newPath, info.Mode().Perm()|p.d.settings.DirMode.Perm()); err != nil {
		return err
	}
	return p.stage("prepared")
}
func (p *directoryPublisher) install() error {
	_, to, work := p.paths()
	backup := path.Join(work, "old")
	newPath := path.Join(work, "new")
	if err := p.owned(); err != nil {
		return err
	}
	if !p.matches(p.j.SourceID, p.item.From) || !p.matches(p.j.OldID, to) || !p.matches(p.j.NewID, newPath) || !p.absent(backup) {
		return errDirectoryRecovery
	}
	if err := p.stage("backupIntent"); err != nil {
		return err
	}
	if _, err := p.auth(); err != nil {
		return err
	}
	if !p.matches(p.j.OldID, to) {
		return errDirectoryRecovery
	}
	moveErr := files.PublishUpload(p.fs, to, backup, false)
	if !p.matches(p.j.OldID, backup) || !p.absent(to) {
		if moveErr != nil {
			return moveErr
		}
		return errDirectoryRecovery
	}
	if err := p.stage("backedUp"); err != nil {
		return err
	}
	if moveErr != nil {
		return moveErr
	}
	if _, err := p.auth(); err != nil {
		return err
	}
	if err := p.stage("publishIntent"); err != nil {
		return err
	}
	if _, err := p.auth(); err != nil {
		return err
	}
	moveErr = files.PublishUpload(p.fs, newPath, to, false)
	if !p.matches(p.j.NewID, to) || !p.absent(newPath) {
		if moveErr != nil {
			return moveErr
		}
		return errDirectoryRecovery
	}
	if err := p.stage("installed"); err != nil {
		return err
	}
	return moveErr
}
func (p *directoryPublisher) finish() error {
	from, to, work := p.paths()
	if !p.matches(p.j.NewID, to) {
		return errDirectoryRecovery
	}
	if p.j.Phase == "clean" {
		if !p.absent(work) || !p.j.Done {
			return errors.New("此前目录操作已完成，当前路径随后变化；请刷新重新选择")
		}
		return nil
	}
	if p.j.Phase == "cleanupIntent" && p.absent(work) && p.j.Done {
		return p.stage("clean")
	}
	if err := p.owned(); err != nil {
		return err
	}
	if !p.absent(path.Join(work, "new")) {
		return errDirectoryRecovery
	}
	if p.task.Type == tasks.TypeFileMove && !p.j.SourceRemoved {
		current, err := p.auth()
		if err != nil {
			return err
		}
		if !p.j.MetadataApplied {
			if !p.matches(p.j.SourceID, from) {
				return errDirectoryRecovery
			}
			if err := p.stage("metadataIntent"); err != nil {
				return err
			}
			if _, err := p.auth(); err != nil {
				return err
			}
			if err := rewritePathMetadata(current, from, to); err != nil {
				return err
			}
			p.j.MetadataApplied = true
			if err := p.stage("metadataApplied"); err != nil {
				return err
			}
		}
		alreadyRemoving := p.j.Phase == "sourceRemoveIntent"
		if !p.matches(p.j.SourceID, from) && !(alreadyRemoving && p.absent(from)) {
			return errDirectoryRecovery
		}
		if err := p.stage("sourceRemoveIntent"); err != nil {
			return err
		}
		if _, err := p.auth(); err != nil {
			return err
		}
		if !p.absent(from) {
			if !p.matches(p.j.SourceID, from) {
				return errDirectoryRecovery
			}
			if err := p.fs.RemoveAll(from); err != nil {
				return err
			}
		}
		p.j.SourceRemoved = true
		if err := p.stage("sourceRemoved"); err != nil {
			return err
		}
	}
	if !p.j.Done {
		p.j.Done = true
		if err := p.stage("logicalDone"); err != nil {
			return err
		}
	}
	if _, err := p.auth(); err != nil {
		return err
	}
	if err := p.stage("cleanupIntent"); err != nil {
		return err
	}
	if _, err := p.auth(); err != nil {
		return err
	}
	backup := path.Join(work, "old")
	if !p.absent(backup) {
		if !p.matches(p.j.OldID, backup) {
			return errDirectoryRecovery
		}
		if err := p.fs.RemoveAll(backup); err != nil {
			return err
		}
	}
	if err := p.removeWork(); err != nil {
		return err
	}
	return p.stage("clean")
}
func (p *directoryPublisher) recover() error {
	if err := validateDirectoryJournal(p.j); err != nil {
		return err
	}
	from, to, work := p.paths()
	if from != p.item.From || to != p.item.To || p.task.UserID != p.j.UserID || directoryScope(p.d) != p.j.Scope {
		return errFileTransferWorkspaceChanged
	}
	for encoded, actual := range map[string]string{p.j.PhysicalFrom: metadataRealPath(p.d.user, from), p.j.PhysicalTo: metadataRealPath(p.d.user, to), p.j.PhysicalWork: metadataRealPath(p.d.user, work)} {
		expected, err := base64.StdEncoding.DecodeString(encoded)
		if err != nil || string(expected) != actual {
			return errFileTransferWorkspaceChanged
		}
	}
	if _, err := p.auth(); err != nil {
		return err
	}
	if p.j.Phase == "restored" {
		return errDirectoryRestored
	}
	if p.j.Phase == "clean" {
		return p.finish()
	}
	if p.matches(p.j.NewID, to) && p.absent(path.Join(work, "new")) {
		if p.j.Phase == "publishIntent" || p.j.Phase == "backedUp" {
			if err := p.stage("installed"); err != nil {
				return err
			}
		}
		return p.finish()
	}
	if err := p.owned(); err != nil {
		return err
	}
	if p.matches(p.j.OldID, to) && p.absent(path.Join(work, "old")) {
		if err := p.discard(); err != nil {
			return err
		}
		return errDirectoryRestored
	}
	if p.matches(p.j.OldID, path.Join(work, "old")) && p.absent(to) && p.matches(p.j.NewID, path.Join(work, "new")) {
		if err := p.rollback(); err != nil {
			return err
		}
		return errDirectoryRestored
	}
	return errDirectoryRecovery
}

type directoryPublicationError struct {
	message string
	cause   error
}

func (err *directoryPublicationError) Error() string { return err.message }
func (err *directoryPublicationError) Unwrap() error { return err.cause }
func directoryPublicError(j *directoryPublication, err error) error {
	if err == nil {
		return nil
	}
	if j != nil {
		log.Printf("directory publication origin=%s nonce=%s phase=%s: %v", j.Origin, j.Nonce, j.Phase, err)
	} else {
		log.Printf("directory publication prepare: %v", err)
	}
	message := "目录交换需要恢复；新目录可能已发布，源和旧备份未继续清除"
	if errors.Is(err, errDirectoryRestored) {
		message = errDirectoryRestored.Error()
	} else if j == nil {
		message = "无法准备安全目录交换，原目标未改变"
	} else if j.Phase == "clean" {
		message = "此前目录操作已完成，当前路径随后变化；请刷新重新选择"
	}
	return &directoryPublicationError{message: message, cause: err}
}

func runDirectoryPublication(ctx context.Context, d *data, task *tasks.Task, item fileTransferItem, journals map[string]*directoryPublication, persist func() error, authorize func() (*data, error), copied func(int64) error) error {
	p := &directoryPublisher{ctx: ctx, d: d, task: task, item: item, journals: journals, persist: persist, authorize: authorize, copied: copied, fs: d.user.Fs}
	p.j = journals[directoryKey(item)]
	if p.j != nil {
		return directoryPublicError(p.j, p.recover())
	}
	if err := p.prepare(); err != nil {
		if p.j != nil && p.j.NewID != nil {
			return directoryPublicError(p.j, p.failure(err))
		}
		return directoryPublicError(p.j, err)
	}
	if err := p.install(); err != nil {
		return directoryPublicError(p.j, p.failure(err))
	}
	if err := p.finish(); err != nil {
		return directoryPublicError(p.j, p.failure(err))
	}
	return nil
}
