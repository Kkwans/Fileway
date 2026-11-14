package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"path"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"github.com/gorilla/mux"
)

type mediaItemSummary struct {
	ID            string                   `json:"id"`
	LibraryID     string                   `json:"libraryId"`
	Kind          string                   `json:"kind"`
	ParentID      string                   `json:"parentId,omitempty"`
	Title         string                   `json:"title"`
	Revision      int64                    `json:"revision"`
	CreatedAt     int64                    `json:"createdAt"`
	SourceState   mediascan.ReferenceState `json:"sourceState"`
	MetadataState string                   `json:"metadataState"`
}

type mediaItemDetail struct {
	Item                  mediaItemSummary                      `json:"item"`
	Card                  *catalogCard                          `json:"card,omitempty"`
	UserState             *medialibrary.UserState               `json:"userState,omitempty"`
	PageState             string                                `json:"pageState,omitempty"`
	MetadataRevision      int64                                 `json:"metadataRevision"`
	Fields                map[string]medialibrary.MetadataField `json:"fields"`
	Credits               []medialibrary.MetadataCredit         `json:"credits"`
	Ratings               []medialibrary.MetadataRating         `json:"ratings"`
	Artwork               []medialibrary.MetadataArtwork        `json:"artwork"`
	ExternalIDs           []medialibrary.MetadataExternalID     `json:"externalIds"`
	Issues                []string                              `json:"issues"`
	Candidates            []mediaMetadataCandidate              `json:"candidates"`
	CreditsNextCursor     string                                `json:"creditsNextCursor,omitempty"`
	RatingsNextCursor     string                                `json:"ratingsNextCursor,omitempty"`
	ArtworkNextCursor     string                                `json:"artworkNextCursor,omitempty"`
	ExternalIDsNextCursor string                                `json:"externalIdsNextCursor,omitempty"`
	IssuesNextCursor      string                                `json:"issuesNextCursor,omitempty"`
	OnlineMatch           *mediaOnlineMatch                     `json:"onlineMatch,omitempty"`
	providerMatch         *medialibrary.ProviderMatch
}

type mediaOnlineMatch struct {
	Provider   string `json:"provider"`
	Kind       string `json:"kind"`
	ExternalID string `json:"externalId"`
	FetchedAt  int64  `json:"fetchedAt"`
}

type mediaMetadataCandidate struct {
	Code        string   `json:"code"`
	SourceKinds []string `json:"sourceKinds"`
}

type mediaItemAccess struct {
	sourceState                        mediascan.ReferenceState
	metadataState                      string
	fallbackTitle                      string
	allowedInputs                      map[string]bool
	size                               *int64
	extension, sourceFileID, bindingID string
	proofEpoch                         int64
	playable                           bool
	budget                             *catalogBudget
	budgetExhausted                    bool
	view                               *catalogView
}

func itemSummary(item medialibrary.CatalogItem, access mediaItemAccess) mediaItemSummary {
	return mediaItemSummary{ID: item.ID, LibraryID: item.LibraryID, Kind: item.Kind, ParentID: item.ParentID,
		Title: item.Title, Revision: item.Revision, CreatedAt: item.CreatedAt, SourceState: access.sourceState, MetadataState: access.metadataState}
}

func itemDetailFields(ctx context.Context, d *data, library *medialibrary.Library, detail *medialibrary.ItemDetail, access *mediaItemAccess) (mediaItemDetail, error) {
	fields := make(map[string]medialibrary.MetadataField, len(detail.Fields))
	for name, field := range detail.Fields {
		if field.InputID == "" && field.Source == "filename" {
			if name == "title" {
				// Stored filename evidence may belong to another binding whose
				// permission was revoked. Reconstruct only from the selected,
				// currently authorized video's basename.
				field.Values = []string{access.fallbackTitle}
				fields[name] = field
			}
			continue
		}
		allowed := field.InputID == "" && field.Source == "manual"
		if field.InputID != "" {
			var err error
			allowed, err = metadataInputAllowed(ctx, d, library, detail.Item.ID, access, field.InputID)
			if err != nil {
				return mediaItemDetail{}, err
			}
		}
		if allowed {
			fields[name] = field
		} else {
			if access.budgetExhausted {
				access.metadataState = "partial"
			} else {
				access.metadataState = "restricted"
			}
		}
	}
	item := detail.Item
	match, err := d.store.MediaLibrary.VisibleProviderMatch(ctx, d.user.ID, library.ID, item.ID, item.Revision)
	if err != nil {
		return mediaItemDetail{}, err
	}
	if match != nil {
		for name, values := range match.Document.Item.Fields {
			current, exists := fields[name]
			if current.ExplicitClear || current.Locked || detail.Fields[name].ExplicitClear || detail.Fields[name].Locked {
				continue
			}
			if !exists || match.OnlineFirst || current.Source == "filename" {
				fields[name] = medialibrary.MetadataField{Values: values, Source: "online", Revision: match.Revision}
			}
		}
		if access.metadataState == "none" {
			access.metadataState = "available"
		}
	}
	if title, allowed := fields["title"]; allowed && title.ExplicitClear {
		item.Title = ""
	} else if allowed && len(title.Values) > 0 {
		item.Title = title.Values[0]
	} else {
		item.Title = access.fallbackTitle
	}
	return mediaItemDetail{Item: itemSummary(item, *access), MetadataRevision: detail.MetadataRevision, Fields: fields, providerMatch: match,
		Credits: []medialibrary.MetadataCredit{}, Ratings: []medialibrary.MetadataRating{}, Artwork: []medialibrary.MetadataArtwork{}, ExternalIDs: []medialibrary.MetadataExternalID{}, Issues: []string{}, Candidates: []mediaMetadataCandidate{}}, nil
}

func itemLibrary(r *http.Request, d *data) (*medialibrary.Library, error) {
	library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, mux.Vars(r)["id"])
	if err != nil {
		return nil, err
	}
	if library.ArchivedAt != nil || !mediaLibraryAccessAllowed(d, library) {
		return nil, medialibrary.ErrNotFound
	}
	return library, nil
}

// ownerBinding re-resolves the source principal independently of the viewer.
// Viewer membership remains checked by each Store query; no file permissions
// are copied to the viewer or inferred from the library association.
func ownerBinding(ctx context.Context, d *data, library *medialibrary.Library, bindingID string, ref medialibrary.SourceBindingRef, source medialibrary.LocalSource) (*medialibrary.LibrarySource, error) {
	if source.OwnerUserID != library.OwnerUserID {
		return nil, fs.ErrPermission
	}
	binding, err := d.store.MediaLibrary.GetLibrarySourceByID(ctx, source.OwnerUserID, bindingID)
	if err != nil {
		return nil, err
	}
	current := binding.Ref()
	if binding.LibraryID != library.ID || binding.Source.OwnerUserID != source.OwnerUserID || binding.Source.ConfigurationRef != source.ConfigurationRef ||
		current.BindingID != ref.BindingID || current.ProofEpoch != ref.ProofEpoch || current.SourceID != ref.SourceID || current.BindingRevision != ref.BindingRevision || current.AuthorizationRevision != ref.AuthorizationRevision || !bytes.Equal(current.RootLocator, ref.RootLocator) {
		return nil, fs.ErrPermission
	}
	return binding, nil
}

func ownerReference(ctx context.Context, d *data, binding *medialibrary.LibrarySource, locator, objectKey []byte, identityKind, version string) (mediascan.ReferenceState, error) {
	state, err := mediascan.CheckLocalReference(ctx, binding.RootLocator, locator, objectKey, identityKind, version, func(ctx context.Context) (mediasource.LocalAuthority, error) {
		authority, _, err := resolveAttestedMediaConnection(ctx, d.store, d.server, binding.Source.OwnerUserID, binding.Source.ConfigurationRef, binding.RootLocator, binding.Proof)
		return authority, err
	})
	if err != nil {
		return "", err
	}
	// A scope or root replacement while checking an object cannot authorize old
	// metadata. Re-resolve the proof immediately before using that result.
	if _, _, err := resolveAttestedMediaConnection(ctx, d.store, d.server, binding.Source.OwnerUserID, binding.Source.ConfigurationRef, binding.RootLocator, binding.Proof); err != nil {
		return "", err
	}
	return state, nil
}

func sourceStateRank(state mediascan.ReferenceState) int {
	switch state {
	case mediascan.ReferenceAvailable:
		return 0
	case mediascan.ReferenceUnverified:
		return 1
	case mediascan.ReferenceChanged:
		return 2
	default:
		return 3
	}
}

const metadataAccessCacheLimit = 128

func metadataSourceAllowed(ctx context.Context, d *data, library *medialibrary.Library, entry medialibrary.ItemMetadataSourceAccess) (mediascan.ReferenceState, bool, error) {
	binding, err := ownerBinding(ctx, d, library, entry.BindingID, entry.Binding, entry.Source)
	if err != nil {
		if errors.Is(err, medialibrary.ErrNotFound) || errors.Is(err, fs.ErrPermission) {
			return "", false, nil
		}
		return "", false, err
	}
	state, err := ownerReference(ctx, d, binding, entry.SourceKey, nil, "", "")
	if err != nil {
		if ctx.Err() != nil {
			return "", false, ctx.Err()
		}
		return "", false, nil
	}
	if state == mediascan.ReferenceOffline && binding.Proof.RootIdentityKind == "virtual" {
		return state, false, nil
	}
	return state, true, nil
}

func metadataInputAllowed(ctx context.Context, d *data, library *medialibrary.Library, itemID string, access *mediaItemAccess, inputID string) (bool, error) {
	if inputID == "" {
		return false, nil
	}
	if allowed, exists := access.allowedInputs[inputID]; exists {
		return allowed, nil
	}
	if access.budget != nil && !access.budget.take() {
		access.budgetExhausted = true
		return false, errCatalogWorkPending
	}
	entry, err := d.store.MediaLibrary.GetItemMetadataSourceAccess(ctx, d.user.ID, library.ID, itemID, inputID)
	if err != nil {
		if errors.Is(err, medialibrary.ErrNotFound) {
			access.metadataState = "restricted"
			return false, nil
		}
		return false, err
	}
	var state mediascan.ReferenceState
	var allowed bool
	if access.view != nil {
		state, allowed, err = access.view.checkMetadata(ctx, library, *entry)
	} else {
		state, allowed, err = metadataSourceAllowed(ctx, d, library, *entry)
	}
	if err == nil {
		if !allowed {
			access.metadataState = "restricted"
		} else if state == mediascan.ReferenceOffline && access.metadataState != "restricted" {
			access.metadataState = "offline"
		} else if access.metadataState == "none" {
			access.metadataState = "available"
		}
	}
	if len(access.allowedInputs) < metadataAccessCacheLimit {
		access.allowedInputs[inputID] = allowed
	}
	return allowed, err
}

// itemAccess filters before publication and never exposes a hidden source
// count. Each NFO-derived field, credit, rating, artwork declaration and
// candidate is independently filtered by its current source authorization.
func itemAccess(ctx context.Context, d *data, library *medialibrary.Library, itemID string) (mediaItemAccess, bool, error) {
	access := mediaItemAccess{metadataState: "none", allowedInputs: make(map[string]bool)}
	var visible bool
	after := ""
	for {
		page, err := d.store.MediaLibrary.ListItemSourceAccess(ctx, d.user.ID, library.ID, itemID, after, 100)
		if err != nil {
			return access, false, err
		}
		for _, entry := range page.Items {
			binding, err := ownerBinding(ctx, d, library, entry.BindingID, entry.Binding, entry.Source)
			if err != nil {
				if errors.Is(err, medialibrary.ErrNotFound) || errors.Is(err, fs.ErrPermission) {
					continue
				}
				return access, false, err
			}
			state, err := ownerReference(ctx, d, binding, entry.Locator, entry.ObjectKey, entry.IdentityKind, entry.SourceVersion)
			if err != nil {
				if ctx.Err() != nil {
					return access, false, ctx.Err()
				}
				continue // A denied or unresolvable source cannot grant visibility.
			}
			if state == mediascan.ReferenceReplaced || state == mediascan.ReferenceOffline && binding.Proof.RootIdentityKind == "virtual" {
				// Virtual-computer roots require selected-volume evidence before
				// an offline object can be attributed to its previous drive.
				continue
			}
			if !visible || sourceStateRank(state) < sourceStateRank(access.sourceState) {
				access.sourceState = state
				access.fallbackTitle = mediascan.DisplayFilename(path.Base(string(entry.Locator)))
				access.size = nil
				if state == mediascan.ReferenceAvailable {
					access.size = entry.SizeBytes
				}
				access.extension = strings.ToLower(strings.TrimPrefix(path.Ext(string(entry.Locator)), "."))
				access.sourceFileID, access.bindingID, access.proofEpoch = entry.SourceFileID, entry.BindingID, entry.Binding.ProofEpoch
				access.playable = state == mediascan.ReferenceAvailable || state == mediascan.ReferenceUnverified
			}
			visible = true
		}
		if page.NextCursor == "" {
			break
		}
		if page.NextCursor == after {
			return access, false, fs.ErrInvalid
		}
		after = page.NextCursor
	}
	if !visible {
		return access, false, nil
	}
	if err := loadItemMetadataAccess(ctx, d, library, itemID, &access); err != nil {
		return access, false, err
	}
	return access, true, nil
}

// Source visibility and metadata provenance are independent, including for
// series/season containers whose visibility was established via descendants.
func loadItemMetadataAccess(ctx context.Context, d *data, library *medialibrary.Library, itemID string, access *mediaItemAccess) error {
	if access.allowedInputs == nil {
		access.allowedInputs = make(map[string]bool)
	}
	after := ""
	for {
		limit := 100
		if access.budget != nil {
			if access.budget.remaining <= 0 {
				return errCatalogWorkPending
			}
			limit = min(limit, access.budget.remaining)
		}
		page, err := d.store.MediaLibrary.ListItemMetadataSourceAccess(ctx, d.user.ID, library.ID, itemID, after, limit)
		if err != nil {
			return err
		}
		for _, entry := range page.Items {
			if access.budget != nil && !access.budget.take() {
				return errCatalogWorkPending
			}
			var state mediascan.ReferenceState
			var allowed bool
			if access.view != nil {
				state, allowed, err = access.view.checkMetadata(ctx, library, entry)
			} else {
				state, allowed, err = metadataSourceAllowed(ctx, d, library, entry)
			}
			if err != nil {
				return err
			}
			if len(access.allowedInputs) < metadataAccessCacheLimit {
				access.allowedInputs[entry.ID] = allowed
			}
			if !allowed {
				access.metadataState = "restricted"
				continue
			}
			if state == mediascan.ReferenceOffline && access.metadataState != "restricted" {
				access.metadataState = "offline"
			} else if access.metadataState == "none" {
				access.metadataState = "available"
			}
		}
		if page.NextCursor == "" {
			break
		}
		if page.NextCursor == after {
			return fs.ErrInvalid
		}
		after = page.NextCursor
	}
	return nil
}

func verifyItemRevision(ctx context.Context, d *data, library *medialibrary.Library, itemID string, revision int64) error {
	currentLibrary, err := d.store.MediaLibrary.GetLibrary(ctx, d.user.ID, library.ID)
	if err != nil {
		return err
	}
	if currentLibrary.ArchivedAt != nil || !mediaLibraryAccessAllowed(d, currentLibrary) {
		return medialibrary.ErrNotFound
	}
	if currentLibrary.Revision != library.Revision || currentLibrary.Role != library.Role {
		return medialibrary.ErrRevisionConflict
	}
	current, err := d.store.MediaLibrary.GetCatalogItem(ctx, d.user.ID, library.ID, itemID)
	if err != nil {
		return err
	}
	if current.Revision != revision {
		return medialibrary.ErrRevisionConflict
	}
	return nil
}

var mediaItemsListHandler = mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	library, err := itemLibrary(r, d)
	if err != nil {
		return mediaScanError(err)
	}
	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		limit, err = strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > 100 {
			return http.StatusBadRequest, fmt.Errorf("条目分页参数无效")
		}
	}
	after := r.URL.Query().Get("cursor")
	if len(after) > 200 {
		return http.StatusBadRequest, fmt.Errorf("条目分页参数无效")
	}
	items := make([]mediaItemSummary, 0, limit)
	for {
		page, err := d.store.MediaLibrary.ListCatalogItems(r.Context(), d.user.ID, library.ID, medialibrary.CatalogListOptions{Limit: 100, AfterID: after})
		if err != nil {
			return mediaScanError(err)
		}
		for _, item := range page.Items {
			detail, err := d.store.MediaLibrary.GetItemDetail(r.Context(), d.user.ID, library.ID, item.ID)
			if err != nil {
				return mediaScanError(err)
			}
			if detail.Item.Revision != item.Revision {
				return mediaScanError(medialibrary.ErrRevisionConflict)
			}
			access, visible, err := itemAccess(r.Context(), d, library, item.ID)
			if err != nil {
				return mediaScanError(err)
			}
			if !visible {
				continue
			}
			if err := verifyItemRevision(r.Context(), d, library, item.ID, item.Revision); err != nil {
				return mediaScanError(err)
			}
			if len(items) == limit {
				return renderJSON(w, r, struct {
					Items      []mediaItemSummary `json:"items"`
					NextCursor string             `json:"nextCursor,omitempty"`
				}{items, items[len(items)-1].ID})
			}
			filtered, err := itemDetailFields(r.Context(), d, library, detail, &access)
			if err != nil {
				return mediaScanError(err)
			}
			items = append(items, filtered.Item)
		}
		if page.NextCursor == "" {
			break
		}
		if page.NextCursor == after {
			return http.StatusInternalServerError, fmt.Errorf("暂时无法读取影视条目")
		}
		after = page.NextCursor
	}
	return renderJSON(w, r, struct {
		Items []mediaItemSummary `json:"items"`
	}{items})
})

func visibleItemDetail(r *http.Request, d *data, library *medialibrary.Library, itemID string) (*medialibrary.ItemDetail, mediaItemAccess, error) {
	detail, err := d.store.MediaLibrary.GetItemDetail(r.Context(), d.user.ID, library.ID, itemID)
	if err != nil {
		return nil, mediaItemAccess{}, err
	}
	var access mediaItemAccess
	var visible bool
	var containerView *catalogView
	if detail.Item.Kind == "series" || detail.Item.Kind == "season" {
		view := newCatalogView(d)
		containerView = view
		result, ok, complete, _, accessErr := view.itemAccess(r.Context(), library, &detail.Item, nil)
		if accessErr != nil {
			return nil, mediaItemAccess{}, accessErr
		}
		if !complete {
			return nil, mediaItemAccess{}, errCatalogWorkPending
		}
		access, visible = result.mediaItemAccess, ok
	} else {
		access, visible, err = itemAccess(r.Context(), d, library, itemID)
	}
	if err != nil {
		return nil, mediaItemAccess{}, err
	}
	if !visible {
		return nil, mediaItemAccess{}, medialibrary.ErrNotFound
	}
	if detail.Item.Kind == "series" || detail.Item.Kind == "season" {
		if err = loadItemMetadataAccess(r.Context(), d, library, itemID, &access); err != nil {
			return nil, mediaItemAccess{}, err
		}
		if err = containerView.revalidateSources(r.Context()); err != nil {
			return nil, mediaItemAccess{}, err
		}
	}
	if err := verifyItemRevision(r.Context(), d, library, itemID, detail.Item.Revision); err != nil {
		return nil, mediaItemAccess{}, err
	}
	return detail, access, nil
}

var mediaItemGetHandler = mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	library, err := itemLibrary(r, d)
	if err != nil {
		return mediaScanError(err)
	}
	itemID := mux.Vars(r)["itemId"]
	detail, err := d.store.MediaLibrary.GetItemDetail(r.Context(), d.user.ID, library.ID, itemID)
	if err != nil {
		return mediaScanError(err)
	}
	if detail.Item.Kind != "series" && detail.Item.Kind != "season" {
		snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
		if err != nil {
			return catalogError(w, err)
		}
		legacy, access, err := visibleItemDetail(r, d, library, itemID)
		if err != nil {
			return mediaScanError(err)
		}
		view := newCatalogView(d)
		access.budget = view.budget
		access.view = view
		if err = loadItemMetadataAccess(r.Context(), d, library, itemID, &access); err != nil {
			return catalogError(w, err)
		}
		filtered, err := loadVisibleItemDetail(r.Context(), d, library, legacy, &access)
		if err != nil {
			return mediaScanError(err)
		}
		card, err := view.structureCard(r.Context(), library, itemID, catalogAccess{mediaItemAccess: access, size: access.size, extension: access.extension, playable: access.playable, sourceFileID: access.sourceFileID, bindingID: access.bindingID, proofEpoch: access.proofEpoch})
		if err != nil {
			return catalogError(w, err)
		}
		if !card.classificationIncomplete {
			filtered.Card = &card
		}
		filtered.UserState = card.UserState
		filtered.PageState = "ready"
		if card.classificationIncomplete {
			filtered.PageState = "partial_metadata"
		} else if card.Availability == "offline" {
			filtered.PageState = "offline"
		} else if card.MetadataState == "restricted" || card.MetadataState == "unmatched" || card.MetadataState == "offline" || card.MetadataState == "partial" {
			filtered.PageState = "partial_metadata"
		}
		if err = verifyItemRevision(r.Context(), d, library, itemID, legacy.Item.Revision); err != nil {
			return mediaScanError(err)
		}
		if err = view.revalidateSources(r.Context()); err != nil {
			return catalogError(w, err)
		}
		if err = verifyCatalogSnapshot(r.Context(), d, snapshot); err != nil {
			return catalogError(w, err)
		}
		return renderJSON(w, r, filtered)
	}
	query := fmt.Sprintf("detail:%q:%q", library.ID, itemID)
	snapshot, err := beginCatalogSnapshot(r.Context(), d, library.ID)
	if err != nil {
		return catalogError(w, err)
	}
	cursor, err := newCatalogCursor(d, snapshot, query, r.URL.Query().Get("cursor"))
	if err != nil {
		return catalogError(w, err)
	}
	structure, err := d.store.MediaLibrary.GetItemStructure(r.Context(), d.user.ID, library.ID, itemID)
	if err != nil {
		return catalogError(w, err)
	}
	if cursor.Pending != nil && (cursor.Pending.ItemID != itemID || cursor.Pending.ItemRevision != detail.Item.Revision || cursor.Pending.StructureRevision != structure.Revision) {
		return catalogError(w, errCatalogCursorStale)
	}
	view := newCatalogView(d)
	result, visible, complete, pending, err := view.itemAccess(r.Context(), library, &detail.Item, cursor.Pending)
	if err != nil {
		return catalogError(w, err)
	}
	if !complete {
		pending.ItemRevision, pending.StructureRevision = detail.Item.Revision, structure.Revision
		cursor.Pending = pending
		next, err := encodeCatalogCursor(d.settings.Key, cursor)
		if err == nil {
			err = verifyCatalogSnapshot(r.Context(), d, snapshot)
		}
		if err != nil {
			return catalogError(w, err)
		}
		return renderJSON(w, r, struct {
			PageState  string `json:"pageState"`
			NextCursor string `json:"nextCursor"`
			HasMore    bool   `json:"hasMore"`
		}{"partial", next, true})
	}
	if !visible {
		return catalogError(w, medialibrary.ErrNotFound)
	}
	access := result.mediaItemAccess
	if err = loadItemMetadataAccess(r.Context(), d, library, itemID, &access); err != nil {
		return catalogError(w, err)
	}
	filtered, err := loadVisibleItemDetail(r.Context(), d, library, detail, &access)
	if err != nil {
		return mediaScanError(err)
	}
	result.mediaItemAccess = access
	card, err := view.structureCard(r.Context(), library, itemID, result)
	if err != nil {
		return catalogError(w, err)
	}
	if !card.classificationIncomplete {
		filtered.Card = &card
	}
	filtered.UserState = card.UserState
	filtered.PageState = "ready"
	if card.classificationIncomplete {
		filtered.PageState = "partial_metadata"
	} else if card.Availability == "offline" {
		filtered.PageState = "offline"
	} else if card.MetadataState == "restricted" || card.MetadataState == "unmatched" || card.MetadataState == "offline" || card.MetadataState == "partial" {
		filtered.PageState = "partial_metadata"
	}
	if err := verifyItemRevision(r.Context(), d, library, detail.Item.ID, detail.Item.Revision); err != nil {
		return mediaScanError(err)
	}
	if err = view.revalidateSources(r.Context()); err != nil {
		return catalogError(w, err)
	}
	if err = verifyCatalogSnapshot(r.Context(), d, snapshot); err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, filtered)
})

type mediaMetadataPatchBody struct {
	OperationID      string                                     `json:"operationId"`
	ExpectedRevision int64                                      `json:"expectedRevision"`
	Fields           map[string]medialibrary.MetadataFieldPatch `json:"fields"`
}

type mediaMetadataUndoBody struct {
	OperationID      string `json:"operationId"`
	ExpectedRevision int64  `json:"expectedRevision"`
	TargetRevision   int64  `json:"targetRevision"`
}

func decodeMetadataBody(w http.ResponseWriter, r *http.Request, target any) error {
	if r.Body == nil {
		return fs.ErrInvalid
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fs.ErrInvalid
	}
	if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
		return fs.ErrInvalid
	}
	return nil
}

func mediaMetadataChangeHandler(undo bool) handleFunc {
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		library, err := itemLibrary(r, d)
		if err != nil {
			return mediaScanError(err)
		}
		if library.Role != medialibrary.RoleOwner && library.Role != medialibrary.RoleEditor {
			return http.StatusForbidden, fmt.Errorf("没有资料编辑权限")
		}
		itemID := mux.Vars(r)["itemId"]
		current, access, err := visibleItemDetail(r, d, library, itemID)
		if err != nil {
			return mediaScanError(err)
		}
		var result *medialibrary.MetadataMutationResult
		if undo {
			var body mediaMetadataUndoBody
			if decodeMetadataBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision < 1 || body.TargetRevision < 1 {
				return http.StatusBadRequest, fmt.Errorf("资料撤销参数无效")
			}
			if access.metadataState == "restricted" {
				return http.StatusForbidden, fmt.Errorf("请先恢复受限资料的访问权限")
			}
			result, err = d.store.MediaLibrary.UndoMetadata(r.Context(), d.user.ID, library.ID, itemID, body.ExpectedRevision, body.TargetRevision, body.OperationID)
		} else {
			var body mediaMetadataPatchBody
			if decodeMetadataBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision < 1 || len(body.Fields) == 0 {
				return http.StatusBadRequest, fmt.Errorf("资料修改参数无效")
			}
			for name := range body.Fields {
				field, exists := current.Fields[name]
				if !exists {
					continue
				}
				if field.InputID != "" {
					allowed, checkErr := metadataInputAllowed(r.Context(), d, library, itemID, &access, field.InputID)
					if checkErr != nil {
						return mediaScanError(checkErr)
					}
					if !allowed {
						return http.StatusForbidden, fmt.Errorf("请先恢复该字段来源的访问权限")
					}
				} else if field.Source != "manual" && field.Source != "filename" {
					return http.StatusForbidden, fmt.Errorf("请先恢复该字段来源的访问权限")
				}
			}
			result, err = d.store.MediaLibrary.ApplyMetadata(r.Context(), d.user.ID, library.ID, itemID, body.ExpectedRevision, body.OperationID, medialibrary.MetadataPatch{Fields: body.Fields})
		}
		if err != nil {
			return mediaScanError(err)
		}
		// Recheck source and membership after the mutation; a successful receipt
		// must not turn a concurrent authorization revocation into a data leak.
		_, _, err = visibleItemDetail(r, d, library, itemID)
		if err != nil {
			return mediaScanError(err)
		}
		// A receipt contains only stable IDs and revisions. Returning an old
		// full-detail receipt could reveal metadata whose source was since revoked.
		return renderJSON(w, r, struct {
			ItemID           string `json:"itemId"`
			Revision         int64  `json:"revision"`
			MetadataRevision int64  `json:"metadataRevision"`
		}{result.ItemID, result.ItemRevision, result.MetadataRevision})
	})
}
