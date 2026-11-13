package medialibrary

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
)

func metadataPositionCursor(position int, id string) string {
	return fmt.Sprintf("%020d:%s", position, id)
}

type metadataPage[T any] struct {
	Items        []T
	NextCursor   string
	ItemRevision int64
}

func metadataPageRevision(ctx context.Context, reader metadataReader, actor uint, libraryID, itemID string) (int64, error) {
	if actor == 0 {
		return 0, ErrForbidden
	}
	var revision int64
	err := reader.QueryRowContext(ctx, `SELECT i.revision FROM library_item i JOIN library l ON l.id=i.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL WHERE i.library_id=? AND i.id=? AND `+libraryVisibility+` AND l.archived_at IS NULL`, actor, libraryID, itemID, actor).Scan(&revision)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNotFound
	}
	return revision, err
}

func queryMetadataPage[T any](ctx context.Context, reader metadataReader, actor uint, libraryID, itemID, after string, limit int, query string, read func(*sql.Rows) (T, string, error)) (*metadataPage[T], error) {
	if limit == 0 {
		limit = 20
	}
	if limit < 1 || limit > 100 || len(after) > 256 {
		return nil, fmt.Errorf("%w: metadata page", ErrInvalid)
	}
	library, err := activeCatalogLibrary(ctx, reader, actor, libraryID)
	if err != nil {
		return nil, err
	}
	revision, err := metadataPageRevision(ctx, reader, actor, libraryID, itemID)
	if err != nil {
		return nil, err
	}
	rows, err := reader.QueryContext(ctx, query, actor, libraryID, itemID, actor, after, limit+1)
	if err != nil {
		return nil, err
	}
	page := &metadataPage[T]{Items: make([]T, 0, limit), ItemRevision: revision}
	last := ""
	for rows.Next() {
		item, cursor, err := read(rows)
		if err != nil {
			_ = rows.Close()
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = last
			break
		}
		page.Items = append(page.Items, item)
		last = cursor
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	current, err := metadataPageRevision(ctx, reader, actor, libraryID, itemID)
	if err != nil {
		return nil, err
	}
	if current != revision {
		return nil, ErrRevisionConflict
	}
	currentLibrary, err := activeCatalogLibrary(ctx, reader, actor, libraryID)
	if err != nil {
		return nil, err
	}
	if currentLibrary.Revision != library.Revision {
		return nil, ErrRevisionConflict
	}
	return page, nil
}

const metadataPageVisibility = ` JOIN library l ON l.id=x.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL WHERE x.library_id=? AND x.item_id=? AND ` + libraryVisibility + ` AND l.archived_at IS NULL `
const metadataPageInput = ` AND EXISTS(SELECT 1 FROM metadata_input mi JOIN library_source ls ON ls.binding_id=mi.binding_id AND ls.library_id=mi.library_id WHERE mi.id=x.input_id AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0) `

func (store *Store) ListItemCredits(ctx context.Context, viewer uint, libraryID, itemID, after string, limit int) (*MetadataCreditPage, error) {
	query := `SELECT x.id,x.person_id,x.display_name,x.kind,x.role,x.roles_json,x.position,x.input_id FROM item_credit x` + metadataPageVisibility + metadataPageInput + ` AND x.active=1 AND printf('%020d:%s',x.position,x.id)>? ORDER BY x.position,x.id LIMIT ?`
	page, err := queryMetadataPage(ctx, store.db, viewer, libraryID, itemID, after, limit, query, func(rows *sql.Rows) (MetadataCredit, string, error) {
		var item MetadataCredit
		var roles string
		err := rows.Scan(&item.ID, &item.PersonID, &item.Name, &item.Kind, &item.Role, &roles, &item.Position, &item.InputID)
		if err == nil {
			err = json.Unmarshal([]byte(roles), &item.Roles)
		}
		item.Cursor = metadataPositionCursor(item.Position, item.ID)
		return item, item.Cursor, err
	})
	if err != nil {
		return nil, err
	}
	return &MetadataCreditPage{Items: page.Items, NextCursor: page.NextCursor, ItemRevision: page.ItemRevision}, nil
}

func (store *Store) ListItemRatings(ctx context.Context, viewer uint, libraryID, itemID, after string, limit int) (*MetadataRatingPage, error) {
	query := `SELECT x.id,x.scope,mi.source_type,mi.created_at,x.rating_json,x.input_id,x.position FROM rating_observation x JOIN metadata_input mi ON mi.id=x.input_id` + metadataPageVisibility + metadataPageInput + ` AND x.active=1 AND printf('%020d:%s',x.position,x.id)>? ORDER BY x.position,x.id LIMIT ?`
	page, err := queryMetadataPage(ctx, store.db, viewer, libraryID, itemID, after, limit, query, func(rows *sql.Rows) (MetadataRating, string, error) {
		var item MetadataRating
		var raw string
		err := rows.Scan(&item.ID, &item.Scope, &item.Origin, &item.ImportedAt, &raw, &item.InputID, &item.Position)
		if err == nil {
			err = json.Unmarshal([]byte(raw), &item.Rating)
		}
		item.Cursor = metadataPositionCursor(item.Position, item.ID)
		return item, item.Cursor, err
	})
	if err != nil {
		return nil, err
	}
	return &MetadataRatingPage{Items: page.Items, NextCursor: page.NextCursor, ItemRevision: page.ItemRevision}, nil
}

func (store *Store) ListItemArtwork(ctx context.Context, viewer uint, libraryID, itemID, after string, limit int) (*MetadataArtworkPage, error) {
	query := `SELECT x.id,x.role,x.status,x.input_id,x.position FROM artwork_declaration x` + metadataPageVisibility + metadataPageInput + ` AND x.active=1 AND printf('%020d:%s',x.position,x.id)>? ORDER BY x.position,x.id LIMIT ?`
	page, err := queryMetadataPage(ctx, store.db, viewer, libraryID, itemID, after, limit, query, func(rows *sql.Rows) (MetadataArtwork, string, error) {
		var item MetadataArtwork
		err := rows.Scan(&item.ID, &item.Role, &item.Status, &item.InputID, &item.Position)
		item.Cursor = metadataPositionCursor(item.Position, item.ID)
		return item, item.Cursor, err
	})
	if err != nil {
		return nil, err
	}
	return &MetadataArtworkPage{Items: page.Items, NextCursor: page.NextCursor, ItemRevision: page.ItemRevision}, nil
}

func (store *Store) ListItemExternalIDs(ctx context.Context, viewer uint, libraryID, itemID, after string, limit int) (*MetadataExternalIDPage, error) {
	query := `SELECT x.id,x.provider,x.entity_kind,x.external_id,x.input_id FROM item_external_id x` + metadataPageVisibility + metadataPageInput + ` AND x.active=1 AND x.id>? ORDER BY x.id LIMIT ?`
	page, err := queryMetadataPage(ctx, store.db, viewer, libraryID, itemID, after, limit, query, func(rows *sql.Rows) (MetadataExternalID, string, error) {
		var item MetadataExternalID
		err := rows.Scan(&item.ID, &item.Provider, &item.EntityKind, &item.Value, &item.InputID)
		item.Cursor = item.ID
		return item, item.Cursor, err
	})
	if err != nil {
		return nil, err
	}
	return &MetadataExternalIDPage{Items: page.Items, NextCursor: page.NextCursor, ItemRevision: page.ItemRevision}, nil
}

func (store *Store) ListItemMetadataIssues(ctx context.Context, viewer uint, libraryID, itemID, after string, limit int) (*MetadataIssuePage, error) {
	query := `SELECT x.id,x.issue_code,x.evidence_json FROM metadata_input x` + metadataPageVisibility + ` AND x.is_current=1 AND x.status='candidate' AND x.issue_code!='' AND EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=x.binding_id AND ls.library_id=x.library_id AND ls.proof_epoch=x.binding_epoch AND ls.proof_epoch>0) AND x.id>? ORDER BY x.id LIMIT ?`
	page, err := queryMetadataPage(ctx, store.db, viewer, libraryID, itemID, after, limit, query, func(rows *sql.Rows) (MetadataIssueSource, string, error) {
		var item MetadataIssueSource
		var raw string
		err := rows.Scan(&item.ID, &item.Code, &raw)
		item.InputID = item.ID
		item.Cursor = item.ID
		if err != nil {
			return item, item.ID, err
		}
		var doc struct {
			Items []struct {
				Kind string `json:"kind"`
			} `json:"items"`
		}
		if err = json.Unmarshal([]byte(raw), &doc); err != nil {
			return item, item.ID, err
		}
		seen := map[string]bool{}
		for _, v := range doc.Items {
			if v.Kind != "" && !seen[v.Kind] {
				item.SourceKinds = append(item.SourceKinds, v.Kind)
				seen[v.Kind] = true
			}
		}
		return item, item.Cursor, nil
	})
	if err != nil {
		return nil, err
	}
	return &MetadataIssuePage{Items: page.Items, NextCursor: page.NextCursor, ItemRevision: page.ItemRevision}, nil
}
