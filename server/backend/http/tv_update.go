package fbhttp

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/tvupdate"
	"github.com/gorilla/mux"
)

const (
	tvUpdateCheckPath     = "/app-updates/tv"
	tvUpdateDownloadsPath = tvUpdateCheckPath + "/downloads"
	tvUpdateHandle        = "{id:[A-Za-z0-9_-]{32}}"
)

func allowDeviceTVUpdate(method, path string) bool {
	if path == tvUpdateCheckPath {
		return method == http.MethodGet
	}
	if path == tvUpdateDownloadsPath {
		return method == http.MethodPost
	}
	remainder, ok := strings.CutPrefix(path, tvUpdateDownloadsPath+"/")
	if !ok {
		return false
	}
	id, child, nested := strings.Cut(remainder, "/")
	if len(id) != 32 || strings.IndexFunc(id, func(r rune) bool {
		return !(r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-' || r == '_')
	}) != -1 {
		return false
	}
	if nested {
		return child == "content" && (method == http.MethodGet || method == http.MethodHead)
	}
	return method == http.MethodGet
}

func tvUpdateOwner(d *data) tvupdate.Owner {
	return tvupdate.Owner{Account: d.user.ID, DeviceSession: d.deviceSession}
}

func tvUpdateFailure(w http.ResponseWriter, failure *tvupdate.Failure) (int, error) {
	if failure.RetryAfter > 0 {
		w.Header().Set("Retry-After", strconv.FormatInt(int64(failure.RetryAfter.Round(time.Second)/time.Second), 10))
	}
	return renderJSONStatus(w, failure, failure.Status)
}

func tvUpdateNoStore(fn handleFunc) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("Cache-Control", "private, no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		return fn(w, r, d)
	})
}

func tvUpdateCheckHandler(service *tvupdate.Service) handleFunc {
	return tvUpdateNoStore(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if len(r.URL.RawQuery) > 512 {
			return tvUpdateFailure(w, tvupdate.InvalidRequest)
		}
		query, err := url.ParseQuery(r.URL.RawQuery)
		if err != nil || len(query) != 3 || len(query["versionCode"]) != 1 || len(query["api"]) != 1 {
			return tvUpdateFailure(w, tvupdate.InvalidRequest)
		}
		version, versionErr := strconv.ParseInt(query.Get("versionCode"), 10, 64)
		api, apiErr := strconv.Atoi(query.Get("api"))
		if versionErr != nil || apiErr != nil {
			return tvUpdateFailure(w, tvupdate.InvalidRequest)
		}
		directory, failure := service.Check(r.Context(), tvUpdateOwner(d), tvupdate.Device{VersionCode: version, API: api, ABIs: query["abi"]})
		if failure != nil {
			return tvUpdateFailure(w, failure)
		}
		return renderJSON(w, r, directory)
	})
}

func tvUpdateStartHandler(service *tvupdate.Service) handleFunc {
	return tvUpdateNoStore(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if r.URL.RawQuery != "" {
			return tvUpdateFailure(w, tvupdate.InvalidRequest)
		}
		var request struct {
			OfferID string `json:"offerId"`
		}
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil || decoder.Decode(new(any)) != io.EOF {
			return tvUpdateFailure(w, tvupdate.InvalidRequest)
		}
		status, failure := service.Start(r.Context(), tvUpdateOwner(d), request.OfferID)
		if failure != nil {
			return tvUpdateFailure(w, failure)
		}
		if status.State == "preparing" {
			return renderJSONStatus(w, status, http.StatusAccepted)
		}
		return renderJSON(w, r, status)
	})
}

func tvUpdateStatusHandler(service *tvupdate.Service) handleFunc {
	return tvUpdateNoStore(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if r.URL.RawQuery != "" {
			return tvUpdateFailure(w, tvupdate.InvalidRequest)
		}
		status, failure := service.Status(r.Context(), tvUpdateOwner(d), mux.Vars(r)["id"])
		if failure != nil {
			return tvUpdateFailure(w, failure)
		}
		return renderJSON(w, r, status)
	})
}

func tvUpdateContentHandler(service *tvupdate.Service) handleFunc {
	return tvUpdateNoStore(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if r.URL.RawQuery != "" {
			return tvUpdateFailure(w, tvupdate.InvalidRequest)
		}
		if failure := service.ServeContent(w, r, tvUpdateOwner(d), mux.Vars(r)["id"]); failure != nil {
			return tvUpdateFailure(w, failure)
		}
		return 0, nil
	})
}
