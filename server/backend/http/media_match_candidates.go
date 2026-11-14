package fbhttp

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

type matchCandidateSearch struct {
	ConnectionID      string `json:"connectionId"`
	Title             string `json:"title"`
	Kind              string `json:"kind"`
	Year              int    `json:"year"`
	Page              int    `json:"page"`
	ItemRevision      int64  `json:"itemRevision"`
	StructureRevision int64  `json:"structureRevision"`
}

type matchCandidateRow struct {
	Provider      string             `json:"provider"`
	Kind          mediaprovider.Kind `json:"kind"`
	ExternalID    string             `json:"externalId"`
	Title         string             `json:"title"`
	OriginalTitle string             `json:"originalTitle,omitempty"`
	Year          int                `json:"year,omitempty"`
	Evidence      []string           `json:"evidence"`
}

// Browsing candidates is a deliberate provider lookup, not a scan job or a
// metadata mutation. The presentation may offer film/series choices for an
// unmatched file, but never guesses the parent of a season or episode.
func mediaMatchCandidatesHandler(slots chan struct{}, starts <-chan time.Time) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var input matchCandidateSearch
		if decodeMediaScanBody(w, r, &input) != nil || input.ItemRevision < 1 ||
			input.StructureRevision < 1 || input.Page < 1 || input.Page > 500 ||
			input.Year < 0 || input.Year > 9999 || (input.Year > 0 && input.Year < 1000) ||
			len(input.Title) > 512 || !utf8.ValidString(input.Title) || strings.ContainsAny(input.Title, "/\\") ||
			strings.IndexFunc(input.Title, unicode.IsControl) >= 0 {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		kind := mediaprovider.Kind(input.Kind)
		if kind != mediaprovider.Movie && kind != mediaprovider.Series {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		policy, err := d.store.MediaLibrary.GetLibraryMetadataPolicy(r.Context(), d.user.ID, library.ID)
		if err != nil {
			return catalogError(w, err)
		}
		if !policy.OnlineEnabled() {
			return writeCatalogError(w, http.StatusUnprocessableEntity, "library_offline_policy", "此媒体库尚未启用在线补全", false)
		}
		if d.store.MediaProviderVault == nil || d.store.TMDBHTTPClient == nil {
			return writeCatalogError(w, http.StatusServiceUnavailable, "provider_configuration_required", "TMDB 连接尚未配置", true)
		}
		if input.ConnectionID == "" {
			input.ConnectionID = policy.ConnectionID
		}
		fence, sealed, err := d.store.MediaLibrary.GetProviderCredentialForUse(r.Context(), d.user.ID, library.ID, input.ConnectionID)
		if err != nil {
			return catalogError(w, err)
		}
		if fence.PolicyRevision != policy.Revision {
			return catalogError(w, medialibrary.ErrRevisionConflict)
		}
		snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
		if err != nil {
			return catalogError(w, err)
		}
		item, view, err := mediaIdentification(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		if item.ItemRevision != input.ItemRevision || item.StructureRevision != input.StructureRevision {
			return catalogError(w, medialibrary.ErrRevisionConflict)
		}
		switch item.StructureKind {
		case "video":
		case "movie", "series":
			if item.StructureKind == "movie" && kind != mediaprovider.Movie ||
				item.StructureKind == "series" && kind != mediaprovider.Series {
				return catalogError(w, medialibrary.ErrInvalid)
			}
		default:
			return catalogError(w, medialibrary.ErrInvalid)
		}
		title := strings.TrimSpace(input.Title)
		if title == "" && item.File != nil {
			title = strings.TrimSpace(item.File.ParsedTitle)
		}
		if title == "" {
			title = strings.TrimSpace(item.Title)
		}
		if title == "" || len(title) > 512 || strings.ContainsAny(title, "/\\") || strings.IndexFunc(title, unicode.IsControl) >= 0 {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		year := input.Year
		if year == 0 && item.File != nil && item.File.Year != nil {
			year = *item.File.Year
		}
		hint := mediaprovider.MatchHint{Kind: kind, Title: title, Year: year}
		for _, id := range item.ExternalIDs {
			if number, err := strconv.ParseInt(id.Value, 10, 64); err == nil &&
				id.Provider == "tmdb" && id.EntityKind == string(kind) && number > 0 {
				hint.ExplicitIDs = append(hint.ExplicitIDs, mediaprovider.EntityRef{Provider: "tmdb", Kind: kind, ID: number})
			}
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			w.Header().Set("Retry-After", "1")
			return writeCatalogError(w, http.StatusTooManyRequests, "provider_busy", "TMDB 查询正在处理，请稍后再试", true)
		}
		select {
		case <-r.Context().Done():
			return catalogError(w, r.Context().Err())
		case <-starts:
		}
		check := func(ctx context.Context) error {
			current, _, err := d.store.MediaLibrary.GetProviderCredentialForUse(ctx, d.user.ID, library.ID, input.ConnectionID)
			if err != nil {
				return err
			}
			if *current != *fence {
				return medialibrary.ErrRevisionConflict
			}
			if err := verifyCatalogSnapshot(ctx, d, snapshot); err != nil {
				return err
			}
			if err := view.revalidateSources(ctx); err != nil {
				return err
			}
			revision, structure, superseded, err := d.store.MediaLibrary.GetBrowseItemFence(ctx, d.user.ID, library.ID, item.Ref.ItemID)
			if err != nil {
				return err
			}
			if superseded || revision != item.ItemRevision || structure != item.StructureRevision {
				return medialibrary.ErrRevisionConflict
			}
			return nil
		}
		if err := check(r.Context()); err != nil {
			return catalogError(w, err)
		}
		vault := d.store.MediaProviderVault
		credentialContext, err := medialibrary.ProviderCredentialContext(input.ConnectionID)
		if err != nil {
			return catalogError(w, err)
		}
		client := mediaprovider.NewTMDBClient(d.store.TMDBHTTPClient, func(context.Context) (string, error) {
			return vault.Open(credentialContext, sealed)
		})
		page, err := client.Search(r.Context(), mediaprovider.SearchQuery{
			Kind: kind, Title: title, Year: input.Year, Page: input.Page, Language: policy.Language, Region: policy.Region,
		})
		if err := check(r.Context()); err != nil {
			return catalogError(w, err)
		}
		if err != nil {
			return matchProviderError(w, err)
		}
		ranked := mediaprovider.RankCandidates(hint, page.Items)
		items := make([]matchCandidateRow, 0, len(ranked))
		for _, candidate := range ranked {
			items = append(items, matchCandidateRow{candidate.Entity.Provider, candidate.Entity.Kind,
				strconv.FormatInt(candidate.Entity.ID, 10), candidate.Title, candidate.OriginalTitle, candidate.Year, candidate.Evidence})
		}
		return renderJSON(w, r, struct {
			Ref               catalogRef          `json:"ref"`
			ItemRevision      int64               `json:"itemRevision"`
			StructureRevision int64               `json:"structureRevision"`
			Items             []matchCandidateRow `json:"items"`
			Page              int                 `json:"page"`
			HasMore           bool                `json:"hasMore"`
		}{item.Ref, item.ItemRevision, item.StructureRevision, items, page.Page, page.Page < min(page.TotalPages, 500)})
	})
}

func matchProviderError(w http.ResponseWriter, err error) (int, error) {
	var failure *mediaprovider.Error
	if errors.As(err, &failure) {
		switch failure.Code {
		case mediaprovider.RateLimited:
			delay := max(1, int64(time.Until(failure.RetryAfter).Seconds())+1)
			w.Header().Set("Retry-After", strconv.FormatInt(delay, 10))
			return writeCatalogError(w, http.StatusTooManyRequests, "provider_rate_limited", "TMDB 已限流，请稍后再试", true)
		case mediaprovider.NeedsConfig, mediaprovider.Unauthorized:
			return writeCatalogError(w, http.StatusServiceUnavailable, "provider_configuration_required", "请检查所选 TMDB 连接的凭据", true)
		}
	}
	return writeCatalogError(w, http.StatusBadGateway, "provider_unavailable", "暂时无法从 TMDB 读取候选", true)
}
