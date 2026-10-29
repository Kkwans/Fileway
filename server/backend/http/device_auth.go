package fbhttp

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/gorilla/mux"
)

// Resource handlers receive a stripped file path; authorization must use the
// API operation selected by the router, before that internal transformation.
func canonicalHandlerPath(prefix, path string) string {
	if prefix != "" && prefix != "/" {
		return prefix + path
	}
	return path
}

func deviceMediaRoute(method, path string, user uint) bool {
	p := strings.TrimPrefix(path, "/api/")
	if method == http.MethodGet || method == http.MethodHead {
		for _, prefix := range []string{"resources", "preview", "raw", "download", "media/", "subtitle", "search", "favorites", "recent", "playback"} {
			if p == prefix || strings.HasPrefix(p, prefix+"/") || strings.HasSuffix(prefix, "/") && strings.HasPrefix(p, prefix) {
				return true
			}
		}
		return p == "client-capabilities" || p == "instance" || p == "users/"+strconv.FormatUint(uint64(user), 10)
	}
	if p == "renew" && method == http.MethodPost {
		return true
	}
	if p == "media/hls" && method == http.MethodPost {
		return true
	}
	if p == "media/playback" && (method == http.MethodPut || method == http.MethodDelete) {
		return true
	}
	if p == "media/playback/delete" && method == http.MethodPost {
		return true
	}
	if p == "media/playback/recent" && method == http.MethodDelete {
		return true
	}
	if p == "resources/batch" && method == http.MethodPost {
		return true
	}
	for _, prefix := range []string{"playback", "recent", "favorites"} {
		if p == prefix || strings.HasPrefix(p, prefix+"/") {
			return method == http.MethodPost || method == http.MethodPut || method == http.MethodDelete
		}
	}
	return false
}
func deviceBody(w http.ResponseWriter, r *http.Request, target any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 8192)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fmt.Errorf("设备请求格式无效")
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return fmt.Errorf("设备请求格式无效")
	}
	return nil
}

func devicePublic(fn handleFunc) handleFunc {
	return func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if d.store.Devices == nil {
			return http.StatusNotImplemented, fmt.Errorf("设备授权不可用")
		}
		if !d.store.Devices.Allow(r.RemoteAddr) {
			return http.StatusTooManyRequests, fmt.Errorf("设备授权请求过多，请稍后重试")
		}
		return fn(w, r, d)
	}
}

var deviceInstanceHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.Devices == nil {
		return http.StatusNotImplemented, fmt.Errorf("设备授权不可用")
	}
	value, err := d.store.Devices.Identity()
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, value)
})
var deviceProofHandler handleFunc = func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body struct {
		Nonce string `json:"nonce"`
	}
	if err := deviceBody(w, r, &body); err != nil {
		return http.StatusBadRequest, err
	}
	if d.store.Devices == nil {
		return http.StatusNotImplemented, fmt.Errorf("设备授权不可用")
	}
	identity, proof, err := d.store.Devices.Proof(body.Nonce)
	if err != nil {
		return http.StatusBadRequest, fmt.Errorf("实例证明请求无效")
	}
	return renderJSON(w, r, struct {
		deviceauth.Identity
		Signature string `json:"signature"`
	}{identity, proof})
}
var deviceRequestHandler handleFunc = func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body struct {
		DeviceID  string `json:"deviceId"`
		Name      string `json:"name"`
		PublicKey string `json:"publicKey"`
	}
	if err := deviceBody(w, r, &body); err != nil {
		return http.StatusBadRequest, err
	}
	if d.store.Devices == nil {
		return http.StatusNotImplemented, fmt.Errorf("设备授权不可用")
	}
	value, err := d.store.Devices.Create(body.DeviceID, body.Name, body.PublicKey)
	if err != nil {
		return http.StatusTooManyRequests, fmt.Errorf("设备授权请求无效或过多")
	}
	return renderJSON(w, r, value)
}
var deviceApproveHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.deviceSession != "" {
		return http.StatusForbidden, fmt.Errorf("请使用手机账号批准设备登录")
	}
	var body struct {
		DeviceID  string `json:"deviceId"`
		PublicKey string `json:"publicKey"`
	}
	if err := deviceBody(w, r, &body); err != nil {
		return http.StatusBadRequest, err
	}
	if d.store.Devices == nil {
		return http.StatusNotImplemented, fmt.Errorf("设备授权不可用")
	}
	if err := d.store.Devices.Approve(mux.Vars(r)["id"], body.DeviceID, body.PublicKey, d.user.ID); err != nil {
		return http.StatusForbidden, fmt.Errorf("授权请求已过期或设备身份不匹配")
	}
	return renderJSON(w, r, map[string]bool{"approved": true})
})

func deviceCredentialHandler(refresh bool) handleFunc {
	return func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body struct {
			ID      string `json:"id"`
			Nonce   string `json:"nonce"`
			Proof   string `json:"proof"`
			Refresh string `json:"refresh,omitempty"`
		}
		if err := deviceBody(w, r, &body); err != nil {
			return http.StatusBadRequest, err
		}
		if d.store.Devices == nil {
			return http.StatusNotImplemented, fmt.Errorf("设备授权不可用")
		}
		var value deviceauth.Credential
		var err error
		if refresh {
			value, err = d.store.Devices.Refresh(body.ID, body.Refresh, body.Nonce, body.Proof)
		} else {
			value, err = d.store.Devices.Exchange(body.ID, body.Nonce, body.Proof)
		}
		if err != nil {
			return http.StatusUnauthorized, fmt.Errorf("设备授权已失效，请在手机重新授权")
		}
		user, err := d.store.Users.Get(d.server.Root, value.Session.UserID)
		if err != nil {
			return http.StatusUnauthorized, fmt.Errorf("设备账号不可用")
		}
		d.deviceSession = value.Session.ID
		// Reuse the normal token/user shape and renew contract, with an independent
		// signing key and media scope. Capture locally; never put it in a URL/log.
		output := &deviceTokenWriter{header: make(http.Header)}
		if status, err := printToken(output, r, d, user, tokenExpirationTime(d)); err != nil {
			return status, err
		}
		identity, err := d.store.Devices.Identity()
		if err != nil {
			return http.StatusInternalServerError, err
		}
		w.Header().Set("Cache-Control", "no-store")
		return renderJSON(w, r, map[string]any{"token": output.String(), "refresh": value.Refresh, "sessionId": value.Session.ID, "serverId": identity.ID, "userId": value.Session.UserID, "username": user.Username})
	}
}

type deviceTokenWriter struct {
	bytes.Buffer
	header http.Header
}

func (w *deviceTokenWriter) Header() http.Header { return w.header }
func (w *deviceTokenWriter) WriteHeader(int)     {}

var deviceSessionsHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.deviceSession != "" || d.store.Devices == nil {
		return http.StatusForbidden, fmt.Errorf("请使用手机账号管理设备")
	}
	if r.Method == http.MethodDelete {
		if err := d.store.Devices.Revoke(mux.Vars(r)["id"], d.user.ID); err != nil {
			return http.StatusNotFound, fmt.Errorf("设备登录不存在")
		}
		return renderJSON(w, r, map[string]bool{"revoked": true})
	}
	values, err := d.store.Devices.List(d.user.ID)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	public := make([]map[string]any, 0, len(values))
	for _, v := range values {
		public = append(public, map[string]any{"id": v.ID, "deviceId": v.DeviceID, "name": v.Name, "createdAt": v.CreatedAt, "expiresAt": v.ExpiresAt})
	}
	return renderJSON(w, r, public)
})
