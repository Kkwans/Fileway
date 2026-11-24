package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

// Unsupported shapes are explicit: assigning a movie/episode, merging an
// occupied position, and remapping an item containing several files require a
// separate preview contract. None of those cases is inferred from filenames.
var ErrMappingUnsupported = errors.New("manual mapping shape unsupported")

type SeriesMappingRequest struct {
	LibraryID         string `json:"libraryId"`
	ItemID            string `json:"itemId"`
	ItemRevision      int64  `json:"itemRevision"`
	StructureRevision int64  `json:"structureRevision"`
	SeriesID          string `json:"seriesId,omitempty"`
	NewSeriesTitle    string `json:"newSeriesTitle,omitempty"`
	SeasonNumber      int    `json:"seasonNumber"`
	EpisodeNumbers    []int  `json:"episodeNumbers"`
	ConnectionID      string `json:"connectionId,omitempty"`
	ExternalID        int64  `json:"externalId,omitempty,string"`
}

type SeriesMappingProviderDocument struct {
	ProviderMatchDocument
	Language  string
	Region    string
	FetchedAt int64
}

type SeriesMappingPreview struct {
	PreviewID             string               `json:"previewId"`
	ExpiresAt             int64                `json:"expiresAt"`
	Request               SeriesMappingRequest `json:"mapping"`
	PreservedManualFields []string             `json:"preservedManualFields"`
	PlayMode              string               `json:"playMode"`
	IndependentStart      bool                 `json:"independentStart"`
	UserStateEffect       string               `json:"userStateEffect"`
}

type SeriesMappingResult struct {
	LibraryID         string   `json:"libraryId"`
	ItemID            string   `json:"itemId"`
	SeriesID          string   `json:"seriesId"`
	SeasonID          string   `json:"seasonId"`
	EpisodeIDs        []string `json:"episodeIds"`
	MappingRevision   string   `json:"mappingRevision"`
	ItemRevision      int64    `json:"itemRevision"`
	StructureRevision int64    `json:"structureRevision"`
	PlayMode          string   `json:"playMode"`
	IndependentStart  bool     `json:"independentStart"`
}

// Only opaque IDs and monotonic database fences are persisted. No locator,
// provider credential or native object key is placed in a receipt or preview.
type mappingSnapshot struct {
	Kind, ParentID, MatchState, IssueCode, SupersededBy string
	Revision, StructureRevision, FirstAddedAt           int64
	SeriesUpdatedAt                                     *int64
	Files                                               string
	Mappings                                            string
	Fields                                              string
	Series                                              string
	Order                                               string
	Positions                                           string
	PreservedManualFields                               []string
}

func readMappingSnapshot(ctx context.Context, tx *sql.Tx, libraryID, itemID, seriesID string) (mappingSnapshot, error) {
	var value mappingSnapshot
	err := tx.QueryRowContext(ctx, `SELECT i.kind,COALESCE(i.parent_id,''),i.revision,st.structure_revision,st.match_state,st.issue_code,COALESCE(st.superseded_by,''),st.first_added_at,st.series_updated_at
FROM library_item i JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id WHERE i.library_id=? AND i.id=?`, libraryID, itemID).
		Scan(&value.Kind, &value.ParentID, &value.Revision, &value.StructureRevision, &value.MatchState, &value.IssueCode, &value.SupersededBy, &value.FirstAddedAt, &value.SeriesUpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return value, ErrNotFound
	}
	if err != nil {
		return value, err
	}
	// Binding/source/file/locator and edition/variant generations all participate
	// in the manifest. Even an ABA change invalidates an earlier preview.
	err = tx.QueryRowContext(ctx, `SELECT COALESCE(json_group_array(json_array(id,file,vr,edition,er,binding,epoch,br,ar,fr,ie,lr,missing)), '[]') FROM (
SELECT v.id AS id,v.source_file_id AS file,v.mapping_revision AS vr,e.id AS edition,e.mapping_revision AS er,ls.binding_id AS binding,ls.proof_epoch AS epoch,ls.revision AS br,s.authorization_revision AS ar,f.revision AS fr,f.identity_epoch AS ie,lf.locator_revision AS lr,lf.missing_since AS missing
FROM edition e JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id JOIN source_file f ON f.id=v.source_file_id JOIN source s ON s.id=f.source_id
JOIN library_file lf ON lf.library_id=e.library_id AND lf.source_file_id=f.id AND lf.source_id=f.source_id JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator
WHERE e.library_id=? AND e.item_id=? AND lf.binding_epoch=ls.proof_epoch AND ls.active_proof_version=1 ORDER BY v.id,ls.binding_id LIMIT 101)`, libraryID, itemID).Scan(&value.Files)
	if err != nil {
		return value, err
	}
	err = tx.QueryRowContext(ctx, `SELECT json_group_array(json_array(id,item_id,variant_id,source_file_id,COALESCE(binding_id,''),binding_epoch,role,confirmed_by,mapping_revision,active)) FROM (SELECT * FROM file_item_mapping WHERE library_id=? AND (item_id=? OR source_file_id IN (SELECT v.source_file_id FROM variant v JOIN edition e ON e.library_id=v.library_id AND e.id=v.edition_id WHERE e.library_id=? AND e.item_id=?)) ORDER BY id LIMIT 1001)`, libraryID, itemID, libraryID, itemID).Scan(&value.Mappings)
	if err != nil {
		return value, err
	}
	err = tx.QueryRowContext(ctx, `SELECT json_group_array(json_array(field,revision,source,locked,explicit_clear)) FROM (SELECT * FROM item_field WHERE library_id=? AND item_id=? ORDER BY field)`, libraryID, itemID).Scan(&value.Fields)
	if err != nil {
		return value, err
	}
	value.PreservedManualFields = []string{}
	rows, err := tx.QueryContext(ctx, `SELECT field FROM item_field WHERE library_id=? AND item_id=? AND (source='manual' OR locked=1 OR explicit_clear=1) ORDER BY field`, libraryID, itemID)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		var field string
		if err = rows.Scan(&field); err != nil {
			_ = rows.Close()
			return value, err
		}
		value.PreservedManualFields = append(value.PreservedManualFields, field)
	}
	err = rows.Err()
	_ = rows.Close()
	if err != nil {
		return value, err
	}
	if seriesID != "" {
		err = tx.QueryRowContext(ctx, `SELECT json_array(i.id,i.kind,i.revision,st.structure_revision,COALESCE(st.superseded_by,''),st.first_added_at,st.series_updated_at) FROM library_item i JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id WHERE i.library_id=? AND i.id=?`, libraryID, seriesID).Scan(&value.Series)
		if errors.Is(err, sql.ErrNoRows) {
			return value, ErrNotFound
		}
		if err != nil {
			return value, err
		}
		err = tx.QueryRowContext(ctx, `SELECT json_group_array(json_array(id,revision)) FROM (SELECT id,revision FROM episode_order WHERE library_id=? AND series_id=? AND scheme='local' ORDER BY id)`, libraryID, seriesID).Scan(&value.Order)
		if err != nil {
			return value, err
		}
		err = tx.QueryRowContext(ctx, `SELECT json_group_array(json_array(season_number,episode_number,episode_id,revision,structure_revision)) FROM (SELECT ep.*,i.revision,st.structure_revision FROM episode_position ep JOIN episode_order eo ON eo.library_id=ep.library_id AND eo.id=ep.order_id JOIN library_item i ON i.library_id=ep.library_id AND i.id=ep.episode_id JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id WHERE eo.library_id=? AND eo.series_id=? AND eo.scheme='local' AND EXISTS(SELECT 1 FROM file_item_mapping fm WHERE fm.library_id=ep.library_id AND fm.item_id=ep.episode_id AND fm.active=1 AND fm.source_file_id IN (SELECT v.source_file_id FROM variant v JOIN edition e ON e.library_id=v.library_id AND e.id=v.edition_id WHERE e.library_id=? AND e.item_id=?)) ORDER BY ep.season_number,ep.episode_number LIMIT 101)`, libraryID, seriesID, libraryID, itemID).Scan(&value.Positions)
	}
	if err == nil {
		for _, bounded := range []struct {
			raw string
			max int
		}{{value.Files, 100}, {value.Mappings, 1000}, {value.Positions, 100}} {
			if bounded.raw == "" {
				continue
			}
			var entries []json.RawMessage
			if err = json.Unmarshal([]byte(bounded.raw), &entries); err != nil {
				return value, err
			}
			if len(entries) > bounded.max {
				return value, ErrMappingUnsupported
			}
		}
	}
	return value, err
}

func validateSeriesMapping(request SeriesMappingRequest) error {
	if request.LibraryID == "" || request.ItemID == "" || request.ItemRevision < 1 || request.StructureRevision < 1 || request.SeasonNumber < 0 || request.SeasonNumber > 999 || len(request.EpisodeNumbers) < 1 || len(request.EpisodeNumbers) > 100 {
		return ErrInvalid
	}
	if request.SeriesID != "" {
		if request.NewSeriesTitle != "" || request.ExternalID != 0 || request.ConnectionID != "" {
			return ErrInvalid
		}
	} else {
		if _, err := catalogTitle(request.NewSeriesTitle); err != nil {
			return err
		}
	}
	previous := -1
	for _, number := range request.EpisodeNumbers {
		if number < 0 || number > 9999 || number <= previous {
			return ErrInvalid
		}
		previous = number
	}
	if request.ExternalID < 0 || (request.ExternalID == 0) != (request.ConnectionID == "") {
		return ErrInvalid
	}
	return nil
}

func checkSeriesMappingTx(ctx context.Context, tx *sql.Tx, request SeriesMappingRequest, snapshot mappingSnapshot) error {
	if snapshot.Revision != request.ItemRevision || snapshot.StructureRevision != request.StructureRevision {
		return ErrRevisionConflict
	}
	if snapshot.Kind != "video" || snapshot.ParentID != "" || snapshot.SupersededBy != "" {
		return ErrMappingUnsupported
	}
	var files, bindings, other, provider int
	err := tx.QueryRowContext(ctx, `SELECT count(DISTINCT v.source_file_id) FROM edition e JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id WHERE e.library_id=? AND e.item_id=?`, request.LibraryID, request.ItemID).Scan(&files)
	if err != nil {
		return err
	}
	err = tx.QueryRowContext(ctx, `SELECT count(DISTINCT m.binding_id) FROM file_item_mapping m JOIN library_source ls ON ls.binding_id=m.binding_id AND ls.library_id=m.library_id WHERE m.library_id=? AND m.item_id=? AND m.active=1 AND m.binding_epoch=ls.proof_epoch AND ls.active_proof_version=1`, request.LibraryID, request.ItemID).Scan(&bindings)
	if err != nil {
		return err
	}
	// Reject other active item associations. They need an explicit merge/split
	// preview; mapping the selected unmatched card must never rewrite siblings.
	err = tx.QueryRowContext(ctx, `SELECT count(*) FROM file_item_mapping WHERE library_id=? AND active=1 AND item_id!=? AND source_file_id IN (SELECT v.source_file_id FROM edition e JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id WHERE e.library_id=? AND e.item_id=?)`, request.LibraryID, request.ItemID, request.LibraryID, request.ItemID).Scan(&other)
	if err != nil {
		return err
	}
	err = tx.QueryRowContext(ctx, `SELECT count(*) FROM item_provider_match WHERE library_id=? AND item_id=?`, request.LibraryID, request.ItemID).Scan(&provider)
	if err != nil {
		return err
	}
	var incompatible int
	if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM metadata_input WHERE library_id=? AND item_id=? AND is_current=1 AND status='applied' AND json_extract(evidence_json,'$.items[0].kind') IN ('movie','tvshow','season')`, request.LibraryID, request.ItemID).Scan(&incompatible); err != nil {
		return err
	}
	if files != 1 || bindings != 1 || other != 0 || provider != 0 || incompatible != 0 || snapshot.Files == "[]" {
		return ErrMappingUnsupported
	}
	if request.SeriesID != "" {
		var kind string
		var superseded sql.NullString
		err = tx.QueryRowContext(ctx, `SELECT i.kind,st.superseded_by FROM library_item i JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id WHERE i.library_id=? AND i.id=?`, request.LibraryID, request.SeriesID).Scan(&kind, &superseded)
		if err != nil {
			return err
		}
		if kind != "series" || superseded.Valid {
			return ErrInvalid
		}
		for _, number := range request.EpisodeNumbers {
			id, err := lookupEpisodeTx(ctx, tx, request.LibraryID, request.SeriesID, mediametadata.SeasonEpisode{Season: request.SeasonNumber, Episode: number})
			if err != nil {
				return err
			}
			if id != "" {
				return ErrMappingUnsupported
			}
		}
	}
	if request.ExternalID > 0 {
		var exists bool
		if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM item_provider_match pm JOIN item_structure st ON st.library_id=pm.library_id AND st.item_id=pm.item_id WHERE pm.library_id=? AND pm.provider='tmdb' AND pm.entity_kind='series' AND pm.external_id=? AND st.superseded_by IS NULL)`, request.LibraryID, request.ExternalID).Scan(&exists); err != nil {
			return err
		}
		if exists {
			return ErrMappingUnsupported
		}
	}
	return nil
}

// An undo capability is a current, actor-scoped conditional revision. Returning
// the ID does not authorize its sources; the HTTP caller checks those first.
func (store *Store) CurrentSeriesMappingRevision(ctx context.Context, actor uint, libraryID, itemID string) (string, error) {
	var result string
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := editableCatalogLibrary(ctx, tx, actor, libraryID); err != nil {
			return err
		}
		var id, encoded string
		var expected []byte
		err := tx.QueryRowContext(ctx, `SELECT id,result_json,after_digest FROM manual_mapping_revision WHERE actor_user_id=? AND library_id=? AND item_id=? AND undone=0 ORDER BY json_extract(result_json,'$.itemRevision') DESC LIMIT 1`, actor, libraryID, itemID).Scan(&id, &encoded, &expected)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		var mapping SeriesMappingResult
		if err = json.Unmarshal([]byte(encoded), &mapping); err != nil {
			return err
		}
		snapshot, err := readMappingSnapshot(ctx, tx, libraryID, itemID, mapping.SeriesID)
		if err != nil {
			return err
		}
		digest, err := operationDigest(snapshot)
		if err != nil {
			return err
		}
		if bytes.Equal(digest, expected) {
			result = id
		}
		return nil
	})
	return result, err
}

func (store *Store) PreviewSeriesMapping(ctx context.Context, actor uint, operationID string, request SeriesMappingRequest, document *SeriesMappingProviderDocument, authority *ProviderUseAuthority) (*SeriesMappingPreview, error) {
	if err := validateSeriesMapping(request); err != nil {
		return nil, err
	}
	if request.ExternalID != 0 {
		if document == nil || document.FetchedAt <= 0 || document.Item.Kind != mediametadata.TVShow || len(document.Item.Fields["title"]) == 0 || authority == nil || authority.ConnectionID != request.ConnectionID {
			return nil, ErrInvalid
		}
	} else if document != nil || authority != nil {
		return nil, ErrInvalid
	}
	return providerOperation(ctx, store, actor, "catalog.mapping.preview", operationID, request, func(tx *sql.Tx) error {
		_, err := editableCatalogLibrary(ctx, tx, actor, request.LibraryID)
		return err
	}, func(tx *sql.Tx) (*SeriesMappingPreview, error) {
		if authority != nil {
			current, _, err := readProviderCredentialForUse(ctx, tx, actor, request.LibraryID, authority.ConnectionID)
			if err != nil {
				return nil, err
			}
			if *current != *authority {
				return nil, ErrRevisionConflict
			}
		}
		snapshot, err := readMappingSnapshot(ctx, tx, request.LibraryID, request.ItemID, request.SeriesID)
		if err != nil {
			return nil, err
		}
		if err = checkSeriesMappingTx(ctx, tx, request, snapshot); err != nil {
			return nil, err
		}
		// Expired drafts are disposable. Keep committed previews: HTTP replay
		// must still authorize the original parent before returning its receipt.
		if _, err = tx.ExecContext(ctx, `DELETE FROM manual_mapping_preview WHERE committed=0 AND expires_at<?`, time.Now().UnixMilli()); err != nil {
			return nil, err
		}
		id, err := randomID("mp_")
		if err != nil {
			return nil, err
		}
		reqJSON, _ := json.Marshal(request)
		snapJSON, _ := json.Marshal(snapshot)
		var docJSON any
		if document != nil {
			encoded, err := json.Marshal(struct {
				Document  *SeriesMappingProviderDocument
				Authority *ProviderUseAuthority
			}{document, authority})
			if err != nil || len(encoded) > 256<<10 {
				return nil, ErrInvalid
			}
			docJSON = string(encoded)
		}
		expires := time.Now().Add(15 * time.Minute).UnixMilli()
		_, err = tx.ExecContext(ctx, `INSERT INTO manual_mapping_preview(id,actor_user_id,library_id,item_id,request_json,snapshot_json,document_json,expires_at) VALUES(?,?,?,?,?,?,?,?)`, id, actor, request.LibraryID, request.ItemID, string(reqJSON), string(snapJSON), docJSON, expires)
		if err != nil {
			return nil, err
		}
		mode := "single"
		if len(request.EpisodeNumbers) > 1 {
			mode = "combined_file"
		}
		return &SeriesMappingPreview{id, expires, request, snapshot.PreservedManualFields, mode, len(request.EpisodeNumbers) == 1, "preserve_original_item_state_without_copying_to_other_episodes"}, nil
	})
}

type mappingPreviewRecord struct {
	Request   SeriesMappingRequest
	Snapshot  mappingSnapshot
	Document  *SeriesMappingProviderDocument
	Authority *ProviderUseAuthority
	ExpiresAt int64
}

func readSeriesMappingPreview(ctx context.Context, reader schemaReader, actor uint, libraryID, itemID, previewID string) (mappingPreviewRecord, error) {
	var value mappingPreviewRecord
	var request, snapshot string
	var document sql.NullString
	err := reader.QueryRowContext(ctx, `SELECT request_json,snapshot_json,document_json,expires_at FROM manual_mapping_preview WHERE id=? AND actor_user_id=? AND library_id=? AND item_id=?`, previewID, actor, libraryID, itemID).Scan(&request, &snapshot, &document, &value.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return value, ErrNotFound
	}
	if err != nil {
		return value, err
	}
	if err = json.Unmarshal([]byte(request), &value.Request); err != nil {
		return value, err
	}
	if err = json.Unmarshal([]byte(snapshot), &value.Snapshot); err != nil {
		return value, err
	}
	if document.Valid {
		var doc struct {
			Document  *SeriesMappingProviderDocument
			Authority *ProviderUseAuthority
		}
		if err = json.Unmarshal([]byte(document.String), &doc); err != nil {
			return value, err
		}
		value.Document, value.Authority = doc.Document, doc.Authority
	}
	return value, nil
}

// MappingPreviewRequest permits the HTTP boundary to authorize the explicitly
// chosen existing parent again. IDs are never treated as source capabilities.
func (store *Store) MappingPreviewRequest(ctx context.Context, actor uint, libraryID, itemID, previewID string) (SeriesMappingRequest, error) {
	if _, err := editableCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return SeriesMappingRequest{}, err
	}
	value, err := readSeriesMappingPreview(ctx, store.db, actor, libraryID, itemID, previewID)
	return value.Request, err
}

func (store *Store) ConfirmSeriesMapping(ctx context.Context, actor uint, operationID, libraryID, itemID, previewID string) (*SeriesMappingResult, error) {
	input := struct{ LibraryID, ItemID, PreviewID string }{libraryID, itemID, previewID}
	return providerOperation(ctx, store, actor, "catalog.mapping.confirm", operationID, input, func(tx *sql.Tx) error { _, err := editableCatalogLibrary(ctx, tx, actor, libraryID); return err }, func(tx *sql.Tx) (*SeriesMappingResult, error) {
		preview, err := readSeriesMappingPreview(ctx, tx, actor, libraryID, itemID, previewID)
		if err != nil {
			return nil, err
		}
		if time.Now().UnixMilli() > preview.ExpiresAt {
			return nil, ErrRevisionConflict
		}
		request := preview.Request
		current, err := readMappingSnapshot(ctx, tx, libraryID, itemID, request.SeriesID)
		if err != nil {
			return nil, err
		}
		before, _ := json.Marshal(preview.Snapshot)
		now, _ := json.Marshal(current)
		if !bytes.Equal(before, now) {
			return nil, ErrRevisionConflict
		}
		if err = checkSeriesMappingTx(ctx, tx, request, current); err != nil {
			return nil, err
		}
		if preview.Authority != nil {
			authority, _, err := readProviderCredentialForUse(ctx, tx, actor, libraryID, request.ConnectionID)
			if err != nil {
				return nil, err
			}
			if *authority != *preview.Authority {
				return nil, ErrRevisionConflict
			}
		}
		var binding SourceBindingRef
		var fileID string
		// Exactly one binding/file has already been checked. Never select an
		// arbitrary association with LIMIT 1 to establish content identity.
		rows, err := tx.QueryContext(ctx, `SELECT DISTINCT m.source_file_id,m.binding_id,m.binding_epoch FROM file_item_mapping m JOIN library_source ls ON ls.binding_id=m.binding_id WHERE m.library_id=? AND m.item_id=? AND m.active=1 AND m.binding_epoch=ls.proof_epoch AND ls.active_proof_version=1`, libraryID, itemID)
		if err != nil {
			return nil, err
		}
		count := 0
		for rows.Next() {
			count++
			if err = rows.Scan(&fileID, &binding.BindingID, &binding.ProofEpoch); err != nil {
				_ = rows.Close()
				return nil, err
			}
		}
		err = rows.Err()
		_ = rows.Close()
		if err != nil {
			return nil, err
		}
		if count != 1 {
			return nil, ErrMappingUnsupported
		}
		binding.LibraryID = libraryID
		seriesID := request.SeriesID
		if seriesID == "" {
			series, err := createCatalogItemTx(ctx, tx, actor, libraryID, NewCatalogItem{Kind: "series", Title: request.NewSeriesTitle})
			if err != nil {
				return nil, err
			}
			seriesID = series.ID
			if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_id=item_id,match_state='local_confirmed',first_added_at=? WHERE library_id=? AND item_id=?`, current.FirstAddedAt, libraryID, seriesID); err != nil {
				return nil, err
			}
			if preview.Document != nil {
				// A provider label is a provider projection, not an invented
				// manually locked title. The existing policy controls display.
				if _, err = tx.ExecContext(ctx, `UPDATE item_field SET source='legacy',locked=0 WHERE library_id=? AND item_id=? AND field='title'`, libraryID, seriesID); err != nil {
					return nil, err
				}
				encoded, _ := json.Marshal(preview.Document.ProviderMatchDocument)
				_, err = tx.ExecContext(ctx, `INSERT INTO item_provider_match(library_id,item_id,connection_id,provider,entity_kind,external_id,language,document_json,fetched_at,revision) VALUES(?,?,?,'tmdb','series',?,?,?,?,1)`, libraryID, seriesID, request.ConnectionID, request.ExternalID, preview.Document.Language, string(encoded), preview.Document.FetchedAt)
				if err != nil {
					return nil, err
				}
				if err := recordProviderMatchProvenanceTx(ctx, tx, libraryID, seriesID, 1, "mapping",
					preview.Document.Language, preview.Document.Region, "tmdb-v1", []string{"manual_series_mapping"}, preview.Document.FetchedAt); err != nil {
					return nil, err
				}
			}
		}
		seasonID, orderID, err := ensureSeasonTx(ctx, tx, actor, binding, seriesID, request.SeasonNumber)
		if err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET first_added_at=min(first_added_at,?) WHERE library_id=? AND item_id=?`, current.FirstAddedAt, libraryID, seasonID); err != nil {
			return nil, err
		}
		role, mode := "single", "single"
		if len(request.EpisodeNumbers) > 1 {
			role, mode = "combined", "combined_file"
		}
		ids := make([]string, 0, len(request.EpisodeNumbers))
		for index, number := range request.EpisodeNumbers {
			episodeID := itemID
			if index == 0 {
				if err = promoteVideoTx(ctx, tx, actor, libraryID, itemID, seriesID, seasonID); err != nil {
					return nil, err
				}
			} else {
				episode, err := createCatalogItemTx(ctx, tx, actor, libraryID, NewCatalogItem{Kind: "episode", ParentID: seasonID, Title: fmt.Sprintf("Episode %d", number), initialSource: "filename", initialBinding: &binding})
				if err != nil {
					return nil, err
				}
				episodeID = episode.ID
				if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_id=?,season_id=?,match_state='local_confirmed',first_added_at=? WHERE library_id=? AND item_id=?`, seriesID, seasonID, current.FirstAddedAt, libraryID, episodeID); err != nil {
					return nil, err
				}
				if err = attachScanMappingTx(ctx, tx, actor, binding, episodeID, fileID, "", role); err != nil {
					return nil, err
				}
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO episode_position(library_id,order_id,season_number,episode_number,episode_id) VALUES(?,?,?,?,?)`, libraryID, orderID, request.SeasonNumber, number, episodeID); err != nil {
				return nil, err
			}
			if _, err = tx.ExecContext(ctx, `UPDATE file_item_mapping SET role=?,confirmed_by='manual',mapping_revision=mapping_revision+1 WHERE library_id=? AND item_id=? AND binding_id=? AND binding_epoch=? AND active=1`, role, libraryID, episodeID, binding.BindingID, binding.ProofEpoch); err != nil {
				return nil, err
			}
			ids = append(ids, episodeID)
		}
		if _, err = tx.ExecContext(ctx, `UPDATE episode_order SET revision=revision+1 WHERE id=?`, orderID); err != nil {
			return nil, err
		}
		if err = markNewEpisodeTx(ctx, tx, libraryID, seriesID); err != nil {
			return nil, err
		}
		revisionID, err := randomID("mr_")
		if err != nil {
			return nil, err
		}
		var revision, structure int64
		if err = tx.QueryRowContext(ctx, `SELECT i.revision,st.structure_revision FROM library_item i JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id WHERE i.library_id=? AND i.id=?`, libraryID, itemID).Scan(&revision, &structure); err != nil {
			return nil, err
		}
		result := &SeriesMappingResult{libraryID, itemID, seriesID, seasonID, ids, revisionID, revision, structure, mode, len(ids) == 1}
		after, err := readMappingSnapshot(ctx, tx, libraryID, itemID, seriesID)
		if err != nil {
			return nil, err
		}
		digest, _ := operationDigest(after)
		journal, _ := json.Marshal(struct {
			Snapshot mappingSnapshot
			Request  SeriesMappingRequest
		}{current, request})
		resultJSON, _ := json.Marshal(result)
		if _, err = tx.ExecContext(ctx, `INSERT INTO manual_mapping_revision(id,actor_user_id,library_id,item_id,before_json,after_digest,result_json) VALUES(?,?,?,?,?,?,?)`, revisionID, actor, libraryID, itemID, string(journal), digest, string(resultJSON)); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE manual_mapping_preview SET committed=1 WHERE id=? AND actor_user_id=?`, previewID, actor); err != nil {
			return nil, err
		}
		// The event names every new relationship. It does not copy item-level
		// manual watched state or write progress to the extra covered episodes.
		if err = publishMappingChange(ctx, tx, actor, "catalog.mapping.confirmed", result, request); err != nil {
			return nil, err
		}
		return result, nil
	})
}

func (store *Store) UndoSeriesMapping(ctx context.Context, actor uint, operationID, libraryID, itemID, revisionID string) (*SeriesMappingResult, error) {
	input := struct{ LibraryID, ItemID, RevisionID string }{libraryID, itemID, revisionID}
	return providerOperation(ctx, store, actor, "catalog.mapping.undo", operationID, input, func(tx *sql.Tx) error { _, err := editableCatalogLibrary(ctx, tx, actor, libraryID); return err }, func(tx *sql.Tx) (*SeriesMappingResult, error) {
		var before, resultJSON string
		var expected []byte
		err := tx.QueryRowContext(ctx, `SELECT before_json,after_digest,result_json FROM manual_mapping_revision WHERE id=? AND actor_user_id=? AND library_id=? AND item_id=? AND undone=0`, revisionID, actor, libraryID, itemID).Scan(&before, &expected, &resultJSON)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrRevisionConflict
		}
		if err != nil {
			return nil, err
		}
		var result SeriesMappingResult
		var prior struct {
			Snapshot mappingSnapshot
			Request  SeriesMappingRequest
		}
		if err = json.Unmarshal([]byte(before), &prior); err != nil {
			return nil, err
		}
		if err = json.Unmarshal([]byte(resultJSON), &result); err != nil {
			return nil, err
		}
		snapshot, err := readMappingSnapshot(ctx, tx, libraryID, itemID, result.SeriesID)
		if err != nil {
			return nil, err
		}
		digest, _ := operationDigest(snapshot)
		if !bytes.Equal(digest, expected) {
			return nil, ErrRevisionConflict
		}
		for _, id := range result.EpisodeIDs {
			if _, err = tx.ExecContext(ctx, `DELETE FROM episode_position WHERE library_id=? AND episode_id=?`, libraryID, id); err != nil {
				return nil, err
			}
			if id != itemID {
				if _, err = tx.ExecContext(ctx, `UPDATE file_item_mapping SET active=0,mapping_revision=mapping_revision+1 WHERE library_id=? AND item_id=? AND active=1`, libraryID, id); err != nil {
					return nil, err
				}
				if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET superseded_by=?,structure_revision=structure_revision+1 WHERE library_id=? AND item_id=?`, itemID, libraryID, id); err != nil {
					return nil, err
				}
			}
		}
		if _, err = tx.ExecContext(ctx, `UPDATE library_item SET kind='video',parent_id=NULL,revision=revision+1 WHERE library_id=? AND id=?`, libraryID, itemID); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_id=NULL,season_id=NULL,match_state=?,issue_code=?,structure_revision=structure_revision+1 WHERE library_id=? AND item_id=?`, prior.Snapshot.MatchState, prior.Snapshot.IssueCode, libraryID, itemID); err != nil {
			return nil, err
		}
		// Retain file evidence and historical mappings; undo is a fresh revision.
		var mappings [][]any
		if err = json.Unmarshal([]byte(prior.Snapshot.Mappings), &mappings); err != nil {
			return nil, err
		}
		for _, mapping := range mappings {
			if len(mapping) != 10 {
				return nil, ErrInvalid
			}
			if mapping[1] == itemID {
				if _, err = tx.ExecContext(ctx, `UPDATE file_item_mapping SET role=?,confirmed_by=?,mapping_revision=mapping_revision+1 WHERE id=?`, mapping[6], mapping[7], mapping[0]); err != nil {
					return nil, err
				}
			}
		}
		var oldUpdated *int64
		if prior.Request.SeriesID != "" {
			var series []json.RawMessage
			if err = json.Unmarshal([]byte(prior.Snapshot.Series), &series); err != nil {
				return nil, err
			}
			if len(series) != 7 {
				return nil, ErrInvalid
			}
			if err = json.Unmarshal(series[6], &oldUpdated); err != nil {
				return nil, err
			}
		}
		if _, err = tx.ExecContext(ctx, `UPDATE item_structure SET series_updated_at=?,structure_revision=structure_revision+1 WHERE library_id=? AND item_id=?`, oldUpdated, libraryID, result.SeriesID); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE episode_order SET revision=revision+1 WHERE library_id=? AND series_id=? AND scheme='local'`, libraryID, result.SeriesID); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE manual_mapping_revision SET undone=1 WHERE id=?`, revisionID); err != nil {
			return nil, err
		}
		result.ItemRevision++
		result.StructureRevision++
		result.PlayMode = "single"
		result.IndependentStart = true
		if err = publishMappingChange(ctx, tx, actor, "catalog.mapping.undone", &result, prior.Request); err != nil {
			return nil, err
		}
		return &result, nil
	})
}

func publishMappingChange(ctx context.Context, tx *sql.Tx, actor uint, kind string, result *SeriesMappingResult, request SeriesMappingRequest) error {
	id, err := randomID("evt_")
	if err != nil {
		return err
	}
	payload, err := json.Marshal(struct {
		ActorUserID uint `json:"actorUserId"`
		*SeriesMappingResult
		SeasonNumber   int    `json:"seasonNumber"`
		EpisodeNumbers []int  `json:"episodeNumbers"`
		PreviousKind   string `json:"previousKind"`
	}{actor, result, request.SeasonNumber, request.EpisodeNumbers, "video"})
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO outbox(event_id,library_id,kind,payload_json,created_at) VALUES(?,?,?,?,?)`, id, result.LibraryID, kind, string(payload), time.Now().UnixMilli())
	return err
}
