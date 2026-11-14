package fbhttp

import (
	"context"
	"fmt"
	"io/fs"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/gorilla/mux"
)

type visibleMetadataPage[T any] struct {
	Items      []T    `json:"items"`
	NextCursor string `json:"nextCursor,omitempty"`
	Revision   int64  `json:"revision"`
}

type metadataPageFetch[T any] func(context.Context, string, int) ([]T, string, int64, error)

// Filter while paging rather than taking a fixed unfiltered window first.
// Only authorized items influence the client cursor and returned counts.
func visibleMetadataPageFor[T any](ctx context.Context, revision int64, limit int, after string, fetch metadataPageFetch[T], allowed func(T) (bool, error), cursor func(T) string) (visibleMetadataPage[T], error) {
	result := visibleMetadataPage[T]{Items: make([]T, 0, limit), Revision: revision}
	for {
		items, next, current, err := fetch(ctx, after, 100)
		if err != nil {
			return result, err
		}
		if current != revision {
			return result, medialibrary.ErrRevisionConflict
		}
		for _, item := range items {
			visible, err := allowed(item)
			if err != nil {
				return result, err
			}
			if !visible {
				continue
			}
			if len(result.Items) == limit {
				result.NextCursor = cursor(result.Items[len(result.Items)-1])
				if result.NextCursor == "" {
					return result, fs.ErrInvalid
				}
				return result, nil
			}
			result.Items = append(result.Items, item)
		}
		if next == "" {
			return result, nil
		}
		if next == after {
			return result, fs.ErrInvalid
		}
		after = next
	}
}

func visibleCredits(ctx context.Context, d *data, library *medialibrary.Library, itemID string, revision int64, access *mediaItemAccess, match *medialibrary.ProviderMatch, after string, limit int) (visibleMetadataPage[medialibrary.MetadataCredit], error) {
	local := func(after string, limit int) (visibleMetadataPage[medialibrary.MetadataCredit], error) {
		return visibleMetadataPageFor(ctx, revision, limit, after, func(ctx context.Context, after string, n int) ([]medialibrary.MetadataCredit, string, int64, error) {
			page, err := d.store.MediaLibrary.ListItemCredits(ctx, d.user.ID, library.ID, itemID, after, n)
			if err != nil {
				return nil, "", 0, err
			}
			return page.Items, page.NextCursor, page.ItemRevision, nil
		}, func(item medialibrary.MetadataCredit) (bool, error) {
			return metadataInputAllowed(ctx, d, library, itemID, access, item.InputID)
		}, func(item medialibrary.MetadataCredit) string { return item.Cursor })
	}
	const prefix = "tmdb-credits:"
	continuation := strings.HasPrefix(after, prefix)
	page := visibleMetadataPage[medialibrary.MetadataCredit]{Items: []medialibrary.MetadataCredit{}, Revision: revision}
	if !continuation && (match == nil || !match.OnlineFirst || len(match.Document.Item.Credits) == 0) {
		var err error
		page, err = local(after, limit)
		if err != nil || len(page.Items) != 0 || after != "" || match == nil || len(match.Document.Item.Credits) == 0 {
			return page, err
		}
	}
	if match == nil || len(match.Document.Item.Credits) == 0 {
		return page, medialibrary.ErrRevisionConflict
	}
	tokenBase := prefix + strconv.FormatInt(match.PolicyRevision, 10) + ":"
	start := 0
	if after != "" {
		if !strings.HasPrefix(after, tokenBase) {
			return page, medialibrary.ErrRevisionConflict
		}
		var err error
		start, err = strconv.Atoi(strings.TrimPrefix(after, tokenBase))
		if err != nil || start < 1 || start >= len(match.Document.Item.Credits) {
			return page, medialibrary.ErrInvalid
		}
	}
	for position, credit := range match.Document.Item.Credits[start:] {
		name := credit.Fields["name"]
		if len(name) != 1 || name[0] == "" {
			continue
		}
		position += start
		if len(page.Items) == limit {
			page.NextCursor = tokenBase + strconv.Itoa(position)
			break
		}
		roles := credit.Fields["role"]
		roles = append([]string{}, roles[:min(128, len(roles))]...)
		mainRole := ""
		if len(roles) > 0 {
			mainRole = roles[0]
		}
		id := "tmdb_credit_" + strconv.FormatInt(match.Revision, 10) + "_" + strconv.Itoa(position)
		personID := itemID + "_" + id
		if len(credit.ExternalIDs) > 0 && credit.ExternalIDs[0].Provider == "tmdb" {
			personID = "tmdb_person_" + credit.ExternalIDs[0].Value
		}
		page.Items = append(page.Items, medialibrary.MetadataCredit{
			ID: id, PersonID: personID, Name: name[0], Kind: credit.Kind, Role: mainRole, Roles: roles, Position: position,
		})
	}
	return page, nil
}

func visibleRatings(ctx context.Context, d *data, library *medialibrary.Library, itemID string, revision int64, access *mediaItemAccess, after string, limit int) (visibleMetadataPage[medialibrary.MetadataRating], error) {
	return visibleMetadataPageFor(ctx, revision, limit, after, func(ctx context.Context, after string, n int) ([]medialibrary.MetadataRating, string, int64, error) {
		page, err := d.store.MediaLibrary.ListItemRatings(ctx, d.user.ID, library.ID, itemID, after, n)
		if err != nil {
			return nil, "", 0, err
		}
		return page.Items, page.NextCursor, page.ItemRevision, nil
	}, func(item medialibrary.MetadataRating) (bool, error) {
		if item.InputID == "" && item.Origin == "manual" {
			return true, nil
		}
		return metadataInputAllowed(ctx, d, library, itemID, access, item.InputID)
	}, func(item medialibrary.MetadataRating) string { return item.Cursor })
}

func visibleArtwork(ctx context.Context, d *data, library *medialibrary.Library, itemID string, revision int64, access *mediaItemAccess, after string, limit int) (visibleMetadataPage[medialibrary.MetadataArtwork], error) {
	return visibleMetadataPageFor(ctx, revision, limit, after, func(ctx context.Context, after string, n int) ([]medialibrary.MetadataArtwork, string, int64, error) {
		page, err := d.store.MediaLibrary.ListItemArtwork(ctx, d.user.ID, library.ID, itemID, after, n)
		if err != nil {
			return nil, "", 0, err
		}
		return page.Items, page.NextCursor, page.ItemRevision, nil
	}, func(item medialibrary.MetadataArtwork) (bool, error) {
		return metadataInputAllowed(ctx, d, library, itemID, access, item.InputID)
	}, func(item medialibrary.MetadataArtwork) string { return item.Cursor })
}

func visibleExternalIDs(ctx context.Context, d *data, library *medialibrary.Library, itemID string, revision int64, access *mediaItemAccess, after string, limit int) (visibleMetadataPage[medialibrary.MetadataExternalID], error) {
	return visibleMetadataPageFor(ctx, revision, limit, after, func(ctx context.Context, after string, n int) ([]medialibrary.MetadataExternalID, string, int64, error) {
		page, err := d.store.MediaLibrary.ListItemExternalIDs(ctx, d.user.ID, library.ID, itemID, after, n)
		if err != nil {
			return nil, "", 0, err
		}
		return page.Items, page.NextCursor, page.ItemRevision, nil
	}, func(item medialibrary.MetadataExternalID) (bool, error) {
		return metadataInputAllowed(ctx, d, library, itemID, access, item.InputID)
	}, func(item medialibrary.MetadataExternalID) string { return item.Cursor })
}

func visibleIssues(ctx context.Context, d *data, library *medialibrary.Library, itemID string, revision int64, access *mediaItemAccess, after string, limit int) (visibleMetadataPage[medialibrary.MetadataIssueSource], error) {
	return visibleMetadataPageFor(ctx, revision, limit, after, func(ctx context.Context, after string, n int) ([]medialibrary.MetadataIssueSource, string, int64, error) {
		page, err := d.store.MediaLibrary.ListItemMetadataIssues(ctx, d.user.ID, library.ID, itemID, after, n)
		if err != nil {
			return nil, "", 0, err
		}
		return page.Items, page.NextCursor, page.ItemRevision, nil
	}, func(item medialibrary.MetadataIssueSource) (bool, error) {
		return metadataInputAllowed(ctx, d, library, itemID, access, item.InputID)
	}, func(item medialibrary.MetadataIssueSource) string { return item.Cursor })
}

func loadVisibleItemDetail(ctx context.Context, d *data, library *medialibrary.Library, detail *medialibrary.ItemDetail, access *mediaItemAccess) (mediaItemDetail, error) {
	result, err := itemDetailFields(ctx, d, library, detail, access)
	if err != nil {
		return result, err
	}
	id, revision := detail.Item.ID, detail.Item.Revision
	match := result.providerMatch
	credits, err := visibleCredits(ctx, d, library, id, revision, access, match, "", 20)
	if err != nil {
		return result, err
	}
	ratingLimit := 20
	if match != nil && len(match.Document.Item.Ratings) != 0 {
		ratingLimit--
	}
	ratings, err := visibleRatings(ctx, d, library, id, revision, access, "", ratingLimit)
	if err != nil {
		return result, err
	}
	artwork, err := visibleArtwork(ctx, d, library, id, revision, access, "", 20)
	if err != nil {
		return result, err
	}
	externalIDs, err := visibleExternalIDs(ctx, d, library, id, revision, access, "", 20)
	if err != nil {
		return result, err
	}
	issues, err := visibleIssues(ctx, d, library, id, revision, access, "", 20)
	if err != nil {
		return result, err
	}
	result.Credits, result.CreditsNextCursor = credits.Items, credits.NextCursor
	result.Ratings, result.RatingsNextCursor = ratings.Items, ratings.NextCursor
	result.Artwork, result.ArtworkNextCursor = artwork.Items, artwork.NextCursor
	result.ExternalIDs, result.ExternalIDsNextCursor = externalIDs.Items, externalIDs.NextCursor
	if match != nil {
		externalID := strconv.FormatInt(match.ExternalID, 10)
		result.OnlineMatch = &mediaOnlineMatch{Provider: "tmdb", Kind: match.Kind, ExternalID: externalID, FetchedAt: match.FetchedAt}
		for _, rating := range match.Document.Item.Ratings {
			if rating.Value == nil || rating.Count != nil && *rating.Count == 0 {
				continue
			}
			scope := string(match.Document.Item.Kind)
			index := slices.IndexFunc(result.Ratings, func(row medialibrary.MetadataRating) bool {
				return row.Rating.Provider == rating.Provider && row.Scope == scope
			})
			row := medialibrary.MetadataRating{ID: "tmdb_" + externalID, Scope: scope, Origin: "online", ImportedAt: match.FetchedAt, Rating: rating}
			if index < 0 {
				result.Ratings = append(result.Ratings, row)
			} else if match.OnlineFirst {
				result.Ratings[index] = row
			}
		}
	}
	result.IssuesNextCursor = issues.NextCursor
	seen := make(map[string]bool)
	for _, issue := range issues.Items {
		result.Candidates = append(result.Candidates, mediaMetadataCandidate{Code: issue.Code, SourceKinds: append([]string{}, issue.SourceKinds...)})
		if !seen[issue.Code] {
			result.Issues = append(result.Issues, issue.Code)
			seen[issue.Code] = true
		}
	}
	result.Item.MetadataState = access.metadataState
	return result, nil
}

func mediaItemCollectionHandler(kind string) handleFunc {
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		library, err := itemLibrary(r, d)
		if err != nil {
			return mediaScanError(err)
		}
		itemID := mux.Vars(r)["itemId"]
		snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
		if err != nil {
			return catalogError(w, err)
		}
		detail, access, err := visibleItemDetail(r, d, library, itemID)
		if err != nil {
			return mediaScanError(err)
		}
		limit := 20
		if raw := r.URL.Query().Get("limit"); raw != "" {
			limit, err = strconv.Atoi(raw)
			if err != nil || limit < 1 || limit > 100 {
				return http.StatusBadRequest, fmt.Errorf("资料分页参数无效")
			}
		}
		after := r.URL.Query().Get("cursor")
		if len(after) > 256 {
			return http.StatusBadRequest, fmt.Errorf("资料分页参数无效")
		}
		if after != "" && r.URL.Query().Get("revision") == "" {
			return http.StatusBadRequest, fmt.Errorf("继续分页须提供资料修订")
		}
		if raw := r.URL.Query().Get("revision"); raw != "" {
			expected, err := strconv.ParseInt(raw, 10, 64)
			if err != nil || expected < 1 {
				return http.StatusBadRequest, fmt.Errorf("资料修订参数无效")
			}
			if expected != detail.Item.Revision {
				return mediaScanError(medialibrary.ErrRevisionConflict)
			}
		}
		var response any
		switch kind {
		case "credits":
			var match *medialibrary.ProviderMatch
			match, err = d.store.MediaLibrary.VisibleProviderMatch(r.Context(), d.user.ID, library.ID, itemID, detail.Item.Revision)
			if err == nil {
				response, err = visibleCredits(r.Context(), d, library, itemID, detail.Item.Revision, &access, match, after, limit)
			}
		case "ratings":
			response, err = visibleRatings(r.Context(), d, library, itemID, detail.Item.Revision, &access, after, limit)
		case "artwork":
			response, err = visibleArtwork(r.Context(), d, library, itemID, detail.Item.Revision, &access, after, limit)
		case "external-ids":
			response, err = visibleExternalIDs(r.Context(), d, library, itemID, detail.Item.Revision, &access, after, limit)
		case "issues":
			var page visibleMetadataPage[medialibrary.MetadataIssueSource]
			page, err = visibleIssues(r.Context(), d, library, itemID, detail.Item.Revision, &access, after, limit)
			if err == nil {
				items := make([]mediaMetadataCandidate, 0, len(page.Items))
				for _, issue := range page.Items {
					items = append(items, mediaMetadataCandidate{Code: issue.Code, SourceKinds: append([]string{}, issue.SourceKinds...)})
				}
				response = visibleMetadataPage[mediaMetadataCandidate]{Items: items, NextCursor: page.NextCursor, Revision: page.Revision}
			}
		default:
			return http.StatusNotFound, fmt.Errorf("资料类型不存在")
		}
		if err != nil {
			return mediaScanError(err)
		}
		if err := verifyItemRevision(r.Context(), d, library, itemID, detail.Item.Revision); err != nil {
			return mediaScanError(err)
		}
		if err := verifyCatalogSnapshot(r.Context(), d, snapshot); err != nil {
			return catalogError(w, err)
		}
		return renderJSON(w, r, response)
	})
}
