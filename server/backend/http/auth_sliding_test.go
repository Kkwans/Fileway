package fbhttp

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	fbAuth "github.com/Kkwans/nas-file-browser/backend/auth"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/golang-jwt/jwt/v5"
)

func TestAuthenticatedActivitySlidesFullInstanceDuration(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := firstTrashHTTPUser(h)
	config, err := h.storage.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	config.AuthMethod = fbAuth.MethodJSONAuth
	config.TokenExpirationTime = "720h"
	if err = h.storage.Settings.Save(config); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	token := func(issued, expires time.Time) string {
		claims := authToken{User: userInfo{ID: owner.ID}, RegisteredClaims: jwt.RegisteredClaims{IssuedAt: jwt.NewNumericDate(issued), ExpiresAt: jwt.NewNumericDate(expires)}}
		text, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(config.Key)
		if err != nil {
			t.Fatal(err)
		}
		return text
	}
	old := token(now.Add(-16*24*time.Hour), now.Add(14*24*time.Hour))
	call := func(fn handleFunc, auth string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "/api/owned", nil)
		req.Header.Set("X-Auth", auth)
		res := httptest.NewRecorder()
		handle(fn, "", h.storage, h.server).ServeHTTP(res, req)
		return res
	}
	// Day 16 has fourteen days remaining; activity must already request renewal.
	result := call(withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) { return http.StatusNoContent, nil }), old)
	if result.Code != http.StatusNoContent || result.Header().Get("X-Renew-Token") != "true" {
		t.Fatal("activity did not request renewal", result.Code)
	}
	refreshed := call(renewHandler(), old)
	if refreshed.Code != 200 || refreshed.Header().Get("X-Renew-Token") != "false" {
		t.Fatal("renewal recursed", refreshed.Code)
	}
	var claims authToken
	parsed, err := jwt.ParseWithClaims(refreshed.Body.String(), &claims, func(_ *jwt.Token) (any, error) { return config.Key, nil })
	if err != nil || !parsed.Valid || claims.ExpiresAt.Unix()-claims.IssuedAt.Unix() != int64((30*24*time.Hour)/time.Second) {
		t.Fatal("full configured duration not restored", err)
	}
	if claims.IssuedAt.Unix() < now.Unix() {
		t.Fatal("renewal reused original issue time")
	}
	for _, auth := range []string{token(now.Add(-31*24*time.Hour), now.Add(-time.Second)), "invalid"} {
		rejected := call(renewHandler(), auth)
		if rejected.Code != http.StatusUnauthorized {
			t.Fatal("expired ordinary JWT revived", rejected.Code)
		}
	}
	stored, _ := h.storage.Settings.Get()
	if stored.TokenExpirationTime != "720h" {
		t.Fatal("instance configuration changed")
	}
	// The expiration exception remains exclusive to custom proxy logout.
	errExpired := jwt.ErrTokenExpired
	proxy := &data{settings: &settings.Settings{AuthMethod: fbAuth.MethodProxyAuth, LogoutPage: "/external-logout"}}
	if !renewableErr(errExpired, proxy) {
		t.Fatal("proxy compatibility was removed")
	}
	proxy.settings.LogoutPage = settings.DefaultLogoutPage
	if renewableErr(errExpired, proxy) {
		t.Fatal("ordinary proxy logout unexpectedly revives JWT")
	}
}
