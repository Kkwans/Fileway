package fbhttp

import (
	"context"
	"errors"
	"io/fs"
	"path"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
)

const (
	catalogCandidateBudget     = 1000
	catalogContinuationReserve = 32
)

type catalogBudget struct{ remaining int }

func (b *catalogBudget) take() bool {
	if b.remaining <= 0 {
		return false
	}
	b.remaining--
	return true
}

type catalogAccess struct {
	mediaItemAccess
	size                    *int64
	extension               string
	playable                bool
	contextOnly             bool
	sourceFileID, bindingID string
	proofEpoch              int64
}

type catalogView struct {
	d              *data
	budget         *catalogBudget
	checks         map[catalogSourceKey]*catalogSourceCheck
	coverage       map[catalogCoverageKey]catalogEpisodePlayback
	metadataChecks map[catalogMetadataKey]catalogMetadataCheck
}

func newCatalogView(d *data) *catalogView {
	return &catalogView{d: d, budget: &catalogBudget{remaining: catalogCandidateBudget}, checks: map[catalogSourceKey]*catalogSourceCheck{}, coverage: map[catalogCoverageKey]catalogEpisodePlayback{}, metadataChecks: map[catalogMetadataKey]catalogMetadataCheck{}}
}

type catalogSourceKey struct {
	BindingID, FileID, Version, IdentityKind                                           string
	ProofEpoch, BindingRevision, AuthorizationRevision, LocatorRevision, IdentityEpoch int64
}
type catalogSourceCheck struct {
	LibraryID string
	Entry     medialibrary.ItemSourceAccess
	State     mediascan.ReferenceState
	ItemIDs   map[string]bool
}
type catalogCoverageKey struct {
	LibraryID, SeriesID, BindingID, FileID, Role string
	Epoch                                        int64
}
type catalogMetadataKey struct {
	ID, BindingID, SourceKey                                            string
	ProofEpoch, BindingRevision, AuthorizationRevision, ContextRevision int64
}
type catalogMetadataCheck struct {
	LibraryID string
	Entry     medialibrary.ItemMetadataSourceAccess
	State     mediascan.ReferenceState
}

func (v *catalogView) checkMetadata(ctx context.Context, library *medialibrary.Library, entry medialibrary.ItemMetadataSourceAccess) (mediascan.ReferenceState, bool, error) {
	key := catalogMetadataKey{entry.ID, entry.BindingID, string(entry.SourceKey), entry.Binding.ProofEpoch, entry.Binding.BindingRevision, entry.Binding.AuthorizationRevision, entry.ContextRevision}
	if prior, ok := v.metadataChecks[key]; ok {
		return prior.State, true, nil
	}
	state, allowed, err := metadataSourceAllowed(ctx, v.d, library, entry)
	if err == nil && allowed && v.metadataChecks != nil {
		v.metadataChecks[key] = catalogMetadataCheck{LibraryID: library.ID, Entry: entry, State: state}
	}
	return state, allowed, err
}

func sourceCheckKey(entry medialibrary.ItemSourceAccess) catalogSourceKey {
	return catalogSourceKey{entry.BindingID, entry.SourceFileID, entry.SourceVersion, entry.IdentityKind, entry.Binding.ProofEpoch, entry.Binding.BindingRevision, entry.Binding.AuthorizationRevision, entry.LocatorRevision, entry.IdentityEpoch}
}

func (v *catalogView) checkSource(ctx context.Context, library *medialibrary.Library, itemID string, entry medialibrary.ItemSourceAccess) (mediascan.ReferenceState, error) {
	key := sourceCheckKey(entry)
	if prior, ok := v.checks[key]; ok {
		prior.ItemIDs[itemID] = true
		return prior.State, nil
	}
	binding, err := ownerBinding(ctx, v.d, library, entry.BindingID, entry.Binding, entry.Source)
	if err != nil {
		return "", err
	}
	state, err := ownerReference(ctx, v.d, binding, entry.Locator, entry.ObjectKey, entry.IdentityKind, entry.SourceVersion)
	if err != nil {
		return "", err
	}
	if state == mediascan.ReferenceReplaced || state == mediascan.ReferenceOffline && binding.Proof.RootIdentityKind == "virtual" {
		return mediascan.ReferenceReplaced, nil
	}
	if v.checks != nil {
		v.checks[key] = &catalogSourceCheck{LibraryID: library.ID, Entry: entry, State: state, ItemIDs: map[string]bool{itemID: true}}
	}
	return state, nil
}

// Recheck each distinct source once before publication. Episode mappings that
// share a combined file reuse that exact file proof only within this request.
func (v *catalogView) revalidateSources(ctx context.Context) error {
	for _, check := range v.checks {
		library, err := currentMediaLibrary(ctx, v.d, v.d.user.ID, check.LibraryID)
		if err != nil {
			return medialibrary.ErrRevisionConflict
		}
		binding, err := ownerBinding(ctx, v.d, library, check.Entry.BindingID, check.Entry.Binding, check.Entry.Source)
		if err != nil {
			return medialibrary.ErrRevisionConflict
		}
		state, err := ownerReference(ctx, v.d, binding, check.Entry.Locator, check.Entry.ObjectKey, check.Entry.IdentityKind, check.Entry.SourceVersion)
		if err != nil || state != check.State {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return medialibrary.ErrRevisionConflict
		}
		for itemID := range check.ItemIDs {
			current, err := v.d.store.MediaLibrary.CheckItemSourceRevision(ctx, v.d.user.ID, check.LibraryID, itemID, check.Entry)
			if err != nil {
				return err
			}
			if !current {
				return medialibrary.ErrRevisionConflict
			}
		}
	}
	for _, check := range v.metadataChecks {
		library, err := currentMediaLibrary(ctx, v.d, v.d.user.ID, check.LibraryID)
		if err != nil {
			return medialibrary.ErrRevisionConflict
		}
		state, allowed, err := metadataSourceAllowed(ctx, v.d, library, check.Entry)
		if err != nil || !allowed || state != check.State {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return medialibrary.ErrRevisionConflict
		}
		if check.Entry.ContextItemID != "" {
			current, err := v.d.store.MediaLibrary.CheckDirectoryContextSource(ctx, v.d.user.ID, check.LibraryID, check.Entry)
			if err != nil {
				return err
			}
			if !current {
				return medialibrary.ErrRevisionConflict
			}
		}
	}
	return nil
}

// leafAccess reuses the existing owner/binding/reference checks, but bounds
// every inspected source and retains a continuation when none is visible yet.
func (v *catalogView) leafAccess(ctx context.Context, library *medialibrary.Library, itemID, after string) (catalogAccess, bool, bool, string, error) {
	var best catalogAccess
	visible := false
	for {
		if v.budget.remaining <= catalogContinuationReserve {
			if visible {
				best.sourceState = mediascan.ReferenceState("checking")
				best.playable = false
				return best, true, true, "", nil
			}
			return best, false, false, after, nil
		}
		limit := min(100, v.budget.remaining-catalogContinuationReserve)
		page, err := v.d.store.MediaLibrary.ListItemSourceAccess(ctx, v.d.user.ID, library.ID, itemID, after, limit)
		if err != nil {
			return best, false, false, after, err
		}
		for _, entry := range page.Items {
			if !v.budget.take() {
				return best, false, false, after, nil
			}
			after = entry.ID
			state, err := v.checkSource(ctx, library, itemID, entry)
			if err != nil {
				if ctx.Err() != nil {
					return best, false, false, after, ctx.Err()
				}
				if errors.Is(err, medialibrary.ErrNotFound) || errors.Is(err, fs.ErrPermission) || errors.Is(err, mediascan.ErrBindingChanged) || errors.Is(err, mediascan.ErrBindingUnverified) {
					continue
				}
				return best, false, false, after, err
			}
			if state == mediascan.ReferenceReplaced {
				continue
			}
			if entry.MissingSince != nil && (state == mediascan.ReferenceAvailable || state == mediascan.ReferenceUnverified) {
				state = mediascan.ReferenceState("checking")
			}
			if !visible || sourceStateRank(state) < sourceStateRank(best.sourceState) {
				var size *int64
				if state == mediascan.ReferenceAvailable {
					size = entry.SizeBytes
				}
				best = catalogAccess{mediaItemAccess: mediaItemAccess{sourceState: state, metadataState: "none", fallbackTitle: mediascan.DisplayFilename(path.Base(string(entry.Locator))), allowedInputs: make(map[string]bool), budget: v.budget, view: v}, size: size, extension: strings.ToLower(strings.TrimPrefix(path.Ext(string(entry.Locator)), ".")), playable: state == mediascan.ReferenceAvailable || state == mediascan.ReferenceUnverified, sourceFileID: entry.SourceFileID, bindingID: entry.BindingID, proofEpoch: entry.Binding.ProofEpoch}
			}
			visible = true
			if state == mediascan.ReferenceAvailable {
				return best, true, true, "", nil
			}
		}
		if page.NextCursor == "" {
			return best, visible, true, "", nil
		}
		if page.NextCursor != after {
			return best, false, false, after, medialibrary.ErrInvalid
		}
	}
}

func (v *catalogView) contextAccess(ctx context.Context, library *medialibrary.Library, itemID, after string) (catalogAccess, bool, bool, string, error) {
	var access catalogAccess
	for {
		if v.budget.remaining <= catalogContinuationReserve {
			return access, false, false, after, nil
		}
		page, err := v.d.store.MediaLibrary.ListDirectoryContextSources(ctx, v.d.user.ID, library.ID, itemID, after, min(100, v.budget.remaining-catalogContinuationReserve))
		if err != nil {
			return access, false, false, after, err
		}
		for _, entry := range page.Items {
			if !v.budget.take() {
				return access, false, false, after, nil
			}
			after = entry.ID
			state, allowed, err := v.checkMetadata(ctx, library, entry)
			if err != nil {
				return access, false, false, after, err
			}
			if allowed {
				metadataState := "available"
				if state == mediascan.ReferenceOffline {
					metadataState = "offline"
				}
				access.mediaItemAccess = mediaItemAccess{sourceState: state, metadataState: metadataState, allowedInputs: make(map[string]bool), budget: v.budget, view: v}
				access.contextOnly = true
				return access, true, true, "", nil
			}
		}
		if page.NextCursor == "" {
			return access, false, true, "", nil
		}
		if page.NextCursor != after {
			return access, false, false, after, medialibrary.ErrInvalid
		}
	}
}

func (v *catalogView) movieNFOState(ctx context.Context, library *medialibrary.Library, itemID string, access *mediaItemAccess) (movie, review, complete bool, resultErr error) {
	after := ""
	for v.budget.remaining > 0 {
		page, err := v.d.store.MediaLibrary.ListItemMetadataSourceAccess(ctx, v.d.user.ID, library.ID, itemID, after, min(100, v.budget.remaining))
		if err != nil {
			return false, false, false, err
		}
		for _, entry := range page.Items {
			if !v.budget.take() {
				return false, false, false, nil
			}
			after = entry.ID
			if !entry.Current || entry.Status != "candidate" && (entry.Status != "applied" || entry.Kind != "movie") {
				continue
			}
			allowed, known := access.allowedInputs[entry.ID]
			if !known {
				var state mediascan.ReferenceState
				state, allowed, err = v.checkMetadata(ctx, library, entry)
				if err != nil {
					return false, false, false, err
				}
				if !allowed {
					access.metadataState = "restricted"
				} else if state == mediascan.ReferenceOffline && access.metadataState != "restricted" {
					access.metadataState = "offline"
				} else if access.metadataState == "none" {
					access.metadataState = "available"
				}
				if len(access.allowedInputs) < metadataAccessCacheLimit {
					access.allowedInputs[entry.ID] = allowed
				}
			}
			if allowed {
				if entry.Status == "candidate" {
					review = true
				} else {
					movie = true
				}
			}
		}
		if page.NextCursor == "" {
			return movie, review, true, nil
		}
		if page.NextCursor != after {
			return false, false, false, medialibrary.ErrInvalid
		}
	}
	return false, false, false, nil
}

// containerAccess uses only authorized descendants or an independently
// authorized local directory context. Cursor state never leaves encrypted.
func (v *catalogView) containerAccess(ctx context.Context, library *medialibrary.Library, itemID, seriesID, seasonID string, p *catalogVisibilityCursor) (catalogAccess, bool, bool, *catalogVisibilityCursor, error) {
	if p == nil {
		p = &catalogVisibilityCursor{ItemID: itemID, Stage: "episodes", Season: -1, Episode: -1}
	}
	for p.Stage != "context" {
		if p.EpisodeID != "" {
			access, visible, complete, next, err := v.leafAccess(ctx, library, p.EpisodeID, p.SourceAfter)
			if err != nil {
				return access, false, false, p, err
			}
			if !complete {
				p.SourceAfter = next
				return access, false, false, p, nil
			}
			if visible {
				return access, true, true, nil, nil
			}
			p.EpisodeID, p.SourceAfter = "", ""
		}
		if v.budget.remaining <= catalogContinuationReserve || !v.budget.take() {
			return catalogAccess{}, false, false, p, nil
		}
		items, _, err := v.d.store.MediaLibrary.ListSeriesEpisodeCandidates(ctx, v.d.user.ID, library.ID, seriesID, seasonID, p.Season, p.Episode, 1)
		if err != nil {
			return catalogAccess{}, false, false, p, err
		}
		if len(items) == 0 {
			p.Stage = "context"
			p.SourceAfter = ""
			break
		}
		p.EpisodeID, p.Season, p.Episode = items[0].EpisodeID, items[0].SeasonNumber, items[0].EpisodeNumber
	}
	access, visible, complete, next, err := v.contextAccess(ctx, library, itemID, p.SourceAfter)
	if !complete {
		p.SourceAfter = next
		return access, false, false, p, err
	}
	return access, visible, true, nil, err
}

func (v *catalogView) itemAccess(ctx context.Context, library *medialibrary.Library, item *medialibrary.CatalogItem, p *catalogVisibilityCursor) (catalogAccess, bool, bool, *catalogVisibilityCursor, error) {
	if item.Kind == "series" || item.Kind == "season" {
		seriesID, seasonID := item.ID, ""
		if item.Kind == "season" {
			structure, err := v.d.store.MediaLibrary.GetItemStructure(ctx, v.d.user.ID, library.ID, item.ID)
			if err != nil {
				return catalogAccess{}, false, false, p, err
			}
			seriesID, seasonID = structure.SeriesID, item.ID
			if seriesID == "" {
				return catalogAccess{}, false, true, nil, nil
			}
		}
		access, visible, complete, next, err := v.containerAccess(ctx, library, item.ID, seriesID, seasonID, p)
		if item.Kind == "season" {
			access.fallbackTitle = "季"
		} else {
			access.fallbackTitle = "剧集"
		}
		return access, visible, complete, next, err
	}
	after := ""
	if p != nil {
		after = p.SourceAfter
	}
	access, visible, complete, next, err := v.leafAccess(ctx, library, item.ID, after)
	if !complete {
		return access, false, false, &catalogVisibilityCursor{ItemID: item.ID, SourceAfter: next}, err
	}
	return access, visible, true, nil, err
}

type catalogRef struct {
	LibraryID string `json:"libraryId"`
	ItemID    string `json:"itemId"`
}
type catalogArtwork struct {
	AssetID  string `json:"assetId"`
	Revision int64  `json:"revision"`
	Role     string `json:"role"`
	Endpoint string `json:"endpoint"`
}
type catalogRating struct {
	Provider       string   `json:"provider"`
	Metric         string   `json:"metric"`
	Value          *float64 `json:"value"`
	Scale          *float64 `json:"scale"`
	Audience       string   `json:"audience,omitempty"`
	Count          *int64   `json:"count"`
	CountKind      string   `json:"countKind,omitempty"`
	CountPrecision string   `json:"countPrecision,omitempty"`
}
type catalogDuration struct {
	Seconds   float64 `json:"seconds"`
	Basis     string  `json:"basis"`
	EditionID string  `json:"editionId,omitempty"`
}
type catalogEpisodeCounts struct {
	Collected         *int    `json:"collected"`
	Available         *int    `json:"available"`
	Specials          *int    `json:"specials"`
	UnmappedFileCount *int    `json:"unmappedFileCount"`
	Total             *int    `json:"total"`
	TotalBasis        *string `json:"totalBasis"`
	OrderID           string  `json:"orderId,omitempty"`
	CountState        string  `json:"countState"`
	Endpoint          string  `json:"endpoint,omitempty"`
	visibleUpdatedAt  *int64
}

func deferCatalogCounts(counts *catalogEpisodeCounts) {
	counts.Collected, counts.Available, counts.Specials = nil, nil, nil
	counts.OrderID = ""
	counts.CountState = "deferred"
	counts.visibleUpdatedAt = nil
}

type catalogCard struct {
	Ref                      catalogRef              `json:"ref"`
	Revision                 int64                   `json:"revision"`
	StructureKind            string                  `json:"structureKind"`
	ContentCategories        []string                `json:"contentCategories"`
	MatchState               string                  `json:"matchState"`
	Title                    string                  `json:"title"`
	OriginalTitle            *string                 `json:"originalTitle"`
	Year                     *int                    `json:"year"`
	DisplayMode              string                  `json:"displayMode"`
	Artwork                  *catalogArtwork         `json:"artwork"`
	Backdrop                 *catalogArtwork         `json:"backdrop,omitempty"`
	PrimaryRating            *catalogRating          `json:"primaryRating"`
	Duration                 *catalogDuration        `json:"duration"`
	Episodes                 *catalogEpisodeCounts   `json:"episodes"`
	Availability             string                  `json:"availability"`
	MetadataState            string                  `json:"metadataState"`
	FirstAddedAt             int64                   `json:"firstAddedAt"`
	SeriesUpdatedAt          *int64                  `json:"seriesUpdatedAt"`
	Size                     *int64                  `json:"size"`
	Extension                string                  `json:"extension,omitempty"`
	UserState                *medialibrary.UserState `json:"userState"`
	CanActions               map[string]bool         `json:"canActions"`
	ActionReasons            map[string]string       `json:"actionReasons"`
	classificationIncomplete bool
	structureRevision        int64
}

func catalogAvailability(state mediascan.ReferenceState) string {
	switch state {
	case mediascan.ReferenceAvailable, mediascan.ReferenceUnverified:
		return "available"
	case mediascan.ReferenceOffline:
		return "offline"
	case mediascan.ReferenceChanged:
		return "checking"
	default:
		return "checking"
	}
}

func catalogActions(library *medialibrary.Library, kind string, playable, mediaDevice bool, availability string) (map[string]bool, map[string]string) {
	actions := map[string]bool{"play": playable && (kind == "video" || kind == "movie" || kind == "episode"), "openDetails": true, "editMetadata": library.Role == medialibrary.RoleOwner || library.Role == medialibrary.RoleEditor, "manageSources": library.Role == medialibrary.RoleOwner, "manageMembers": library.Role == medialibrary.RoleOwner}
	if mediaDevice {
		actions["editMetadata"], actions["manageSources"], actions["manageMembers"] = false, false, false
	}
	reasons := map[string]string{}
	if !actions["play"] {
		if kind == "series" || kind == "season" {
			reasons["play"] = "choose_episode"
		} else {
			switch availability {
			case "offline":
				reasons["play"] = "offline"
			case "missing":
				reasons["play"] = "missing"
			default:
				reasons["play"] = "verification_required"
			}
		}
	}
	actions["favorite"], actions["markWatched"] = true, true
	for _, name := range []string{"resume", "startOver", "searchMatch", "confirmMatch", "changeOrder", "refreshMetadata", "retryArtwork", "removeFromContinue"} {
		actions[name] = false
		reasons[name] = "feature_unavailable"
	}
	for _, name := range []string{"editMetadata", "manageSources", "manageMembers"} {
		if !actions[name] {
			reasons[name] = "not_granted"
		}
	}
	return actions, reasons
}

func (v *catalogView) projectCard(ctx context.Context, library *medialibrary.Library, c medialibrary.BrowseCandidate, access catalogAccess, fullCounts bool) (catalogCard, error) {
	detail, err := v.d.store.MediaLibrary.GetItemDetail(ctx, v.d.user.ID, library.ID, c.ID)
	if err != nil {
		return catalogCard{}, err
	}
	if detail.Item.Revision != c.Revision {
		return catalogCard{}, medialibrary.ErrRevisionConflict
	}
	if c.Kind == "series" {
		access.fallbackTitle = "剧集"
	}
	filtered, err := itemDetailFields(ctx, v.d, library, detail, &access.mediaItemAccess)
	if err != nil {
		return catalogCard{}, err
	}
	card := catalogCard{Ref: catalogRef{library.ID, c.ID}, Revision: c.Revision, StructureKind: c.Kind, ContentCategories: []string{}, MatchState: "unmatched", Title: filtered.Item.Title, DisplayMode: "unmatched", Availability: catalogAvailability(access.sourceState), MetadataState: access.metadataState, FirstAddedAt: c.FirstAddedAt, SeriesUpdatedAt: c.SeriesUpdatedAt, Size: access.size, Extension: access.extension, structureRevision: c.StructureRevision}
	if access.contextOnly && card.Availability == "available" {
		card.Availability = "missing"
	}
	if c.Kind == "video" {
		movie, review, complete, err := v.movieNFOState(ctx, library, c.ID, &access.mediaItemAccess)
		if err != nil {
			return catalogCard{}, err
		}
		if !complete {
			card.MetadataState = "partial"
			card.classificationIncomplete = true
		}
		if complete && review {
			card.MatchState = "needs_review"
		} else if complete && movie {
			card.StructureKind = "movie"
			card.MatchState = "local_confirmed"
			card.DisplayMode = "poster"
			card.Size = nil
			card.Extension = ""
			if card.MetadataState == "none" {
				card.MetadataState = "available"
			}
		}
		if card.MatchState == "unmatched" && access.sourceFileID != "" {
			evidence, err := v.d.store.MediaLibrary.GetFileIdentification(ctx, v.d.user.ID, library.ID, access.sourceFileID, access.bindingID, access.proofEpoch)
			if err != nil && !errors.Is(err, medialibrary.ErrNotFound) {
				return catalogCard{}, err
			}
			if err == nil && evidence.MatchState == "needs_review" {
				for _, issue := range evidence.Name.Ambiguities {
					if issue != "missing_title" && issue != "combined_episode_file" {
						card.MatchState = "needs_review"
						break
					}
				}
			}
		}
	}
	if c.Kind == "series" || c.Kind == "season" || c.Kind == "movie" {
		card.DisplayMode = "poster"
		card.Size = nil
		card.Extension = ""
	}
	if c.Kind == "episode" {
		card.DisplayMode = "landscape"
	}
	if field, ok := filtered.Fields["originaltitle"]; ok && len(field.Values) > 0 {
		value := field.Values[0]
		card.OriginalTitle = &value
	}
	if field, ok := filtered.Fields["year"]; ok && len(field.Values) > 0 {
		if year, err := strconv.Atoi(field.Values[0]); err == nil && year >= 1800 && year <= 2199 {
			card.Year = &year
		}
	}
	for _, field := range filtered.Fields {
		if field.InputID != "" && card.MetadataState == "none" {
			card.MetadataState = "available"
			break
		}
	}
	if access.metadataState == "restricted" || access.metadataState == "offline" || access.metadataState == "partial" {
		card.MetadataState = access.metadataState
	}
	if card.MetadataState == "none" {
		card.MetadataState = "unmatched"
	}
	if (c.Kind == "series" || c.Kind == "season") && c.MatchState == "local_confirmed" {
		allowed, complete := access.contextOnly, true
		var err error
		if !allowed {
			_, allowed, complete, _, err = v.contextAccess(ctx, library, c.ID, "")
		}
		if err != nil {
			return catalogCard{}, err
		}
		if complete && allowed {
			card.MatchState = "local_confirmed"
		} else if !complete {
			card.MetadataState = "partial"
			card.classificationIncomplete = true
		}
	}
	if c.Kind == "episode" && c.MatchState == "local_confirmed" && access.sourceFileID != "" {
		card.MatchState = "local_confirmed"
	}
	if c.Kind == "movie" && c.MatchState != "unmatched" && card.MetadataState == "available" {
		card.MatchState = c.MatchState
	}
	for _, art := range detail.Artwork {
		var target **catalogArtwork
		switch art.Role {
		case "poster":
			target = &card.Artwork
		case "backdrop":
			target = &card.Backdrop
		default:
			continue
		}
		if *target != nil || art.Status != "declared" {
			continue
		}
		allowed, err := metadataInputAllowed(ctx, v.d, library, c.ID, &access.mediaItemAccess, art.InputID)
		if err != nil {
			return catalogCard{}, err
		}
		if allowed {
			*target = &catalogArtwork{AssetID: art.ID, Revision: c.Revision, Role: art.Role, Endpoint: "/api/media-library/v1/libraries/" + library.ID + "/items/" + c.ID + "/artwork/" + art.ID}
			if card.Artwork != nil && card.Backdrop != nil {
				break
			}
		}
	}
	best := 99
	for _, observation := range detail.Ratings {
		rating := observation.Rating
		rank := 99
		switch rating.Provider {
		case "douban":
			rank = 0
		case "imdb":
			rank = 1
		case "rottentomatoes":
			if rating.Metric == "critic_approval" || rating.Metric == "audience_approval" || rating.Metric == "critic_average" {
				rank = 2
			}
		}
		if rank >= best || rating.Value == nil || rating.Scale == nil {
			continue
		}
		allowed, err := metadataInputAllowed(ctx, v.d, library, c.ID, &access.mediaItemAccess, observation.InputID)
		if err != nil {
			return catalogCard{}, err
		}
		if allowed {
			best = rank
			card.PrimaryRating = &catalogRating{rating.Provider, string(rating.Metric), rating.Value, rating.Scale, rating.Audience, rating.Count, rating.CountKind, rating.CountPrecision}
		}
	}
	if c.Kind == "series" {
		if fullCounts {
			card.Episodes = v.seriesCounts(ctx, library, c.ID, "")
		} else {
			card.Episodes = v.cardCounts(ctx, library, c.ID, "")
		}
		card.SeriesUpdatedAt = card.Episodes.visibleUpdatedAt
		current, err := v.d.store.MediaLibrary.GetItemStructure(ctx, v.d.user.ID, library.ID, c.ID)
		if err != nil {
			return catalogCard{}, err
		}
		if current.Revision != c.StructureRevision {
			deferCatalogCounts(card.Episodes)
			card.SeriesUpdatedAt = nil
		}
	}
	if access.metadataState == "restricted" || access.metadataState == "offline" || access.metadataState == "partial" {
		card.MetadataState = access.metadataState
	}
	if access.budgetExhausted {
		card.MetadataState = "partial"
		card.classificationIncomplete = true
	}
	card.UserState, err = v.d.store.MediaLibrary.GetUserState(ctx, v.d.user.ID, library.ID, c.ID)
	if err != nil {
		return catalogCard{}, err
	}
	card.CanActions, card.ActionReasons = catalogActions(library, card.StructureKind, access.playable, v.d.deviceSession != "", card.Availability)
	return card, nil
}

func (v *catalogView) cardCounts(ctx context.Context, library *medialibrary.Library, seriesID, seasonID string) *catalogEpisodeCounts {
	allowance := min(64, v.budget.remaining)
	child := &catalogView{d: v.d, budget: &catalogBudget{remaining: allowance}, checks: v.checks, coverage: v.coverage, metadataChecks: v.metadataChecks}
	counts := child.seriesCounts(ctx, library, seriesID, seasonID)
	v.budget.remaining -= allowance - child.budget.remaining
	return counts
}

func (v *catalogView) seriesCounts(ctx context.Context, library *medialibrary.Library, seriesID, seasonID string) *catalogEpisodeCounts {
	endpoint := "/api/media-library/v1/libraries/" + library.ID + "/items/" + seriesID + "/episode-counts"
	if seasonID != "" {
		endpoint += "?seasonId=" + seasonID
	}
	counts := &catalogEpisodeCounts{CountState: "deferred", Endpoint: endpoint}
	collected, available, specials := 0, 0, 0
	var updated *int64
	var orderID string
	var orderRevision int64
	afterSeason, afterEpisode := -1, -1
	// Large series defer counts rather than spending
	// the entire page budget or publishing a partially authorized count.
	for seen := 0; seen < 400; seen++ {
		if !v.budget.take() {
			return counts
		}
		items, _, err := v.d.store.MediaLibrary.ListSeriesEpisodeCandidates(ctx, v.d.user.ID, library.ID, seriesID, seasonID, afterSeason, afterEpisode, 1)
		if err != nil {
			counts.CountState = "error"
			return counts
		}
		if len(items) == 0 {
			if orderID != "" {
				current, err := v.d.store.MediaLibrary.ListStructureSeasons(ctx, v.d.user.ID, library.ID, seriesID, nil, 1)
				if err != nil {
					counts.CountState = "error"
					return counts
				}
				if current.OrderID != orderID || current.OrderRevision != orderRevision {
					return counts
				}
			}
			counts.Collected = &collected
			counts.Available = &available
			counts.Specials = &specials
			counts.CountState = "ready"
			counts.visibleUpdatedAt = updated
			return counts
		}
		item := items[0]
		if orderID == "" {
			orderID, orderRevision = item.OrderID, item.OrderRevision
		} else if orderID != item.OrderID || orderRevision != item.OrderRevision {
			return counts
		}
		access, visible, complete, _, err := v.leafAccess(ctx, library, item.EpisodeID, "")
		if err != nil {
			counts.CountState = "error"
			return counts
		}
		if !complete || access.sourceState == mediascan.ReferenceState("checking") {
			return counts
		}
		if visible {
			counts.OrderID = item.OrderID
			if updated == nil || item.FirstAddedAt > *updated {
				value := item.FirstAddedAt
				updated = &value
			}
			if item.SeasonNumber == 0 {
				specials++
			} else {
				collected++
				if access.playable {
					available++
				}
			}
		}
		afterSeason, afterEpisode = item.SeasonNumber, item.EpisodeNumber
	}
	return counts
}
