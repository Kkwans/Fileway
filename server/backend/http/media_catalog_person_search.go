package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/gorilla/mux"
)

type catalogPersonRef struct {
	LibraryID string `json:"libraryId"`
	PersonID  string `json:"personId"`
}

type catalogPerson struct {
	Ref      catalogPersonRef `json:"ref"`
	Name     string           `json:"name"`
	Roles    []string         `json:"roles"`
	Portrait *catalogArtwork  `json:"portrait,omitempty"`
	Preview  []catalogCard    `json:"preview"`
}

type catalogPersonPage struct {
	Items      []catalogPerson `json:"items"`
	NextCursor string          `json:"nextCursor,omitempty"`
	HasMore    bool            `json:"hasMore"`
	PageState  string          `json:"pageState"`
	Revision   string          `json:"revision"`
}

func (v *catalogView) projectPersonWork(ctx context.Context, library *medialibrary.Library, candidate medialibrary.BrowseCandidate) (catalogCard, bool, error) {
	var access catalogAccess
	var visible, complete bool
	var err error
	if candidate.Kind == "series" {
		access, visible, complete, _, err = v.containerAccess(ctx, library, candidate.ID, candidate.ID, "", nil)
	} else {
		var next string
		access, visible, complete, next, err = v.leafAccess(ctx, library, candidate.ID, "")
		if !complete && next == "" {
			return catalogCard{}, false, errCatalogWorkPending
		}
	}
	if err != nil {
		return catalogCard{}, false, err
	}
	if !complete {
		return catalogCard{}, false, errCatalogWorkPending
	}
	if !visible {
		return catalogCard{}, false, nil
	}
	card, err := v.projectCard(ctx, library, candidate, access, false)
	if err != nil {
		return catalogCard{}, false, err
	}
	if card.classificationIncomplete {
		return catalogCard{}, false, errCatalogWorkPending
	}
	return card, true, nil
}

func catalogPersonPortrait(ctx context.Context, d *data, libraryID, personID string, preview []catalogCard) (*catalogArtwork, error) {
	if len(preview) == 0 {
		return nil, nil
	}
	work := preview[0]
	match, err := d.store.MediaLibrary.VisibleProviderMatch(ctx, d.user.ID, libraryID, work.Ref.ItemID, work.Revision)
	if err != nil {
		return nil, err
	}
	if match == nil {
		return nil, nil
	}
	for position, credit := range match.Document.Item.Credits {
		for _, id := range credit.ExternalIDs {
			if id.Provider == "tmdb" && "tmdb_person_"+id.Value == personID {
				return tmdbCreditPortrait(libraryID, work.Ref.ItemID, work.Revision, match, position), nil
			}
		}
	}
	return nil, nil
}

func parsePersonSearchRequest(r *http.Request) (string, string, int, error) {
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	libraryID := r.URL.Query().Get("libraryId")
	limit := 20
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 50 {
			return "", "", 0, medialibrary.ErrInvalid
		}
		limit = value
	}
	if query == "" || len(libraryID) > 128 || utf8.RuneCountInString(query) > 200 || len(query) > 800 || strings.IndexFunc(query, unicode.IsControl) >= 0 {
		return "", "", 0, medialibrary.ErrInvalid
	}
	for key := range r.URL.Query() {
		if key != "q" && key != "libraryId" && key != "limit" && key != "cursor" {
			return "", "", 0, medialibrary.ErrInvalid
		}
	}
	return query, libraryID, limit, nil
}

var mediaCatalogPeopleHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	query, libraryID, limit, err := parsePersonSearchRequest(r)
	if err != nil {
		return catalogError(w, err)
	}
	snapshot, err := beginCatalogSnapshot(r.Context(), d, libraryID)
	if err != nil {
		return catalogError(w, err)
	}
	cursorKey := fmt.Sprintf("people:%q:%q:%d", libraryID, query, limit)
	cursor, err := newCatalogCursor(d, snapshot, cursorKey, r.URL.Query().Get("cursor"))
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page := catalogPersonPage{Items: make([]catalogPerson, 0, limit), PageState: "ready"}
	for {
		if view.budget.remaining <= 0 {
			page.PageState, page.HasMore = "partial", true
			break
		}
		candidates, err := d.store.MediaLibrary.ListPersonCandidates(r.Context(), d.user.ID, medialibrary.PersonCandidateOptions{
			LibraryID: libraryID, Query: query, AfterID: cursor.AfterID, Limit: 1,
		})
		if err != nil {
			return catalogError(w, err)
		}
		if len(candidates.Items) == 0 {
			break
		}
		candidate := candidates.Items[0]
		before := cursor
		library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, candidate.LibraryID)
		if err != nil {
			return catalogError(w, err)
		}
		if !mediaLibraryAccessAllowed(d, library) {
			return catalogError(w, medialibrary.ErrNotFound)
		}
		roles, err := d.store.MediaLibrary.ListPersonRoleLabels(r.Context(), d.user.ID, candidate.LibraryID, candidate.ID)
		if err != nil {
			return catalogError(w, err)
		}
		preview := make([]catalogCard, 0, 3)
		after := ""
		for len(preview) < 3 {
			works, err := d.store.MediaLibrary.ListPersonWorkCandidates(r.Context(), d.user.ID, candidate.LibraryID, candidate.ID, after, 4)
			if err != nil {
				return catalogError(w, err)
			}
			if len(works.Items) == 0 {
				break
			}
			for _, work := range works.Items {
				if !view.budget.take() {
					cursor = before
					page.PageState, page.HasMore = "deferred", true
					break
				}
				card, visible, err := view.projectPersonWork(r.Context(), library, work)
				if err != nil {
					if errors.Is(err, errCatalogWorkPending) {
						cursor = before
						page.PageState, page.HasMore = "deferred", true
						break
					}
					return catalogError(w, err)
				}
				if visible {
					preview = append(preview, card)
					if len(preview) == 3 {
						break
					}
				}
			}
			if page.HasMore || len(preview) == 3 || !works.HasMore {
				break
			}
			after = works.Items[len(works.Items)-1].ID
		}
		if page.HasMore {
			break
		}
		if len(preview) != 0 {
			if len(page.Items) == limit {
				cursor = before
				page.HasMore = true
				break
			}
			portrait, err := catalogPersonPortrait(r.Context(), d, candidate.LibraryID, candidate.ID, preview)
			if err != nil {
				return catalogError(w, err)
			}
			page.Items = append(page.Items, catalogPerson{
				Ref:  catalogPersonRef{LibraryID: candidate.LibraryID, PersonID: candidate.ID},
				Name: candidate.Name, Roles: roles, Portrait: portrait, Preview: preview,
			})
		}
		cursor.AfterID = candidate.ID
		if !candidates.HasMore {
			break
		}
	}
	if page.HasMore {
		page.NextCursor, err = encodeCatalogCursor(d.settings.Key, cursor)
		if err != nil {
			return catalogError(w, err)
		}
	}
	for _, person := range page.Items {
		if err := verifyCatalogCards(r.Context(), d, person.Preview); err != nil {
			return catalogError(w, err)
		}
	}
	if err := view.revalidateSources(r.Context()); err != nil {
		return catalogError(w, err)
	}
	if err := verifyCatalogSnapshot(r.Context(), d, snapshot); err != nil {
		return catalogError(w, err)
	}
	page.Revision = catalogPublicRevision(d, snapshot, cursorKey, page.Items)
	return renderJSON(w, r, page)
})

var mediaCatalogPersonWorksHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	libraryID := r.URL.Query().Get("libraryId")
	personID := mux.Vars(r)["personId"]
	limit := 20
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 100 {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		limit = value
	}
	if len(libraryID) > 128 {
		return catalogError(w, medialibrary.ErrInvalid)
	}
	for key := range r.URL.Query() {
		if key != "libraryId" && key != "limit" && key != "cursor" {
			return catalogError(w, medialibrary.ErrInvalid)
		}
	}
	library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, libraryID)
	if err != nil || !mediaLibraryAccessAllowed(d, library) || library.ArchivedAt != nil {
		return catalogError(w, medialibrary.ErrNotFound)
	}
	snapshot, err := beginCatalogSnapshot(r.Context(), d, libraryID)
	if err != nil {
		return catalogError(w, err)
	}
	query := fmt.Sprintf("person-works:%q:%q:%d", libraryID, personID, limit)
	cursor, err := newCatalogCursor(d, snapshot, query, r.URL.Query().Get("cursor"))
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page := catalogCardPage{Items: make([]catalogCard, 0, limit), PageState: "ready"}
	for {
		if view.budget.remaining <= 0 {
			page.PageState, page.HasMore = "partial", true
			break
		}
		candidates, err := d.store.MediaLibrary.ListPersonWorkCandidates(r.Context(), d.user.ID, libraryID, personID, cursor.AfterID, 1)
		if err != nil {
			return catalogError(w, err)
		}
		if len(candidates.Items) == 0 {
			break
		}
		candidate := candidates.Items[0]
		before := cursor
		if !view.budget.take() {
			cursor = before
			page.PageState, page.HasMore = "deferred", true
			break
		}
		card, visible, err := view.projectPersonWork(r.Context(), library, candidate)
		if err != nil {
			if errors.Is(err, errCatalogWorkPending) {
				cursor = before
				page.PageState, page.HasMore = "deferred", true
				break
			}
			return catalogError(w, err)
		}
		if visible {
			if len(page.Items) == limit {
				cursor = before
				page.HasMore = true
				break
			}
			page.Items = append(page.Items, card)
		}
		cursor.AfterID = candidate.ID
		if !candidates.HasMore {
			break
		}
	}
	if page.HasMore {
		page.NextCursor, err = encodeCatalogCursor(d.settings.Key, cursor)
		if err != nil {
			return catalogError(w, err)
		}
	}
	if err := verifyCatalogCards(r.Context(), d, page.Items); err != nil {
		return catalogError(w, err)
	}
	if err := view.revalidateSources(r.Context()); err != nil {
		return catalogError(w, err)
	}
	if err := verifyCatalogSnapshot(r.Context(), d, snapshot); err != nil {
		return catalogError(w, err)
	}
	page.Revision = catalogPublicRevision(d, snapshot, query, page.Items)
	return renderJSON(w, r, page)
})
