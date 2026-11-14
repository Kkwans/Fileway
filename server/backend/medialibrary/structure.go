package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

// DirectoryContextRef is private scan state. It is a revision fence, not an
// authority to read a path, metadata, an item, or a source file.
type DirectoryContextRef struct {
	ID       string `json:"id,omitempty"`
	Revision int64  `json:"revision,omitempty"`
}

type DirectorySidecar struct {
	Status string
	NFO    *MetadataObservation
}

type DirectoryContextObservation struct {
	Locator []byte
	Parent  DirectoryContextRef
	TVShow  DirectorySidecar
	Season  DirectorySidecar
}

type directoryContext struct {
	ID, ParentID, SeriesID, SeasonID, LocalSeriesID, LocalSeasonID string
	ParentRevision, Revision, RulesRevision                        int64
	SeasonNumber                                                   sql.NullInt64
	SeriesProblem, SeasonProblem                                   string
	TVStatus, SeasonStatus                                         string
	TVKey, TVDigest, SeasonKey, SeasonDigest                       []byte
	TVParser, SeasonParser                                         int
	TVEvidence, SeasonEvidence                                     sql.NullString
}

const directoryContextColumns = `id,COALESCE(parent_context_id,''),parent_revision,
COALESCE(series_id,''),COALESCE(season_id,''),season_number,series_problem,season_problem,COALESCE(local_series_id,''),COALESCE(local_season_id,''),
tv_status,season_status,tv_key,tv_digest,tv_parser,season_key,season_digest,season_parser,
tv_evidence_json,season_evidence_json,rules_revision,revision`

func readDirectoryContext(row rowScanner) (*directoryContext, error) {
	var value directoryContext
	err := row.Scan(&value.ID, &value.ParentID, &value.ParentRevision, &value.SeriesID, &value.SeasonID, &value.SeasonNumber, &value.SeriesProblem, &value.SeasonProblem,
		&value.LocalSeriesID, &value.LocalSeasonID, &value.TVStatus, &value.SeasonStatus,
		&value.TVKey, &value.TVDigest, &value.TVParser, &value.SeasonKey, &value.SeasonDigest, &value.SeasonParser,
		&value.TVEvidence, &value.SeasonEvidence, &value.RulesRevision, &value.Revision)
	return &value, err
}

func validateDirectorySidecar(sidecar DirectorySidecar, kind mediametadata.Kind) error {
	switch sidecar.Status {
	case "missing", "invalid", "unavailable", "changed", "conflict":
		if sidecar.NFO != nil {
			return ErrInvalid
		}
	case "parsed":
		if sidecar.NFO == nil || len(sidecar.NFO.SourceKey) == 0 || len(sidecar.NFO.SourceKey) > 8192 || len(sidecar.NFO.Digest) != 32 || sidecar.NFO.ParserVersion < 1 || sidecar.NFO.AssociationAmbiguous || len(sidecar.NFO.Document.Items) != 1 || sidecar.NFO.Document.Items[0].Kind != kind {
			return ErrInvalid
		}
	default:
		return ErrInvalid
	}
	return nil
}

func contextPathPrefix(root []byte) []byte {
	prefix := bytes.Clone(root)
	if len(prefix) != 1 || prefix[0] != '/' {
		prefix = append(prefix, '/')
	}
	return prefix
}

// Locators are compared as original bytes, not normalized filesystem paths.
func directDirectoryChild(parent, child []byte) bool {
	prefix := bytes.Clone(parent)
	if len(prefix) == 0 {
		return false
	}
	if prefix[len(prefix)-1] != '/' {
		prefix = append(prefix, '/')
	}
	if !bytes.HasPrefix(child, prefix) {
		return false
	}
	tail := child[len(prefix):]
	return len(tail) > 0 && !bytes.ContainsAny(tail, "/\x00") && !bytes.Equal(tail, []byte(".")) && !bytes.Equal(tail, []byte(".."))
}

// A child revision is insufficient if an ancestor changed after the child was
// committed. Recheck the inherited chain inside the same write transaction.
func validateContextAncestorsTx(ctx context.Context, tx *sql.Tx, binding SourceBindingRef, contextID string) error {
	var invalid int
	err := tx.QueryRowContext(ctx, `WITH RECURSIVE chain(id,parent_id,parent_revision,depth) AS (
SELECT id,parent_context_id,parent_revision,0 FROM directory_context WHERE id=? AND binding_id=? AND binding_epoch=?
UNION ALL SELECT p.id,p.parent_context_id,p.parent_revision,c.depth+1 FROM chain c JOIN directory_context p ON p.id=c.parent_id WHERE c.depth<128)
SELECT count(*) FROM chain c LEFT JOIN directory_context p ON p.id=c.parent_id
WHERE c.parent_id IS NOT NULL AND (p.id IS NULL OR p.revision!=c.parent_revision OR p.binding_id!=? OR p.binding_epoch!=? OR c.depth=128)`, contextID, binding.BindingID, binding.ProofEpoch, binding.BindingID, binding.ProofEpoch).Scan(&invalid)
	if err != nil {
		return err
	}
	if invalid != 0 {
		return ErrRevisionConflict
	}
	return nil
}

func sidecarAtDirectory(sidecar DirectorySidecar, dir []byte, filename string) bool {
	if sidecar.NFO == nil {
		return true
	}
	key := sidecar.NFO.SourceKey
	if !directDirectoryChild(dir, key) {
		return false
	}
	i := bytes.LastIndexByte(key, '/')
	return i >= 0 && strings.EqualFold(string(key[i+1:]), filename)
}

func safeContextEvidence(nfo *MetadataObservation) (sql.NullString, error) {
	if nfo == nil {
		return sql.NullString{}, nil
	}
	safe, err := sanitizeNFOEvidence(nfo.Document)
	if errors.Is(err, errNFOEvidenceTooLarge) {
		return sql.NullString{}, nil
	}
	if err != nil {
		return sql.NullString{}, err
	}
	raw, err := json.Marshal(safe)
	if err != nil {
		return sql.NullString{}, err
	}
	return sql.NullString{String: string(raw), Valid: true}, nil
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}
	return value
}

// A path-only key embeds its binding, epoch and exact opaque locator. Verify
// these bytes before carrying an epoch-1 legacy link forward. This is not a
// content hash and makes no claim about playback-timeline continuity.
func verifyMigratedPathMappings(ctx context.Context, tx *sql.Tx) error {
	type candidate struct {
		id, binding  string
		epoch        int64
		key, locator []byte
	}
	after := ""
	for {
		rows, err := tx.QueryContext(ctx, `SELECT m.id,m.binding_id,m.binding_epoch,f.object_key,lf.locator
FROM file_item_mapping m JOIN source_file f ON f.id=m.source_file_id AND f.identity_kind='path_unverified'
JOIN library_source ls ON ls.binding_id=m.binding_id AND ls.library_id=m.library_id
JOIN library_file lf ON lf.library_id=ls.library_id AND lf.source_id=ls.source_id AND lf.root_locator=ls.root_locator AND lf.source_file_id=f.id
WHERE m.confirmed_by='legacy' AND m.active=1 AND m.binding_epoch=1 AND m.id>? ORDER BY m.id LIMIT 128`, after)
		if err != nil {
			return err
		}
		var batch []candidate
		for rows.Next() {
			var c candidate
			if err = rows.Scan(&c.id, &c.binding, &c.epoch, &c.key, &c.locator); err != nil {
				_ = rows.Close()
				return err
			}
			batch = append(batch, c)
		}
		readErr, closeErr := rows.Err(), rows.Close()
		if readErr != nil {
			return readErr
		}
		if closeErr != nil {
			return closeErr
		}
		if len(batch) == 0 {
			return nil
		}
		for _, c := range batch {
			if !bytes.Equal(c.key, UnverifiedPathObjectKey(c.binding, c.epoch, c.locator)) {
				if _, err = tx.ExecContext(ctx, `UPDATE file_item_mapping SET active=0,mapping_revision=mapping_revision+1 WHERE id=?`, c.id); err != nil {
					return err
				}
			}
		}
		after = batch[len(batch)-1].id
	}
}

// CommitScanContext stores a directory and its checkpoint atomically. The
// caller performs directory enumeration and safe sidecar reads beforehand.
func (store *Store) CommitScanContext(ctx context.Context, actor uint, lease ScanLease, input DirectoryContextObservation, checkpoint ScanCheckpoint) (*DirectoryContextRef, error) {
	if len(input.Locator) == 0 || len(input.Locator) > 8192 || bytes.IndexByte(input.Locator, 0) >= 0 || (input.Parent.ID == "") != (input.Parent.Revision == 0) || input.Parent.Revision < 0 ||
		validateDirectorySidecar(input.TVShow, mediametadata.TVShow) != nil || validateDirectorySidecar(input.Season, mediametadata.Season) != nil ||
		!sidecarAtDirectory(input.TVShow, input.Locator, "tvshow.nfo") || !sidecarAtDirectory(input.Season, input.Locator, "season.nfo") ||
		!validCheckpoint(checkpoint) || checkpoint.Phase != "enumerate" {
		return nil, fmt.Errorf("%w: directory context", ErrInvalid)
	}
	digest, err := scanDigest("directory_context", struct {
		Input      DirectoryContextObservation
		Checkpoint ScanCheckpoint
	}{input, checkpoint})
	if err != nil {
		return nil, err
	}
	var result *DirectoryContextRef
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		r, err := requireScanLease(ctx, tx, actor, lease, true)
		if err != nil {
			return err
		}
		if r.Checkpoint.Phase != "enumerate" {
			return ErrScanState
		}
		if checkpoint.Sequence == r.Checkpoint.Sequence {
			if !bytes.Equal(digest, r.lastDigest) || !r.lastResult.Valid {
				return ErrRevisionConflict
			}
			result = &DirectoryContextRef{}
			return json.Unmarshal([]byte(r.lastResult.String), result)
		}
		if checkpoint.Sequence != r.Checkpoint.Sequence+1 || checkpoint.Processed != r.Checkpoint.Processed || !bytes.Equal(checkpoint.LastObjectKey, r.Checkpoint.LastObjectKey) {
			return ErrRevisionConflict
		}
		root := contextPathPrefix(r.Binding.RootLocator)
		var parent *directoryContext
		if input.Parent.ID == "" {
			if !bytes.Equal(root, input.Locator) {
				return ErrInvalid
			}
		} else {
			var parentLocator []byte
			parent, err = readDirectoryContext(tx.QueryRowContext(ctx, `SELECT `+directoryContextColumns+` FROM directory_context WHERE id=? AND library_id=? AND binding_id=? AND binding_epoch=?`, input.Parent.ID, r.LibraryID, r.Binding.BindingID, r.Binding.ProofEpoch))
			if errors.Is(err, sql.ErrNoRows) {
				return ErrRevisionConflict
			}
			if err != nil {
				return err
			}
			if parent.Revision != input.Parent.Revision {
				return ErrRevisionConflict
			}
			if err = validateContextAncestorsTx(ctx, tx, r.Binding, parent.ID); err != nil {
				return err
			}
			if err = tx.QueryRowContext(ctx, `SELECT directory_locator FROM directory_context WHERE id=?`, parent.ID).Scan(&parentLocator); err != nil {
				return err
			}
			if !directDirectoryChild(parentLocator, input.Locator) {
				return ErrInvalid
			}
		}
		current, err := readDirectoryContext(tx.QueryRowContext(ctx, `SELECT `+directoryContextColumns+` FROM directory_context WHERE binding_id=? AND binding_epoch=? AND directory_locator=?`, r.Binding.BindingID, r.Binding.ProofEpoch, input.Locator))
		newRow := errors.Is(err, sql.ErrNoRows)
		if err != nil && !newRow {
			return err
		}
		if newRow {
			current = &directoryContext{}
			current.ID, err = randomID("dirctx_")
			if err != nil {
				return err
			}
		}
		before := *current
		current.ParentID, current.ParentRevision = input.Parent.ID, input.Parent.Revision
		current.RulesRevision = r.RulesRevision
		current.TVStatus, current.SeasonStatus = input.TVShow.Status, input.Season.Status
		current.SeriesID, current.SeasonID = "", ""
		current.SeasonNumber = sql.NullInt64{}
		current.SeriesProblem, current.SeasonProblem = "", ""
		if parent != nil {
			current.SeriesID, current.SeasonID, current.SeasonNumber = parent.SeriesID, parent.SeasonID, parent.SeasonNumber
			current.SeriesProblem, current.SeasonProblem = parent.SeriesProblem, parent.SeasonProblem
		}
		if input.TVShow.NFO != nil {
			current.TVKey = bytes.Clone(input.TVShow.NFO.SourceKey)
			current.TVDigest = bytes.Clone(input.TVShow.NFO.Digest)
			current.TVParser = input.TVShow.NFO.ParserVersion
			if current.TVEvidence, err = safeContextEvidence(input.TVShow.NFO); err != nil {
				return err
			}
			current.SeasonID = ""
			current.SeasonNumber = sql.NullInt64{}
			current.SeriesProblem, current.SeasonProblem = "", ""
			current.SeriesID, err = resolveDirectorySeriesTx(ctx, tx, actor, r.Binding, current, input.TVShow.NFO)
			if err != nil {
				return err
			}
			if current.TVStatus == "needs_review" {
				current.SeriesProblem = "conflict"
			}
		} else if input.TVShow.Status != "missing" || current.LocalSeriesID != "" {
			// A missing or unreadable formerly local series is a boundary, not a
			// reason to attach its files to an ancestor series.
			current.SeriesID, current.SeasonID = "", ""
			current.SeasonNumber = sql.NullInt64{}
			current.SeriesProblem = "unavailable"
			current.SeasonProblem = ""
			if input.TVShow.Status == "conflict" {
				current.SeriesProblem = "conflict"
			}
		}
		if input.Season.NFO != nil {
			current.SeasonKey = bytes.Clone(input.Season.NFO.SourceKey)
			current.SeasonDigest = bytes.Clone(input.Season.NFO.Digest)
			current.SeasonParser = input.Season.NFO.ParserVersion
			if current.SeasonEvidence, err = safeContextEvidence(input.Season.NFO); err != nil {
				return err
			}
			current.SeasonID, current.SeasonNumber, err = resolveDirectorySeasonTx(ctx, tx, actor, r.Binding, current, input.Season.NFO)
			if err != nil {
				return err
			}
			if current.SeasonID == "" {
				current.SeasonProblem = "unavailable"
				if current.SeasonStatus == "needs_review" {
					current.SeasonProblem = "conflict"
				}
			} else {
				current.SeasonProblem = ""
			}
		} else if input.Season.Status != "missing" || current.LocalSeasonID != "" {
			current.SeasonID = ""
			current.SeasonNumber = sql.NullInt64{}
			current.SeasonProblem = "unavailable"
			if input.Season.Status == "conflict" {
				current.SeasonProblem = "conflict"
			}
		}
		if newRow {
			current.Revision = 1
		} else if !sameDirectoryContext(before, *current) {
			current.Revision++
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO directory_context(id,library_id,binding_id,binding_epoch,directory_locator,parent_context_id,parent_revision,series_id,season_id,season_number,series_problem,season_problem,local_series_id,local_season_id,tv_status,season_status,tv_key,tv_digest,tv_parser,season_key,season_digest,season_parser,tv_evidence_json,season_evidence_json,rules_revision,revision)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(binding_id,binding_epoch,directory_locator) DO UPDATE SET parent_context_id=excluded.parent_context_id,parent_revision=excluded.parent_revision,series_id=excluded.series_id,season_id=excluded.season_id,season_number=excluded.season_number,series_problem=excluded.series_problem,season_problem=excluded.season_problem,local_series_id=excluded.local_series_id,local_season_id=excluded.local_season_id,tv_status=excluded.tv_status,season_status=excluded.season_status,tv_key=excluded.tv_key,tv_digest=excluded.tv_digest,tv_parser=excluded.tv_parser,season_key=excluded.season_key,season_digest=excluded.season_digest,season_parser=excluded.season_parser,tv_evidence_json=excluded.tv_evidence_json,season_evidence_json=excluded.season_evidence_json,rules_revision=excluded.rules_revision,revision=excluded.revision`,
			current.ID, r.LibraryID, r.Binding.BindingID, r.Binding.ProofEpoch, input.Locator, nullableString(current.ParentID), current.ParentRevision, nullableString(current.SeriesID), nullableString(current.SeasonID), current.SeasonNumber, current.SeriesProblem, current.SeasonProblem, nullableString(current.LocalSeriesID), nullableString(current.LocalSeasonID), current.TVStatus, current.SeasonStatus, current.TVKey, current.TVDigest, current.TVParser, current.SeasonKey, current.SeasonDigest, current.SeasonParser, current.TVEvidence, current.SeasonEvidence, current.RulesRevision, current.Revision)
		if err != nil {
			return err
		}
		result = &DirectoryContextRef{ID: current.ID, Revision: current.Revision}
		if err = saveScanCheckpoint(ctx, tx, r, checkpoint, digest, result); err != nil {
			return err
		}
		return publishScanChange(ctx, tx, actor, r, "media.scan.context")
	})
	return result, err
}

func sameDirectoryContext(a, b directoryContext) bool {
	return a.ParentID == b.ParentID && a.ParentRevision == b.ParentRevision && a.SeriesID == b.SeriesID && a.SeasonID == b.SeasonID && a.SeasonNumber == b.SeasonNumber && a.SeriesProblem == b.SeriesProblem && a.SeasonProblem == b.SeasonProblem &&
		a.LocalSeriesID == b.LocalSeriesID && a.LocalSeasonID == b.LocalSeasonID && a.TVStatus == b.TVStatus && a.SeasonStatus == b.SeasonStatus &&
		bytes.Equal(a.TVKey, b.TVKey) && bytes.Equal(a.TVDigest, b.TVDigest) && a.TVParser == b.TVParser && bytes.Equal(a.SeasonKey, b.SeasonKey) && bytes.Equal(a.SeasonDigest, b.SeasonDigest) && a.SeasonParser == b.SeasonParser && a.RulesRevision == b.RulesRevision
}

func resolveDirectorySeriesTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, current *directoryContext, nfo *MetadataObservation) (string, error) {
	item := nfo.Document.Items[0]
	titles := nonemptyValues(item.Fields["title"])
	if explicitIDConflict(item.ExternalIDs) || len(titles) != 1 {
		current.TVStatus = "needs_review"
		return "", nil
	}
	if _, err := catalogTitle(titles[0]); err != nil {
		current.TVStatus = "needs_review"
		return "", nil
	}
	if current.LocalSeriesID == "" {
		created, err := createCatalogItemTx(ctx, tx, actor, binding.LibraryID, NewCatalogItem{Kind: "series", Title: titles[0], initialSource: "filename", initialBinding: &binding})
		if err != nil {
			return "", err
		}
		current.LocalSeriesID = created.ID
	}
	if err := applyNFOObservationTx(ctx, tx, actor, binding, current.LocalSeriesID, *nfo); err != nil {
		return "", err
	}
	status, err := contextNFOStatus(ctx, tx, binding, current.LocalSeriesID, nfo.SourceKey)
	if err != nil {
		return "", err
	}
	if status != "applied" {
		current.TVStatus = "needs_review"
		return "", nil
	}
	if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_id=item_id,match_state='local_confirmed',issue_code='' WHERE library_id=? AND item_id=?`, binding.LibraryID, current.LocalSeriesID); err != nil {
		return "", err
	}
	return current.LocalSeriesID, nil
}

func contextNFOStatus(ctx context.Context, tx *sql.Tx, binding SourceBindingRef, itemID string, key []byte) (string, error) {
	var status string
	err := tx.QueryRowContext(ctx, `SELECT status FROM metadata_input WHERE library_id=? AND item_id=? AND binding_id=? AND binding_epoch=? AND source_type='nfo' AND source_key=? AND is_current=1`, binding.LibraryID, itemID, binding.BindingID, binding.ProofEpoch, key).Scan(&status)
	return status, err
}

func resolveDirectorySeasonTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, current *directoryContext, nfo *MetadataObservation) (string, sql.NullInt64, error) {
	if current.SeriesID == "" {
		current.SeasonStatus = "orphan"
		return "", sql.NullInt64{}, nil
	}
	item := nfo.Document.Items[0]
	number, issue := mediametadata.SeasonNumber(item)
	if issue != "" || number == nil || explicitIDConflict(item.ExternalIDs) {
		current.SeasonStatus = "needs_review"
		return "", sql.NullInt64{}, nil
	}
	if current.LocalSeasonID != "" {
		var existing int
		err := tx.QueryRowContext(ctx, `SELECT sp.season_number FROM season_position sp JOIN episode_order eo ON eo.library_id=sp.library_id AND eo.id=sp.order_id WHERE sp.library_id=? AND sp.season_id=? AND eo.series_id=? AND eo.scheme='local'`, binding.LibraryID, current.LocalSeasonID, current.SeriesID).Scan(&existing)
		if errors.Is(err, sql.ErrNoRows) || err == nil && existing != *number {
			current.SeasonStatus = "needs_review"
			return "", sql.NullInt64{}, nil
		}
		if err != nil {
			return "", sql.NullInt64{}, err
		}
	}
	seasonID, _, err := ensureSeasonTx(ctx, tx, actor, binding, current.SeriesID, *number)
	if err != nil {
		return "", sql.NullInt64{}, err
	}
	if current.LocalSeasonID != "" && current.LocalSeasonID != seasonID {
		current.SeasonStatus = "needs_review"
		return "", sql.NullInt64{}, nil
	}
	current.LocalSeasonID = seasonID
	if err = applyNFOObservationTx(ctx, tx, actor, binding, seasonID, *nfo); err != nil {
		return "", sql.NullInt64{}, err
	}
	status, err := contextNFOStatus(ctx, tx, binding, seasonID, nfo.SourceKey)
	if err != nil {
		return "", sql.NullInt64{}, err
	}
	if status != "applied" {
		current.SeasonStatus = "needs_review"
		return "", sql.NullInt64{}, nil
	}
	return seasonID, sql.NullInt64{Int64: int64(*number), Valid: true}, nil
}

func ensureLocalOrderTx(ctx context.Context, tx *sql.Tx, libraryID, seriesID string) (string, error) {
	var orderID string
	err := tx.QueryRowContext(ctx, `SELECT eo.id FROM episode_order eo JOIN library_item i ON i.library_id=eo.library_id AND i.id=eo.series_id WHERE eo.library_id=? AND eo.series_id=? AND eo.scheme='local' AND i.kind='series'`, libraryID, seriesID).Scan(&orderID)
	if err == nil {
		return orderID, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	var kind string
	if err = tx.QueryRowContext(ctx, `SELECT kind FROM library_item WHERE library_id=? AND id=?`, libraryID, seriesID).Scan(&kind); err != nil {
		return "", err
	}
	if kind != "series" {
		return "", ErrInvalid
	}
	orderID, err = randomID("order_")
	if err != nil {
		return "", err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO episode_order(id,library_id,series_id,scheme) VALUES(?,?,?,'local')`, orderID, libraryID, seriesID)
	return orderID, err
}

func ensureSeasonTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, seriesID string, number int) (string, string, error) {
	if number < 0 || number > 999 {
		return "", "", ErrInvalid
	}
	orderID, err := ensureLocalOrderTx(ctx, tx, binding.LibraryID, seriesID)
	if err != nil {
		return "", "", err
	}
	var seasonID, kind, parent string
	err = tx.QueryRowContext(ctx, `SELECT sp.season_id,i.kind,COALESCE(i.parent_id,'') FROM season_position sp JOIN library_item i ON i.library_id=sp.library_id AND i.id=sp.season_id WHERE sp.library_id=? AND sp.order_id=? AND sp.season_number=?`, binding.LibraryID, orderID, number).Scan(&seasonID, &kind, &parent)
	if err == nil {
		if kind != "season" || parent != seriesID {
			return "", "", ErrRevisionConflict
		}
		return seasonID, orderID, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", "", err
	}
	season, err := createCatalogItemTx(ctx, tx, actor, binding.LibraryID, NewCatalogItem{Kind: "season", ParentID: seriesID, Title: fmt.Sprintf("Season %d", number), initialSource: "filename", initialBinding: &binding})
	if err != nil {
		return "", "", err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_id=?,season_id=item_id,match_state='local_confirmed' WHERE library_id=? AND item_id=?`, seriesID, binding.LibraryID, season.ID); err != nil {
		return "", "", err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO season_position(library_id,order_id,season_id,season_number) VALUES(?,?,?,?)`, binding.LibraryID, orderID, season.ID, number); err != nil {
		return "", "", err
	}
	return season.ID, orderID, nil
}

func currentDirectoryForFile(ctx context.Context, tx *sql.Tx, r *scanRecord, input ScanObservation) (*directoryContext, error) {
	ref := input.DirectoryContext
	if ref.ID == "" && ref.Revision == 0 {
		return nil, nil
	}
	if ref.ID == "" || ref.Revision < 1 || input.ParsedName == nil {
		return nil, ErrInvalid
	}
	current, err := readDirectoryContext(tx.QueryRowContext(ctx, `SELECT `+directoryContextColumns+` FROM directory_context WHERE id=? AND library_id=? AND binding_id=? AND binding_epoch=?`, ref.ID, r.LibraryID, r.Binding.BindingID, r.Binding.ProofEpoch))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrRevisionConflict
	}
	if err != nil {
		return nil, err
	}
	if current.Revision != ref.Revision || current.RulesRevision != r.RulesRevision {
		return nil, ErrRevisionConflict
	}
	if err = validateContextAncestorsTx(ctx, tx, r.Binding, current.ID); err != nil {
		return nil, err
	}
	var directory []byte
	if err = tx.QueryRowContext(ctx, `SELECT directory_locator FROM directory_context WHERE id=?`, ref.ID).Scan(&directory); err != nil {
		return nil, err
	}
	if !directDirectoryChild(directory, input.Locator) {
		return nil, ErrInvalid
	}
	last := bytes.LastIndexByte(input.Locator, '/')
	if last < 0 || !bytes.Equal(input.Locator[last+1:], []byte(input.ParsedName.OriginalName)) {
		return nil, ErrInvalid
	}
	return current, nil
}

func recordNameEvidenceTx(ctx context.Context, tx *sql.Tx, r *scanRecord, fileID string, ref DirectoryContextRef, name *mediametadata.ParsedName, resolution mediametadata.EpisodeResolution) (string, error) {
	if name == nil {
		return "", nil
	}
	nameJSON, err := json.Marshal(name)
	if err != nil {
		return "", err
	}
	if len(nameJSON) > 65536 {
		// The original name is retained; unusually dense derived evidence is
		// held for review instead of expanding the database without bound.
		compact := mediametadata.ParsedName{OriginalName: name.OriginalName, Ambiguities: []string{"name_evidence_too_large"}}
		nameJSON, err = json.Marshal(compact)
		if err != nil {
			return "", err
		}
		resolution.State = "needs_review"
		resolution.Issues = []string{"name_evidence_too_large"}
	}
	state := resolution.State
	if state == "resolved" {
		state = "local_confirmed"
	}
	if state != "unmatched" && state != "local_confirmed" && state != "needs_review" {
		return "", ErrInvalid
	}
	issues, err := json.Marshal(resolution.Issues)
	if err != nil {
		return "", err
	}
	var id, oldName, oldState, oldIssues, oldContext string
	var oldRevision, oldParser, oldContextRevision int64
	err = tx.QueryRowContext(ctx, `SELECT id,evidence_json,match_state,issue_json,COALESCE(context_id,''),context_revision,parser_revision,revision FROM identification_evidence WHERE library_id=? AND source_file_id=? AND binding_id=? AND binding_epoch=?`, r.LibraryID, fileID, r.Binding.BindingID, r.Binding.ProofEpoch).Scan(&id, &oldName, &oldState, &oldIssues, &oldContext, &oldContextRevision, &oldParser, &oldRevision)
	if errors.Is(err, sql.ErrNoRows) {
		id, err = randomID("evidence_")
		if err != nil {
			return "", err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO identification_evidence(id,library_id,source_file_id,binding_id,binding_epoch,context_id,context_revision,parser_revision,evidence_json,match_state,issue_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)`, id, r.LibraryID, fileID, r.Binding.BindingID, r.Binding.ProofEpoch, nullableString(ref.ID), ref.Revision, r.RulesRevision, string(nameJSON), state, string(issues))
		return id, err
	}
	if err != nil {
		return "", err
	}
	if oldName != string(nameJSON) || oldState != state || oldIssues != string(issues) || oldContext != ref.ID || oldContextRevision != ref.Revision || oldParser != r.RulesRevision {
		_, err = tx.ExecContext(ctx, `UPDATE identification_evidence SET context_id=?,context_revision=?,parser_revision=?,evidence_json=?,match_state=?,issue_json=?,revision=revision+1 WHERE id=?`, nullableString(ref.ID), ref.Revision, r.RulesRevision, string(nameJSON), state, string(issues), id)
	}
	return id, err
}

func markNewEpisodeTx(ctx context.Context, tx *sql.Tx, libraryID, seriesID string) error {
	// Only creation of a distinct position advances the parent series. Adding
	// another variant or rescanning never changes this value.
	_, err := tx.ExecContext(ctx, `UPDATE item_structure SET series_updated_at=max(first_added_at,COALESCE(series_updated_at,first_added_at),?),structure_revision=structure_revision+1 WHERE library_id=? AND item_id=?`, time.Now().UnixMilli(), libraryID, seriesID)
	return err
}

type scanMapping struct {
	ItemID, Kind, SeriesID, SeasonID, SupersededBy string
	Manual                                         bool
}

func scanFileMappingsTx(ctx context.Context, tx *sql.Tx, binding SourceBindingRef, fileID string) ([]scanMapping, error) {
	rows, err := tx.QueryContext(ctx, `SELECT i.id,i.kind,COALESCE(s.series_id,''),COALESCE(s.season_id,''),COALESCE(s.superseded_by,''),MAX(CASE WHEN m.confirmed_by='manual' THEN 1 ELSE 0 END)
FROM file_item_mapping m JOIN library_item i ON i.library_id=m.library_id AND i.id=m.item_id JOIN item_structure s ON s.library_id=i.library_id AND s.item_id=i.id
WHERE m.library_id=? AND m.source_file_id=? AND m.binding_id=? AND m.binding_epoch=? AND m.active=1 GROUP BY i.id,i.kind,s.series_id,s.season_id,s.superseded_by`, binding.LibraryID, fileID, binding.BindingID, binding.ProofEpoch)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	var result []scanMapping
	for rows.Next() {
		var item scanMapping
		var manual int
		if err = rows.Scan(&item.ItemID, &item.Kind, &item.SeriesID, &item.SeasonID, &item.SupersededBy, &manual); err != nil {
			return nil, err
		}
		item.Manual = manual != 0
		result = append(result, item)
	}
	return result, rows.Err()
}

// A fresh native observation may safely retain a sole, unedited filename-only
// video item. Do not carry episode identities, NFO inputs, manual fields or
// explicit manual associations across binding epochs on this basis.
func recoverFilenameVideoTx(ctx context.Context, tx *sql.Tx, libraryID, fileID string) (string, error) {
	rows, err := tx.QueryContext(ctx, `SELECT DISTINCT i.id FROM file_item_mapping m
JOIN library_item i ON i.library_id=m.library_id AND i.id=m.item_id AND i.kind='video'
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id
WHERE m.library_id=? AND m.source_file_id=? AND m.active=1 AND m.confirmed_by IN ('legacy','local') AND st.match_state='unmatched' AND st.superseded_by IS NULL
AND EXISTS(SELECT 1 FROM item_field f WHERE f.library_id=i.library_id AND f.item_id=i.id AND f.field='title' AND f.source='filename' AND f.locked=0 AND f.explicit_clear=0)
AND NOT EXISTS(SELECT 1 FROM item_field f WHERE f.library_id=i.library_id AND f.item_id=i.id AND (f.source!='filename' OR f.locked!=0 OR f.explicit_clear!=0))
AND NOT EXISTS(SELECT 1 FROM metadata_input mi WHERE mi.library_id=i.library_id AND mi.item_id=i.id)
AND NOT EXISTS(SELECT 1 FROM metadata_revision mr WHERE mr.library_id=i.library_id AND mr.item_id=i.id AND mr.origin!='filename')
AND NOT EXISTS(SELECT 1 FROM file_item_mapping manual WHERE manual.library_id=i.library_id AND manual.item_id=i.id AND manual.active=1 AND manual.confirmed_by='manual')
AND NOT EXISTS(SELECT 1 FROM edition e JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id WHERE e.library_id=i.library_id AND e.item_id=i.id AND v.source_file_id!=?)
LIMIT 2`, libraryID, fileID, fileID)
	if err != nil {
		return "", err
	}
	defer func() { _ = rows.Close() }()
	var candidate string
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return "", err
		}
		if candidate != "" {
			return "", nil
		}
		candidate = id
	}
	return candidate, rows.Err()
}

func reviewResolution(resolution *mediametadata.EpisodeResolution, issue string) {
	resolution.State = "needs_review"
	resolution.Positions = nil
	for _, existing := range resolution.Issues {
		if existing == issue {
			return
		}
	}
	resolution.Issues = append(resolution.Issues, issue)
}

// Missing, unreadable or malformed source metadata is not affirmative
// evidence that a previously established file-to-episode mapping is wrong.
func unavailableResolution(resolution *mediametadata.EpisodeResolution, issue string) {
	if resolution.State == "needs_review" {
		return
	}
	resolution.State = "unmatched"
	resolution.Positions = nil
	for _, existing := range resolution.Issues {
		if existing == issue {
			return
		}
	}
	resolution.Issues = append(resolution.Issues, issue)
}

func seriesNameConflictTx(ctx context.Context, tx *sql.Tx, binding SourceBindingRef, seriesID string, name mediametadata.ParsedName) (bool, error) {
	for _, id := range name.ProviderIDs {
		if id.MediaType != "tv" {
			continue
		}
		var count int
		err := tx.QueryRowContext(ctx, `SELECT count(*) FROM item_external_id x JOIN metadata_input mi ON mi.id=x.input_id JOIN library_source ls ON ls.binding_id=mi.binding_id
WHERE x.library_id=? AND x.item_id=? AND x.provider=? AND x.entity_kind='tvshow' AND x.active=1 AND x.external_id!=? AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1`, binding.LibraryID, seriesID, id.Provider, id.ID).Scan(&count)
		if err != nil {
			return false, err
		}
		if count > 0 {
			return true, nil
		}
	}
	return false, nil
}

func episodeIDsConflictTx(ctx context.Context, tx *sql.Tx, binding SourceBindingRef, itemID string, item mediametadata.Item) (bool, error) {
	if explicitIDConflict(item.ExternalIDs) {
		return true, nil
	}
	for _, id := range item.ExternalIDs {
		provider, value, ok := safeExternalID(id)
		if !ok {
			continue
		}
		var count int
		err := tx.QueryRowContext(ctx, `SELECT count(*) FROM item_external_id x JOIN metadata_input mi ON mi.id=x.input_id JOIN library_source ls ON ls.binding_id=mi.binding_id
WHERE x.library_id=? AND x.item_id=? AND x.provider=? AND x.entity_kind='episodedetails' AND x.active=1 AND x.external_id!=? AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1`, binding.LibraryID, itemID, provider, value).Scan(&count)
		if err != nil {
			return false, err
		}
		if count > 0 {
			return true, nil
		}
	}
	return false, nil
}

func episodeNFOForPosition(nfo *MetadataObservation, position mediametadata.SeasonEpisode, contextSeason *int, total int) *MetadataObservation {
	if nfo == nil || len(nfo.Document.Items) == 0 {
		return nil
	}
	if len(nfo.Document.Items) == 1 && total == 1 {
		return nfo
	}
	for _, item := range nfo.Document.Items {
		one := mediametadata.Document{Items: []mediametadata.Item{item}}
		parsed := mediametadata.ResolveEpisode(mediametadata.ParsedName{}, &one, contextSeason)
		if parsed.State == "resolved" && len(parsed.Positions) == 1 && parsed.Positions[0] == position {
			copy := *nfo
			copy.Document = one
			return &copy
		}
	}
	return nil
}

func lookupEpisodeTx(ctx context.Context, tx *sql.Tx, libraryID, seriesID string, position mediametadata.SeasonEpisode) (string, error) {
	var id string
	err := tx.QueryRowContext(ctx, `SELECT ep.episode_id FROM episode_position ep JOIN episode_order eo ON eo.library_id=ep.library_id AND eo.id=ep.order_id
JOIN season_position sp ON sp.library_id=ep.library_id AND sp.order_id=ep.order_id AND sp.season_number=ep.season_number
JOIN library_item i ON i.library_id=ep.library_id AND i.id=ep.episode_id AND i.kind='episode' AND i.parent_id=sp.season_id
WHERE ep.library_id=? AND eo.series_id=? AND eo.scheme='local' AND ep.season_number=? AND ep.episode_number=?`, libraryID, seriesID, position.Season, position.Episode).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return id, err
}

func canPromoteVideoTx(ctx context.Context, tx *sql.Tx, libraryID, fileID string, mapping scanMapping) (bool, error) {
	if mapping.Kind != "video" || mapping.Manual || mapping.SupersededBy != "" {
		return false, nil
	}
	var blocked int
	err := tx.QueryRowContext(ctx, `SELECT
(SELECT count(*) FROM item_field WHERE library_id=? AND item_id=? AND (locked=1 OR explicit_clear=1 OR source='manual'))+
(SELECT count(*) FROM metadata_input WHERE library_id=? AND item_id=? AND is_current=1 AND status='applied' AND json_extract(evidence_json,'$.items[0].kind')='movie')+
(SELECT count(DISTINCT v.source_file_id) FROM edition e JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id WHERE e.library_id=? AND e.item_id=? AND v.source_file_id!=?)+
(SELECT count(*) FROM file_item_mapping WHERE library_id=? AND item_id=? AND active=1 AND confirmed_by='manual')`, libraryID, mapping.ItemID, libraryID, mapping.ItemID, libraryID, mapping.ItemID, fileID, libraryID, mapping.ItemID).Scan(&blocked)
	return blocked == 0, err
}

func promoteVideoTx(ctx context.Context, tx *sql.Tx, actor uint, libraryID, itemID, seriesID, seasonID string) error {
	var revision int64
	if err := tx.QueryRowContext(ctx, `SELECT revision FROM library_item WHERE library_id=? AND id=? AND kind='video'`, libraryID, itemID).Scan(&revision); err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, `UPDATE library_item SET kind='episode',parent_id=?,revision=revision+1 WHERE library_id=? AND id=? AND kind='video' AND revision=?`, seasonID, libraryID, itemID, revision)
	if err != nil {
		return err
	}
	if count, _ := result.RowsAffected(); count != 1 {
		return ErrRevisionConflict
	}
	if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_id=?,season_id=?,match_state='local_confirmed',issue_code='',structure_revision=structure_revision+1 WHERE library_id=? AND item_id=?`, seriesID, seasonID, libraryID, itemID); err != nil {
		return err
	}
	return publishCatalogChange(ctx, tx, actor, libraryID, "catalog.item.structure.changed", catalogChange{ItemID: itemID, Revision: revision + 1})
}

func attachScanMappingTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, itemID, fileID, evidenceID, role string) error {
	var revision int64
	if err := tx.QueryRowContext(ctx, `SELECT revision FROM library_item WHERE library_id=? AND id=?`, binding.LibraryID, itemID).Scan(&revision); err != nil {
		return err
	}
	link, err := attachCatalogFileTx(ctx, tx, actor, binding.LibraryID, itemID, revision, fileID)
	if err != nil {
		return err
	}
	mappingID, err := randomID("map_")
	if err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO file_item_mapping(id,library_id,source_file_id,item_id,variant_id,binding_id,binding_epoch,role,evidence_id,confirmed_by)
VALUES(?,?,?,?,?,?,?,?,?,'local') ON CONFLICT DO NOTHING`, mappingID, binding.LibraryID, fileID, itemID, link.VariantID, binding.BindingID, binding.ProofEpoch, role, nullableString(evidenceID)); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `UPDATE file_item_mapping SET role=?,evidence_id=?,confirmed_by='local',mapping_revision=mapping_revision+CASE WHEN role!=? OR evidence_id IS NOT ? OR confirmed_by!='local' THEN 1 ELSE 0 END WHERE library_id=? AND item_id=? AND source_file_id=? AND binding_id=? AND binding_epoch=? AND active=1 AND confirmed_by IN ('legacy','local')`, role, nullableString(evidenceID), role, nullableString(evidenceID), binding.LibraryID, itemID, fileID, binding.BindingID, binding.ProofEpoch)
	return err
}

func episodeShowTitleConflictTx(ctx context.Context, tx *sql.Tx, binding SourceBindingRef, seriesID string, nfo *MetadataObservation) (bool, error) {
	if nfo == nil {
		return false, nil
	}
	var declared []string
	for _, item := range nfo.Document.Items {
		for _, title := range nonemptyValues(item.Fields["showtitle"]) {
			title = strings.Join(strings.Fields(title), " ")
			declared = append(declared, title)
		}
	}
	if len(declared) == 0 {
		return false, nil
	}
	rows, err := tx.QueryContext(ctx, `SELECT evidence_json FROM metadata_input WHERE library_id=? AND item_id=? AND binding_id=? AND binding_epoch=? AND source_type='nfo' AND is_current=1 AND status='applied'`, binding.LibraryID, seriesID, binding.BindingID, binding.ProofEpoch)
	if err != nil {
		return false, err
	}
	defer func() { _ = rows.Close() }()
	var titles []string
	for rows.Next() {
		var raw string
		if err = rows.Scan(&raw); err != nil {
			return false, err
		}
		var doc mediametadata.Document
		if err = json.Unmarshal([]byte(raw), &doc); err != nil {
			return false, err
		}
		for _, item := range doc.Items {
			if item.Kind != mediametadata.TVShow {
				continue
			}
			for _, key := range []string{"title", "originaltitle"} {
				for _, title := range nonemptyValues(item.Fields[key]) {
					titles = append(titles, strings.Join(strings.Fields(title), " "))
				}
			}
		}
	}
	if err = rows.Err(); err != nil {
		return false, err
	}
	if len(titles) == 0 {
		return false, nil
	}
	for _, candidate := range declared {
		found := false
		for _, title := range titles {
			if strings.EqualFold(candidate, title) {
				found = true
				break
			}
		}
		if !found {
			return true, nil
		}
	}
	return false, nil
}

type structureDecision struct {
	ItemID     string
	EvidenceID string
	Handled    bool
	Resolution mediametadata.EpisodeResolution
}

// identifyScanFileTx is invoked within the existing file/checkpoint transaction.
// It never chooses an item by filename or an arbitrary first variant. An old
// mapping that disagrees with current evidence is kept for review.
func identifyScanFileTx(ctx context.Context, tx *sql.Tx, actor uint, r *scanRecord, fileID string, input ScanObservation, sameGeneration bool) (structureDecision, error) {
	decision := structureDecision{Resolution: mediametadata.EpisodeResolution{State: "unmatched"}}
	current, err := currentDirectoryForFile(ctx, tx, r, input)
	if err != nil {
		return decision, err
	}
	if input.ParsedName == nil {
		return decision, nil
	}
	mappings, err := scanFileMappingsTx(ctx, tx, r.Binding, fileID)
	if err != nil {
		return decision, err
	}
	var season *int
	if current != nil && current.SeasonNumber.Valid {
		value := int(current.SeasonNumber.Int64)
		season = &value
	}
	if current != nil && current.SeriesID != "" {
		var doc *mediametadata.Document
		if input.NFO != nil {
			doc = &input.NFO.Document
		}
		decision.Resolution = mediametadata.ResolveEpisode(*input.ParsedName, doc, season)
		switch current.SeasonProblem {
		case "conflict":
			reviewResolution(&decision.Resolution, "season_context_conflict")
		case "unavailable":
			unavailableResolution(&decision.Resolution, "season_context_unavailable")
		}
		if input.NFOStatus == "invalid" || input.NFOStatus == "unavailable" || input.NFOStatus == "changed" {
			unavailableResolution(&decision.Resolution, "episode_nfo_unavailable")
		}
		if input.NFO != nil && input.NFO.AssociationAmbiguous {
			reviewResolution(&decision.Resolution, "ambiguous_nfo_association")
		}
		conflict, err := seriesNameConflictTx(ctx, tx, r.Binding, current.SeriesID, *input.ParsedName)
		if err != nil {
			return decision, err
		}
		if conflict {
			reviewResolution(&decision.Resolution, "series_external_id_conflict")
		}
		conflict, err = episodeShowTitleConflictTx(ctx, tx, r.Binding, current.SeriesID, input.NFO)
		if err != nil {
			return decision, err
		}
		if conflict {
			reviewResolution(&decision.Resolution, "series_title_conflict")
		}
		if input.NFO != nil {
			for _, item := range input.NFO.Document.Items {
				if explicitIDConflict(item.ExternalIDs) {
					reviewResolution(&decision.Resolution, "episode_external_id_conflict")
				}
			}
		}
	} else if current != nil && (current.SeriesProblem == "conflict" || current.SeasonProblem == "conflict") {
		reviewResolution(&decision.Resolution, "series_context_conflict")
	} else if current != nil && (current.SeriesProblem == "unavailable" || current.SeasonProblem == "unavailable") {
		unavailableResolution(&decision.Resolution, "series_context_unavailable")
	}
	if encoded, err := json.Marshal(input.ParsedName); err != nil {
		return decision, err
	} else if len(encoded) > 65536 {
		reviewResolution(&decision.Resolution, "name_evidence_too_large")
	}

	var expected []string
	if decision.Resolution.State == "resolved" {
		for _, position := range decision.Resolution.Positions {
			itemID, err := lookupEpisodeTx(ctx, tx, r.LibraryID, current.SeriesID, position)
			if err != nil {
				return decision, err
			}
			expected = append(expected, itemID)
			if itemID != "" {
				nfo := episodeNFOForPosition(input.NFO, position, season, len(decision.Resolution.Positions))
				if nfo != nil {
					conflict, err := episodeIDsConflictTx(ctx, tx, r.Binding, itemID, nfo.Document.Items[0])
					if err != nil {
						return decision, err
					}
					if conflict {
						reviewResolution(&decision.Resolution, "episode_external_id_conflict")
						break
					}
				}
			}
		}
	}
	var video *scanMapping
	if decision.Resolution.State == "resolved" {
		expectedSet := map[string]bool{}
		for _, id := range expected {
			if id != "" {
				expectedSet[id] = true
			}
		}
		episodes := 0
		for _, mapping := range mappings {
			if mapping.SupersededBy != "" {
				continue
			}
			switch mapping.Kind {
			case "episode":
				episodes++
				if !expectedSet[mapping.ItemID] {
					reviewResolution(&decision.Resolution, "existing_mapping_conflict")
				}
			case "video":
				if video != nil {
					reviewResolution(&decision.Resolution, "multiple_existing_items")
					break
				}
				copy := mapping
				video = &copy
			default:
				reviewResolution(&decision.Resolution, "existing_mapping_conflict")
			}
		}
		// Since all positions for one file were committed together, a different
		// set on rescan requires review rather than silently adding/removing one.
		if episodes > 0 && episodes != len(expected) {
			reviewResolution(&decision.Resolution, "existing_mapping_conflict")
		}
		if video != nil && decision.Resolution.State == "resolved" {
			allowed, err := canPromoteVideoTx(ctx, tx, r.LibraryID, fileID, *video)
			if err != nil {
				return decision, err
			}
			if !allowed {
				reviewResolution(&decision.Resolution, "manual_or_modified_item")
			}
		}
	}
	if sameGeneration {
		var priorState, priorIssues string
		err = tx.QueryRowContext(ctx, `SELECT match_state,issue_json FROM identification_evidence WHERE library_id=? AND source_file_id=? AND binding_id=? AND binding_epoch=?`, r.LibraryID, fileID, r.Binding.BindingID, r.Binding.ProofEpoch).Scan(&priorState, &priorIssues)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return decision, err
		}
		if err == nil && priorState == "needs_review" {
			var issues []string
			if err = json.Unmarshal([]byte(priorIssues), &issues); err != nil {
				return decision, err
			}
			if len(issues) == 0 {
				issues = []string{"prior_observation_conflict"}
			}
			for _, issue := range issues {
				reviewResolution(&decision.Resolution, issue)
			}
		}
	}
	decision.EvidenceID, err = recordNameEvidenceTx(ctx, tx, r, fileID, input.DirectoryContext, input.ParsedName, decision.Resolution)
	if err != nil {
		return decision, err
	}
	// Keep every non-manual mapping for this binding tied to the shared file
	// evidence, including pre-v9 legacy mappings. A conflict can then hide
	// only inferred episode links while the unmatched file remains playable.
	if _, err = tx.ExecContext(ctx, `UPDATE file_item_mapping SET evidence_id=?,mapping_revision=mapping_revision+CASE WHEN evidence_id IS NOT ? THEN 1 ELSE 0 END WHERE library_id=? AND source_file_id=? AND binding_id=? AND binding_epoch=? AND active=1 AND confirmed_by IN ('legacy','local')`, decision.EvidenceID, decision.EvidenceID, r.LibraryID, fileID, r.Binding.BindingID, r.Binding.ProofEpoch); err != nil {
		return decision, err
	}
	if decision.Resolution.State != "resolved" {
		return decision, nil
	}
	role := "single"
	if len(decision.Resolution.Positions) > 1 {
		role = "combined"
	}
	var firstID string
	videoConsumed := false
	for index, position := range decision.Resolution.Positions {
		seasonID, orderID, err := ensureSeasonTx(ctx, tx, actor, r.Binding, current.SeriesID, position.Season)
		if err != nil {
			return decision, err
		}
		itemID := expected[index]
		if itemID == "" {
			if video != nil && !videoConsumed {
				itemID = video.ItemID
				if err = promoteVideoTx(ctx, tx, actor, r.LibraryID, itemID, current.SeriesID, seasonID); err != nil {
					return decision, err
				}
				videoConsumed = true
			} else {
				item, err := createCatalogItemTx(ctx, tx, actor, r.LibraryID, NewCatalogItem{Kind: "episode", ParentID: seasonID, Title: input.InitialTitle, initialSource: "filename", initialBinding: &r.Binding})
				if err != nil {
					return decision, err
				}
				itemID = item.ID
				if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_id=?,season_id=?,match_state='local_confirmed' WHERE library_id=? AND item_id=?`, current.SeriesID, seasonID, r.LibraryID, itemID); err != nil {
					return decision, err
				}
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO episode_position(library_id,order_id,season_number,episode_number,episode_id) VALUES(?,?,?,?,?)`, r.LibraryID, orderID, position.Season, position.Episode, itemID); err != nil {
				return decision, err
			}
			if err = markNewEpisodeTx(ctx, tx, r.LibraryID, current.SeriesID); err != nil {
				return decision, err
			}
		}
		if firstID == "" {
			firstID = itemID
		}
		if err = attachScanMappingTx(ctx, tx, actor, r.Binding, itemID, fileID, decision.EvidenceID, role); err != nil {
			return decision, err
		}
		if err = applyFilenameObservationTx(ctx, tx, actor, r.Binding, itemID, input.InitialTitle); err != nil {
			return decision, err
		}
		if nfo := episodeNFOForPosition(input.NFO, position, season, len(decision.Resolution.Positions)); nfo != nil {
			if err = applyNFOObservationTx(ctx, tx, actor, r.Binding, itemID, *nfo); err != nil {
				return decision, err
			}
		}
	}
	if video != nil && !videoConsumed && firstID != "" {
		// Keep the historical item, metadata and links available to old refs,
		// while a structured catalog can omit the redundant unmatched card.
		if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET superseded_by=?,structure_revision=structure_revision+1 WHERE library_id=? AND item_id=? AND superseded_by IS NULL`, firstID, r.LibraryID, video.ItemID); err != nil {
			return decision, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET first_added_at=min(first_added_at,(SELECT first_added_at FROM item_structure WHERE library_id=? AND item_id=?)) WHERE library_id=? AND item_id=?`, r.LibraryID, video.ItemID, r.LibraryID, firstID); err != nil {
			return decision, err
		}
	}
	decision.ItemID, decision.Handled = firstID, true
	return decision, nil
}

// ItemStructure is an internal catalog projection. A service returning it to
// a viewer must first apply its current per-source visibility checks; a
// library membership or these IDs are not themselves a source read grant.
type ItemStructure struct {
	ItemID, Kind, SeriesID, SeasonID, SupersededBy string
	MatchState, IssueCode                          string
	Revision, FirstAddedAt                         int64
	SeriesUpdatedAt                                *int64
}

type StructureSeason struct {
	SeasonID string
	Number   int
}

type StructureEpisode struct {
	EpisodeID, SeasonID         string
	SeasonNumber, EpisodeNumber int
}

type SeasonStructurePage struct {
	OrderID    string
	Items      []StructureSeason
	NextNumber *int
}

type EpisodeStructurePage struct {
	OrderID    string
	Items      []StructureEpisode
	NextNumber *int
}

func (store *Store) GetItemStructure(ctx context.Context, actor uint, libraryID, itemID string) (*ItemStructure, error) {
	if _, err := activeCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return nil, err
	}
	var value ItemStructure
	var updated sql.NullInt64
	err := store.db.QueryRowContext(ctx, `SELECT i.id,i.kind,COALESCE(s.series_id,''),COALESCE(s.season_id,''),COALESCE(s.superseded_by,''),s.match_state,s.issue_code,s.structure_revision,s.first_added_at,s.series_updated_at FROM item_structure s JOIN library_item i ON i.library_id=s.library_id AND i.id=s.item_id WHERE s.library_id=? AND s.item_id=?`, libraryID, itemID).Scan(&value.ItemID, &value.Kind, &value.SeriesID, &value.SeasonID, &value.SupersededBy, &value.MatchState, &value.IssueCode, &value.Revision, &value.FirstAddedAt, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if updated.Valid {
		value.SeriesUpdatedAt = &updated.Int64
	}
	return &value, nil
}

// ListStructureSeasons and ListStructureEpisodes expose only bounded local
// positions, never raw paths or unfiltered counts. The service must filter each
// returned item before displaying it and before computing any episode counts.
func (store *Store) ListStructureSeasons(ctx context.Context, actor uint, libraryID, seriesID string, after *int, limit int) (*SeasonStructurePage, error) {
	if limit < 1 || limit > 100 || after != nil && (*after < 0 || *after > 999) {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return nil, err
	}
	var kind string
	if err := store.db.QueryRowContext(ctx, `SELECT kind FROM library_item WHERE library_id=? AND id=?`, libraryID, seriesID).Scan(&kind); errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	} else if err != nil {
		return nil, err
	}
	if kind != "series" {
		return nil, ErrInvalid
	}
	page := &SeasonStructurePage{Items: []StructureSeason{}}
	err := store.db.QueryRowContext(ctx, `SELECT id FROM episode_order WHERE library_id=? AND series_id=? AND scheme='local'`, libraryID, seriesID).Scan(&page.OrderID)
	if errors.Is(err, sql.ErrNoRows) {
		return page, nil
	}
	if err != nil {
		return nil, err
	}
	start := -1
	if after != nil {
		start = *after
	}
	rows, err := store.db.QueryContext(ctx, `SELECT sp.season_id,sp.season_number FROM season_position sp JOIN library_item i ON i.library_id=sp.library_id AND i.id=sp.season_id AND i.kind='season' AND i.parent_id=? WHERE sp.library_id=? AND sp.order_id=? AND sp.season_number>? ORDER BY sp.season_number LIMIT ?`, seriesID, libraryID, page.OrderID, start, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		var item StructureSeason
		if err = rows.Scan(&item.SeasonID, &item.Number); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			number := page.Items[len(page.Items)-1].Number
			page.NextNumber = &number
			break
		}
		page.Items = append(page.Items, item)
	}
	return page, rows.Err()
}

func (store *Store) ListStructureEpisodes(ctx context.Context, actor uint, libraryID, seriesID, seasonID string, after *int, limit int) (*EpisodeStructurePage, error) {
	if limit < 1 || limit > 100 || after != nil && (*after < 0 || *after > 9999) {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return nil, err
	}
	page := &EpisodeStructurePage{Items: []StructureEpisode{}}
	var seasonNumber int
	err := store.db.QueryRowContext(ctx, `SELECT eo.id,sp.season_number FROM episode_order eo JOIN season_position sp ON sp.library_id=eo.library_id AND sp.order_id=eo.id JOIN library_item s ON s.library_id=sp.library_id AND s.id=sp.season_id AND s.kind='season' AND s.parent_id=eo.series_id JOIN library_item series ON series.library_id=eo.library_id AND series.id=eo.series_id AND series.kind='series' WHERE eo.library_id=? AND eo.series_id=? AND eo.scheme='local' AND sp.season_id=?`, libraryID, seriesID, seasonID).Scan(&page.OrderID, &seasonNumber)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	start := -1
	if after != nil {
		start = *after
	}
	rows, err := store.db.QueryContext(ctx, `SELECT ep.episode_id,ep.season_number,ep.episode_number FROM episode_position ep JOIN library_item i ON i.library_id=ep.library_id AND i.id=ep.episode_id AND i.kind='episode' AND i.parent_id=? WHERE ep.library_id=? AND ep.order_id=? AND ep.season_number=? AND ep.episode_number>? ORDER BY ep.episode_number LIMIT ?`, seasonID, libraryID, page.OrderID, seasonNumber, start, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		item := StructureEpisode{SeasonID: seasonID}
		if err = rows.Scan(&item.EpisodeID, &item.SeasonNumber, &item.EpisodeNumber); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			number := page.Items[len(page.Items)-1].EpisodeNumber
			page.NextNumber = &number
			break
		}
		page.Items = append(page.Items, item)
	}
	return page, rows.Err()
}

type StructureFileMapping struct {
	VariantID, SourceFileID, Role string
	IndependentStart              bool
	CoveredEpisodeIDs             []string
}

type FileIdentification struct {
	Name       mediametadata.ParsedName
	MatchState string
	Issues     []string
	Revision   int64
}

// GetFileIdentification is source-scoped evidence for the calling service;
// live source authorization must still be verified before exposing it.
func (store *Store) GetFileIdentification(ctx context.Context, actor uint, libraryID, sourceFileID, bindingID string, bindingEpoch int64) (*FileIdentification, error) {
	if bindingID == "" || bindingEpoch < 1 {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return nil, err
	}
	var value FileIdentification
	var name, issues string
	err := store.db.QueryRowContext(ctx, `SELECT ie.evidence_json,ie.match_state,ie.issue_json,ie.revision FROM identification_evidence ie JOIN library_source ls ON ls.binding_id=ie.binding_id AND ls.library_id=ie.library_id JOIN library_file lf ON lf.library_id=ls.library_id AND lf.source_id=ls.source_id AND lf.root_locator=ls.root_locator AND lf.source_file_id=ie.source_file_id WHERE ie.library_id=? AND ie.source_file_id=? AND ie.binding_id=? AND ie.binding_epoch=? AND ie.binding_epoch=ls.proof_epoch AND lf.binding_epoch=ie.binding_epoch AND ls.active_proof_version=1`, libraryID, sourceFileID, bindingID, bindingEpoch).Scan(&name, &value.MatchState, &issues, &value.Revision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err = json.Unmarshal([]byte(name), &value.Name); err != nil {
		return nil, err
	}
	if err = json.Unmarshal([]byte(issues), &value.Issues); err != nil {
		return nil, err
	}
	return &value, nil
}

// GetStructureFileMapping describes one already-selected variant. Callers must
// authorize that source and every covered item independently before publishing
// it; filtered IDs and counts must never be inferred from hidden mappings.
func (store *Store) GetStructureFileMapping(ctx context.Context, actor uint, libraryID, itemID, variantID, bindingID string, bindingEpoch int64) (*StructureFileMapping, error) {
	if bindingID == "" || bindingEpoch < 1 {
		return nil, ErrInvalid
	}
	if _, err := activeCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return nil, err
	}
	value := &StructureFileMapping{VariantID: variantID, CoveredEpisodeIDs: []string{}}
	err := store.db.QueryRowContext(ctx, `SELECT m.source_file_id,m.role FROM file_item_mapping m JOIN library_source ls ON ls.binding_id=m.binding_id AND ls.library_id=m.library_id JOIN library_item i ON i.library_id=m.library_id AND i.id=m.item_id WHERE m.library_id=? AND m.item_id=? AND m.variant_id=? AND m.binding_id=? AND m.binding_epoch=? AND m.active=1 AND ls.proof_epoch=m.binding_epoch AND ls.active_proof_version=1 AND (i.kind!='episode' OR m.confirmed_by='manual' OR m.evidence_id IS NULL OR EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=m.evidence_id AND ie.match_state!='needs_review'))`, libraryID, itemID, variantID, bindingID, bindingEpoch).Scan(&value.SourceFileID, &value.Role)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	value.IndependentStart = value.Role != "combined"
	rows, err := store.db.QueryContext(ctx, `SELECT DISTINCT i.id FROM file_item_mapping m JOIN library_item i ON i.library_id=m.library_id AND i.id=m.item_id WHERE m.library_id=? AND m.source_file_id=? AND m.binding_id=? AND m.binding_epoch=? AND m.active=1 AND i.kind='episode' AND (m.confirmed_by='manual' OR m.evidence_id IS NULL OR EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=m.evidence_id AND ie.match_state!='needs_review')) ORDER BY i.id LIMIT 129`, libraryID, value.SourceFileID, bindingID, bindingEpoch)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		if len(value.CoveredEpisodeIDs) >= 128 {
			return nil, ErrInvalid
		}
		value.CoveredEpisodeIDs = append(value.CoveredEpisodeIDs, id)
	}
	return value, rows.Err()
}
