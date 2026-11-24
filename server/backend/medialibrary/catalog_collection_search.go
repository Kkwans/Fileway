package medialibrary

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"
)

const nfoCollectionCTE = `WITH declaration AS (
SELECT x.library_id,x.item_id,x.id input_id,c.key collection_key,
lower(trim(json_extract(c.value,'$.fields.name[0]'))) name_key,
trim(json_extract(c.value,'$.fields.name[0]')) name,
CASE WHEN json_extract(e.value,'$.value') IS NOT NULL
 AND length(json_extract(e.value,'$.value')) BETWEEN 1 AND 256
 AND json_extract(e.value,'$.value') NOT GLOB '*[!A-Za-z0-9_.:-]*'
 THEN json_extract(e.value,'$.value') ELSE NULL END external_id
FROM metadata_input x,json_each(x.evidence_json,'$.items') doc_item,json_each(doc_item.value,'$.collections') c
LEFT JOIN json_each(c.value,'$.externalIds') e ON lower(json_extract(e.value,'$.provider'))='tmdb'
WHERE x.is_current=1 AND x.source_type='nfo' AND x.status IN ('candidate','applied')
AND EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=x.binding_id AND ls.library_id=x.library_id AND ls.proof_epoch=x.binding_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1)
),identity AS (
SELECT library_id,name_key,min(name) name,external_id FROM declaration
WHERE name_key IS NOT NULL AND name_key<>'' AND length(name_key)<=1024
GROUP BY library_id,name_key,external_id
)`

type CollectionCandidate struct {
	ID, LibraryID, Name, NameKey, ExternalID string
}

type CollectionCandidateOptions struct {
	LibraryID, Query, AfterLibrary, AfterName, AfterExternal string
	Limit                                                    int
}

type CollectionCandidatePage struct {
	Items   []CollectionCandidate
	HasMore bool
}

type CollectionSource struct {
	InputID, ItemID string
}

type CollectionSourcePage struct {
	Items   []CollectionSource
	HasMore bool
}

func collectionCandidateID(libraryID, nameKey, externalID string) string {
	if externalID != "" {
		return "tmdb_collection_" + externalID
	}
	digest := sha256.Sum256([]byte(libraryID + "\x00" + nameKey))
	return fmt.Sprintf("nfo_collection_%x", digest[:16])
}

func (store *Store) ListCollectionCandidates(ctx context.Context, viewer uint, options CollectionCandidateOptions) (*CollectionCandidatePage, error) {
	if viewer == 0 || options.Limit < 1 || options.Limit > 100 || len(options.LibraryID) > 128 ||
		utf8.RuneCountInString(options.Query) > 200 || len(options.Query) > 800 || strings.IndexFunc(options.Query, unicode.IsControl) >= 0 {
		return nil, fmt.Errorf("%w: collection candidate options", ErrInvalid)
	}
	pattern := "%" + escapeCatalogLike(options.Query) + "%"
	rows, err := store.db.QueryContext(ctx, nfoCollectionCTE+` SELECT i.library_id,i.name_key,i.name,COALESCE(i.external_id,'') FROM identity i
JOIN library l ON l.id=i.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE `+libraryVisibility+` AND l.archived_at IS NULL AND (?='' OR i.library_id=?)
AND (i.library_id,i.name_key,COALESCE(i.external_id,''))>(?,?,?) AND (?='' OR i.name LIKE ? ESCAPE '\')
ORDER BY i.library_id,i.name_key,COALESCE(i.external_id,'') LIMIT ?`,
		viewer, viewer, options.LibraryID, options.LibraryID, options.AfterLibrary, options.AfterName, options.AfterExternal,
		options.Query, pattern, options.Limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	page := &CollectionCandidatePage{Items: make([]CollectionCandidate, 0, options.Limit)}
	for rows.Next() {
		var candidate CollectionCandidate
		if err := rows.Scan(&candidate.LibraryID, &candidate.NameKey, &candidate.Name, &candidate.ExternalID); err != nil {
			return nil, err
		}
		if len(page.Items) == options.Limit {
			page.HasMore = true
			break
		}
		candidate.ID = collectionCandidateID(candidate.LibraryID, candidate.NameKey, candidate.ExternalID)
		page.Items = append(page.Items, candidate)
	}
	return page, rows.Err()
}

func (store *Store) FindCollectionCandidate(ctx context.Context, viewer uint, libraryID, collectionID string) (*CollectionCandidate, error) {
	if viewer == 0 || len(libraryID) > 128 || len(collectionID) > 128 {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, nfoCollectionCTE+` SELECT i.library_id,i.name_key,i.name,COALESCE(i.external_id,'') FROM identity i WHERE i.library_id=? ORDER BY i.name_key,COALESCE(i.external_id,'')`, libraryID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var candidate CollectionCandidate
		if err := rows.Scan(&candidate.LibraryID, &candidate.NameKey, &candidate.Name, &candidate.ExternalID); err != nil {
			return nil, err
		}
		candidate.ID = collectionCandidateID(candidate.LibraryID, candidate.NameKey, candidate.ExternalID)
		if candidate.ID == collectionID {
			return &candidate, rows.Err()
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return nil, ErrNotFound
}

func (store *Store) ListCollectionSources(ctx context.Context, viewer uint, candidate CollectionCandidate, afterInput, afterItem string, limit int) (*CollectionSourcePage, error) {
	if viewer == 0 || limit < 1 || limit > 200 {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, candidate.LibraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, nfoCollectionCTE+` SELECT DISTINCT d.input_id,d.item_id FROM declaration d
JOIN item_structure st ON st.library_id=d.library_id AND st.item_id=d.item_id AND st.superseded_by IS NULL
WHERE d.library_id=? AND d.name_key=? AND ((?='' AND d.external_id IS NULL) OR d.external_id=?)
AND (d.input_id,d.item_id)>(?,?) ORDER BY d.input_id,d.item_id LIMIT ?`,
		candidate.LibraryID, candidate.NameKey, candidate.ExternalID, candidate.ExternalID, afterInput, afterItem, limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	page := &CollectionSourcePage{Items: make([]CollectionSource, 0, limit)}
	for rows.Next() {
		var item CollectionSource
		if err := rows.Scan(&item.InputID, &item.ItemID); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.HasMore = true
			break
		}
		page.Items = append(page.Items, item)
	}
	return page, rows.Err()
}

func (store *Store) ListCollectionWorkCandidates(ctx context.Context, viewer uint, candidate CollectionCandidate, afterID string, limit int) (*BrowseCandidatePage, error) {
	if viewer == 0 || limit < 1 || limit > 100 || len(afterID) > 200 {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, candidate.LibraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, nfoCollectionCTE+` SELECT DISTINCT i.id,i.library_id,i.kind,i.revision,st.match_state,st.structure_revision,st.first_added_at,st.series_updated_at
FROM declaration d JOIN library_item i ON i.library_id=d.library_id AND i.id=d.item_id
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
WHERE d.library_id=? AND d.name_key=? AND ((?='' AND d.external_id IS NULL) OR d.external_id=?)
AND i.kind IN ('movie','series','video') AND i.id>? ORDER BY i.id LIMIT ?`,
		candidate.LibraryID, candidate.NameKey, candidate.ExternalID, candidate.ExternalID, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	page := &BrowseCandidatePage{Items: make([]BrowseCandidate, 0, limit)}
	for rows.Next() {
		var item BrowseCandidate
		var updated sql.NullInt64
		if err := rows.Scan(&item.ID, &item.LibraryID, &item.Kind, &item.Revision, &item.MatchState,
			&item.StructureRevision, &item.FirstAddedAt, &updated); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.HasMore = true
			break
		}
		if updated.Valid {
			item.SeriesUpdatedAt = &updated.Int64
		}
		page.Items = append(page.Items, item)
	}
	return page, rows.Err()
}
