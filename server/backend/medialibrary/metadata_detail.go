package medialibrary

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
)

type metadataReader interface {
	schemaReader
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}

func (store *Store) GetItemDetail(ctx context.Context, actor uint, libraryID, itemID string) (*ItemDetail, error) {
	return readItemDetail(ctx, store.db, actor, libraryID, itemID)
}

func readItemDetail(ctx context.Context, reader metadataReader, actor uint, libraryID, itemID string) (*ItemDetail, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	library, err := activeCatalogLibrary(ctx, reader, actor, libraryID)
	if err != nil {
		return nil, err
	}
	item, err := scanCatalogItem(reader.QueryRowContext(ctx, catalogSelection+" WHERE i.library_id = ? AND i.id = ? AND "+libraryVisibility+" AND l.archived_at IS NULL", actor, libraryID, itemID, actor))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	detail := &ItemDetail{Item: *item, Fields: map[string]MetadataField{}, Credits: []MetadataCredit{}, Ratings: []MetadataRating{}, Artwork: []MetadataArtwork{}, ExternalIDs: []MetadataExternalID{}, Issues: []string{}, IssueSources: []MetadataIssueSource{}}
	visibility := ` JOIN library l ON l.id=x.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL WHERE x.library_id=? AND x.item_id=? AND ` + libraryVisibility + ` AND l.archived_at IS NULL`
	validInput := ` AND (x.input_id IS NULL OR EXISTS (SELECT 1 FROM metadata_input mi JOIN library_source ls ON ls.binding_id=mi.binding_id AND ls.library_id=mi.library_id WHERE mi.id=x.input_id AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1))`
	validFieldOrigin := ` AND x.source!='legacy' AND (x.source!='filename' OR EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=x.source_binding_id AND ls.library_id=x.library_id AND ls.proof_epoch=x.source_binding_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1))`
	args := []any{actor, libraryID, itemID, actor}
	rows, err := reader.QueryContext(ctx, `SELECT x.field,x.value_json,x.source,x.locked,x.explicit_clear,x.revision,COALESCE(x.input_id,''),COALESCE(x.source_binding_id,''),x.source_binding_epoch FROM item_field x`+visibility+validInput+validFieldOrigin+` ORDER BY x.field`, args...)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var name, encoded string
		var field MetadataField
		var locked, clear int
		if err := rows.Scan(&name, &encoded, &field.Source, &locked, &clear, &field.Revision, &field.InputID, &field.BindingID, &field.BindingEpoch); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if err := json.Unmarshal([]byte(encoded), &field.Values); err != nil {
			_ = rows.Close()
			return nil, err
		}
		field.Locked, field.ExplicitClear = locked == 1, clear == 1
		detail.Fields[name] = field
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	rows, err = reader.QueryContext(ctx, `SELECT x.field FROM item_field x`+visibility+` AND x.source='legacy' ORDER BY x.field`, args...)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var field string
		if err := rows.Scan(&field); err != nil {
			_ = rows.Close()
			return nil, err
		}
		detail.LegacyFields = append(detail.LegacyFields, field)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	if title, ok := detail.Fields["title"]; !ok || title.ExplicitClear || len(title.Values) == 0 {
		detail.Item.Title = ""
	} else {
		detail.Item.Title = title.Values[0]
	}
	rows, err = reader.QueryContext(ctx, `SELECT x.id,x.person_id,x.display_name,x.kind,x.role,x.roles_json,x.position,x.input_id FROM item_credit x`+visibility+validInput+` AND x.active=1 ORDER BY x.position,x.id LIMIT 21`, args...)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var c MetadataCredit
		var roles string
		if err := rows.Scan(&c.ID, &c.PersonID, &c.Name, &c.Kind, &c.Role, &roles, &c.Position, &c.InputID); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if err := json.Unmarshal([]byte(roles), &c.Roles); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if len(detail.Credits) == 20 {
			last := detail.Credits[len(detail.Credits)-1]
			detail.CreditsNextCursor = metadataPositionCursor(last.Position, last.ID)
			break
		}
		detail.Credits = append(detail.Credits, c)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	rows, err = reader.QueryContext(ctx, `SELECT x.id,x.scope,mi.source_type,mi.created_at,x.rating_json,x.input_id,x.position FROM rating_observation x JOIN metadata_input mi ON mi.id=x.input_id`+visibility+validInput+` AND x.active=1 ORDER BY x.position,x.id LIMIT 21`, args...)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var rating MetadataRating
		var encoded string
		if err := rows.Scan(&rating.ID, &rating.Scope, &rating.Origin, &rating.ImportedAt, &encoded, &rating.InputID, &rating.Position); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if err := json.Unmarshal([]byte(encoded), &rating.Rating); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if len(detail.Ratings) == 20 {
			last := detail.Ratings[len(detail.Ratings)-1]
			detail.RatingsNextCursor = metadataPositionCursor(last.Position, last.ID)
			break
		}
		detail.Ratings = append(detail.Ratings, rating)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	rows, err = reader.QueryContext(ctx, `SELECT x.id,x.role,x.status,x.input_id,x.position FROM artwork_declaration x`+visibility+validInput+` AND x.active=1 ORDER BY x.position,x.id LIMIT 21`, args...)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var artwork MetadataArtwork
		if err := rows.Scan(&artwork.ID, &artwork.Role, &artwork.Status, &artwork.InputID, &artwork.Position); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if len(detail.Artwork) == 20 {
			last := detail.Artwork[len(detail.Artwork)-1]
			detail.ArtworkNextCursor = metadataPositionCursor(last.Position, last.ID)
			break
		}
		detail.Artwork = append(detail.Artwork, artwork)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	rows, err = reader.QueryContext(ctx, `SELECT x.id,x.provider,x.entity_kind,x.external_id,x.input_id FROM item_external_id x`+visibility+validInput+` AND x.active=1 ORDER BY x.id LIMIT 21`, args...)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id MetadataExternalID
		if err := rows.Scan(&id.ID, &id.Provider, &id.EntityKind, &id.Value, &id.InputID); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if len(detail.ExternalIDs) == 20 {
			detail.ExternalIDsNextCursor = detail.ExternalIDs[len(detail.ExternalIDs)-1].ID
			break
		}
		detail.ExternalIDs = append(detail.ExternalIDs, id)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	rows, err = reader.QueryContext(ctx, `SELECT x.issue_code,x.id,x.evidence_json FROM metadata_input x`+visibility+` AND x.is_current=1 AND x.status='candidate' AND x.issue_code!='' AND EXISTS(SELECT 1 FROM library_source ls WHERE ls.binding_id=x.binding_id AND ls.library_id=x.library_id AND ls.proof_epoch=x.binding_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1) ORDER BY x.id LIMIT 21`, args...)
	if err != nil {
		return nil, err
	}
	issueCodes := map[string]bool{}
	for rows.Next() {
		var issue MetadataIssueSource
		var evidence string
		if err := rows.Scan(&issue.Code, &issue.InputID, &evidence); err != nil {
			_ = rows.Close()
			return nil, err
		}
		issue.ID = issue.InputID
		if len(detail.IssueSources) == 20 {
			detail.IssuesNextCursor = detail.IssueSources[len(detail.IssueSources)-1].ID
			break
		}
		var document struct {
			Items []struct {
				Kind string `json:"kind"`
			} `json:"items"`
		}
		if err := json.Unmarshal([]byte(evidence), &document); err != nil {
			_ = rows.Close()
			return nil, err
		}
		seenKinds := map[string]bool{}
		for _, item := range document.Items {
			if item.Kind != "" && !seenKinds[item.Kind] {
				issue.SourceKinds = append(issue.SourceKinds, item.Kind)
				seenKinds[item.Kind] = true
			}
		}
		detail.IssueSources = append(detail.IssueSources, issue)
		if !issueCodes[issue.Code] {
			detail.Issues = append(detail.Issues, issue.Code)
			issueCodes[issue.Code] = true
		}
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	err = reader.QueryRowContext(ctx, `SELECT COALESCE(max(x.revision),0) FROM metadata_revision x`+visibility, args...).Scan(&detail.MetadataRevision)
	if err != nil {
		return nil, err
	}
	// Recheck after reading: a grant revoked during this multi-query read must
	// not be followed by a response containing the previously read details.
	currentLibrary, err := activeCatalogLibrary(ctx, reader, actor, libraryID)
	if err != nil {
		return nil, err
	}
	if currentLibrary.Revision != library.Revision {
		return nil, ErrRevisionConflict
	}
	var currentRevision int64
	err = reader.QueryRowContext(ctx, `SELECT i.revision FROM library_item i JOIN library l ON l.id=i.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL WHERE i.library_id=? AND i.id=? AND `+libraryVisibility+` AND l.archived_at IS NULL`, actor, libraryID, itemID, actor).Scan(&currentRevision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if currentRevision != detail.Item.Revision {
		return nil, ErrRevisionConflict
	}
	return detail, nil
}

func (store *Store) ApplyMetadata(ctx context.Context, actor uint, libraryID, itemID string, expectedRevision int64, operationID string, patch MetadataPatch) (*MetadataMutationResult, error) {
	type receiptField struct {
		ValuesPresent bool
		Values        []string
		Clear         bool
		Reset         bool
		Locked        *bool
	}
	canonical := make(map[string]receiptField, len(patch.Fields))
	for name, field := range patch.Fields {
		canonical[name] = receiptField{ValuesPresent: field.Values != nil, Values: field.Values, Clear: field.Clear, Reset: field.Reset, Locked: field.Locked}
	}
	return operationTx(ctx, store, actor, "metadata.apply", operationID, struct {
		LibraryID, ItemID string
		Revision          int64
		Patch             map[string]receiptField
	}{libraryID, itemID, expectedRevision, canonical}, func(tx *sql.Tx) (*MetadataMutationResult, error) {
		revision, err := applyMetadataTx(ctx, tx, actor, libraryID, itemID, expectedRevision, patch)
		if err != nil {
			return nil, err
		}
		metaRevision, err := metadataRevision(ctx, tx, libraryID, itemID)
		if err != nil {
			return nil, err
		}
		return &MetadataMutationResult{LibraryID: libraryID, ItemID: itemID, ItemRevision: revision, MetadataRevision: metaRevision}, nil
	})
}

func (store *Store) UndoMetadata(ctx context.Context, actor uint, libraryID, itemID string, expectedRevision, targetRevision int64, operationID string) (*MetadataMutationResult, error) {
	return operationTx(ctx, store, actor, "metadata.undo", operationID, struct {
		LibraryID, ItemID string
		Revision, Target  int64
	}{libraryID, itemID, expectedRevision, targetRevision}, func(tx *sql.Tx) (*MetadataMutationResult, error) {
		revision, err := metadataItemRevision(ctx, tx, actor, libraryID, itemID, true)
		if err != nil {
			return nil, err
		}
		if revision != expectedRevision || expectedRevision < 1 {
			return nil, ErrRevisionConflict
		}
		latest, err := metadataRevision(ctx, tx, libraryID, itemID)
		if err != nil {
			return nil, err
		}
		if targetRevision < 1 || latest != targetRevision {
			return nil, ErrRevisionConflict
		}
		var encoded string
		if err := tx.QueryRowContext(ctx, "SELECT changes_json FROM metadata_revision WHERE library_id=? AND item_id=? AND revision=?", libraryID, itemID, targetRevision).Scan(&encoded); errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		} else if err != nil {
			return nil, err
		}
		var change metadataChange
		if err := json.Unmarshal([]byte(encoded), &change); err != nil || change.Version != 1 {
			return nil, fmt.Errorf("%w: unsupported metadata revision", ErrInvalid)
		}
		before, err := readMetadataSnapshot(ctx, tx, libraryID, itemID)
		if err != nil {
			return nil, err
		}
		if !reflect.DeepEqual(before, change.After) {
			return nil, ErrRevisionConflict
		}
		if err := restoreMetadataSnapshot(ctx, tx, libraryID, itemID, revision+1, before, change.Before); err != nil {
			return nil, err
		}
		result, err := tx.ExecContext(ctx, "UPDATE library_item SET title=?,revision=revision+1 WHERE library_id=? AND id=? AND revision=?", change.Before.Title, libraryID, itemID, revision)
		if err != nil {
			return nil, err
		}
		count, err := result.RowsAffected()
		if err != nil {
			return nil, err
		}
		if count != 1 {
			return nil, ErrRevisionConflict
		}
		after, err := readMetadataSnapshot(ctx, tx, libraryID, itemID)
		if err != nil {
			return nil, err
		}
		if err := recordMetadataChange(ctx, tx, actor, libraryID, itemID, "undo", revision+1, before, after); err != nil {
			return nil, err
		}
		return &MetadataMutationResult{LibraryID: libraryID, ItemID: itemID, ItemRevision: revision + 1, MetadataRevision: revision + 1}, nil
	})
}

func restoreMetadataSnapshot(ctx context.Context, tx *sql.Tx, libraryID, itemID string, newRevision int64, current, target metadataSnapshot) error {
	wanted := make(map[string]metadataFieldState, len(target.Fields))
	existing := make(map[string]metadataFieldState, len(current.Fields))
	for _, field := range target.Fields {
		wanted[field.Name] = field
	}
	for _, field := range current.Fields {
		existing[field.Name] = field
		if _, ok := wanted[field.Name]; !ok {
			if _, err := tx.ExecContext(ctx, "DELETE FROM item_field WHERE library_id=? AND item_id=? AND field=?", libraryID, itemID, field.Name); err != nil {
				return err
			}
		}
	}
	for _, field := range target.Fields {
		if previous, ok := existing[field.Name]; !ok || !metadataStatesEqual(previous, field) {
			field.Revision = newRevision
		} else {
			field.Revision = previous.Revision
		}
		if err := putMetadataField(ctx, tx, libraryID, itemID, field); err != nil {
			return err
		}
	}
	for _, entry := range []struct {
		table   string
		current []string
		target  []string
	}{{"item_credit", current.CreditIDs, target.CreditIDs}, {"rating_observation", current.RatingIDs, target.RatingIDs}, {"artwork_declaration", current.ArtworkIDs, target.ArtworkIDs}, {"item_external_id", current.ExternalIDs, target.ExternalIDs}} {
		currentIDs := make(map[string]bool, len(entry.current))
		targetIDs := make(map[string]bool, len(entry.target))
		for _, id := range entry.current {
			currentIDs[id] = true
		}
		for _, id := range entry.target {
			targetIDs[id] = true
		}
		for _, id := range entry.current {
			if targetIDs[id] {
				continue
			}
			result, err := tx.ExecContext(ctx, "UPDATE "+entry.table+" SET active=0 WHERE library_id=? AND item_id=? AND id=? AND active=1", libraryID, itemID, id)
			if err != nil {
				return err
			}
			n, err := result.RowsAffected()
			if err != nil {
				return err
			}
			if n != 1 {
				return ErrRevisionConflict
			}
		}
		for _, id := range entry.target {
			if currentIDs[id] {
				continue
			}
			result, err := tx.ExecContext(ctx, "UPDATE "+entry.table+" SET active=1 WHERE library_id=? AND item_id=? AND id=? AND active=0", libraryID, itemID, id)
			if err != nil {
				return err
			}
			n, err := result.RowsAffected()
			if err != nil {
				return err
			}
			if n != 1 {
				return ErrRevisionConflict
			}
		}
	}
	return nil
}
