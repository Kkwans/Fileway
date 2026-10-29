package fbhttp

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/golang-jwt/jwt/v5/request"

	fbAuth "github.com/Kkwans/nas-file-browser/backend/auth"
	fberrors "github.com/Kkwans/nas-file-browser/backend/errors"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

const (
	DefaultTokenExpirationTime = time.Hour * 2
	MinimumTokenExpirationTime = 10 * time.Minute
	MaximumTokenExpirationTime = 30 * 24 * time.Hour
)

type userInfo struct {
	ID                    uint                     `json:"id"`
	Locale                string                   `json:"locale"`
	ViewMode              users.ViewMode           `json:"viewMode"`
	SingleClick           bool                     `json:"singleClick"`
	RedirectAfterCopyMove bool                     `json:"redirectAfterCopyMove"`
	Perm                  users.Permissions        `json:"perm"`
	Commands              []string                 `json:"commands"`
	Sorting               files.Sorting            `json:"sorting"`
	LockPassword          bool                     `json:"lockPassword"`
	HideDotfiles          bool                     `json:"hideDotfiles"`
	DateFormat            bool                     `json:"dateFormat"`
	Username              string                   `json:"username"`
	AceEditorTheme        string                   `json:"aceEditorTheme"`
	ListingPreferences    users.ListingPreferences `json:"listingPreferences"`
	PlayerPreferences     users.PlayerPreferences  `json:"playerPreferences"`
}

type authToken struct {
	User          userInfo     `json:"user"`
	Instance      instanceInfo `json:"instance"`
	DeviceSession string       `json:"sid,omitempty"`
	Scope         string       `json:"scope,omitempty"`
	jwt.RegisteredClaims
}

// instanceInfo contains non-secret runtime identity that is useful after a
// user has authenticated. It intentionally lives in the authenticated token;
// the public bootstrap page must not disclose the host name to logged-out
// visitors.
type instanceInfo struct {
	Hostname string `json:"hostname,omitempty"`
}

type extractor []string

func (e extractor) ExtractToken(r *http.Request) (string, error) {
	token, _ := request.HeaderExtractor{"X-Auth"}.ExtractToken(r)

	// Checks if the token isn't empty and if it contains two dots.
	// The former prevents incompatibility with URLs that previously
	// used basic auth.
	if token != "" && strings.Count(token, ".") == 2 {
		return token, nil
	}

	if r.Method == http.MethodGet {
		cookie, _ := r.Cookie("auth")
		if cookie != nil && strings.Count(cookie.Value, ".") == 2 {
			return cookie.Value, nil
		}
	}

	return "", request.ErrNoTokenInRequest
}

func renewableErr(err error, d *data) bool {
	if d.settings.AuthMethod != fbAuth.MethodProxyAuth || err == nil {
		return false
	}

	if d.settings.LogoutPage == settings.DefaultLogoutPage {
		return false
	}

	if !errors.Is(err, jwt.ErrTokenExpired) {
		return false
	}

	return true
}

func withUser(fn handleFunc) handleFunc {
	return func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		keyFunc := func(token *jwt.Token) (interface{}, error) {
			if token.Header["kid"] == "fileway.device.v1" {
				if d.store.Devices == nil {
					return nil, fmt.Errorf("设备授权不可用")
				}
				return d.store.Devices.SigningKey()
			}
			if _, present := token.Header["kid"]; present {
				return nil, fmt.Errorf("未知签名密钥")
			}
			return d.settings.Key, nil
		}

		var tk authToken
		p := jwt.NewParser(jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}), jwt.WithExpirationRequired())
		token, err := request.ParseFromRequest(r, &extractor{}, keyFunc, request.WithClaims(&tk), request.WithParser(p))
		if (err != nil || !token.Valid) && !(token != nil && token.Header["kid"] == nil && renewableErr(err, d)) {
			return http.StatusUnauthorized, fmt.Errorf("未授权，请重新登录")
		}
		if tk.DeviceSession != "" || token.Header["kid"] == "fileway.device.v1" {
			if token.Header["kid"] != "fileway.device.v1" || tk.DeviceSession == "" || tk.Scope != "media" || d.store.Devices == nil {
				return http.StatusUnauthorized, fmt.Errorf("设备凭据无效")
			}
			if _, err := d.store.Devices.Validate(tk.DeviceSession, tk.User.ID); err != nil {
				return http.StatusUnauthorized, fmt.Errorf("设备登录已撤销或过期")
			}
			path := d.apiPath
			if path == "" {
				path = r.URL.Path
			}
			if !deviceMediaRoute(r.Method, path, tk.User.ID) {
				return http.StatusForbidden, fmt.Errorf("TV设备仅允许媒体浏览与播放")
			}
			d.deviceSession = tk.DeviceSession
		}

		// JWT timestamps have second precision. Every authenticated business call
		// in a later second slides the full configured window, even far from expiry.
		active := tk.IssuedAt == nil || tk.IssuedAt.Unix() < time.Now().Unix()
		updated := tk.IssuedAt != nil && tk.IssuedAt.Unix() < d.store.Users.LastUpdate(tk.User.ID)

		if active || updated {
			w.Header().Add("X-Renew-Token", "true")
		}

		d.user, err = d.store.Users.Get(d.server.Root, tk.User.ID)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		if d.deviceSession != "" {
			copy := *d.user
			copy.Perm.Admin = false
			copy.Perm.Execute = false
			copy.Perm.Create = false
			copy.Perm.Delete = false
			copy.Perm.Modify = false
			copy.Perm.Rename = false
			copy.Commands = []string{}
			d.user = &copy
		}
		return fn(w, r, d)
	}
}

func withAdmin(fn handleFunc) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Admin {
			return http.StatusForbidden, fmt.Errorf("没有管理员权限")
		}

		return fn(w, r, d)
	})
}

func parseTokenExpirationTime(value string) (time.Duration, error) {
	duration, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("会话超时时间格式无效")
	}
	if duration < MinimumTokenExpirationTime || duration > MaximumTokenExpirationTime {
		return 0, fmt.Errorf("会话超时时间必须在 10 分钟到 30 天之间")
	}
	return duration, nil
}

func tokenExpirationTime(d *data) time.Duration {
	value := d.settings.TokenExpirationTime
	if value == "" {
		value = d.server.TokenExpirationTime
	}
	if value == "" {
		return DefaultTokenExpirationTime
	}
	duration, err := parseTokenExpirationTime(value)
	if err != nil {
		log.Printf("[WARN] %v，已使用默认值 %s", err, DefaultTokenExpirationTime)
		return DefaultTokenExpirationTime
	}
	return duration
}

func loginHandler() handleFunc {
	return func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		auther, err := d.store.Auth.Get(d.settings.AuthMethod)
		if err != nil {
			return http.StatusInternalServerError, err
		}

		user, err := auther.Auth(r, d.store.Users, d.settings, d.server)
		switch {
		case errors.Is(err, os.ErrPermission):
			return http.StatusForbidden, fmt.Errorf("用户名或密码错误")
		case err != nil:
			return http.StatusInternalServerError, err
		}

		return printToken(w, r, d, user, tokenExpirationTime(d))
	}
}

type signupBody struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

var signupHandler = func(_ http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if !d.settings.Signup {
		return http.StatusMethodNotAllowed, fmt.Errorf("注册功能已关闭")
	}

	if r.Body == nil {
		return http.StatusBadRequest, fmt.Errorf("请求体为空")
	}

	info := &signupBody{}
	err := json.NewDecoder(r.Body).Decode(info)
	if err != nil {
		return http.StatusBadRequest, err
	}

	if info.Password == "" || info.Username == "" {
		return http.StatusBadRequest, fmt.Errorf("用户名和密码不能为空")
	}

	user := &users.User{
		Username: info.Username,
	}

	d.settings.Defaults.Apply(user)

	// Users signed up via the signup handler should never become admins, even
	// if that is the default permission.
	user.Perm.Admin = false

	// Self-registered users should not inherit execution capabilities from
	// default settings, regardless of what the administrator has configured
	// as the default. Execution rights must be explicitly granted by an admin.
	user.Perm.Execute = false
	user.Commands = []string{}

	pwd, err := users.ValidateAndHashPwd(info.Password, d.settings.MinimumPasswordLength)
	if err != nil {
		return http.StatusBadRequest, err
	}

	user.Password = pwd
	if d.settings.CreateUserDir {
		user.Scope = ""
	}

	userHome, err := d.settings.MakeUserDir(user.Username, user.Scope, d.server.Root)
	if err != nil {
		log.Printf("create user: failed to mkdir user home dir: [%s]", userHome)
		return http.StatusInternalServerError, err
	}
	user.Scope = userHome
	log.Printf("new user: %s, home dir: [%s].", user.Username, userHome)

	err = d.store.Users.Save(user)
	if errors.Is(err, fberrors.ErrExist) {
		return http.StatusConflict, err
	} else if err != nil {
		return http.StatusInternalServerError, err
	}

	return http.StatusOK, nil
}

func renewHandler() handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("X-Renew-Token", "false")
		return printToken(w, r, d, d.user, tokenExpirationTime(d))
	})
}

func printToken(w http.ResponseWriter, _ *http.Request, d *data, user *users.User, tokenExpirationTime time.Duration) (int, error) {
	claims := &authToken{
		User: userInfo{
			ID:                    user.ID,
			Locale:                user.Locale,
			ViewMode:              user.ViewMode,
			SingleClick:           user.SingleClick,
			RedirectAfterCopyMove: user.RedirectAfterCopyMove,
			Perm:                  user.Perm,
			LockPassword:          user.LockPassword,
			Commands:              user.Commands,
			Sorting:               user.Sorting,
			HideDotfiles:          user.HideDotfiles,
			DateFormat:            user.DateFormat,
			Username:              user.Username,
			AceEditorTheme:        user.AceEditorTheme,
			ListingPreferences:    user.ListingPreferences,
			PlayerPreferences:     user.PlayerPreferences,
		},
		Instance:      instanceInfo{Hostname: currentHostname()},
		DeviceSession: d.deviceSession,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(tokenExpirationTime)),
			Issuer:    "File Browser",
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	key := d.settings.Key
	if d.deviceSession != "" {
		claims.Scope = "media"
		claims.Issuer = "Fileway device"
		claims.User.Perm.Admin = false
		claims.User.Perm.Execute = false
		claims.User.Perm.Create = false
		claims.User.Perm.Delete = false
		claims.User.Perm.Modify = false
		claims.User.Perm.Rename = false
		claims.User.Commands = []string{}
		var err error
		key, err = d.store.Devices.SigningKey()
		if err != nil {
			return http.StatusInternalServerError, err
		}
		token.Header["kid"] = "fileway.device.v1"
	}
	signed, err := token.SignedString(key)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	w.Header().Set("Content-Type", "text/plain")
	if _, err := w.Write([]byte(signed)); err != nil {
		return http.StatusInternalServerError, err
	}
	return 0, nil
}

func currentHostname() string {
	if displayName := strings.TrimSpace(os.Getenv("FB_INSTANCE_HOSTNAME")); displayName != "" {
		return displayName
	}
	// Docker's default hostname identifies the container, not the NAS. Hide it
	// unless the deployment explicitly supplies the host's display name.
	if _, err := os.Stat("/.dockerenv"); err == nil {
		return ""
	}
	hostname, err := os.Hostname()
	if err != nil {
		return ""
	}
	return hostname
}
