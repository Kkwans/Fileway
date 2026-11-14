package medialibrary

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
)

// ContinueCandidate is private evidence. Only the HTTP authorization layer
// can establish that a native handle still has this exact timeline.
type ContinueCandidate struct {
	LibraryID, ItemID, EditionID, VariantID string
	watchRecord
}

func (c ContinueCandidate) Matches(access *PlaybackAccess, stamp string) bool {
	return access != nil && access.LibraryID == c.LibraryID && access.ItemID == c.ItemID &&
		access.EditionID == c.EditionID && access.VariantID == c.VariantID &&
		c.sameTimeline(access, stamp, c.resetRevision)
}

// Revision fences a specific observation, including a new session or a
// same-millisecond update. Hiding preserves the observation for replay.
func (c ContinueCandidate) Revision() string {
	value, _ := json.Marshal([]any{c.LibraryID, c.ItemID, c.EditionID, c.VariantID, c.session,
		c.Sequence, c.resetRevision, c.updatedAt, c.stamp})
	sum := sha256.Sum256(value)
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

const continueCandidatesQuery = `SELECT w.library_id,w.item_id,w.edition_id,w.variant_id,w.source_file_id,
w.native_stamp,w.session_id,w.file_revision,w.identity_epoch,w.edition_revision,w.mapping_revision,w.reset_revision,
w.sequence,w.position_ms,w.duration_ms,w.completed,w.updated_at
FROM user_watch_progress w JOIN library l ON l.id=w.library_id
JOIN item_structure i ON i.library_id=w.library_id AND i.item_id=w.item_id AND i.superseded_by IS NULL
JOIN edition e ON e.library_id=w.library_id AND e.id=w.edition_id AND e.item_id=w.item_id AND e.mapping_revision=w.edition_revision
JOIN variant v ON v.library_id=w.library_id AND v.id=w.variant_id AND v.edition_id=w.edition_id AND v.source_file_id=w.source_file_id AND v.mapping_revision=w.mapping_revision
JOIN source_file f ON f.id=w.source_file_id AND f.revision=w.file_revision AND f.identity_epoch=w.identity_epoch AND f.identity_kind='native'
LEFT JOIN library_member m ON m.library_id=w.library_id AND m.user_id=w.user_id AND m.revoked_at IS NULL
LEFT JOIN user_item_state s ON s.user_id=w.user_id AND s.library_id=w.library_id AND s.item_id=w.item_id
WHERE w.user_id=? AND l.archived_at IS NULL AND ` + libraryVisibility + ` AND (?=0 OR l.owner_user_id=w.user_id)
AND w.continue_hidden=0 AND w.completed=0 AND w.position_ms>0 AND w.updated_at>0
AND w.reset_revision=COALESCE(s.watch_reset_revision,0) AND COALESCE(s.manual_watched,0)=0
AND (w.updated_at,w.variant_id) < (?,?)
ORDER BY w.updated_at DESC,w.variant_id DESC LIMIT ?`

func (store *Store) ListContinueCandidates(ctx context.Context, viewer uint, onlyOwned bool, before int64, after string, limit int) ([]ContinueCandidate, error) {
	if viewer == 0 || limit < 1 || limit > 100 {
		return nil, ErrInvalid
	}
	rows, err := store.db.QueryContext(ctx, continueCandidatesQuery, viewer, viewer, onlyOwned, before, after, limit)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	result := make([]ContinueCandidate, 0, limit)
	for rows.Next() {
		var c ContinueCandidate
		err = rows.Scan(&c.LibraryID, &c.ItemID, &c.EditionID, &c.VariantID, &c.source,
			&c.stamp, &c.session, &c.fileRevision, &c.identityEpoch, &c.editionRevision, &c.mappingRevision, &c.resetRevision,
			&c.Sequence, &c.PositionMs, &c.DurationMs, &c.Completed, &c.updatedAt)
		if err != nil {
			return nil, err
		}
		c.variant = c.VariantID
		result = append(result, c)
	}
	return result, rows.Err()
}

func (c ContinueCandidate) UpdatedAt() int64 { return c.updatedAt }

func (record watchRecord) candidate(access *PlaybackAccess) ContinueCandidate {
	return ContinueCandidate{access.LibraryID, access.ItemID, access.EditionID, access.VariantID, record}
}

func (record watchRecord) canContinue(access *PlaybackAccess, stamp string, state *UserState, revision string) bool {
	return !record.hidden && !record.Completed && record.PositionMs > 0 && (state.ManualWatched == nil || !*state.ManualWatched) &&
		record.sameTimeline(access, stamp, state.WatchResetRevision) && record.candidate(access).Revision() == revision
}

func (store *Store) ContinueCurrent(ctx context.Context, access *PlaybackAccess, stamp, revision string) (bool, error) {
	record, err := readWatchRecord(ctx, store.db, access)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	state, err := readUserState(ctx, store.db, access.ViewerUserID, access.LibraryID, access.ItemID)
	if err != nil {
		return false, err
	}
	return record.canContinue(access, stamp, state, revision), nil
}

func (store *Store) RemoveContinue(ctx context.Context, access *PlaybackAccess, stamp, revision string) error {
	return store.WithTx(ctx, func(tx *sql.Tx) error {
		state, err := validateWatchAccess(ctx, tx, access)
		if err != nil {
			return err
		}
		record, err := readWatchRecord(ctx, tx, access)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		candidate := record.candidate(access)
		if !record.sameTimeline(access, stamp, state.WatchResetRevision) || record.Completed || record.PositionMs <= 0 || candidate.Revision() != revision {
			return ErrRevisionConflict
		}
		if record.hidden {
			return nil
		}
		_, err = tx.ExecContext(ctx, `UPDATE user_watch_progress SET continue_hidden=1
WHERE user_id=? AND library_id=? AND item_id=? AND edition_id=? AND variant_id=?`,
			access.ViewerUserID, access.LibraryID, access.ItemID, access.EditionID, access.VariantID)
		return err
	})
}
