package fbhttp

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/gorilla/mux"
)

type catalogSnapshot struct {
	scope     string
	libraryID string
}

func beginCatalogSnapshot(ctx context.Context, d *data, libraryID string) (catalogSnapshot, error) {
	scope, err := catalogScope(ctx, d, libraryID)
	if err != nil {
		return catalogSnapshot{}, err
	}
	return catalogSnapshot{scope: scope, libraryID: libraryID}, nil
}

func verifyCatalogSnapshot(ctx context.Context, d *data, snapshot catalogSnapshot) error {
	now, err := beginCatalogSnapshot(ctx, d, snapshot.libraryID)
	if err != nil {
		return err
	}
	if now != snapshot {
		return errCatalogCursorStale
	}
	return nil
}

func catalogPublicRevision(d *data, snapshot catalogSnapshot, query string, projection any) string {
	mac := hmac.New(sha256.New, d.settings.Key)
	_, _ = fmt.Fprintf(mac, "fileway/catalog-revision/v1\x00%s\x00%s\x00", snapshot.scope, query)
	encoded, _ := json.Marshal(projection)
	_, _ = mac.Write(encoded)
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil)[:16])
}

func verifyCatalogCards(ctx context.Context, d *data, cards []catalogCard) error {
	for _, card := range cards {
		revision, structureRevision, superseded, err := d.store.MediaLibrary.GetBrowseItemFence(ctx, d.user.ID, card.Ref.LibraryID, card.Ref.ItemID)
		if err != nil {
			return err
		}
		if revision != card.Revision || structureRevision != card.structureRevision || superseded {
			return medialibrary.ErrRevisionConflict
		}
	}
	return nil
}

func newCatalogCursor(d *data, snapshot catalogSnapshot, query, token string) (catalogCursor, error) {
	if token != "" {
		return decodeCatalogCursor(d.settings.Key, token, d.user.ID, d.deviceSession, query, snapshot.scope)
	}
	return catalogCursor{Version: 1, Actor: d.user.ID, Session: d.deviceSession, Query: query, Scope: snapshot.scope, Expires: time.Now().Add(catalogCursorTTL).UnixMilli()}, nil
}

type catalogCardPage struct {
	Items      []catalogCard `json:"items"`
	NextCursor string        `json:"nextCursor,omitempty"`
	HasMore    bool          `json:"hasMore"`
	PageState  string        `json:"pageState"`
	Revision   string        `json:"revision"`
	sawHidden  bool
}

type browseRequest struct {
	LibraryID, Kind, MatchState, Sort string
	Limit                             int
	Hero                              bool
}

func browseQuery(req browseRequest) string {
	return fmt.Sprintf("browse:%q:%q:%q:%q:%d:%t", req.LibraryID, req.Kind, req.MatchState, req.Sort, req.Limit, req.Hero)
}

func (v *catalogView) browse(ctx context.Context, snapshot catalogSnapshot, req browseRequest, token string) (catalogCardPage, error) {
	if req.Sort == "updated" {
		return v.browseUpdated(ctx, snapshot, req, token)
	}
	query := browseQuery(req)
	cursor, err := newCatalogCursor(v.d, snapshot, query, token)
	if err != nil {
		return catalogCardPage{}, err
	}
	page := catalogCardPage{Items: make([]catalogCard, 0, req.Limit), PageState: "ready", Revision: catalogPublicRevision(v.d, snapshot, query, nil)}
	if v.budget.remaining <= 0 && token == "" {
		page.PageState = "preparing"
		return page, nil
	}
	for {
		if v.budget.remaining <= 0 {
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		candidateKind := ""
		if req.Kind == "series" {
			candidateKind = "series"
		}
		options := medialibrary.BrowseCandidateOptions{LibraryID: req.LibraryID, OnlyOwned: mediaSharingDisabled(v.d), Sort: req.Sort, Kind: candidateKind, AfterID: cursor.AfterID, AfterValue: cursor.AfterValue, Limit: 1}
		candidatePage, err := v.d.store.MediaLibrary.ListBrowseCandidates(ctx, v.d.user.ID, options)
		if err != nil {
			return page, err
		}
		if len(candidatePage.Items) == 0 {
			break
		}
		candidate := candidatePage.Items[0]
		before := cursor
		if cursor.Pending != nil {
			pending := *cursor.Pending
			before.Pending = &pending
		}
		if cursor.Pending != nil && cursor.Pending.ItemID != candidate.ID {
			return page, errCatalogCursorStale
		}
		if cursor.Pending != nil && (cursor.Pending.ItemRevision != candidate.Revision || cursor.Pending.StructureRevision != candidate.StructureRevision) {
			return page, errCatalogCursorStale
		}
		if cursor.Pending == nil {
			if !v.budget.take() {
				page.PageState = "partial"
				page.HasMore = true
				break
			}
			cursor.Pending = &catalogVisibilityCursor{ItemID: candidate.ID, ItemRevision: candidate.Revision, StructureRevision: candidate.StructureRevision}
		}
		library, err := v.d.store.MediaLibrary.GetLibrary(ctx, v.d.user.ID, candidate.LibraryID)
		if err != nil {
			return page, err
		}
		if !mediaLibraryAccessAllowed(v.d, library) {
			return page, medialibrary.ErrNotFound
		}
		var access catalogAccess
		var visible, complete bool
		if candidate.Kind == "series" {
			if cursor.Pending.Stage == "" {
				cursor.Pending.Stage = "episodes"
				cursor.Pending.Season = -1
				cursor.Pending.Episode = -1
			}
			access, visible, complete, cursor.Pending, err = v.containerAccess(ctx, library, candidate.ID, candidate.ID, "", cursor.Pending)
		} else {
			var next string
			access, visible, complete, next, err = v.leafAccess(ctx, library, candidate.ID, cursor.Pending.SourceAfter)
			if !complete {
				cursor.Pending.SourceAfter = next
			} else {
				cursor.Pending = nil
			}
		}
		if err != nil {
			return page, err
		}
		if !complete {
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		cursor.Pending = nil
		cursor.AfterID = candidate.ID
		if req.Sort == "newest" {
			value := candidate.FirstAddedAt
			cursor.AfterValue = &value
		}
		if req.Sort == "updated" {
			value := int64(0)
			if candidate.SeriesUpdatedAt != nil {
				value = *candidate.SeriesUpdatedAt
			}
			cursor.AfterValue = &value
		}
		if req.Sort == "updated" && candidate.SeriesUpdatedAt == nil {
			visible = false
		}
		if visible {
			card, err := v.projectCard(ctx, library, candidate, access, false)
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
			if req.Sort == "updated" && card.SeriesUpdatedAt == nil {
				visible = false
			}
			if visible && (!req.Hero || (card.MatchState == "local_confirmed" || card.MatchState == "provider_confirmed") && (card.StructureKind == "movie" || card.StructureKind == "series") && card.Title != "") && (req.Kind == "" || req.Kind == card.StructureKind) && (req.MatchState == "" || req.MatchState == card.MatchState) {
				if len(page.Items) == req.Limit {
					cursor = before
					page.HasMore = true
					break
				}
				page.Items = append(page.Items, card)
			}
		} else {
			page.sawHidden = true
		}
		if !candidatePage.HasMore {
			break
		}
	}
	if page.HasMore {
		page.NextCursor, err = encodeCatalogCursor(v.d.settings.Key, cursor)
		if err != nil {
			return page, err
		}
	}
	if err = verifyCatalogCards(ctx, v.d, page.Items); err != nil {
		return page, err
	}
	page.Revision = catalogPublicRevision(v.d, snapshot, query, struct {
		Items []catalogCard
		State string
	}{page.Items, page.PageState})
	return page, nil
}

// Update order is computed only after each episode's source has been checked.
// If the bounded traversal cannot finish, do not sort by the hidden global
// series timestamp or publish a misleading top-N.
func (v *catalogView) browseUpdated(ctx context.Context, snapshot catalogSnapshot, req browseRequest, token string) (catalogCardPage, error) {
	query := browseQuery(req)
	cursor, err := newCatalogCursor(v.d, snapshot, query, token)
	if err != nil {
		return catalogCardPage{}, err
	}
	page := catalogCardPage{Items: []catalogCard{}, PageState: "ready", Revision: catalogPublicRevision(v.d, snapshot, query, nil)}
	if v.budget.remaining <= 0 && token == "" {
		page.PageState = "deferred"
		return page, nil
	}
	var cards []catalogCard
	after := ""
	for {
		candidates, err := v.d.store.MediaLibrary.ListBrowseCandidates(ctx, v.d.user.ID, medialibrary.BrowseCandidateOptions{LibraryID: req.LibraryID, OnlyOwned: mediaSharingDisabled(v.d), Sort: "id", Kind: "series", AfterID: after, Limit: 100})
		if err != nil {
			return page, err
		}
		for _, candidate := range candidates.Items {
			if !v.budget.take() {
				page.PageState = "deferred"
				return page, nil
			}
			library, err := v.d.store.MediaLibrary.GetLibrary(ctx, v.d.user.ID, candidate.LibraryID)
			if err != nil {
				return page, err
			}
			if !mediaLibraryAccessAllowed(v.d, library) {
				return page, medialibrary.ErrNotFound
			}
			access, visible, complete, _, err := v.containerAccess(ctx, library, candidate.ID, candidate.ID, "", nil)
			if err != nil {
				return page, err
			}
			if !complete {
				page.PageState = "deferred"
				return page, nil
			}
			if !visible {
				after = candidate.ID
				continue
			}
			card, err := v.projectCard(ctx, library, candidate, access, true)
			if err != nil {
				if errors.Is(err, errCatalogWorkPending) {
					page.PageState = "deferred"
					return page, nil
				}
				return page, err
			}
			if card.classificationIncomplete {
				page.PageState = "deferred"
				return page, nil
			}
			if card.Episodes != nil && card.Episodes.CountState == "error" {
				return page, medialibrary.ErrRevisionConflict
			}
			if card.Episodes != nil && card.Episodes.CountState == "deferred" {
				page.PageState = "deferred"
				return page, nil
			}
			if card.SeriesUpdatedAt != nil && (req.MatchState == "" || req.MatchState == card.MatchState) {
				cards = append(cards, card)
			}
			after = candidate.ID
		}
		if !candidates.HasMore {
			break
		}
		if len(candidates.Items) == 0 {
			return page, medialibrary.ErrInvalid
		}
	}
	sort.Slice(cards, func(i, j int) bool {
		if *cards[i].SeriesUpdatedAt != *cards[j].SeriesUpdatedAt {
			return *cards[i].SeriesUpdatedAt > *cards[j].SeriesUpdatedAt
		}
		return cards[i].Ref.ItemID < cards[j].Ref.ItemID
	})
	prefix := func(boundary int64, id string) string {
		type key struct {
			ID    string
			Added int64
		}
		visible := make([]key, 0, len(cards))
		for _, card := range cards {
			value := *card.SeriesUpdatedAt
			if value > boundary || value == boundary && card.Ref.ItemID <= id {
				visible = append(visible, key{card.Ref.ItemID, value})
			}
		}
		return catalogPublicRevision(v.d, snapshot, query, visible)
	}
	if cursor.AfterValue != nil && cursor.OrderPrefix != prefix(*cursor.AfterValue, cursor.AfterID) {
		return page, errCatalogCursorStale
	}
	for _, card := range cards {
		if cursor.AfterValue != nil && (*card.SeriesUpdatedAt > *cursor.AfterValue || *card.SeriesUpdatedAt == *cursor.AfterValue && card.Ref.ItemID <= cursor.AfterID) {
			continue
		}
		if len(page.Items) == req.Limit {
			page.HasMore = true
			break
		}
		page.Items = append(page.Items, card)
	}
	if page.HasMore {
		last := page.Items[len(page.Items)-1]
		value := *last.SeriesUpdatedAt
		cursor.AfterValue, cursor.AfterID = &value, last.Ref.ItemID
		cursor.OrderPrefix = prefix(value, last.Ref.ItemID)
		page.NextCursor, err = encodeCatalogCursor(v.d.settings.Key, cursor)
	}
	if err == nil {
		err = verifyCatalogCards(ctx, v.d, cards)
	}
	page.Revision = catalogPublicRevision(v.d, snapshot, query, struct {
		Items []catalogCard
		State string
	}{page.Items, page.PageState})
	return page, err
}

func parseBrowseRequest(r *http.Request) (browseRequest, error) {
	req := browseRequest{LibraryID: r.URL.Query().Get("libraryId"), Kind: r.URL.Query().Get("structureKind"), MatchState: r.URL.Query().Get("matchState"), Sort: r.URL.Query().Get("sort"), Limit: 20}
	if req.Sort == "" {
		req.Sort = "id"
	}
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 100 {
			return req, medialibrary.ErrInvalid
		}
		req.Limit = value
	}
	if len(req.LibraryID) > 128 || req.Sort != "id" && req.Sort != "newest" && req.Sort != "updated" || req.Kind != "" && req.Kind != "movie" && req.Kind != "series" && req.Kind != "video" || req.MatchState != "" && req.MatchState != "unmatched" && req.MatchState != "local_confirmed" && req.MatchState != "provider_confirmed" && req.MatchState != "needs_review" {
		return req, medialibrary.ErrInvalid
	}
	for key := range r.URL.Query() {
		if key != "libraryId" && key != "structureKind" && key != "matchState" && key != "sort" && key != "limit" && key != "cursor" {
			return req, medialibrary.ErrInvalid
		}
	}
	if req.Sort == "updated" {
		if req.Kind != "" && req.Kind != "series" {
			return req, medialibrary.ErrInvalid
		}
		req.Kind = "series"
	}
	return req, nil
}

var mediaCatalogItemsHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	req, err := parseBrowseRequest(r)
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
	page, err := view.browse(r.Context(), snapshot, req, r.URL.Query().Get("cursor"))
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

type catalogHomeRow struct {
	ID         string        `json:"id"`
	Kind       string        `json:"kind"`
	State      string        `json:"state"`
	Endpoint   string        `json:"endpoint"`
	Items      []catalogCard `json:"items,omitempty"`
	NextCursor string        `json:"nextCursor,omitempty"`
	HasMore    bool          `json:"hasMore"`
	Revision   string        `json:"revision"`
}
type catalogHome struct {
	SchemaVersion int                   `json:"schemaVersion"`
	State         string                `json:"state"`
	Libraries     []mediaLibrarySummary `json:"libraries"`
	Hero          *catalogHomeRow       `json:"hero,omitempty"`
	Rows          []catalogHomeRow      `json:"rows"`
	Capabilities  map[string]bool       `json:"capabilities"`
	CanActions    map[string]bool       `json:"canActions"`
	ActionReasons map[string]string     `json:"actionReasons"`
}

func homeRow(id string, page catalogCardPage) catalogHomeRow {
	return catalogHomeRow{ID: id, Kind: strings.ReplaceAll(id, "-", "_"), State: page.PageState, Endpoint: "/api/media-library/v1/home/rows/" + id, Items: page.Items, NextCursor: page.NextCursor, HasMore: page.HasMore, Revision: page.Revision}
}

var mediaCatalogHomeHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	snapshot, err := beginCatalogSnapshot(r.Context(), d, "")
	if err != nil {
		return catalogError(w, err)
	}
	result := catalogHome{SchemaVersion: 1, State: "no_libraries", Libraries: []mediaLibrarySummary{}, Rows: []catalogHomeRow{}, Capabilities: map[string]bool{"watchState": false, "favorites": false, "search": false, "collections": false, "categories": false, "localCatalog": true}, CanActions: map[string]bool{"createLibrary": d.deviceSession == ""}, ActionReasons: map[string]string{}}
	if d.deviceSession != "" {
		result.ActionReasons["createLibrary"] = "not_granted"
	}
	after := ""
	for {
		page, err := d.store.MediaLibrary.ListLibraries(r.Context(), d.user.ID, medialibrary.LibraryListOptions{OnlyOwned: mediaSharingDisabled(d), AfterID: after, Limit: 100})
		if err != nil {
			return catalogError(w, err)
		}
		for _, lib := range page.Items {
			result.Libraries = append(result.Libraries, librarySummary(&lib))
		}
		if page.NextCursor == "" {
			break
		}
		after = page.NextCursor
	}
	if len(result.Libraries) > 0 {
		view := newCatalogView(d)
		result.Rows = append(result.Rows, catalogHomeRow{ID: "continue", Kind: "continue", State: "unsupported", Endpoint: "/api/media-library/v1/home/rows/continue", Revision: catalogPublicRevision(d, snapshot, "continue", nil)})
		added, err := view.browse(r.Context(), snapshot, browseRequest{Sort: "newest", Limit: 12}, "")
		if err != nil {
			return catalogError(w, err)
		}
		if len(added.Items) > 0 || added.PageState != "ready" {
			result.Rows = append(result.Rows, homeRow("recently-added", added))
		}
		hero, err := view.browse(r.Context(), snapshot, browseRequest{Sort: "newest", Limit: 5, Hero: true}, "")
		if err != nil {
			return catalogError(w, err)
		}
		if len(hero.Items) > 0 || hero.PageState != "ready" {
			row := homeRow("hero", hero)
			result.Hero = &row
		}
		updated, err := view.browse(r.Context(), snapshot, browseRequest{Sort: "updated", Kind: "series", Limit: 12}, "")
		if err != nil {
			return catalogError(w, err)
		}
		if len(updated.Items) > 0 || updated.PageState != "ready" {
			result.Rows = append(result.Rows, homeRow("recently-updated-series", updated))
		}
		result.State = "ready"
		if added.PageState == "partial" {
			result.State = "partial"
		} else if len(added.Items) == 0 {
			if added.sawHidden {
				result.State = "restricted"
			} else {
				result.State = "empty_library"
			}
		} else {
			offline, anyOffline := true, false
			for _, card := range added.Items {
				if card.Availability == "offline" {
					anyOffline = true
				}
				if card.Availability != "offline" && card.Availability != "missing" {
					offline = false
					break
				}
			}
			if offline && anyOffline {
				if added.HasMore {
					result.State = "partial"
				} else {
					result.State = "source_offline"
				}
			}
		}
		active, err := d.store.MediaLibrary.HasVisibleCatalogScan(r.Context(), d.user.ID, mediaSharingDisabled(d))
		if err != nil {
			return catalogError(w, err)
		}
		if active && result.State != "restricted" && result.State != "partial" {
			result.State = "scanning"
		}
		if hero.PageState != "ready" || updated.PageState != "ready" {
			result.State = "partial"
		}
		if err = view.revalidateSources(r.Context()); err != nil {
			return catalogError(w, err)
		}
	}
	if err = verifyCatalogSnapshot(r.Context(), d, snapshot); err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, result)
})

var mediaCatalogHomeRowHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	id := mux.Vars(r)["rowId"]
	if id == "continue" || id == "favorites" {
		snapshot, err := beginCatalogSnapshot(r.Context(), d, "")
		if err == nil {
			err = verifyCatalogSnapshot(r.Context(), d, snapshot)
		}
		if err != nil {
			return catalogError(w, err)
		}
		return renderJSON(w, r, catalogHomeRow{ID: id, Kind: id, State: "unsupported", Endpoint: "/api/media-library/v1/home/rows/" + id, Revision: catalogPublicRevision(d, snapshot, id, nil)})
	}
	req := browseRequest{Limit: 12, Sort: "newest"}
	switch id {
	case "hero":
		req.Hero = true
		req.Limit = 5
	case "recently-added":
	case "recently-updated-series":
		req.Sort = "updated"
		req.Kind = "series"
	case "unmatched":
		req.Kind = "video"
		req.MatchState = "unmatched"
	default:
		return catalogError(w, medialibrary.ErrNotFound)
	}
	snapshot, err := beginCatalogSnapshot(r.Context(), d, "")
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page, err := view.browse(r.Context(), snapshot, req, r.URL.Query().Get("cursor"))
	if err == nil {
		err = view.revalidateSources(r.Context())
	}
	if err == nil {
		err = verifyCatalogSnapshot(r.Context(), d, snapshot)
	}
	if err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, homeRow(id, page))
})
