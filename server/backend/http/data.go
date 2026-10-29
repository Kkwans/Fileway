package fbhttp

import (
	"log"
	"net/http"
	"runtime"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/tomasen/realip"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/runner"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

type handleFunc func(w http.ResponseWriter, r *http.Request, d *data) (int, error)

type data struct {
	taskRuntime *tasks.Runtime
	fileCache   FileCache
	mediaAssets *mediaAssetService
	*runner.Runner
	settings      *settings.Settings
	server        *settings.Server
	store         *storage.Storage
	user          *users.User
	deviceSession string
	apiPath       string
	raw           interface{}
}

// Check implements rules.Checker.
func (d *data) Check(path string) bool {
	// HTTP resource paths use '/' separators. On Windows, accepting '\\'
	// would let Fs normalize a different path after rules were checked, and
	// BasePathFs can then resolve '..\\' into a same-prefix sibling scope.
	// Invalid UTF-8 would become U+FFFD in Windows UTF-16 conversion and
	// could address a different Unicode sibling after the rules check.
	// Linux keeps backslashes as legal filename bytes.
	if runtime.GOOS == "windows" && (strings.ContainsRune(path, '\\') || !utf8.ValidString(path)) {
		return false
	}
	if trash.IsInternalPath(path) || files.IsPrivateResourcePath(d.user.Fs, path) {
		return false
	}
	allow := true
	for _, rule := range d.settings.Rules {
		if rule.Matches(path) {
			allow = rule.Allow
		}
	}

	for _, rule := range d.user.Rules {
		if rule.Matches(path) {
			allow = rule.Allow
		}
	}

	return allow
}

func handle(fn handleFunc, prefix string, store *storage.Storage, server *settings.Server) http.Handler {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for k, v := range globalHeaders {
			w.Header().Set(k, v)
		}

		settings, err := store.Settings.Get()
		if err != nil {
			log.Fatalf("ERROR: couldn't get settings: %v\n", err)
			return
		}

		status, err := fn(w, r, &data{
			Runner:   &runner.Runner{Enabled: server.EnableExec, Settings: settings},
			store:    store,
			settings: settings,
			server:   server,
			apiPath:  canonicalHandlerPath(prefix, r.URL.Path),
		})

		if status >= 400 || err != nil {
			clientIP := realip.FromRequest(r)
			log.Printf("%s: %v %s %v", r.URL.Path, status, clientIP, err)
		}

		if status != 0 {
			txt := http.StatusText(status)
			if status >= 400 && err != nil {
				// 优先返回中文错误信息，不带 HTTP 状态文本前缀
				txt = err.Error()
			}
			http.Error(w, strconv.Itoa(status)+" "+txt, status)
			return
		}
	})

	return stripPrefix(prefix, handler)
}
