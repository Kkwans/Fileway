package fbhttp

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/gorilla/mux"
)

type catalogSeason struct {
	SeasonID string      `json:"seasonId"`
	Number   int         `json:"number"`
	Label    string      `json:"label"`
	OrderID  string      `json:"orderId"`
	Card     catalogCard `json:"card"`
}
type catalogEpisodePlayback struct {
	PlayMode          string   `json:"playMode"`
	IndependentStart  bool     `json:"independentStart"`
	CoveredEpisodeIDs []string `json:"coveredEpisodeIds"`
	CoverageState     string   `json:"coverageState"`
	Label             string   `json:"label,omitempty"`
}
type catalogEpisode struct {
	EpisodeID     string                  `json:"episodeId"`
	SeasonID      string                  `json:"seasonId"`
	SeasonNumber  int                     `json:"seasonNumber"`
	EpisodeNumber int                     `json:"episodeNumber"`
	OrderID       string                  `json:"orderId"`
	Card          catalogCard             `json:"card"`
	Playback      *catalogEpisodePlayback `json:"playback"`
}
type catalogStructurePage[T any] struct {
	Items      []T    `json:"items"`
	NextCursor string `json:"nextCursor,omitempty"`
	HasMore    bool   `json:"hasMore"`
	PageState  string `json:"pageState"`
	OrderID    string `json:"orderId,omitempty"`
	Revision   string `json:"revision"`
}

func structureLimit(r *http.Request) (int, error) {
	limit := 20
	if raw := r.URL.Query().Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > 100 {
			return 0, medialibrary.ErrInvalid
		}
		limit = n
	}
	return limit, nil
}

func (v *catalogView) parentAccess(ctx context.Context, library *medialibrary.Library, seriesID string, cursor *catalogCursor) (bool, bool, error) {
	structure, err := v.d.store.MediaLibrary.GetItemStructure(ctx, v.d.user.ID, library.ID, seriesID)
	if err != nil {
		return false, false, err
	}
	if structure.Kind != "series" {
		return false, false, medialibrary.ErrNotFound
	}
	if cursor.Parent != nil && (cursor.Parent.ItemID != seriesID || cursor.Parent.StructureRevision != structure.Revision) {
		return false, false, errCatalogCursorStale
	}
	_, visible, complete, pending, err := v.containerAccess(ctx, library, seriesID, seriesID, "", cursor.Parent)
	if pending != nil {
		pending.StructureRevision = structure.Revision
	}
	cursor.Parent = pending
	return visible, complete, err
}

func bindStructureOrder(cursor *catalogCursor, id string, revision int64) error {
	if id == "" {
		if cursor.OrderID != "" {
			return errCatalogCursorStale
		}
		return nil
	}
	if cursor.OrderID != "" && (cursor.OrderID != id || cursor.OrderRevision != revision) {
		return errCatalogCursorStale
	}
	cursor.OrderID, cursor.OrderRevision = id, revision
	return nil
}

func verifyStructureOrder(ctx context.Context, d *data, libraryID, seriesID string, cursor catalogCursor) error {
	if cursor.OrderID == "" {
		return nil
	}
	page, err := d.store.MediaLibrary.ListStructureSeasons(ctx, d.user.ID, libraryID, seriesID, nil, 1)
	if err != nil {
		return err
	}
	if page.OrderID != cursor.OrderID || page.OrderRevision != cursor.OrderRevision {
		return errCatalogCursorStale
	}
	return nil
}

func (v *catalogView) structureCard(ctx context.Context, library *medialibrary.Library, itemID string, access catalogAccess) (catalogCard, error) {
	structure, err := v.d.store.MediaLibrary.GetItemStructure(ctx, v.d.user.ID, library.ID, itemID)
	if err != nil {
		return catalogCard{}, err
	}
	item, err := v.d.store.MediaLibrary.GetCatalogItem(ctx, v.d.user.ID, library.ID, itemID)
	if err != nil {
		return catalogCard{}, err
	}
	c := medialibrary.BrowseCandidate{ID: item.ID, LibraryID: item.LibraryID, Kind: item.Kind, MatchState: structure.MatchState, Revision: item.Revision, StructureRevision: structure.Revision, FirstAddedAt: structure.FirstAddedAt, SeriesUpdatedAt: structure.SeriesUpdatedAt}
	return v.projectCard(ctx, library, c, access, false)
}

func sealStructurePage[T any](d *data, cursor catalogCursor, page *catalogStructurePage[T]) error {
	if !page.HasMore {
		return nil
	}
	token, err := encodeCatalogCursor(d.settings.Key, cursor)
	page.NextCursor = token
	return err
}

var mediaCatalogSeasonsHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	library, err := itemLibrary(r, d)
	if err != nil {
		return catalogError(w, err)
	}
	limit, err := structureLimit(r)
	if err != nil {
		return catalogError(w, err)
	}
	for key := range r.URL.Query() {
		if key != "limit" && key != "cursor" {
			return catalogError(w, medialibrary.ErrInvalid)
		}
	}
	seriesID := mux.Vars(r)["itemId"]
	query := fmt.Sprintf("seasons:%q:%q:%d", library.ID, seriesID, limit)
	snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
	if err != nil {
		return catalogError(w, err)
	}
	cursor, err := newCatalogCursor(d, snapshot, query, r.URL.Query().Get("cursor"))
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page := catalogStructurePage[catalogSeason]{Items: []catalogSeason{}, PageState: "ready"}
	visible, complete, err := view.parentAccess(r.Context(), library, seriesID, &cursor)
	if err != nil {
		return catalogError(w, err)
	}
	if !complete {
		page.PageState = "partial"
		page.HasMore = true
	} else if !visible {
		return catalogError(w, medialibrary.ErrNotFound)
	}
	var parentStructure *medialibrary.ItemStructure
	if complete && visible {
		parentStructure, err = d.store.MediaLibrary.GetItemStructure(r.Context(), d.user.ID, library.ID, seriesID)
		if err != nil {
			return catalogError(w, err)
		}
	}
	for complete && visible {
		if view.budget.remaining <= 0 {
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		positions, err := d.store.MediaLibrary.ListStructureSeasons(r.Context(), d.user.ID, library.ID, seriesID, cursor.AfterNumber, 1)
		if err != nil {
			return catalogError(w, err)
		}
		if err = bindStructureOrder(&cursor, positions.OrderID, positions.OrderRevision); err != nil {
			return catalogError(w, err)
		}
		if len(positions.Items) == 0 {
			break
		}
		position := positions.Items[0]
		before := cursor
		if cursor.Pending != nil {
			p := *cursor.Pending
			before.Pending = &p
		}
		if cursor.Pending != nil && cursor.Pending.ItemID != position.SeasonID {
			return catalogError(w, errCatalogCursorStale)
		}
		if cursor.Pending != nil && (cursor.Pending.ItemRevision != position.Revision || cursor.Pending.StructureRevision != position.StructureRevision || cursor.Pending.ParentStructureRevision != parentStructure.Revision) {
			return catalogError(w, errCatalogCursorStale)
		}
		if cursor.Pending == nil {
			if !view.budget.take() {
				page.PageState = "partial"
				page.HasMore = true
				break
			}
			cursor.Pending = &catalogVisibilityCursor{ItemID: position.SeasonID, Stage: "episodes", Season: -1, Episode: -1, ItemRevision: position.Revision, StructureRevision: position.StructureRevision, ParentStructureRevision: parentStructure.Revision}
		}
		access, allowed, finished, pending, err := view.containerAccess(r.Context(), library, position.SeasonID, seriesID, position.SeasonID, cursor.Pending)
		if err != nil {
			return catalogError(w, err)
		}
		cursor.Pending = pending
		if !finished {
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		cursor.Pending = nil
		number := position.Number
		cursor.AfterNumber = &number
		if allowed {
			page.OrderID = positions.OrderID
			label := fmt.Sprintf("第 %d 季", position.Number)
			if position.Number == 0 {
				label = "特别篇"
			}
			access.fallbackTitle = label
			card, err := view.structureCard(r.Context(), library, position.SeasonID, access)
			if err != nil {
				return catalogError(w, err)
			}
			if card.classificationIncomplete {
				page.PageState = "deferred"
				break
			}
			card.Episodes = view.cardCounts(r.Context(), library, seriesID, position.SeasonID)
			current, structureErr := d.store.MediaLibrary.GetItemStructure(r.Context(), d.user.ID, library.ID, seriesID)
			if structureErr != nil {
				return catalogError(w, structureErr)
			}
			if current.Revision != parentStructure.Revision {
				deferCatalogCounts(card.Episodes)
			}
			if len(page.Items) == limit {
				cursor = before
				page.HasMore = true
				break
			}
			page.Items = append(page.Items, catalogSeason{SeasonID: position.SeasonID, Number: position.Number, Label: label, OrderID: positions.OrderID, Card: card})
		}
		if positions.NextNumber == nil {
			break
		}
	}
	cards := make([]catalogCard, 0, len(page.Items))
	for _, item := range page.Items {
		cards = append(cards, item.Card)
	}
	if err = verifyCatalogCards(r.Context(), d, cards); err != nil {
		return catalogError(w, err)
	}
	if err = verifyStructureOrder(r.Context(), d, library.ID, seriesID, cursor); err != nil {
		return catalogError(w, err)
	}
	if err = view.revalidateSources(r.Context()); err != nil {
		return catalogError(w, err)
	}
	page.Revision = catalogPublicRevision(d, snapshot, query, struct {
		Items []catalogSeason
		State string
	}{page.Items, page.PageState})
	if err = sealStructurePage(d, cursor, &page); err == nil {
		err = verifyCatalogSnapshot(r.Context(), d, snapshot)
	}
	if err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, page)
})

func (v *catalogView) episodePlayback(ctx context.Context, library *medialibrary.Library, seriesID, episodeID string, selected catalogAccess) (*catalogEpisodePlayback, error) {
	if selected.sourceFileID == "" {
		return nil, nil
	}
	checking := func() *catalogEpisodePlayback {
		return &catalogEpisodePlayback{PlayMode: "checking", IndependentStart: false, CoveredEpisodeIDs: []string{}, CoverageState: "deferred"}
	}
	after := ""
	for v.budget.remaining > 0 {
		variants, err := v.d.store.MediaLibrary.ListPlaybackVariants(ctx, v.d.user.ID, library.ID, episodeID, after, min(100, v.budget.remaining))
		if err != nil {
			return nil, err
		}
		for _, variant := range variants.Items {
			if !v.budget.take() {
				return checking(), nil
			}
			mapping, err := v.d.store.MediaLibrary.GetStructureFileMapping(ctx, v.d.user.ID, library.ID, episodeID, variant.VariantID, selected.bindingID, selected.proofEpoch)
			if errors.Is(err, medialibrary.ErrNotFound) {
				continue
			}
			if err != nil {
				return nil, err
			}
			if mapping.SourceFileID != selected.sourceFileID {
				continue
			}
			key := catalogCoverageKey{LibraryID: library.ID, SeriesID: seriesID, BindingID: selected.bindingID, FileID: selected.sourceFileID, Role: mapping.Role, Epoch: selected.proofEpoch}
			if cached, ok := v.coverage[key]; ok {
				copy := cached
				copy.CoveredEpisodeIDs = append([]string(nil), cached.CoveredEpisodeIDs...)
				return &copy, nil
			}
			playback := &catalogEpisodePlayback{PlayMode: "single", IndependentStart: true, CoveredEpisodeIDs: []string{}, CoverageState: "ready"}
			if mapping.Role == "combined" {
				playback.PlayMode = "combined_file"
				playback.IndependentStart = false
			}
			var numbers []int
			season := -1
			for _, id := range mapping.CoveredEpisodeIDs {
				position, err := v.d.store.MediaLibrary.GetSeriesEpisodeCandidate(ctx, v.d.user.ID, library.ID, seriesID, id)
				if err != nil {
					if err == medialibrary.ErrNotFound {
						continue
					}
					return nil, err
				}
				_, visible, complete, _, err := v.leafAccess(ctx, library, id, "")
				if err != nil {
					return nil, err
				}
				if !complete {
					playback.CoverageState = "partial"
					break
				}
				if visible {
					playback.CoveredEpisodeIDs = append(playback.CoveredEpisodeIDs, id)
					numbers = append(numbers, position.EpisodeNumber)
					if season == -1 {
						season = position.SeasonNumber
					} else if season != position.SeasonNumber {
						season = -2
					}
				}
			}
			if playback.PlayMode == "combined_file" {
				playback.Label = "合并文件"
				if playback.CoverageState == "ready" && season >= 0 && len(numbers) > 1 {
					sort.Ints(numbers)
					contiguous := true
					for i := 1; i < len(numbers); i++ {
						if numbers[i] != numbers[i-1]+1 {
							contiguous = false
							break
						}
					}
					if contiguous {
						playback.Label = fmt.Sprintf("第 %d–%d 集合并文件", numbers[0], numbers[len(numbers)-1])
					}
				}
			}
			if playback.CoverageState == "ready" && v.coverage != nil {
				copy := *playback
				copy.CoveredEpisodeIDs = append([]string(nil), playback.CoveredEpisodeIDs...)
				v.coverage[key] = copy
			}
			return playback, nil
		}
		if variants.NextCursor == "" {
			break
		}
		if variants.NextCursor == after {
			return nil, medialibrary.ErrInvalid
		}
		after = variants.NextCursor
	}
	if v.budget.remaining <= 0 {
		return checking(), nil
	}
	return nil, medialibrary.ErrRevisionConflict
}

var mediaCatalogEpisodesHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	library, err := itemLibrary(r, d)
	if err != nil {
		return catalogError(w, err)
	}
	limit, err := structureLimit(r)
	if err != nil {
		return catalogError(w, err)
	}
	seasonID, orderID := r.URL.Query().Get("seasonId"), r.URL.Query().Get("orderId")
	if seasonID == "" || len(seasonID) > 128 || len(orderID) > 128 {
		return catalogError(w, medialibrary.ErrInvalid)
	}
	for key := range r.URL.Query() {
		if key != "limit" && key != "cursor" && key != "seasonId" && key != "orderId" {
			return catalogError(w, medialibrary.ErrInvalid)
		}
	}
	seriesID := mux.Vars(r)["itemId"]
	query := fmt.Sprintf("episodes:%q:%q:%q:%q:%d", library.ID, seriesID, seasonID, orderID, limit)
	snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
	if err != nil {
		return catalogError(w, err)
	}
	cursor, err := newCatalogCursor(d, snapshot, query, r.URL.Query().Get("cursor"))
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	page := catalogStructurePage[catalogEpisode]{Items: []catalogEpisode{}, PageState: "ready"}
	visible, complete, err := view.parentAccess(r.Context(), library, seriesID, &cursor)
	if err != nil {
		return catalogError(w, err)
	}
	if !complete {
		page.PageState = "partial"
		page.HasMore = true
	} else if !visible {
		return catalogError(w, medialibrary.ErrNotFound)
	}
	var parentStructure *medialibrary.ItemStructure
	if complete && visible {
		parentStructure, err = d.store.MediaLibrary.GetItemStructure(r.Context(), d.user.ID, library.ID, seriesID)
		if err != nil {
			return catalogError(w, err)
		}
	}
	if complete && visible {
		if cursor.SeasonParent != nil && (cursor.SeasonParent.ItemID != seasonID || cursor.SeasonParent.ParentStructureRevision != parentStructure.Revision) {
			return catalogError(w, errCatalogCursorStale)
		}
		_, seasonVisible, seasonComplete, pending, accessErr := view.containerAccess(r.Context(), library, seasonID, seriesID, seasonID, cursor.SeasonParent)
		if accessErr != nil {
			return catalogError(w, accessErr)
		}
		if pending != nil {
			pending.ParentStructureRevision = parentStructure.Revision
		}
		cursor.SeasonParent = pending
		if !seasonComplete {
			page.PageState = "partial"
			page.HasMore = true
			complete = false
		} else if !seasonVisible {
			return catalogError(w, medialibrary.ErrNotFound)
		}
	}
	for complete && visible {
		if view.budget.remaining <= 0 {
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		positions, err := d.store.MediaLibrary.ListStructureEpisodes(r.Context(), d.user.ID, library.ID, seriesID, seasonID, cursor.AfterNumber, 1)
		if err != nil {
			return catalogError(w, err)
		}
		if err = bindStructureOrder(&cursor, positions.OrderID, positions.OrderRevision); err != nil {
			return catalogError(w, err)
		}
		if orderID != "" && orderID != positions.OrderID {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		if len(positions.Items) == 0 {
			break
		}
		position := positions.Items[0]
		before := cursor
		if cursor.Pending != nil {
			p := *cursor.Pending
			before.Pending = &p
		}
		if cursor.Pending != nil && cursor.Pending.ItemID != position.EpisodeID {
			return catalogError(w, errCatalogCursorStale)
		}
		if cursor.Pending != nil && (cursor.Pending.ItemRevision != position.Revision || cursor.Pending.StructureRevision != position.StructureRevision) {
			return catalogError(w, errCatalogCursorStale)
		}
		if cursor.Pending == nil {
			if !view.budget.take() {
				page.PageState = "partial"
				page.HasMore = true
				break
			}
			cursor.Pending = &catalogVisibilityCursor{ItemID: position.EpisodeID, ItemRevision: position.Revision, StructureRevision: position.StructureRevision}
		}
		access, allowed, finished, next, err := view.leafAccess(r.Context(), library, position.EpisodeID, cursor.Pending.SourceAfter)
		if err != nil {
			return catalogError(w, err)
		}
		if !finished {
			cursor.Pending.SourceAfter = next
			page.PageState = "partial"
			page.HasMore = true
			break
		}
		cursor.Pending = nil
		number := position.EpisodeNumber
		cursor.AfterNumber = &number
		if allowed {
			page.OrderID = positions.OrderID
			card, err := view.structureCard(r.Context(), library, position.EpisodeID, access)
			if err != nil {
				return catalogError(w, err)
			}
			if card.classificationIncomplete {
				page.PageState = "deferred"
				break
			}
			playback, err := view.episodePlayback(r.Context(), library, seriesID, position.EpisodeID, access)
			if err != nil {
				return catalogError(w, err)
			}
			if len(page.Items) == limit {
				cursor = before
				page.HasMore = true
				break
			}
			page.Items = append(page.Items, catalogEpisode{EpisodeID: position.EpisodeID, SeasonID: seasonID, SeasonNumber: position.SeasonNumber, EpisodeNumber: position.EpisodeNumber, OrderID: positions.OrderID, Card: card, Playback: playback})
		}
		if positions.NextNumber == nil {
			break
		}
	}
	cards := make([]catalogCard, 0, len(page.Items))
	for _, item := range page.Items {
		cards = append(cards, item.Card)
	}
	if err = verifyCatalogCards(r.Context(), d, cards); err != nil {
		return catalogError(w, err)
	}
	if err = verifyStructureOrder(r.Context(), d, library.ID, seriesID, cursor); err != nil {
		return catalogError(w, err)
	}
	if err = view.revalidateSources(r.Context()); err != nil {
		return catalogError(w, err)
	}
	page.Revision = catalogPublicRevision(d, snapshot, query, struct {
		Items []catalogEpisode
		State string
	}{page.Items, page.PageState})
	if err = sealStructurePage(d, cursor, &page); err == nil {
		err = verifyCatalogSnapshot(r.Context(), d, snapshot)
	}
	if err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, page)
})

// Counts are a separate bounded request. No partial numerator is published;
// a long or currently unresolved traversal reports deferred with null counts.
var mediaCatalogEpisodeCountsHandler = catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	library, err := itemLibrary(r, d)
	if err != nil {
		return catalogError(w, err)
	}
	seasonID := r.URL.Query().Get("seasonId")
	if len(seasonID) > 128 {
		return catalogError(w, medialibrary.ErrInvalid)
	}
	for key := range r.URL.Query() {
		if key != "seasonId" {
			return catalogError(w, medialibrary.ErrInvalid)
		}
	}
	seriesID := mux.Vars(r)["itemId"]
	if seasonID != "" {
		season, structureErr := d.store.MediaLibrary.GetItemStructure(r.Context(), d.user.ID, library.ID, seasonID)
		if structureErr != nil || season.Kind != "season" || season.SeriesID != seriesID {
			return catalogError(w, medialibrary.ErrNotFound)
		}
	}
	snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
	if err != nil {
		return catalogError(w, err)
	}
	view := newCatalogView(d)
	cursor := catalogCursor{}
	visible, complete, err := view.parentAccess(r.Context(), library, seriesID, &cursor)
	if err != nil {
		return catalogError(w, err)
	}
	if complete && !visible {
		return catalogError(w, medialibrary.ErrNotFound)
	}
	if complete && seasonID != "" {
		_, seasonVisible, seasonComplete, _, accessErr := view.containerAccess(r.Context(), library, seasonID, seriesID, seasonID, nil)
		if accessErr != nil {
			return catalogError(w, accessErr)
		}
		if seasonComplete && !seasonVisible {
			return catalogError(w, medialibrary.ErrNotFound)
		}
		complete = seasonComplete
	}
	var counts *catalogEpisodeCounts
	if complete {
		before, structureErr := d.store.MediaLibrary.GetItemStructure(r.Context(), d.user.ID, library.ID, seriesID)
		if structureErr != nil {
			return catalogError(w, structureErr)
		}
		counts = view.seriesCounts(r.Context(), library, seriesID, seasonID)
		after, structureErr := d.store.MediaLibrary.GetItemStructure(r.Context(), d.user.ID, library.ID, seriesID)
		if structureErr != nil {
			return catalogError(w, structureErr)
		}
		if before.Revision != after.Revision {
			deferCatalogCounts(counts)
		}
	} else {
		counts = &catalogEpisodeCounts{CountState: "deferred"}
	}
	if err = view.revalidateSources(r.Context()); err != nil {
		return catalogError(w, err)
	}
	if err = verifyCatalogSnapshot(r.Context(), d, snapshot); err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, counts)
})
