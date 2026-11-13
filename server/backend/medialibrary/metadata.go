package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

type MetadataField struct {
	Values        []string `json:"values"`
	Source        string   `json:"source"`
	Locked        bool     `json:"locked"`
	ExplicitClear bool     `json:"explicitClear"`
	Revision      int64    `json:"revision"`
	InputID       string   `json:"-"`
	BindingID     string   `json:"-"`
	BindingEpoch  int64    `json:"-"`
}

type MetadataFieldPatch struct {
	Values []string `json:"values,omitempty"`
	Clear  bool     `json:"clear,omitempty"`
	Reset  bool     `json:"reset,omitempty"`
	Locked *bool    `json:"locked,omitempty"`
}

type MetadataPatch struct {
	Fields map[string]MetadataFieldPatch `json:"fields"`
}

// MetadataMutationResult is a safe, replayable acknowledgement. Current
// detail must be fetched separately under current source authorization.
type MetadataMutationResult struct {
	LibraryID        string `json:"libraryId"`
	ItemID           string `json:"itemId"`
	ItemRevision     int64  `json:"itemRevision"`
	MetadataRevision int64  `json:"metadataRevision"`
}

type MetadataCredit struct {
	ID       string   `json:"id"`
	PersonID string   `json:"personId"`
	Name     string   `json:"name"`
	Kind     string   `json:"kind"`
	Role     string   `json:"role"`
	Roles    []string `json:"roles"`
	Position int      `json:"position"`
	InputID  string   `json:"-"`
	Cursor   string   `json:"-"`
}

type MetadataRating struct {
	ID         string               `json:"id"`
	Scope      string               `json:"scope"`
	Origin     string               `json:"origin"`
	ImportedAt int64                `json:"importedAt"`
	Rating     mediametadata.Rating `json:"rating"`
	InputID    string               `json:"-"`
	Position   int                  `json:"-"`
	Cursor     string               `json:"-"`
}

type MetadataArtwork struct {
	ID       string `json:"id"`
	Role     string `json:"role"`
	Status   string `json:"status"`
	InputID  string `json:"-"`
	Position int    `json:"-"`
	Cursor   string `json:"-"`
}

type MetadataIssueSource struct {
	ID          string   `json:"-"`
	Code        string   `json:"code"`
	InputID     string   `json:"-"`
	SourceKinds []string `json:"sourceKinds"`
	Cursor      string   `json:"-"`
}

type MetadataExternalID struct {
	ID         string `json:"-"`
	Provider   string `json:"provider"`
	EntityKind string `json:"entityKind"`
	Value      string `json:"value"`
	InputID    string `json:"-"`
	Cursor     string `json:"-"`
}

// ItemDetail intentionally excludes evidence, source paths, credentials and
// artwork references. A client cannot use an artwork declaration as a URL.
type ItemDetail struct {
	Item                  CatalogItem              `json:"item"`
	MetadataRevision      int64                    `json:"metadataRevision"`
	Fields                map[string]MetadataField `json:"fields"`
	Credits               []MetadataCredit         `json:"credits"`
	Ratings               []MetadataRating         `json:"ratings"`
	Artwork               []MetadataArtwork        `json:"artwork"`
	ExternalIDs           []MetadataExternalID     `json:"externalIds"`
	Issues                []string                 `json:"issues"`
	IssueSources          []MetadataIssueSource    `json:"-"`
	LegacyFields          []string                 `json:"legacyFields,omitempty"`
	CreditsNextCursor     string                   `json:"creditsNextCursor,omitempty"`
	RatingsNextCursor     string                   `json:"ratingsNextCursor,omitempty"`
	ArtworkNextCursor     string                   `json:"artworkNextCursor,omitempty"`
	ExternalIDsNextCursor string                   `json:"externalIdsNextCursor,omitempty"`
	IssuesNextCursor      string                   `json:"issuesNextCursor,omitempty"`
}

type MetadataCreditPage struct {
	Items        []MetadataCredit
	NextCursor   string
	ItemRevision int64
}
type MetadataRatingPage struct {
	Items        []MetadataRating
	NextCursor   string
	ItemRevision int64
}
type MetadataArtworkPage struct {
	Items        []MetadataArtwork
	NextCursor   string
	ItemRevision int64
}
type MetadataExternalIDPage struct {
	Items        []MetadataExternalID
	NextCursor   string
	ItemRevision int64
}
type MetadataIssuePage struct {
	Items        []MetadataIssueSource
	NextCursor   string
	ItemRevision int64
}

type metadataFieldState struct {
	Name          string   `json:"name"`
	Values        []string `json:"values"`
	Source        string   `json:"source"`
	SourceKey     []byte   `json:"sourceKey,omitempty"`
	InputID       string   `json:"inputId,omitempty"`
	BindingID     string   `json:"bindingId,omitempty"`
	BindingEpoch  int64    `json:"bindingEpoch,omitempty"`
	Locked        bool     `json:"locked"`
	ExplicitClear bool     `json:"explicitClear"`
	Revision      int64    `json:"revision"`
}

type metadataSnapshot struct {
	Title       string               `json:"title"`
	Fields      []metadataFieldState `json:"fields"`
	CreditIDs   []string             `json:"creditIds"`
	RatingIDs   []string             `json:"ratingIds"`
	ArtworkIDs  []string             `json:"artworkIds"`
	ExternalIDs []string             `json:"externalIds"`
}

type metadataChange struct {
	Version int              `json:"version"`
	Before  metadataSnapshot `json:"before"`
	After   metadataSnapshot `json:"after"`
}

func metadataFieldLimit(name string) int {
	switch name {
	case "title", "originaltitle", "sorttitle", "showtitle":
		return 1024
	case "plot", "outline":
		return 65536
	case "tagline":
		return 4096
	case "year", "premiered", "aired", "releasedate", "season", "episode", "seasonnumber", "displayseason", "displayepisode", "runtime", "mpaa", "country", "genre", "tag", "studio", "director", "credits", "writer", "status", "collectionnumber":
		return 1024
	default:
		return 0
	}
}

func validateMetadataValues(name string, values []string) error {
	limit := metadataFieldLimit(name)
	if limit == 0 || len(values) == 0 || len(values) > 128 {
		return fmt.Errorf("%w: metadata field", ErrInvalid)
	}
	for _, value := range values {
		if !utf8.ValidString(value) || strings.TrimSpace(value) == "" || utf8.RuneCountInString(value) > limit {
			return fmt.Errorf("%w: metadata field value", ErrInvalid)
		}
		if strings.IndexFunc(value, func(r rune) bool { return unicode.IsControl(r) && r != '\n' && r != '\r' && r != '\t' }) >= 0 {
			return fmt.Errorf("%w: metadata field control", ErrInvalid)
		}
	}
	if name == "title" && len(values) != 1 {
		return fmt.Errorf("%w: title is singular", ErrInvalid)
	}
	return nil
}

func readMetadataSnapshot(ctx context.Context, tx *sql.Tx, libraryID, itemID string) (metadataSnapshot, error) {
	snapshot := metadataSnapshot{Fields: []metadataFieldState{}, CreditIDs: []string{}, RatingIDs: []string{}, ArtworkIDs: []string{}, ExternalIDs: []string{}}
	if err := tx.QueryRowContext(ctx, "SELECT title FROM library_item WHERE library_id=? AND id=?", libraryID, itemID).Scan(&snapshot.Title); err != nil {
		return snapshot, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT field,value_json,source,source_key,COALESCE(input_id,''),COALESCE(source_binding_id,''),source_binding_epoch,locked,explicit_clear,revision FROM item_field WHERE library_id=? AND item_id=? ORDER BY field`, libraryID, itemID)
	if err != nil {
		return snapshot, err
	}
	for rows.Next() {
		var field metadataFieldState
		var encoded string
		var locked, clear int
		if err := rows.Scan(&field.Name, &encoded, &field.Source, &field.SourceKey, &field.InputID, &field.BindingID, &field.BindingEpoch, &locked, &clear, &field.Revision); err != nil {
			_ = rows.Close()
			return snapshot, err
		}
		if err := json.Unmarshal([]byte(encoded), &field.Values); err != nil {
			_ = rows.Close()
			return snapshot, err
		}
		field.Locked, field.ExplicitClear = locked == 1, clear == 1
		snapshot.Fields = append(snapshot.Fields, field)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return snapshot, err
	}
	if err := rows.Close(); err != nil {
		return snapshot, err
	}
	for _, target := range []struct {
		table string
		ids   *[]string
	}{{"item_credit", &snapshot.CreditIDs}, {"rating_observation", &snapshot.RatingIDs}, {"artwork_declaration", &snapshot.ArtworkIDs}, {"item_external_id", &snapshot.ExternalIDs}} {
		rows, err := tx.QueryContext(ctx, "SELECT id FROM "+target.table+" WHERE library_id=? AND item_id=? AND active=1 ORDER BY id", libraryID, itemID)
		if err != nil {
			return snapshot, err
		}
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				_ = rows.Close()
				return snapshot, err
			}
			*target.ids = append(*target.ids, id)
		}
		if err := rows.Err(); err != nil {
			_ = rows.Close()
			return snapshot, err
		}
		if err := rows.Close(); err != nil {
			return snapshot, err
		}
	}
	return snapshot, nil
}

func metadataStatesEqual(a, b metadataFieldState) bool {
	return a.Name == b.Name && a.Source == b.Source && a.InputID == b.InputID && a.BindingID == b.BindingID && a.BindingEpoch == b.BindingEpoch && bytes.Equal(a.SourceKey, b.SourceKey) && a.Locked == b.Locked && a.ExplicitClear == b.ExplicitClear && slicesEqual(a.Values, b.Values)
}

func slicesEqual(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func putMetadataField(ctx context.Context, tx *sql.Tx, libraryID, itemID string, field metadataFieldState) error {
	encoded, err := json.Marshal(field.Values)
	if err != nil {
		return err
	}
	var inputID any
	if field.InputID != "" {
		inputID = field.InputID
	}
	var bindingID any
	if field.BindingID != "" {
		bindingID = field.BindingID
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO item_field(library_id,item_id,field,value_json,source,source_key,input_id,source_binding_id,source_binding_epoch,locked,explicit_clear,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(library_id,item_id,field) DO UPDATE SET value_json=excluded.value_json,source=excluded.source,source_key=excluded.source_key,input_id=excluded.input_id,source_binding_id=excluded.source_binding_id,source_binding_epoch=excluded.source_binding_epoch,locked=excluded.locked,explicit_clear=excluded.explicit_clear,revision=excluded.revision`, libraryID, itemID, field.Name, string(encoded), field.Source, field.SourceKey, inputID, bindingID, field.BindingEpoch, field.Locked, field.ExplicitClear, field.Revision)
	return err
}

func checkMetadataFieldBudget(ctx context.Context, tx *sql.Tx, libraryID, itemID string) error {
	var total int
	if err := tx.QueryRowContext(ctx, `SELECT COALESCE(sum(length(CAST(f.value_json AS BLOB))),0) FROM item_field f WHERE f.library_id=? AND f.item_id=? AND f.source!='legacy'
AND (f.source!='filename' OR EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=f.source_binding_id AND ls.library_id=f.library_id AND ls.proof_epoch=f.source_binding_epoch AND ls.proof_epoch>0))
AND (f.input_id IS NULL OR EXISTS(SELECT 1 FROM metadata_input mi JOIN library_source ls ON ls.binding_id=mi.binding_id AND ls.library_id=mi.library_id WHERE mi.id=f.input_id AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0))`, libraryID, itemID).Scan(&total); err != nil {
		return err
	}
	if total > 64<<10 {
		return fmt.Errorf("%w: metadata fields exceed detail budget", ErrInvalid)
	}
	return nil
}

func fieldHasCurrentOrigin(ctx context.Context, tx *sql.Tx, libraryID string, field metadataFieldState) (bool, error) {
	if field.Source == "legacy" {
		return false, nil
	}
	if field.Source == "filename" {
		var count int
		err := tx.QueryRowContext(ctx, "SELECT count(*) FROM library_source WHERE binding_id=? AND library_id=? AND proof_epoch=? AND proof_epoch>0", field.BindingID, libraryID, field.BindingEpoch).Scan(&count)
		return count > 0, err
	}
	if field.InputID != "" {
		var count int
		err := tx.QueryRowContext(ctx, `SELECT count(*) FROM metadata_input mi JOIN library_source ls ON ls.binding_id=mi.binding_id AND ls.library_id=mi.library_id WHERE mi.id=? AND mi.library_id=? AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0`, field.InputID, libraryID).Scan(&count)
		return count > 0, err
	}
	return true, nil
}

func metadataRevision(ctx context.Context, tx *sql.Tx, libraryID, itemID string) (int64, error) {
	var revision int64
	err := tx.QueryRowContext(ctx, "SELECT COALESCE(max(revision),0) FROM metadata_revision WHERE library_id=? AND item_id=?", libraryID, itemID).Scan(&revision)
	return revision, err
}

func recordMetadataChange(ctx context.Context, tx *sql.Tx, actor uint, libraryID, itemID, origin string, revision int64, before, after metadataSnapshot) error {
	id, err := randomID("metadatarev_")
	if err != nil {
		return err
	}
	changes, err := json.Marshal(metadataChange{Version: 1, Before: before, After: after})
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO metadata_revision(id,library_id,item_id,revision,actor_user_id,origin,changes_json,created_at) VALUES (?,?,?,?,?,?,?,?)`, id, libraryID, itemID, revision, actor, origin, string(changes), time.Now().UnixMilli()); err != nil {
		return err
	}
	return publishCatalogChange(ctx, tx, actor, libraryID, "catalog.item.metadata.changed", catalogChange{ItemID: itemID, Revision: revision})
}

func metadataItemRevision(ctx context.Context, tx *sql.Tx, actor uint, libraryID, itemID string, editable bool) (int64, error) {
	if editable {
		if _, err := editableCatalogLibrary(ctx, tx, actor, libraryID); err != nil {
			return 0, err
		}
	} else {
		if _, err := activeCatalogLibrary(ctx, tx, actor, libraryID); err != nil {
			return 0, err
		}
	}
	var revision int64
	err := tx.QueryRowContext(ctx, "SELECT revision FROM library_item WHERE library_id=? AND id=?", libraryID, itemID).Scan(&revision)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNotFound
	}
	return revision, err
}

func updateMetadataItem(ctx context.Context, tx *sql.Tx, libraryID, itemID string, oldRevision int64) error {
	result, err := tx.ExecContext(ctx, `UPDATE library_item SET revision=revision+1,title=COALESCE((SELECT CASE WHEN explicit_clear=1 THEN '' ELSE json_extract(value_json,'$[0]') END FROM item_field WHERE library_id=? AND item_id=? AND field='title'),'') WHERE library_id=? AND id=? AND revision=?`, libraryID, itemID, libraryID, itemID, oldRevision)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return ErrRevisionConflict
	}
	return nil
}

func applyMetadataTx(ctx context.Context, tx *sql.Tx, actor uint, libraryID, itemID string, expectedRevision int64, patch MetadataPatch) (int64, error) {
	if len(patch.Fields) == 0 || len(patch.Fields) > 64 {
		return 0, fmt.Errorf("%w: metadata patch", ErrInvalid)
	}
	revision, err := metadataItemRevision(ctx, tx, actor, libraryID, itemID, true)
	if err != nil {
		return 0, err
	}
	if expectedRevision < 1 || revision != expectedRevision {
		return 0, ErrRevisionConflict
	}
	before, err := readMetadataSnapshot(ctx, tx, libraryID, itemID)
	if err != nil {
		return 0, err
	}
	current := make(map[string]metadataFieldState, len(before.Fields))
	for _, field := range before.Fields {
		current[field.Name] = field
	}
	keys := make([]string, 0, len(patch.Fields))
	for key := range patch.Fields {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	changed := false
	for _, name := range keys {
		change := patch.Fields[name]
		old, exists := current[name]
		if metadataFieldLimit(name) == 0 || (change.Clear && (change.Values != nil || change.Reset)) || (change.Reset && (change.Values != nil || (change.Locked != nil && *change.Locked))) || (!change.Reset && !change.Clear && change.Values == nil && change.Locked == nil) {
			return 0, fmt.Errorf("%w: metadata patch field", ErrInvalid)
		}
		if !exists && change.Values == nil && !change.Clear {
			return 0, ErrNotFound
		}
		next := old
		next.Name = name
		if change.Reset {
			if !old.ExplicitClear {
				return 0, fmt.Errorf("%w: field is not explicitly cleared", ErrInvalid)
			}
			if _, err := tx.ExecContext(ctx, "DELETE FROM item_field WHERE library_id=? AND item_id=? AND field=?", libraryID, itemID, name); err != nil {
				return 0, err
			}
			if _, err := tx.ExecContext(ctx, "UPDATE metadata_input SET projection_stale=1 WHERE library_id=? AND item_id=? AND source_type='nfo' AND is_current=1", libraryID, itemID); err != nil {
				return 0, err
			}
			changed = true
			continue
		}
		if change.Clear {
			next.Values = []string{}
			next.Source = "manual"
			next.SourceKey = nil
			next.InputID = ""
			next.BindingID = ""
			next.BindingEpoch = 0
			next.ExplicitClear = true
			next.Locked = true
		} else if change.Values != nil {
			if err := validateMetadataValues(name, change.Values); err != nil {
				return 0, err
			}
			next.Values = append([]string(nil), change.Values...)
			next.Source = "manual"
			next.SourceKey = nil
			next.InputID = ""
			next.BindingID = ""
			next.BindingEpoch = 0
			next.ExplicitClear = false
			next.Locked = true
		}
		if change.Locked != nil {
			next.Locked = *change.Locked
		}
		if !exists || !metadataStatesEqual(old, next) {
			next.Revision = revision + 1
			if err := putMetadataField(ctx, tx, libraryID, itemID, next); err != nil {
				return 0, err
			}
			if !next.Locked && !next.ExplicitClear {
				if _, err := tx.ExecContext(ctx, "UPDATE metadata_input SET projection_stale=1 WHERE library_id=? AND item_id=? AND source_type='nfo' AND is_current=1", libraryID, itemID); err != nil {
					return 0, err
				}
			}
			changed = true
		}
	}
	if !changed {
		return revision, nil
	}
	if err := checkMetadataFieldBudget(ctx, tx, libraryID, itemID); err != nil {
		return 0, err
	}
	if err := updateMetadataItem(ctx, tx, libraryID, itemID, revision); err != nil {
		return 0, err
	}
	after, err := readMetadataSnapshot(ctx, tx, libraryID, itemID)
	if err != nil {
		return 0, err
	}
	if err := recordMetadataChange(ctx, tx, actor, libraryID, itemID, "manual", revision+1, before, after); err != nil {
		return 0, err
	}
	return revision + 1, nil
}

// Refresh only a filename-derived title. Manual or NFO data is never replaced
// here; a stale binding epoch cannot leak the previous workspace's filename.
func applyFilenameObservationTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, itemID, title string) error {
	var source, oldBinding string
	var oldEpoch int64
	var locked, cleared int
	err := tx.QueryRowContext(ctx, "SELECT source,COALESCE(source_binding_id,''),source_binding_epoch,locked,explicit_clear FROM item_field WHERE library_id=? AND item_id=? AND field='title'", binding.LibraryID, itemID).Scan(&source, &oldBinding, &oldEpoch, &locked, &cleared)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if source != "filename" || locked != 0 || cleared != 0 {
		return nil
	}
	var currentOrigin int
	if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM library_source WHERE binding_id=? AND library_id=? AND proof_epoch=? AND proof_epoch>0", oldBinding, binding.LibraryID, oldEpoch).Scan(&currentOrigin); err != nil {
		return err
	}
	if currentOrigin > 0 {
		return nil
	}
	before, err := readMetadataSnapshot(ctx, tx, binding.LibraryID, itemID)
	if err != nil {
		return err
	}
	var old metadataFieldState
	for _, field := range before.Fields {
		if field.Name == "title" {
			old = field
			break
		}
	}
	title, err = catalogTitle(title)
	if err != nil {
		return err
	}
	next := old
	next.Values = []string{title}
	next.BindingID = binding.BindingID
	next.BindingEpoch = binding.ProofEpoch
	if metadataStatesEqual(old, next) {
		return nil
	}
	var revision int64
	if err := tx.QueryRowContext(ctx, "SELECT revision FROM library_item WHERE library_id=? AND id=?", binding.LibraryID, itemID).Scan(&revision); err != nil {
		return err
	}
	next.Revision = revision + 1
	if err := putMetadataField(ctx, tx, binding.LibraryID, itemID, next); err != nil {
		return err
	}
	if err := updateMetadataItem(ctx, tx, binding.LibraryID, itemID, revision); err != nil {
		return err
	}
	after, err := readMetadataSnapshot(ctx, tx, binding.LibraryID, itemID)
	if err != nil {
		return err
	}
	return recordMetadataChange(ctx, tx, actor, binding.LibraryID, itemID, "filename", revision+1, before, after)
}
