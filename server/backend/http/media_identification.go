package fbhttp

import (
	"errors"
	"net/http"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

type mediaIdentificationFile struct {
	Filename       string                         `json:"filename"`
	ParsedTitle    string                         `json:"parsedTitle"`
	Year           *int                           `json:"year,omitempty"`
	TypeHint       string                         `json:"typeHint"`
	ProviderIDs    []mediametadata.NameProviderID `json:"providerIds,omitempty"`
	SeasonEpisodes []mediametadata.SeasonEpisode  `json:"seasonEpisodes,omitempty"`
	Absolute       []int                          `json:"absoluteEpisodes,omitempty"`
	DateHint       string                         `json:"dateHint,omitempty"`
	State          string                         `json:"state"`
	Issues         []string                       `json:"issues,omitempty"`
	Ambiguities    []string                       `json:"ambiguities,omitempty"`
	Revision       int64                          `json:"revision"`
}

type mediaIdentificationResponse struct {
	Ref               catalogRef                        `json:"ref"`
	Title             string                            `json:"title"`
	StructureKind     string                            `json:"structureKind"`
	MatchState        string                            `json:"matchState"`
	StructureRevision int64                             `json:"structureRevision"`
	ItemRevision      int64                             `json:"itemRevision"`
	ExternalIDs       []medialibrary.MetadataExternalID `json:"externalIds"`
	ExternalIDsCursor string                            `json:"externalIdsNextCursor,omitempty"`
	File              *mediaIdentificationFile          `json:"file,omitempty"`
}

var mediaIdentificationHandler = mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	result, _, err := mediaIdentification(r, d)
	if err != nil {
		return catalogError(w, err)
	}
	return renderJSON(w, r, result)
})

func mediaIdentification(r *http.Request, d *data) (mediaIdentificationResponse, *catalogView, error) {
	var result mediaIdentificationResponse
	ctx := r.Context()
	library, err := itemLibrary(r, d)
	if err != nil {
		return result, nil, err
	}
	itemID := mux.Vars(r)["itemId"]
	detail, err := d.store.MediaLibrary.GetItemDetail(ctx, d.user.ID, library.ID, itemID)
	if err != nil {
		return result, nil, err
	}
	view := newCatalogView(d)
	access, visible, complete, _, err := view.itemAccess(ctx, library, &detail.Item, nil)
	if err != nil {
		return result, nil, err
	}
	if !complete {
		return result, nil, errCatalogWorkPending
	}
	if !visible {
		return result, nil, medialibrary.ErrNotFound
	}
	card, err := view.structureCard(ctx, library, itemID, access)
	if err != nil {
		return result, nil, err
	}
	if card.classificationIncomplete {
		return result, nil, errCatalogWorkPending
	}
	if card.Revision != detail.Item.Revision {
		return result, nil, medialibrary.ErrRevisionConflict
	}
	ids, err := visibleExternalIDs(ctx, d, library, itemID, card.Revision, &access.mediaItemAccess, "", 20)
	if err != nil {
		return result, nil, err
	}
	if access.budgetExhausted {
		return result, nil, errCatalogWorkPending
	}
	result = mediaIdentificationResponse{
		Ref: card.Ref, Title: card.Title, StructureKind: card.StructureKind, MatchState: card.MatchState,
		StructureRevision: card.structureRevision, ItemRevision: card.Revision,
		ExternalIDs: ids.Items, ExternalIDsCursor: ids.NextCursor,
	}
	// A container's accessible descendant proves visibility. Its filename
	// cannot supply the container's own candidate query or explicit IDs.
	leaf := detail.Item.Kind != "series" && detail.Item.Kind != "season"
	if leaf && access.sourceFileID != "" && access.sourceState != mediascan.ReferenceChanged {
		evidence, err := d.store.MediaLibrary.GetFileIdentification(ctx, d.user.ID, library.ID, access.sourceFileID, access.bindingID, access.proofEpoch)
		if err != nil && !errors.Is(err, medialibrary.ErrNotFound) {
			return result, nil, err
		}
		if evidence != nil {
			name := evidence.Name
			result.File = &mediaIdentificationFile{
				Filename: name.OriginalName, ParsedTitle: name.Title, Year: name.Year, TypeHint: name.TypeHint,
				ProviderIDs: name.ProviderIDs, SeasonEpisodes: name.SeasonEpisodes, Absolute: name.AbsoluteEpisodes,
				DateHint: name.DateHint, State: evidence.MatchState, Issues: evidence.Issues,
				Ambiguities: name.Ambiguities, Revision: evidence.Revision,
			}
		}
	}
	if err = view.revalidateSources(ctx); err != nil {
		return result, nil, err
	}
	if err = verifyCatalogCards(ctx, d, []catalogCard{card}); err != nil {
		return result, nil, err
	}
	return result, view, nil
}
