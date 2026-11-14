package fbhttp

import (
	"net/http"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
)

var mediaMatchUndoHandler = providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body struct {
		OperationID       string `json:"operationId"`
		ItemRevision      int64  `json:"itemRevision"`
		StructureRevision int64  `json:"structureRevision"`
		MatchRevision     int64  `json:"matchRevision"`
	}
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) ||
		body.ItemRevision < 1 || body.StructureRevision < 1 || body.MatchRevision < 1 {
		return providerError(w, medialibrary.ErrInvalid)
	}
	ctx := r.Context()
	item, view, err := mediaIdentification(r, d)
	if err != nil {
		return providerError(w, err)
	}
	snapshot, err := beginCatalogSnapshot(ctx, d, item.Ref.LibraryID)
	if err != nil {
		return providerError(w, err)
	}
	if err = view.revalidateSources(ctx); err != nil {
		return providerError(w, err)
	}
	receipt, err := d.store.MediaLibrary.UndoProviderMatch(ctx, d.user.ID, body.OperationID, medialibrary.ProviderUndoRequest{
		LibraryID: item.Ref.LibraryID, ItemID: item.Ref.ItemID, ItemRevision: body.ItemRevision,
		StructureRevision: body.StructureRevision, MatchRevision: body.MatchRevision,
	})
	if err != nil {
		return providerError(w, err)
	}
	if verifyCatalogSnapshot(ctx, d, snapshot) != nil || view.revalidateSources(ctx) != nil {
		return providerUnknown(w)
	}
	return renderJSON(w, r, receipt)
})
