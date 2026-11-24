package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

type catalogCollectionRef struct {
	LibraryID    string `json:"libraryId"`
	CollectionID string `json:"collectionId"`
}

type catalogCollection struct {
	Ref     catalogCollectionRef `json:"ref"`
	Name    string               `json:"name"`
	Preview []catalogCard        `json:"preview"`
}

type catalogCollectionPage struct {
	Items      []catalogCollection `json:"items"`
	NextCursor string              `json:"nextCursor,omitempty"`
	HasMore    bool                `json:"hasMore"`
	PageState  string              `json:"pageState"`
	Revision   string              `json:"revision"`
}

func (v *catalogView) collectionAllowed(ctx context.Context, library *medialibrary.Library, candidate medialibrary.CollectionCandidate) (bool, error) {
	sources, err := v.d.store.MediaLibrary.ListCollectionSources(ctx, v.d.user.ID, candidate, 100)
	if err != nil {
		return false, err
	}
	for _, source := range sources {
		entry, err := v.d.store.MediaLibrary.GetItemMetadataSourceAccess(ctx, v.d.user.ID, library.ID, source.ItemID, source.InputID)
		if err != nil {
			if errors.Is(err, medialibrary.ErrNotFound) {
				continue
			}
			return false, err
		}
		state, allowed, err := v.checkMetadata(ctx, library, *entry)
		if err != nil {
			if errors.Is(err, medialibrary.ErrNotFound) || errors.Is(err, fs.ErrPermission) || errors.Is(err, mediascan.ErrBindingChanged) || errors.Is(err, mediascan.ErrBindingUnverified) {
				continue
			}
			return false, err
		}
		if allowed && state != mediascan.ReferenceReplaced {
			return true, nil
		}
	}
	return false, nil
}

var mediaCatalogCollectionsHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	query, libraryID, limit, err := parsePersonSearchRequest(r)
	if err != nil {
		return catalogError(w, err)
	}
	snapshot, err := beginCatalogSnapshot(r.Context(), d, libraryID)
	if err != nil {
		return catalogError(w, err)
	}
	cursorKey := fmt.Sprintf("collections:%q:%q:%d", libraryID, query, limit)
	cursor, err := newCatalogCursor(d, snapshot, cursorKey, r.URL.Query().Get("cursor"))
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page := catalogCollectionPage{Items: make([]catalogCollection, 0, limit), PageState: "ready"}
	for {
		if view.budget.remaining <= 0 {
			page.PageState, page.HasMore = "partial", true
			break
		}
		candidates, err := d.store.MediaLibrary.ListCollectionCandidates(r.Context(), d.user.ID, medialibrary.CollectionCandidateOptions{
			LibraryID: libraryID, Query: query, AfterLibrary: cursor.AfterLibrary, AfterName: cursor.AfterName,
			AfterExternal: cursor.AfterExternal, Limit: 1,
		})
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
		library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, candidate.LibraryID)
		if err != nil {
			return catalogError(w, err)
		}
		if !mediaLibraryAccessAllowed(d, library) {
			return catalogError(w, medialibrary.ErrNotFound)
		}
		allowed, err := view.collectionAllowed(r.Context(), library, candidate)
		if err != nil {
			return catalogError(w, err)
		}
		preview := make([]catalogCard, 0, 3)
		if allowed {
			after := ""
			for len(preview) < 3 {
				works, err := d.store.MediaLibrary.ListCollectionWorkCandidates(r.Context(), d.user.ID, candidate, after, 4)
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
			page.Items = append(page.Items, catalogCollection{
				Ref:  catalogCollectionRef{LibraryID: candidate.LibraryID, CollectionID: candidate.ID},
				Name: candidate.Name, Preview: preview,
			})
		}
		cursor.AfterLibrary, cursor.AfterName, cursor.AfterExternal = candidate.LibraryID, candidate.NameKey, candidate.ExternalID
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
	for _, collection := range page.Items {
		if err := verifyCatalogCards(r.Context(), d, collection.Preview); err != nil {
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

var mediaCatalogCollectionWorksHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	libraryID := r.URL.Query().Get("libraryId")
	collectionID := mux.Vars(r)["collectionId"]
	limit := 20
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 100 {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		limit = value
	}
	if len(libraryID) > 128 || len(collectionID) > 128 {
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
	query := fmt.Sprintf("collection-works:%q:%q:%d", libraryID, collectionID, limit)
	cursor, err := newCatalogCursor(d, snapshot, query, r.URL.Query().Get("cursor"))
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page := catalogCardPage{Items: make([]catalogCard, 0, limit), PageState: "ready"}
	candidate, err := d.store.MediaLibrary.FindCollectionCandidate(r.Context(), d.user.ID, libraryID, collectionID)
	if err != nil {
		return catalogError(w, err)
	}
	allowed, err := view.collectionAllowed(r.Context(), library, *candidate)
	if err != nil {
		return catalogError(w, err)
	}
	if allowed {
		for {
			if view.budget.remaining <= 0 {
				page.PageState, page.HasMore = "partial", true
				break
			}
			candidates, err := d.store.MediaLibrary.ListCollectionWorkCandidates(r.Context(), d.user.ID, *candidate, cursor.AfterID, 1)
			if err != nil {
				return catalogError(w, err)
			}
			if len(candidates.Items) == 0 {
				break
			}
			work := candidates.Items[0]
			before := cursor
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
				if len(page.Items) == limit {
					cursor = before
					page.HasMore = true
					break
				}
				page.Items = append(page.Items, card)
			}
			cursor.AfterID = work.ID
			if !candidates.HasMore {
				break
			}
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
