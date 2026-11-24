package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"path"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"golang.org/x/text/unicode/norm"
)

type catalogFileSearchResult struct {
	FileID   string      `json:"fileId"`
	FileName string      `json:"fileName"`
	Card     catalogCard `json:"card"`
}

type catalogFilePage struct {
	Items      []catalogFileSearchResult `json:"items"`
	NextCursor string                    `json:"nextCursor,omitempty"`
	HasMore    bool                      `json:"hasMore"`
	PageState  string                    `json:"pageState"`
	Revision   string                    `json:"revision"`
}

func catalogFilenameMatches(query, filename string) bool {
	needle := norm.NFKC.String(strings.ToLower(strings.TrimSpace(query)))
	return needle != "" && strings.Contains(norm.NFKC.String(strings.ToLower(filename)), needle)
}

func fileSearchQuery(libraryID, query string, limit int) string {
	return fmt.Sprintf("files:%q:%q:%d", libraryID, query, limit)
}

func (v *catalogView) searchFiles(ctx context.Context, snapshot catalogSnapshot, libraryID, query string, limit int, token string) (catalogFilePage, error) {
	key := fileSearchQuery(libraryID, query, limit)
	cursor, err := newCatalogCursor(v.d, snapshot, key, token)
	if err != nil {
		return catalogFilePage{}, err
	}
	page := catalogFilePage{Items: make([]catalogFileSearchResult, 0, limit), PageState: "ready", Revision: catalogPublicRevision(v.d, snapshot, key, nil)}
	for {
		if v.budget.remaining <= 0 {
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		candidates, err := v.d.store.MediaLibrary.ListBrowseFileCandidates(ctx, v.d.user.ID, medialibrary.BrowseFileCandidateOptions{
			LibraryID: libraryID, OnlyOwned: mediaSharingDisabled(v.d), Query: query,
			AfterID: cursor.AfterID, AfterFileID: cursor.AfterFileID, Limit: 1,
		})
		if err != nil {
			return page, err
		}
		if len(candidates.Items) == 0 {
			break
		}
		candidate := candidates.Items[0]
		if !v.budget.take() {
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		filename := path.Base(candidate.Locator)
		if !catalogFilenameMatches(query, filename) {
			cursor.AfterID, cursor.AfterFileID = candidate.ID, candidate.FileID
			if !candidates.HasMore {
				break
			}
			continue
		}
		before := cursor
		if cursor.Pending != nil {
			pending := *cursor.Pending
			before.Pending = &pending
		}
		if cursor.Pending != nil && (cursor.Pending.ItemID != candidate.ID || cursor.Pending.FileID != candidate.FileID) {
			return page, errCatalogCursorStale
		}
		if cursor.Pending != nil && (cursor.Pending.ItemRevision != candidate.Revision || cursor.Pending.StructureRevision != candidate.StructureRevision) {
			return page, errCatalogCursorStale
		}
		if cursor.Pending == nil {
			cursor.Pending = &catalogVisibilityCursor{ItemID: candidate.ID, FileID: candidate.FileID, ItemRevision: candidate.Revision, StructureRevision: candidate.StructureRevision}
		}
		library, err := v.d.store.MediaLibrary.GetLibrary(ctx, v.d.user.ID, candidate.LibraryID)
		if err != nil {
			return page, err
		}
		if !mediaLibraryAccessAllowed(v.d, library) {
			return page, medialibrary.ErrNotFound
		}
		access, visible, complete, next, err := v.fileAccess(ctx, library, candidate.ID, candidate.FileID, cursor.Pending.SourceAfter)
		if err != nil {
			return page, err
		}
		if !complete {
			cursor.Pending.SourceAfter = next
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		cursor.Pending = nil
		cursor.AfterID, cursor.AfterFileID = candidate.ID, candidate.FileID
		if visible {
			card, err := v.projectCard(ctx, library, candidate.BrowseCandidate, access, false)
			if err != nil {
				if errors.Is(err, errCatalogWorkPending) {
					page.PageState = "deferred"
					break
				}
				return page, err
			}
			if card.classificationIncomplete {
				page.PageState = "deferred"
				break
			}
			if len(page.Items) == limit {
				cursor = before
				page.HasMore = true
				break
			}
			displayName := strings.Map(func(r rune) rune {
				if unicode.IsControl(r) {
					return ' '
				}
				return r
			}, filename)
			page.Items = append(page.Items, catalogFileSearchResult{FileID: candidate.FileID, FileName: displayName, Card: card})
		}
		if !candidates.HasMore {
			break
		}
	}
	if page.HasMore {
		page.NextCursor, err = encodeCatalogCursor(v.d.settings.Key, cursor)
		if err != nil {
			return page, err
		}
	}
	cards := make([]catalogCard, 0, len(page.Items))
	for _, item := range page.Items {
		cards = append(cards, item.Card)
	}
	if err = verifyCatalogCards(ctx, v.d, cards); err != nil {
		return page, err
	}
	page.Revision = catalogPublicRevision(v.d, snapshot, key, struct {
		Items []catalogFileSearchResult
		State string
	}{page.Items, page.PageState})
	return page, nil
}

func parseFileSearchRequest(r *http.Request) (browseRequest, error) {
	req := browseRequest{LibraryID: r.URL.Query().Get("libraryId"), Query: strings.TrimSpace(r.URL.Query().Get("q")), Sort: "id", Limit: 20}
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 100 {
			return req, medialibrary.ErrInvalid
		}
		req.Limit = value
	}
	if len(req.LibraryID) > 128 || req.Query == "" || utf8.RuneCountInString(req.Query) > 200 || len(req.Query) > 800 || strings.IndexFunc(req.Query, unicode.IsControl) >= 0 {
		return req, medialibrary.ErrInvalid
	}
	for key := range r.URL.Query() {
		if key != "libraryId" && key != "q" && key != "limit" && key != "cursor" {
			return req, medialibrary.ErrInvalid
		}
	}
	return req, nil
}

var mediaCatalogFilesHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	req, err := parseFileSearchRequest(r)
	if err != nil {
		return catalogError(w, err)
	}
	if req.LibraryID != "" {
		library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, req.LibraryID)
		if err != nil || !mediaLibraryAccessAllowed(d, library) || library.ArchivedAt != nil {
			return catalogError(w, medialibrary.ErrNotFound)
		}
	}
	snapshot, err := beginCatalogSnapshot(r.Context(), d, req.LibraryID)
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page, err := view.searchFiles(r.Context(), snapshot, req.LibraryID, req.Query, req.Limit, r.URL.Query().Get("cursor"))
	if err == nil {
		err = view.revalidateSources(r.Context())
	}
	if err == nil {
		err = verifyCatalogSnapshot(r.Context(), d, snapshot)
	}
	if err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, page)
})
