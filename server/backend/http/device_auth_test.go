package fbhttp

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/golang-jwt/jwt/v5"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestDeviceJWTMediaScopeRenewAndLegacyRollback(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Admin: true, Execute: true, Download: true}})
	owner := firstTrashHTTPUser(h)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	spki, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	digest := sha256.Sum256(spki)
	deviceID := hex.EncodeToString(digest[:16])
	public := base64.RawURLEncoding.EncodeToString(spki)
	r, err := h.storage.Devices.Create(deviceID, "TV", public)
	if err != nil {
		t.Fatal(err)
	}
	if err = h.storage.Devices.Approve(r.ID, deviceID, public, owner.ID); err != nil {
		t.Fatal(err)
	}
	identity, _ := h.storage.Devices.Identity()
	nonce := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	sum := sha256.Sum256(deviceauth.Message("exchange", identity.ID, r.ID, r.Challenge, nonce))
	sig, _ := ecdsa.SignASN1(rand.Reader, key, sum[:])
	credential, err := h.storage.Devices.Exchange(r.ID, nonce, base64.RawURLEncoding.EncodeToString(sig))
	if err != nil {
		t.Fatal(err)
	}
	signing, _ := h.storage.Devices.SigningKey()
	claims := authToken{User: userInfo{ID: owner.ID}, DeviceSession: credential.Session.ID, Scope: "media", RegisteredClaims: jwt.RegisteredClaims{IssuedAt: jwt.NewNumericDate(time.Now()), ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour))}}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	token.Header["kid"] = "fileway.device.v1"
	signed, _ := token.SignedString(signing)
	call := func(fn handleFunc, method, path, token string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, nil)
		req.Header.Set("X-Auth", token)
		res := httptest.NewRecorder()
		handle(fn, "", h.storage, h.server).ServeHTTP(res, req)
		return res
	}
	probe := withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if d.user.Perm.Admin || d.user.Perm.Execute {
			t.Fatal("device retained admin or execute")
		}
		return http.StatusNoContent, nil
	})
	if response := call(probe, "GET", "/api/resources/", signed); response.Code != 204 {
		t.Fatalf("media rejected: %d %s", response.Code, response.Body)
	}
	request := httptest.NewRequest(http.MethodGet, "/api/resources/", nil)
	request.Header.Set("X-Auth", signed)
	listing := httptest.NewRecorder()
	handle(resourceGetHandler, "/api/resources", h.storage, h.server).ServeHTTP(listing, request)
	if listing.Code != http.StatusOK {
		t.Fatalf("real resource listing rejected: %d %s", listing.Code, listing.Body)
	}
	// Match the real resource route's strip-prefix/authentication order. The
	// original bug only appeared here, not in a direct withUser probe.
	for _, prefix := range []string{"/api/resources", "/api/raw", "/api/preview/thumb", "/api/subtitle", "/api/search"} {
		req := httptest.NewRequest("GET", prefix+"/owned", nil)
		req.Header.Set("X-Auth", signed)
		response := httptest.NewRecorder()
		handle(probe, prefix, h.storage, h.server).ServeHTTP(response, req)
		if response.Code != http.StatusNoContent {
			t.Fatalf("stripped media route %s rejected: %d %s", prefix, response.Code, response.Body)
		}
	}
	for _, path := range []string{"/api/settings", "/api/users", "/api/device-sessions", "/api/tasks/owned"} {
		if response := call(probe, "GET", path, signed); response.Code != 403 {
			t.Fatal("privileged route accepted", path, response.Code)
		}
	}
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/api/resources/owned", nil)
		req.Header.Set("X-Auth", signed)
		response := httptest.NewRecorder()
		handle(probe, "/api/resources", h.storage, h.server).ServeHTTP(response, req)
		if response.Code != http.StatusForbidden {
			t.Fatal("stripped resource mutation accepted", method, response.Code)
		}
	}
	if response := call(probe, "DELETE", "/api/resources/movie.mkv", signed); response.Code != 403 {
		t.Fatal("device deleted resource")
	}
	id := strings.Repeat("A", 32)
	for _, allowed := range []struct{ method, path string }{
		{"POST", "/api" + subtitlePagesRoute},
		{"GET", "/api" + subtitleFileRoute + "/owned.srt"},
		{"HEAD", "/api" + subtitleFileRoute + "/owned.srt"},
		{"GET", "/api/app-updates/tv"},
		{"POST", "/api/app-updates/tv/downloads"},
		{"GET", "/api/app-updates/tv/downloads/" + id},
		{"GET", "/api/app-updates/tv/downloads/" + id + "/content"},
		{"HEAD", "/api/app-updates/tv/downloads/" + id + "/content"},
	} {
		if response := call(probe, allowed.method, allowed.path, signed); response.Code != http.StatusNoContent {
			t.Fatalf("TV update route %s %s rejected: %d", allowed.method, allowed.path, response.Code)
		}
	}
	for _, denied := range []struct{ method, path string }{
		{"POST", "/api" + subtitlePagesRoute + "/other"},
		{"GET", "/api" + subtitlePagesRoute},
		{"POST", "/api" + subtitleFileRoute + "/owned.srt"},
		{"PUT", "/api/app-updates/tv/downloads"},
		{"GET", "/api/app-updates/tv/downloads/not-a-handle/content"},
		{"GET", "/api/app-updates/tv/downloads/" + id + "/other"},
		{"HEAD", "/api/app-updates/tv/downloads/" + id},
	} {
		if response := call(probe, denied.method, denied.path, signed); response.Code != http.StatusForbidden {
			t.Fatalf("unsupported TV update route %s %s passed: %d", denied.method, denied.path, response.Code)
		}
	}
	renewed := call(renewHandler(), "POST", "/api/renew", signed)
	if renewed.Code != 200 {
		t.Fatal("device renew failed", renewed.Code)
	}
	var updated authToken
	parsed, err := jwt.ParseWithClaims(renewed.Body.String(), &updated, func(*jwt.Token) (any, error) { return signing, nil })
	if err != nil || !parsed.Valid || updated.DeviceSession != credential.Session.ID || updated.Scope != "media" {
		t.Fatal("renew lost scope/session", err)
	}
	if _, err = jwt.Parse(renewed.Body.String(), func(*jwt.Token) (any, error) { return trashHTTPKey, nil }); err == nil {
		t.Fatal("old server would accept device JWT")
	}
	if err = h.storage.Devices.Revoke(credential.Session.ID, owner.ID); err != nil {
		t.Fatal(err)
	}
	if response := call(probe, "GET", "/api/resources/", signed); response.Code != 401 {
		t.Fatal("revocation not enforced", response.Code)
	}
	if response := call(probe, "GET", "/api/app-updates/tv/downloads/"+id+"/content", signed); response.Code != 401 {
		t.Fatal("revocation not enforced for TV updates", response.Code)
	}
	subtitles := newSubtitlePages()
	defer subtitles.close()
	if response := call(subtitles.pageHandler(), "POST", "/api"+subtitlePagesRoute, signed); response.Code != 401 {
		t.Fatal("revocation not enforced for subtitle pages", response.Code)
	}
	if response := call(subtitles.fileHandler(), "GET", "/api"+subtitleFileRoute+"/owned.srt", signed); response.Code != 401 {
		t.Fatal("revocation not enforced for subtitle contents", response.Code)
	}
}
