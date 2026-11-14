package fbhttp

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/rules"
)

var (
	errCatalogCursor        = errors.New("invalid catalog cursor")
	errCatalogCursorExpired = errors.New("expired catalog cursor")
	errCatalogCursorStale   = errors.New("stale catalog cursor")
	errCatalogWorkPending   = errors.New("catalog authorization is still being evaluated")
)

const catalogCursorTTL = 15 * time.Minute

type catalogVisibilityCursor struct {
	ItemID                  string `json:"i,omitempty"`
	SourceAfter             string `json:"f,omitempty"`
	Stage                   string `json:"t,omitempty"`
	Season                  int    `json:"s,omitempty"`
	Episode                 int    `json:"n,omitempty"`
	EpisodeID               string `json:"e,omitempty"`
	ItemRevision            int64  `json:"ir,omitempty"`
	StructureRevision       int64  `json:"sr,omitempty"`
	ParentStructureRevision int64  `json:"pr,omitempty"`
}

type catalogCursor struct {
	Version               int  `json:"v"`
	Actor                 uint `json:"a"`
	Session, Query, Scope string
	Expires               int64
	AfterID               string                   `json:"i,omitempty"`
	AfterValue            *int64                   `json:"n,omitempty"`
	AfterNumber           *int                     `json:"p,omitempty"`
	OrderID               string                   `json:"oid,omitempty"`
	OrderRevision         int64                    `json:"or,omitempty"`
	OrderPrefix           string                   `json:"op,omitempty"`
	Parent                *catalogVisibilityCursor `json:"r,omitempty"`
	SeasonParent          *catalogVisibilityCursor `json:"q,omitempty"`
	Pending               *catalogVisibilityCursor `json:"x,omitempty"`
}

func catalogCipher(key []byte) (cipher.AEAD, error) {
	if len(key) == 0 {
		return nil, errors.New("catalog signing key unavailable")
	}
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write([]byte("fileway/catalog-cursor/v1"))
	block, err := aes.NewCipher(mac.Sum(nil))
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func encodeCatalogCursor(key []byte, value catalogCursor) (string, error) {
	aead, err := catalogCipher(key)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(aead.Seal(nonce, nonce, data, nil)), nil
}

func decodeCatalogCursor(key []byte, token string, actor uint, session, query, scope string) (catalogCursor, error) {
	var value catalogCursor
	if len(token) == 0 || len(token) > 4096 {
		return value, errCatalogCursor
	}
	aead, err := catalogCipher(key)
	if err != nil {
		return value, err
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) < aead.NonceSize()+aead.Overhead() {
		return value, errCatalogCursor
	}
	plain, err := aead.Open(nil, raw[:aead.NonceSize()], raw[aead.NonceSize():], nil)
	if err != nil || json.Unmarshal(plain, &value) != nil || value.Version != 1 || value.Actor != actor || value.Session != session || value.Query != query {
		return catalogCursor{}, errCatalogCursor
	}
	if time.Now().UnixMilli() >= value.Expires {
		return catalogCursor{}, errCatalogCursorExpired
	}
	if value.Scope != scope {
		return catalogCursor{}, errCatalogCursorStale
	}
	return value, nil
}

// catalogScope is calculated once per request. It includes the live policies
// of visible library owners so a continuation cannot retain an older scope.
func catalogScope(ctx context.Context, d *data, libraryID string) (string, error) {
	if d.user == nil || d.server == nil || d.store.Users == nil || d.store.Settings == nil {
		return "", medialibrary.ErrForbidden
	}
	if d.authCheck != nil && d.authCheck() != nil {
		return "", medialibrary.ErrForbidden
	}
	viewer, err := d.store.Users.Get(d.server.Root, d.user.ID)
	if err != nil {
		return "", err
	}
	configuration, err := d.store.Settings.Get()
	if err != nil {
		return "", err
	}
	type ownerScope struct {
		ID          uint
		Scope       string
		Download    bool
		Rules       []rules.Rule
		Unavailable bool
	}
	type libraryScope struct {
		ID             string
		Revision       int64
		PolicyRevision int64
		Role           medialibrary.MemberRole
		Owner          uint
	}
	owners := map[uint]ownerScope{}
	libraries := []libraryScope{}
	include := func(lib medialibrary.Library) error {
		policyRevision, err := d.store.MediaLibrary.MetadataPolicyRevision(ctx, lib.ID)
		if err != nil {
			return err
		}
		libraries = append(libraries, libraryScope{lib.ID, lib.Revision, policyRevision, lib.Role, lib.OwnerUserID})
		if _, ok := owners[lib.OwnerUserID]; ok {
			return nil
		}
		owner, getErr := d.store.Users.Get(d.server.Root, lib.OwnerUserID)
		entry := ownerScope{ID: lib.OwnerUserID, Unavailable: getErr != nil || owner == nil}
		if !entry.Unavailable {
			entry.Scope, entry.Download, entry.Rules = owner.Scope, owner.Perm.Download, owner.Rules
		}
		owners[lib.OwnerUserID] = entry
		return nil
	}
	if libraryID != "" {
		lib, err := d.store.MediaLibrary.GetLibrary(ctx, d.user.ID, libraryID)
		if err != nil {
			return "", err
		}
		if lib.ArchivedAt != nil || !mediaLibraryAccessAllowed(d, lib) {
			return "", medialibrary.ErrNotFound
		}
		if err := include(*lib); err != nil {
			return "", err
		}
	} else {
		after := ""
		for {
			page, err := d.store.MediaLibrary.ListLibraries(ctx, d.user.ID, medialibrary.LibraryListOptions{OnlyOwned: mediaSharingDisabled(d), AfterID: after, Limit: 100})
			if err != nil {
				return "", err
			}
			for _, lib := range page.Items {
				if err := include(lib); err != nil {
					return "", err
				}
			}
			if page.NextCursor == "" {
				break
			}
			if page.NextCursor == after {
				return "", medialibrary.ErrInvalid
			}
			after = page.NextCursor
		}
	}
	data, err := json.Marshal(struct {
		Viewer                          uint
		Session, Root, ViewerScope      string
		ViewerRules, GlobalRules        []rules.Rule
		ViewerDownload, SharingDisabled bool
		Libraries                       []libraryScope
		Owners                          map[uint]ownerScope
	}{d.user.ID, d.deviceSession, d.server.Root, viewer.Scope, viewer.Rules, configuration.Rules, viewer.Perm.Download, mediaSharingDisabled(d), libraries, owners})
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(data)
	return base64.RawURLEncoding.EncodeToString(digest[:]), nil
}

func catalogError(w http.ResponseWriter, err error) (int, error) {
	status, code, message, retryable := http.StatusInternalServerError, "service_unavailable", "暂时无法读取影视目录", true
	switch {
	case errors.Is(err, errCatalogCursor):
		status, code, message, retryable = http.StatusBadRequest, "cursor_invalid", "分页参数无效", false
	case errors.Is(err, errCatalogCursorExpired):
		status, code, message, retryable = http.StatusGone, "cursor_expired", "分页已过期，请刷新", true
	case errors.Is(err, errCatalogCursorStale), errors.Is(err, medialibrary.ErrRevisionConflict):
		status, code, message, retryable = http.StatusConflict, "cursor_stale", "目录已更新，请刷新", true
	case errors.Is(err, errCatalogWorkPending):
		status, code, message, retryable = http.StatusServiceUnavailable, "work_deferred", "资料较多，请稍后重试", true
	case errors.Is(err, medialibrary.ErrNotFound), errors.Is(err, medialibrary.ErrArchived), errors.Is(err, medialibrary.ErrForbidden):
		status, code, message, retryable = http.StatusNotFound, "not_found_or_forbidden", "内容不存在或不可访问", false
	case errors.Is(err, medialibrary.ErrInvalid):
		status, code, message, retryable = http.StatusUnprocessableEntity, "invalid_request", "请求参数无效", false
	}
	return writeCatalogError(w, status, code, message, retryable)
}

func writeCatalogError(w http.ResponseWriter, status int, code, message string, retryable bool) (int, error) {
	id := make([]byte, 12)
	if _, readErr := io.ReadFull(rand.Reader, id); readErr != nil {
		return http.StatusInternalServerError, fmt.Errorf("目录请求暂时不可用")
	}
	return renderJSONStatus(w, struct {
		Code      string `json:"code"`
		Message   string `json:"message"`
		RequestID string `json:"requestId"`
		Retryable bool   `json:"retryable"`
	}{code, message, base64.RawURLEncoding.EncodeToString(id), retryable}, status)
}

// New catalog routes retain the existing authentication path while returning
// stable, non-sensitive error codes for middleware failures as well.
func catalogLibraryRead(fn handleFunc) handleFunc {
	wrapped := mediaLibraryRead(fn)
	return func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("Cache-Control", "no-store")
		status, err := wrapped(w, r, d)
		if status < 400 {
			return status, err
		}
		switch status {
		case http.StatusUnauthorized:
			return writeCatalogError(w, status, "unauthorized", "请重新登录", false)
		case http.StatusForbidden:
			return writeCatalogError(w, status, "not_granted", "没有此操作权限", false)
		case http.StatusNotFound:
			return writeCatalogError(w, status, "not_found_or_forbidden", "内容不存在或不可访问", false)
		case http.StatusServiceUnavailable:
			return writeCatalogError(w, status, "service_unavailable", "影视服务暂不可用", true)
		default:
			return writeCatalogError(w, status, "service_unavailable", "暂时无法读取影视目录", true)
		}
	}
}
