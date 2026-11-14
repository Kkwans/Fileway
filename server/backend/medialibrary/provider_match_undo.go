package medialibrary

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

// Store the previous provider projection only. Local fields, playback state
// and mappings are deliberately outside the provider undo transaction.
func recordProviderMatchRevision(ctx context.Context, tx *sql.Tx, libraryID, itemID string, matchRevision, structureRevision int64) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO provider_match_revision(library_id,item_id,match_revision,structure_revision,previous_json)
VALUES(?1,?2,?3,?4,(
 SELECT json_object('connectionId',connection_id,'kind',entity_kind,'externalId',external_id,'revision',revision,
 'language',language,'document',json(document_json),'fetchedAt',fetched_at)
 FROM item_provider_match WHERE library_id=?1 AND item_id=?2
))`, libraryID, itemID, matchRevision, structureRevision)
	return err
}

// ProviderUndoRevision is available only after the HTTP caller verifies live
// access. Old matches remain readable and can be removed after an explicit rematch.
func (store *Store) ProviderUndoRevision(ctx context.Context, actor uint, libraryID, itemID string, structureRevision int64) (int64, error) {
	if _, err := editableCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return 0, err
	}
	var revision int64
	err := store.db.QueryRowContext(ctx, `SELECT COALESCE((
SELECT pm.revision FROM item_provider_match pm JOIN provider_match_revision h
ON h.library_id=pm.library_id AND h.item_id=pm.item_id AND h.match_revision=pm.revision
WHERE pm.library_id=? AND pm.item_id=? AND h.structure_revision=?),0)`, libraryID, itemID, structureRevision).Scan(&revision)
	return revision, err
}

func (store *Store) ProviderMatchRequiresManualReview(ctx context.Context, actor uint, libraryID, itemID string) (bool, error) {
	if _, err := editableCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return false, err
	}
	var held bool
	err := store.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM provider_match_manual_review WHERE library_id=? AND item_id=?)`, libraryID, itemID).Scan(&held)
	return held, err
}

type ProviderUndoRequest struct {
	LibraryID, ItemID                              string
	ItemRevision, StructureRevision, MatchRevision int64
}

type ProviderUndoResult struct {
	LibraryID    string `json:"libraryId"`
	ItemID       string `json:"itemId"`
	ItemRevision int64  `json:"itemRevision"`
	Restored     bool   `json:"restored"`
}

func (store *Store) UndoProviderMatch(ctx context.Context, actor uint, operationID string, request ProviderUndoRequest) (*ProviderUndoResult, error) {
	if request.ItemRevision < 1 || request.StructureRevision < 1 || request.MatchRevision < 1 {
		return nil, ErrInvalid
	}
	return providerOperation(ctx, store, actor, "metadata.tmdb.undo", operationID, request, func(tx *sql.Tx) error {
		_, err := editableCatalogLibrary(ctx, tx, actor, request.LibraryID)
		return err
	}, func(tx *sql.Tx) (*ProviderUndoResult, error) {
		var previous sql.NullString
		err := tx.QueryRowContext(ctx, `SELECT h.previous_json FROM item_provider_match pm
JOIN provider_match_revision h ON h.library_id=pm.library_id AND h.item_id=pm.item_id AND h.match_revision=pm.revision
JOIN library_item i ON i.library_id=pm.library_id AND i.id=pm.item_id
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
WHERE pm.library_id=? AND pm.item_id=? AND pm.revision=? AND i.revision=?
AND st.structure_revision=? AND h.structure_revision=st.structure_revision`, request.LibraryID, request.ItemID,
			request.MatchRevision, request.ItemRevision, request.StructureRevision).Scan(&previous)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrRevisionConflict
		}
		if err != nil {
			return nil, err
		}
		revision := request.ItemRevision + 1
		if previous.Valid {
			_, err = tx.ExecContext(ctx, `UPDATE item_provider_match SET
connection_id=json_extract(?1,'$.connectionId'),entity_kind=json_extract(?1,'$.kind'),external_id=json_extract(?1,'$.externalId'),
language=json_extract(?1,'$.language'),document_json=json_extract(?1,'$.document'),fetched_at=json_extract(?1,'$.fetchedAt'),revision=?2
WHERE library_id=?3 AND item_id=?4`, previous.String, revision, request.LibraryID, request.ItemID)
			if err == nil {
				var prior struct {
					Revision int64 `json:"revision"`
				}
				if err := json.Unmarshal([]byte(previous.String), &prior); err != nil {
					return nil, err
				}
				// Preserve the older undo chain while assigning fresh public
				// revisions so a restored document cannot reuse an old cache key.
				_, err = tx.ExecContext(ctx, `INSERT INTO provider_match_revision(library_id,item_id,match_revision,structure_revision,previous_json)
SELECT library_id,item_id,?,structure_revision,previous_json FROM provider_match_revision
WHERE library_id=? AND item_id=? AND match_revision=? AND structure_revision=?`, revision,
					request.LibraryID, request.ItemID, prior.Revision, request.StructureRevision)
			}
		} else {
			_, err = tx.ExecContext(ctx, `DELETE FROM item_provider_match WHERE library_id=? AND item_id=?`, request.LibraryID, request.ItemID)
			if err == nil {
				_, err = tx.ExecContext(ctx, `INSERT INTO provider_match_manual_review(library_id,item_id) VALUES(?,?) ON CONFLICT DO NOTHING`, request.LibraryID, request.ItemID)
			}
		}
		if err != nil {
			return nil, err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE library_item SET revision=? WHERE library_id=? AND id=?`, revision, request.LibraryID, request.ItemID); err != nil {
			return nil, err
		}
		if err := publishCatalogChange(ctx, tx, actor, request.LibraryID, "catalog.item.metadata.changed", catalogChange{ItemID: request.ItemID, Revision: revision}); err != nil {
			return nil, err
		}
		return &ProviderUndoResult{request.LibraryID, request.ItemID, revision, previous.Valid}, nil
	})
}
