package fbhttp

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
	"github.com/gorilla/mux"
)

// The check exercises both the credential and public image configuration over
// the same service transport used for matches. It never changes a connection.
func mediaProviderCheckHandler(slots chan struct{}, starts <-chan time.Time) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		admin, err := providerAdmin(d)
		if err != nil {
			return providerError(w, err)
		}
		id := mux.Vars(r)["connectionId"]
		if _, err := d.store.MediaLibrary.GetManagedProviderConnection(r.Context(), d.user.ID, admin, id); err != nil {
			return providerError(w, err)
		}
		if d.store.MediaProviderVault == nil || d.store.TMDBHTTPClient == nil {
			return writeCatalogError(w, http.StatusServiceUnavailable, "credential_store_unavailable", "服务端尚未启用 TMDB 凭据存储", true)
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			w.Header().Set("Retry-After", "1")
			return writeCatalogError(w, http.StatusTooManyRequests, "provider_busy", "TMDB 请求较多，请稍后再试", true)
		}
		select {
		case <-r.Context().Done():
			return providerError(w, r.Context().Err())
		case <-starts:
		}
		admin, err = providerAdmin(d)
		if err != nil {
			return providerError(w, err)
		}
		revision, sealed, err := d.store.MediaLibrary.ManagedProviderCredentialForCheck(r.Context(), d.user.ID, admin, id)
		if err != nil {
			return providerError(w, err)
		}
		scope, err := medialibrary.ProviderCredentialContext(id)
		if err != nil {
			return providerError(w, err)
		}
		client := mediaprovider.NewTMDBClient(d.store.TMDBHTTPClient, func(context.Context) (string, error) {
			return d.store.MediaProviderVault.Open(scope, sealed)
		})
		_, result := client.Configuration(r.Context())
		admin, err = providerAdmin(d)
		if err != nil {
			return providerError(w, err)
		}
		current, err := d.store.MediaLibrary.GetManagedProviderConnection(r.Context(), d.user.ID, admin, id)
		if err != nil {
			return providerError(w, err)
		}
		if current.Revision != revision {
			return providerError(w, medialibrary.ErrRevisionConflict)
		}
		if result != nil {
			var failure *mediaprovider.Error
			if errors.As(result, &failure) && failure.Code == mediaprovider.Unauthorized {
				return writeCatalogError(w, http.StatusUnprocessableEntity, "provider_credentials_rejected", "TMDB 拒绝了当前密钥", false)
			}
			return matchProviderError(w, result)
		}
		return renderJSON(w, r, struct {
			ConnectionID string `json:"connectionId"`
			Revision     int64  `json:"revision"`
			Reachable    bool   `json:"reachable"`
		}{id, revision, true})
	})
}
