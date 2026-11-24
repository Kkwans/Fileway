package medialibrary

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"
)

const providerCreditCTE = `WITH credit AS (
SELECT pm.library_id,pm.item_id,
json_extract(c.value,'$.fields.name[0]') name,
json_extract(c.value,'$.fields.role[0]') role,
(SELECT json_extract(e.value,'$.value') FROM json_each(c.value,'$.externalIds') e WHERE json_extract(e.value,'$.provider')='tmdb' LIMIT 1) external_id
FROM item_provider_match pm,json_each(pm.document_json,'$.item.credits') c
WHERE json_extract(c.value,'$.kind') IN ('actor','director','writer')
),person AS (
SELECT library_id,'tmdb_person_'||external_id id,name FROM credit
WHERE external_id IS NOT NULL AND name IS NOT NULL AND name<>'' GROUP BY library_id,id,name
)`

type PersonCandidate struct {
	ID, LibraryID, Name string
}

type PersonCandidateOptions struct {
	LibraryID string
	Query     string
	AfterID   string
	Limit     int
}

func (store *Store) ListPersonCandidates(ctx context.Context, viewer uint, options PersonCandidateOptions) (*PersonCandidatePage, error) {
	if viewer == 0 || options.Limit < 1 || options.Limit > 100 || len(options.LibraryID) > 128 || len(options.AfterID) > 200 ||
		utf8.RuneCountInString(options.Query) > 200 || len(options.Query) > 800 || strings.IndexFunc(options.Query, unicode.IsControl) >= 0 {
		return nil, fmt.Errorf("%w: person candidate options", ErrInvalid)
	}
	pattern := "%" + escapeCatalogLike(options.Query) + "%"
	rows, err := store.db.QueryContext(ctx, providerCreditCTE+` SELECT p.id,p.library_id,p.name FROM person p
JOIN library l ON l.id=p.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE `+libraryVisibility+` AND l.archived_at IS NULL AND (?='' OR p.library_id=?) AND p.id>? AND (?='' OR p.name LIKE ? ESCAPE '\')
AND EXISTS(SELECT 1 FROM credit c JOIN item_structure st ON st.library_id=c.library_id AND st.item_id=c.item_id AND st.superseded_by IS NULL
JOIN library_item i ON i.library_id=c.library_id AND i.id=c.item_id
WHERE c.library_id=p.library_id AND 'tmdb_person_'||c.external_id=p.id AND i.kind IN ('movie','series','video'))
ORDER BY p.id LIMIT ?`, viewer, viewer, options.LibraryID, options.LibraryID, options.AfterID, options.Query, pattern, options.Limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	page := &PersonCandidatePage{Items: make([]PersonCandidate, 0, options.Limit)}
	for rows.Next() {
		var candidate PersonCandidate
		if err := rows.Scan(&candidate.ID, &candidate.LibraryID, &candidate.Name); err != nil {
			return nil, err
		}
		if len(page.Items) == options.Limit {
			page.HasMore = true
			break
		}
		page.Items = append(page.Items, candidate)
	}
	return page, rows.Err()
}

type PersonCandidatePage struct {
	Items   []PersonCandidate
	HasMore bool
}

func (store *Store) ListPersonWorkCandidates(ctx context.Context, viewer uint, libraryID, personID, afterID string, limit int) (*BrowseCandidatePage, error) {
	if limit < 1 || limit > 100 || len(afterID) > 200 {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, providerCreditCTE+` SELECT DISTINCT i.id,i.library_id,i.kind,i.revision,st.match_state,st.structure_revision,st.first_added_at,st.series_updated_at
FROM credit c JOIN library_item i ON i.library_id=c.library_id AND i.id=c.item_id
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
WHERE c.library_id=? AND 'tmdb_person_'||c.external_id=? AND i.kind IN ('movie','series','video') AND i.id>? ORDER BY i.id LIMIT ?`,
		libraryID, personID, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	page := &BrowseCandidatePage{Items: make([]BrowseCandidate, 0, limit)}
	for rows.Next() {
		var candidate BrowseCandidate
		var updated sql.NullInt64
		if err := rows.Scan(&candidate.ID, &candidate.LibraryID, &candidate.Kind, &candidate.Revision, &candidate.MatchState,
			&candidate.StructureRevision, &candidate.FirstAddedAt, &updated); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.HasMore = true
			break
		}
		if updated.Valid {
			candidate.SeriesUpdatedAt = &updated.Int64
		}
		page.Items = append(page.Items, candidate)
	}
	return page, rows.Err()
}

func (store *Store) ListPersonRoleLabels(ctx context.Context, viewer uint, libraryID, personID string) ([]string, error) {
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, providerCreditCTE+` SELECT DISTINCT role FROM credit
WHERE library_id=? AND 'tmdb_person_'||external_id=? AND role IS NOT NULL AND role<>'' ORDER BY role LIMIT 20`, libraryID, personID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	roles := make([]string, 0, 8)
	for rows.Next() {
		var role string
		if err := rows.Scan(&role); err != nil {
			return nil, err
		}
		roles = append(roles, role)
	}
	return roles, rows.Err()
}
