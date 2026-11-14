package medialibrary

import (
	"context"
	"database/sql"
	"fmt"
)

// BrowseCandidate is an authorization-neutral candidate. In particular, its
// structure, dates and IDs are not permission to return it to a viewer.
type BrowseCandidate struct {
	ID, LibraryID, Kind, MatchState string
	Revision, StructureRevision     int64
	FirstAddedAt                    int64
	SeriesUpdatedAt                 *int64
}

type BrowseCandidateOptions struct {
	LibraryID  string
	OnlyOwned  bool
	Sort       string // id, newest, updated
	Kind       string // optional movie, series or video
	AfterID    string
	AfterValue *int64
	Limit      int
}

type BrowseCandidatePage struct {
	Items   []BrowseCandidate
	HasMore bool
}

func (store *Store) GetBrowseItemFence(ctx context.Context, viewer uint, libraryID, itemID string) (int64, int64, bool, error) {
	if viewer == 0 {
		return 0, 0, false, ErrForbidden
	}
	var revision, structureRevision int64
	var superseded int
	err := store.db.QueryRowContext(ctx, `SELECT i.revision,st.structure_revision,st.superseded_by IS NOT NULL FROM library_item i JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id JOIN library l ON l.id=i.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL WHERE i.library_id=? AND i.id=? AND `+libraryVisibility+` AND l.archived_at IS NULL`, viewer, libraryID, itemID, viewer).Scan(&revision, &structureRevision, &superseded)
	if err == sql.ErrNoRows {
		return 0, 0, false, ErrNotFound
	}
	return revision, structureRevision, superseded != 0, err
}

// CheckItemSourceRevision rechecks the stored, binding-specific association
// after live source access. It never opens a path or grants access by itself.
func (store *Store) CheckItemSourceRevision(ctx context.Context, viewer uint, libraryID, itemID string, entry ItemSourceAccess) (bool, error) {
	if viewer == 0 {
		return false, ErrForbidden
	}
	var found int
	err := store.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM library_item i JOIN library l ON l.id=i.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
JOIN edition e ON e.library_id=i.library_id AND e.item_id=i.id JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id JOIN source_file f ON f.id=v.source_file_id
JOIN library_file lf ON lf.library_id=v.library_id AND lf.source_file_id=f.id AND lf.source_id=f.source_id
JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator
JOIN file_item_mapping fm ON fm.library_id=v.library_id AND fm.variant_id=v.id AND fm.item_id=e.item_id AND fm.source_file_id=v.source_file_id AND fm.binding_id=ls.binding_id AND fm.binding_epoch=ls.proof_epoch AND fm.active=1
WHERE i.library_id=? AND i.id=? AND `+libraryVisibility+` AND l.archived_at IS NULL AND ls.binding_id=? AND ls.proof_epoch=? AND ls.active_proof_version=1 AND lf.binding_epoch=ls.proof_epoch AND lf.source_file_id=? AND lf.locator_revision=? AND lf.missing_since IS ? AND f.revision=? AND f.identity_epoch=? AND f.source_version=? AND
(i.kind!='episode' OR fm.confirmed_by='manual' OR fm.evidence_id IS NULL OR EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=fm.evidence_id AND ie.match_state!='needs_review')))`, viewer, libraryID, itemID, viewer, entry.BindingID, entry.Binding.ProofEpoch, entry.SourceFileID, entry.LocatorRevision, entry.MissingSince, entry.FileRevision, entry.IdentityEpoch, entry.SourceVersion).Scan(&found)
	return found != 0, err
}

func (store *Store) HasVisibleCatalogScan(ctx context.Context, viewer uint, onlyOwned bool) (bool, error) {
	if viewer == 0 {
		return false, ErrForbidden
	}
	var active int
	err := store.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM scan_job sj JOIN job j ON j.id=sj.job_id JOIN library l ON l.id=j.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL WHERE `+libraryVisibility+` AND l.archived_at IS NULL AND (?=0 OR l.owner_user_id=?) AND sj.active=1 AND j.status IN ('queued','running','interrupted'))`, viewer, viewer, onlyOwned, viewer).Scan(&active)
	return active != 0, err
}

func (store *Store) ListBrowseCandidates(ctx context.Context, viewer uint, options BrowseCandidateOptions) (*BrowseCandidatePage, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	if options.Limit < 1 || options.Limit > 100 || options.Sort != "id" && options.Sort != "newest" && options.Sort != "updated" || options.Kind != "" && options.Kind != "movie" && options.Kind != "series" && options.Kind != "video" || options.Sort == "id" && options.AfterValue != nil || options.Sort != "id" && (options.AfterID == "") != (options.AfterValue == nil) {
		return nil, fmt.Errorf("%w: browse candidate options", ErrInvalid)
	}
	order := "i.id ASC"
	filter := "i.id > ?"
	args := []any{viewer, viewer, options.OnlyOwned, viewer, options.LibraryID, options.LibraryID, options.Kind, options.Kind}
	if options.Sort == "id" {
		args = append(args, options.AfterID)
	} else {
		column := "st.first_added_at"
		if options.Sort == "updated" {
			column = "COALESCE(st.series_updated_at,0)"
		}
		order = column + " DESC,i.id ASC"
		filter = "(?=0 OR " + column + " < ? OR (" + column + " = ? AND i.id > ?))"
		var after int64
		hasAfter := 0
		if options.AfterValue != nil {
			after = *options.AfterValue
			hasAfter = 1
		}
		args = append(args, hasAfter, after, after, options.AfterID)
	}
	args = append(args, options.Limit+1)
	rows, err := store.db.QueryContext(ctx, `SELECT i.id,i.library_id,i.kind,i.revision,st.match_state,st.structure_revision,st.first_added_at,st.series_updated_at
FROM library_item i JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id JOIN library l ON l.id=i.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE `+libraryVisibility+` AND l.archived_at IS NULL AND (?=0 OR l.owner_user_id=?) AND (?='' OR i.library_id=?) AND (?='' OR i.kind=?) AND i.kind IN ('movie','series','video') AND st.superseded_by IS NULL AND `+filter+` ORDER BY `+order+` LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &BrowseCandidatePage{Items: make([]BrowseCandidate, 0, options.Limit)}
	for rows.Next() {
		var c BrowseCandidate
		var updated sql.NullInt64
		if err = rows.Scan(&c.ID, &c.LibraryID, &c.Kind, &c.Revision, &c.MatchState, &c.StructureRevision, &c.FirstAddedAt, &updated); err != nil {
			return nil, err
		}
		if len(page.Items) == options.Limit {
			page.HasMore = true
			break
		}
		if updated.Valid {
			c.SeriesUpdatedAt = &updated.Int64
		}
		page.Items = append(page.Items, c)
	}
	return page, rows.Err()
}

type SeriesEpisodeCandidate struct {
	EpisodeID, SeasonID, OrderID string
	SeasonNumber, EpisodeNumber  int
	FirstAddedAt                 int64
	OrderRevision                int64
}

// ListSeriesEpisodeCandidates performs only bounded structural reads. The
// caller filters every episode's source before showing a row or counting it.
func (store *Store) ListSeriesEpisodeCandidates(ctx context.Context, viewer uint, libraryID, seriesID, seasonID string, afterSeason, afterEpisode, limit int) ([]SeriesEpisodeCandidate, bool, error) {
	if limit < 1 || limit > 100 || afterSeason < -1 || afterEpisode < -1 {
		return nil, false, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, false, err
	}
	rows, err := store.db.QueryContext(ctx, `SELECT ep.episode_id,sp.season_id,eo.id,ep.season_number,ep.episode_number,st.first_added_at,eo.revision
FROM episode_order eo JOIN season_position sp ON sp.library_id=eo.library_id AND sp.order_id=eo.id
JOIN episode_position ep ON ep.library_id=sp.library_id AND ep.order_id=sp.order_id AND ep.season_number=sp.season_number
JOIN library_item e ON e.library_id=ep.library_id AND e.id=ep.episode_id AND e.kind='episode' AND e.parent_id=sp.season_id
JOIN item_structure st ON st.library_id=e.library_id AND st.item_id=e.id
JOIN library_item s ON s.library_id=sp.library_id AND s.id=sp.season_id AND s.kind='season' AND s.parent_id=eo.series_id
JOIN library_item p ON p.library_id=eo.library_id AND p.id=eo.series_id AND p.kind='series'
WHERE eo.library_id=? AND eo.series_id=? AND eo.scheme='local' AND (?='' OR sp.season_id=?) AND (ep.season_number>? OR (ep.season_number=? AND ep.episode_number>?)) ORDER BY ep.season_number,ep.episode_number LIMIT ?`, libraryID, seriesID, seasonID, seasonID, afterSeason, afterSeason, afterEpisode, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer func() { _ = rows.Close() }()
	items := make([]SeriesEpisodeCandidate, 0, limit)
	for rows.Next() {
		var c SeriesEpisodeCandidate
		if err = rows.Scan(&c.EpisodeID, &c.SeasonID, &c.OrderID, &c.SeasonNumber, &c.EpisodeNumber, &c.FirstAddedAt, &c.OrderRevision); err != nil {
			return nil, false, err
		}
		if len(items) == limit {
			return items, true, nil
		}
		items = append(items, c)
	}
	return items, false, rows.Err()
}

func (store *Store) GetSeriesEpisodeCandidate(ctx context.Context, viewer uint, libraryID, seriesID, episodeID string) (*SeriesEpisodeCandidate, error) {
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, err
	}
	var c SeriesEpisodeCandidate
	err := store.db.QueryRowContext(ctx, `SELECT ep.episode_id,sp.season_id,eo.id,ep.season_number,ep.episode_number,st.first_added_at,eo.revision FROM episode_order eo JOIN episode_position ep ON ep.library_id=eo.library_id AND ep.order_id=eo.id JOIN season_position sp ON sp.library_id=ep.library_id AND sp.order_id=ep.order_id AND sp.season_number=ep.season_number JOIN item_structure st ON st.library_id=ep.library_id AND st.item_id=ep.episode_id WHERE eo.library_id=? AND eo.series_id=? AND eo.scheme='local' AND ep.episode_id=?`, libraryID, seriesID, episodeID).Scan(&c.EpisodeID, &c.SeasonID, &c.OrderID, &c.SeasonNumber, &c.EpisodeNumber, &c.FirstAddedAt, &c.OrderRevision)
	if err == sql.ErrNoRows {
		return nil, ErrNotFound
	}
	return &c, err
}

// ListDirectoryContextSources exposes private provenance for a series or
// season to the service layer. Callers must verify the owner's live access to
// each sidecar; a stored context is neither a grant nor a client DTO.
func (store *Store) ListDirectoryContextSources(ctx context.Context, viewer uint, libraryID, itemID, after string, limit int) (*ItemMetadataSourceAccessPage, error) {
	if limit < 1 || limit > 100 {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, `SELECT dc.id,ls.binding_id,s.id,s.owner_user_id,s.configuration_ref,s.authorization_revision,s.created_at,ls.root_locator,ls.revision,
CASE WHEN dc.local_series_id=? AND dc.tv_status='parsed' THEN dc.tv_key ELSE dc.season_key END,
CASE WHEN dc.local_series_id=? AND dc.tv_status='parsed' THEN dc.tv_digest ELSE dc.season_digest END,
CASE WHEN dc.local_series_id=? AND dc.tv_status='parsed' THEN dc.tv_parser ELSE dc.season_parser END,
ls.proof_epoch,ls.proof_version,ls.mapping_digest,ls.root_identity_kind,ls.root_identity,ls.attested_at,dc.revision
FROM directory_context dc JOIN library_source ls ON ls.binding_id=dc.binding_id AND ls.library_id=dc.library_id JOIN source s ON s.id=ls.source_id JOIN library l ON l.id=dc.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE dc.library_id=? AND `+libraryVisibility+` AND l.archived_at IS NULL AND s.owner_user_id=l.owner_user_id AND s.kind='local' AND dc.binding_epoch=ls.proof_epoch AND ls.active_proof_version=1 AND ls.proof_epoch>0
AND ((dc.local_series_id=? AND dc.tv_status='parsed') OR (dc.local_season_id=? AND dc.season_status='parsed')) AND dc.id>? ORDER BY dc.id LIMIT ?`, itemID, itemID, itemID, viewer, libraryID, viewer, itemID, itemID, after, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &ItemMetadataSourceAccessPage{Items: make([]ItemMetadataSourceAccess, 0, limit)}
	for rows.Next() {
		var entry ItemMetadataSourceAccess
		proof := &BindingProof{}
		if err = rows.Scan(&entry.ID, &entry.BindingID, &entry.Source.ID, &entry.Source.OwnerUserID, &entry.Source.ConfigurationRef, &entry.Source.AuthorizationRevision, &entry.Source.CreatedAt, &entry.Binding.RootLocator, &entry.Binding.BindingRevision, &entry.SourceKey, &entry.Digest, &entry.ParserVersion, &entry.Binding.ProofEpoch, &proof.Version, &proof.MappingDigest, &proof.RootIdentityKind, &proof.RootIdentity, &proof.AttestedAt, &entry.ContextRevision); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		entry.Binding.BindingID, entry.Binding.LibraryID, entry.Binding.SourceID, entry.Binding.AuthorizationRevision = entry.BindingID, libraryID, entry.Source.ID, entry.Source.AuthorizationRevision
		entry.Proof = proof
		entry.ContextItemID = itemID
		page.Items = append(page.Items, entry)
	}
	return page, rows.Err()
}

func (store *Store) CheckDirectoryContextSource(ctx context.Context, viewer uint, libraryID string, entry ItemMetadataSourceAccess) (bool, error) {
	if viewer == 0 || entry.ContextItemID == "" || entry.ContextRevision < 1 {
		return false, ErrInvalid
	}
	var found int
	err := store.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM directory_context dc JOIN library_source ls ON ls.binding_id=dc.binding_id AND ls.library_id=dc.library_id JOIN library l ON l.id=dc.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL WHERE dc.library_id=? AND dc.id=? AND dc.revision=? AND dc.binding_id=? AND dc.binding_epoch=? AND ls.proof_epoch=dc.binding_epoch AND ls.active_proof_version=1 AND l.archived_at IS NULL AND `+libraryVisibility+` AND ((dc.local_series_id=? AND dc.tv_status='parsed' AND dc.tv_key=?) OR (dc.local_season_id=? AND dc.season_status='parsed' AND dc.season_key=?)))`, viewer, libraryID, entry.ID, entry.ContextRevision, entry.BindingID, entry.Binding.ProofEpoch, viewer, entry.ContextItemID, entry.SourceKey, entry.ContextItemID, entry.SourceKey).Scan(&found)
	return found != 0, err
}
