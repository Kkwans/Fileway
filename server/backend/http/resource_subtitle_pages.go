package fbhttp

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
)

const (
	subtitlePagesRoute       = "/resource-directory-pages"
	subtitleFileRoute        = "/resource-subtitle-file"
	subtitlePagesVersion     = 1
	subtitlePageRows         = 32
	subtitlePageBytes        = 128 << 10
	subtitleScanBatch        = 128
	subtitleScanWindow       = 1024
	subtitleScanTTL          = 2 * time.Minute
	subtitleScanLimit        = 16
	subtitleAccountScanLimit = 2
	subtitleMaxWire          = 16 << 10
)

var (
	errSubtitlePageChanged = errors.New("目录已变化或分页已过期，请从第一页重新读取")
	errSubtitleFileChanged = errors.New("字幕文件已变化，请重新选择")
	errSubtitleBusy        = errors.New("字幕读取请求过多，请重试")
	errSubtitleTooLarge    = errors.New("字幕文件超过大小上限")
)

type subtitlePageRequest struct {
	Action       string `json:"action"`
	WirePath     string `json:"wirePath"`
	BrowserNonce string `json:"browserNonce"`
	Cursor       string `json:"cursor"`
	Version      string `json:"version"`
}

type subtitlePageRow struct {
	Name     string    `json:"name"`
	Path     string    `json:"path"`
	WirePath string    `json:"wirePath"`
	IsDir    bool      `json:"isDir"`
	Size     int64     `json:"size"`
	Modified time.Time `json:"modified"`
	Version  string    `json:"version,omitempty"`
}

type subtitlePage struct {
	Version    int               `json:"version"`
	WirePath   string            `json:"wirePath"`
	Items      []subtitlePageRow `json:"items"`
	NextCursor *string           `json:"nextCursor"`
}

type subtitleScanKey struct {
	user   uint
	device string
	nonce  string
	path   string
}

type subtitleScan struct {
	directory *files.SubtitleResource
	stamp     string
	policy    [sha256.Size]byte
	buffer    []fs.DirEntry
	lookahead *subtitlePageRow
	eof       bool
	next      string
	lastInput string
	lastPage  *subtitlePage
	cancel    context.CancelFunc
	timer     *time.Timer
	expires   time.Time
	closing   bool
}

type subtitlePages struct {
	mu     sync.Mutex
	scans  map[subtitleScanKey]*subtitleScan
	files  chan struct{}
	closed bool
}

func newSubtitlePages() *subtitlePages {
	return &subtitlePages{scans: make(map[subtitleScanKey]*subtitleScan), files: make(chan struct{}, subtitleScanLimit)}
}

func (s *subtitlePages) remove(key subtitleScanKey, scan *subtitleScan) {
	if scan.timer != nil {
		scan.timer.Stop()
	}
	if scan.directory != nil {
		_ = scan.directory.Close()
	}
	delete(s.scans, key)
}

func (s *subtitlePages) closeScan(key subtitleScanKey) {
	if scan := s.scans[key]; scan != nil {
		if scan.cancel != nil {
			scan.closing = true
			scan.cancel()
		} else {
			s.remove(key, scan)
		}
	}
}

func (s *subtitlePages) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	for key := range s.scans {
		s.closeScan(key)
	}
}

func (s *subtitlePages) acquire(parent context.Context, key subtitleScanKey, cursor string) (context.Context, *subtitleScan, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, nil, files.ErrSubtitleScopeUnavailable
	}
	scan := s.scans[key]
	if scan != nil && scan.cancel == nil && !time.Now().Before(scan.expires) {
		s.remove(key, scan)
		scan = nil
	}
	if scan == nil {
		if cursor != "" {
			return nil, nil, errSubtitlePageChanged
		}
		owned := 0
		for other, existing := range s.scans {
			if other.user == key.user {
				if other.device == key.device && other.nonce == key.nonce {
					if existing.cancel != nil {
						s.closeScan(other)
						return nil, nil, errSubtitleBusy
					}
					s.remove(other, existing)
					continue
				}
				owned++
			}
		}
		if len(s.scans) >= subtitleScanLimit || owned >= subtitleAccountScanLimit {
			return nil, nil, errSubtitleBusy
		}
		scan = &subtitleScan{}
		s.scans[key] = scan
	} else if scan.cancel != nil {
		return nil, nil, errSubtitleBusy
	} else if cursor != scan.lastInput && (cursor == "" || cursor != scan.next) {
		return nil, nil, errSubtitlePageChanged
	}
	if scan.timer != nil {
		scan.timer.Stop()
	}
	ctx, cancel := context.WithTimeout(parent, 8*time.Second)
	scan.cancel = cancel
	return ctx, scan, nil
}

func (s *subtitlePages) release(key subtitleScanKey, scan *subtitleScan, retained bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	scan.cancel()
	scan.cancel = nil
	if !retained || scan.closing {
		s.remove(key, scan)
		return
	}
	scan.expires = time.Now().Add(subtitleScanTTL)
	scan.timer = time.AfterFunc(subtitleScanTTL, func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.scans[key] == scan && scan.cancel == nil && !time.Now().Before(scan.expires) {
			s.remove(key, scan)
		}
	})
}

func subtitleOpaque(value string, minLength, maxLength int) bool {
	if len(value) < minLength || len(value) > maxLength {
		return false
	}
	for _, char := range value {
		allowed := char >= 'a' && char <= 'z' || char >= 'A' && char <= 'Z' || char >= '0' && char <= '9' || char == '-' || char == '_'
		if !allowed {
			return false
		}
	}
	return true
}

func subtitleWirePath(wire string) (string, error) {
	if len(wire) > subtitleMaxWire {
		return "", fs.ErrInvalid
	}
	raw, err := decodeResourceWirePath(wire)
	if err != nil || files.EncodeWirePath(raw) != wire {
		return "", fs.ErrInvalid
	}
	return raw, nil
}

func subtitlePolicy(d *data) [sha256.Size]byte {
	state, _ := json.Marshal(struct {
		User                uint
		Device, Root, Scope string
		Download            bool
		Global, Rules       []rules.Rule
	}{d.user.ID, d.deviceSession, d.server.Root, d.user.Scope, d.user.Perm.Download, d.settings.Rules, d.user.Rules})
	return sha256.Sum256(state)
}

func subtitleVersion(d *data, policy [sha256.Size]byte, raw, stamp string) string {
	mac := hmac.New(sha256.New, d.settings.Key)
	_, _ = mac.Write([]byte("fileway.external-subtitle.v1\x00"))
	_, _ = mac.Write(policy[:])
	_, _ = mac.Write([]byte(raw))
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write([]byte(stamp))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func externalSubtitle(name string) bool {
	switch strings.ToLower(path.Ext(name)) {
	case ".srt", ".ass", ".ssa", ".vtt", ".sup", ".ttml", ".dfxp", ".smi", ".sami":
		return true
	default:
		return false
	}
}

func openSubtitlePath(d *data, raw string, directory, readData bool) (*files.SubtitleResource, error) {
	if !d.Check(raw) {
		return nil, fs.ErrPermission
	}
	return files.OpenSubtitleResource(d.user.Fs, d.server.Root, raw, directory, readData)
}

func checkSubtitleDirectory(d *data, raw, want string) error {
	current, err := openSubtitlePath(d, raw, true, false)
	if errors.Is(err, fs.ErrNotExist) {
		return errSubtitlePageChanged
	}
	if err != nil {
		return err
	}
	defer func() { _ = current.Close() }()
	_, stamp, err := current.State()
	if err == nil && stamp != want {
		return errSubtitlePageChanged
	}
	return err
}

func (scan *subtitleScan) nextEntry() (fs.DirEntry, error) {
	if len(scan.buffer) == 0 && !scan.eof {
		entries, err := scan.directory.ReadDir(subtitleScanBatch)
		if err != nil && !errors.Is(err, io.EOF) {
			return nil, err
		}
		scan.eof = errors.Is(err, io.EOF) || len(entries) == 0
		scan.buffer = entries
	}
	if len(scan.buffer) == 0 {
		return nil, io.EOF
	}
	entry := scan.buffer[0]
	scan.buffer[0] = nil
	scan.buffer = scan.buffer[1:]
	return entry, nil
}

func (scan *subtitleScan) readPage(ctx context.Context, d *data, raw, wire, cursor string) (*subtitlePage, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if scan.directory == nil {
		directory, err := openSubtitlePath(d, raw, true, true)
		if err != nil {
			return nil, err
		}
		scan.directory = directory
		_, scan.stamp, err = directory.State()
		if err != nil {
			return nil, err
		}
		scan.policy = subtitlePolicy(d)
	} else if subtitlePolicy(d) != scan.policy {
		return nil, errSubtitlePageChanged
	}
	if err := checkSubtitleDirectory(d, raw, scan.stamp); err != nil {
		return nil, err
	}
	if scan.directory.Changed() {
		return nil, errSubtitlePageChanged
	}
	if scan.lastPage != nil && cursor == scan.lastInput {
		for _, row := range scan.lastPage.Items {
			name, err := subtitleWirePath(row.WirePath)
			if err != nil {
				return nil, err
			}
			current, err := openSubtitlePath(d, name, row.IsDir, false)
			if err != nil {
				return nil, errSubtitlePageChanged
			}
			_, stamp, err := current.State()
			_ = current.Close()
			if err != nil || !row.IsDir && row.Version != subtitleVersion(d, scan.policy, name, stamp) {
				return nil, errSubtitlePageChanged
			}
		}
		if scan.directory.Changed() {
			return nil, errSubtitlePageChanged
		}
		return scan.lastPage, ctx.Err()
	}
	page := &subtitlePage{Version: subtitlePagesVersion, WirePath: wire, Items: make([]subtitlePageRow, 0, subtitlePageRows)}
	bytes := len(wire) + 128
	start := time.Now()
	for scanned := 0; scanned < subtitleScanWindow && len(page.Items) < subtitlePageRows; scanned++ {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if scanned > 0 && time.Since(start) > 300*time.Millisecond {
			break
		}
		row := scan.lookahead
		scan.lookahead = nil
		if row == nil {
			entry, err := scan.nextEntry()
			if errors.Is(err, io.EOF) {
				break
			}
			if err != nil {
				return nil, err
			}
			row, err = scan.row(d, raw, entry)
			if err != nil {
				return nil, err
			}
			if row == nil {
				continue
			}
		}
		encoded, err := json.Marshal(row)
		if err != nil {
			return nil, err
		}
		if bytes+len(encoded)+1 > subtitlePageBytes {
			if len(page.Items) == 0 {
				return nil, errSubtitleTooLarge
			}
			scan.lookahead = row
			break
		}
		bytes += len(encoded) + 1
		page.Items = append(page.Items, *row)
	}
	if err := checkSubtitleDirectory(d, raw, scan.stamp); err != nil {
		return nil, err
	}
	_, stamp, err := scan.directory.State()
	if err != nil {
		return nil, err
	}
	if stamp != scan.stamp || scan.directory.Changed() {
		return nil, errSubtitlePageChanged
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	scan.next = ""
	if !scan.eof || len(scan.buffer) != 0 || scan.lookahead != nil {
		var entropy [24]byte
		if _, err := rand.Read(entropy[:]); err != nil {
			return nil, err
		}
		scan.next = base64.RawURLEncoding.EncodeToString(entropy[:])
		next := scan.next
		page.NextCursor = &next
	}
	scan.lastInput, scan.lastPage = cursor, page
	return page, nil
}

func (scan *subtitleScan) row(d *data, parent string, entry fs.DirEntry) (*subtitlePageRow, error) {
	name := entry.Name()
	directory := entry.IsDir()
	if entry.Type()&fs.ModeSymlink != 0 || !directory && (!entry.Type().IsRegular() || !externalSubtitle(name)) {
		return nil, nil
	}
	raw := path.Join(parent, name)
	wire := files.EncodeWirePath(raw)
	if len(wire) > subtitleMaxWire {
		return nil, errSubtitleTooLarge
	}
	resource, err := openSubtitlePath(d, raw, directory, false)
	if errors.Is(err, fs.ErrPermission) || errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer func() { _ = resource.Close() }()
	info, stamp, err := resource.State()
	if err != nil {
		return nil, err
	}
	row := &subtitlePageRow{Name: files.DisplayName(name), Path: files.DisplayPath(raw), WirePath: wire,
		IsDir: directory, Size: info.Size(), Modified: info.ModTime().UTC()}
	if !directory {
		row.Version = subtitleVersion(d, scan.policy, raw, stamp)
	}
	return row, nil
}

func subtitleResourceError(err error) (int, error) {
	switch {
	case errors.Is(err, fs.ErrInvalid):
		return http.StatusBadRequest, errors.New("字幕请求无效")
	case errors.Is(err, fs.ErrPermission):
		return http.StatusForbidden, errors.New("没有读取字幕或目录的权限")
	case errors.Is(err, fs.ErrNotExist):
		return http.StatusNotFound, errors.New("字幕文件或目录不存在")
	case errors.Is(err, errSubtitlePageChanged), errors.Is(err, errSubtitleFileChanged):
		return http.StatusConflict, err
	case errors.Is(err, errSubtitleBusy):
		return http.StatusTooManyRequests, err
	case errors.Is(err, errSubtitleTooLarge):
		return http.StatusRequestEntityTooLarge, err
	case errors.Is(err, files.ErrSubtitleScopeUnavailable):
		return http.StatusNotImplemented, errors.New("当前文件系统不支持安全字幕浏览")
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return http.StatusRequestTimeout, errors.New("字幕读取超时或已取消")
	default:
		return http.StatusInternalServerError, errors.New("无法读取字幕文件或目录")
	}
}

func (s *subtitlePages) pageHandler() handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("Cache-Control", "private, no-store")
		r.Body = http.MaxBytesReader(w, r.Body, 32<<10)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		var request subtitlePageRequest
		if err := decoder.Decode(&request); err != nil || decoder.Decode(new(any)) != io.EOF {
			return subtitleResourceError(fs.ErrInvalid)
		}
		raw, err := subtitleWirePath(request.WirePath)
		if err != nil {
			return subtitleResourceError(err)
		}
		if request.Action == "verify" {
			if request.BrowserNonce != "" || request.Cursor != "" {
				return subtitleResourceError(fs.ErrInvalid)
			}
			return s.serveSubtitleFile(w, r, d, raw, request.Version, false)
		}
		if !subtitleOpaque(request.BrowserNonce, 16, 128) || request.Version != "" ||
			request.Cursor != "" && !subtitleOpaque(request.Cursor, 32, 32) {
			return subtitleResourceError(fs.ErrInvalid)
		}
		key := subtitleScanKey{d.user.ID, d.deviceSession, request.BrowserNonce, raw}
		switch request.Action {
		case "close":
			if request.Cursor != "" {
				return subtitleResourceError(fs.ErrInvalid)
			}
			s.mu.Lock()
			s.closeScan(key)
			s.mu.Unlock()
			return renderJSON(w, r, map[string]bool{"closed": true})
		case "page":
			ctx, scan, err := s.acquire(r.Context(), key, request.Cursor)
			if err != nil {
				return subtitleResourceError(err)
			}
			retained := false
			defer func() { s.release(key, scan, retained) }()
			page, err := scan.readPage(ctx, d, raw, request.WirePath, request.Cursor)
			if err != nil {
				return subtitleResourceError(err)
			}
			retained = true
			return renderJSON(w, r, page)
		default:
			return subtitleResourceError(fs.ErrInvalid)
		}
	})
}

func (s *subtitlePages) fileHandler() handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("Cache-Control", "private, no-store")
		raw, err := subtitleWirePath(r.URL.EscapedPath())
		if err != nil {
			return subtitleResourceError(err)
		}
		query := r.URL.Query()["version"]
		if len(query) != 1 {
			return subtitleResourceError(fs.ErrInvalid)
		}
		return s.serveSubtitleFile(w, r, d, raw, query[0], true)
	})
}

func (s *subtitlePages) serveSubtitleFile(w http.ResponseWriter, r *http.Request, d *data, raw, version string, content bool) (int, error) {
	if !subtitleOpaque(version, 43, 43) || !externalSubtitle(raw) {
		return subtitleResourceError(fs.ErrInvalid)
	}
	if !d.user.Perm.Download {
		return subtitleResourceError(fs.ErrPermission)
	}
	select {
	case s.files <- struct{}{}:
		defer func() { <-s.files }()
	default:
		return subtitleResourceError(errSubtitleBusy)
	}
	file, err := openSubtitlePath(d, raw, false, content)
	if err != nil {
		return subtitleResourceError(err)
	}
	defer func() { _ = file.Close() }()
	info, stamp, err := file.State()
	if err != nil {
		return subtitleResourceError(err)
	}
	if !hmac.Equal([]byte(version), []byte(subtitleVersion(d, subtitlePolicy(d), raw, stamp))) {
		return subtitleResourceError(errSubtitleFileChanged)
	}
	limit := int64(16 << 20)
	if strings.EqualFold(path.Ext(raw), ".sup") {
		limit = 500 << 20
	}
	if info.Size() <= 0 || info.Size() > limit {
		return subtitleResourceError(errSubtitleTooLarge)
	}
	if !content {
		return renderJSON(w, r, map[string]string{"version": version})
	}
	if err := r.Context().Err(); err != nil {
		return subtitleResourceError(err)
	}
	// Serve from the same verified descriptor. A bounded section prevents a
	// later in-place expansion from exceeding the size that was authorized.
	w.Header().Set("ETag", `"`+version+`"`)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, files.DisplayName(path.Base(raw)), info.ModTime(), io.NewSectionReader(file.File, 0, info.Size()))
	return 0, nil
}
