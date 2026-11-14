package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type WatchProgress struct {
	Sequence   int64  `json:"sequence"`
	PositionMs int64  `json:"positionMs"`
	DurationMs *int64 `json:"durationMs"`
	Completed  bool   `json:"completed"`
}

type WatchEvent struct {
	Sequence   int64  `json:"sequence"`
	PositionMs int64  `json:"positionMs"`
	DurationMs *int64 `json:"durationMs"`
	Ended      bool   `json:"ended"`
}

// The source stamp is obtained from the pinned native handle and remains
// server-side. An item may have multiple edition-specific time axes.
type watchRecord struct {
	WatchProgress
	source, variant, stamp, session                                              string
	fileRevision, identityEpoch, editionRevision, mappingRevision, resetRevision int64
	updatedAt                                                                    int64
	hidden                                                                       bool
}

const watchRecordColumns = `source_file_id,variant_id,native_stamp,session_id,file_revision,identity_epoch,edition_revision,mapping_revision,reset_revision,
sequence,position_ms,duration_ms,completed,updated_at,continue_hidden`

func readWatchRecord(ctx context.Context, reader schemaReader, access *PlaybackAccess) (watchRecord, error) {
	var record watchRecord
	err := reader.QueryRowContext(ctx, `SELECT `+watchRecordColumns+` FROM user_watch_progress
WHERE user_id=? AND library_id=? AND item_id=? AND edition_id=? AND variant_id=?`, access.ViewerUserID, access.LibraryID, access.ItemID, access.EditionID, access.VariantID).
		Scan(&record.source, &record.variant, &record.stamp, &record.session, &record.fileRevision, &record.identityEpoch, &record.editionRevision,
			&record.mappingRevision, &record.resetRevision, &record.Sequence, &record.PositionMs, &record.DurationMs, &record.Completed, &record.updatedAt, &record.hidden)
	return record, err
}

func (record watchRecord) sameTimeline(access *PlaybackAccess, stamp string, reset int64) bool {
	return record.source == access.SourceFileID && record.variant == access.VariantID && record.stamp == stamp &&
		record.fileRevision == access.FileRevision && record.identityEpoch == access.IdentityEpoch &&
		record.editionRevision == access.EditionMappingRevision && record.mappingRevision == access.MappingRevision &&
		record.resetRevision == reset
}

func validateWatchAccess(ctx context.Context, tx *sql.Tx, access *PlaybackAccess) (*UserState, error) {
	current, err := resolvePlayback(ctx, tx, access.ViewerUserID, access.LibraryID, access.ItemID, access.EditionID, access.VariantID, access.BindingID)
	if err != nil {
		return nil, err
	}
	if current.MissingSince != nil || !SamePlaybackAccess(access, current) {
		return nil, ErrRevisionConflict
	}
	return readUserState(ctx, tx, access.ViewerUserID, access.LibraryID, access.ItemID)
}

// BeginWatchProgress transfers write ownership of an edition to the new
// session. It preserves the checkpoint only if the native timeline matches.
func (store *Store) BeginWatchProgress(ctx context.Context, access *PlaybackAccess, stamp, session string, fromStart bool, continueRevision string) (WatchProgress, error) {
	progress := WatchProgress{}
	if access == nil || access.IdentityKind != "native" || stamp == "" || session == "" {
		return progress, ErrInvalid
	}
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		state, err := validateWatchAccess(ctx, tx, access)
		if err != nil {
			return err
		}
		record, err := readWatchRecord(ctx, tx, access)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if continueRevision != "" && (err != nil || !record.canContinue(access, stamp, state, continueRevision)) {
			return ErrRevisionConflict
		}
		switch {
		case err == nil && !fromStart && !record.hidden && record.sameTimeline(access, stamp, state.WatchResetRevision):
			progress = record.WatchProgress
			progress.Sequence = 0
			if progress.Completed {
				progress.PositionMs = 0
			}
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO user_watch_progress(user_id,library_id,item_id,source_file_id,edition_id,variant_id,
file_revision,identity_epoch,edition_revision,mapping_revision,native_stamp,session_id,reset_revision,position_ms,duration_ms,completed,updated_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,library_id,item_id,edition_id,variant_id) DO UPDATE SET
source_file_id=excluded.source_file_id,variant_id=excluded.variant_id,file_revision=excluded.file_revision,identity_epoch=excluded.identity_epoch,
edition_revision=excluded.edition_revision,mapping_revision=excluded.mapping_revision,native_stamp=excluded.native_stamp,session_id=excluded.session_id,
reset_revision=excluded.reset_revision,sequence=0,continue_hidden=0,position_ms=excluded.position_ms,duration_ms=excluded.duration_ms,completed=excluded.completed,
updated_at=CASE WHEN excluded.position_ms=0 THEN 0 ELSE user_watch_progress.updated_at END`,
			access.ViewerUserID, access.LibraryID, access.ItemID, access.SourceFileID, access.EditionID, access.VariantID,
			access.FileRevision, access.IdentityEpoch, access.EditionMappingRevision, access.MappingRevision, stamp, session,
			state.WatchResetRevision, progress.PositionMs, progress.DurationMs, progress.Completed, 0)
		return err
	})
	return progress, err
}

const maxWatchMediaMs = 30 * 24 * 60 * 60 * 1000

// Only natural completion is a completion event. A position report at the
// end of a timeline does not imply the viewer watched the video.
func (store *Store) RecordWatchProgress(ctx context.Context, access *PlaybackAccess, stamp, session string, event WatchEvent) (WatchProgress, error) {
	var progress WatchProgress
	if access == nil || access.IdentityKind != "native" || stamp == "" || session == "" ||
		event.Sequence < 1 || event.PositionMs < 0 || event.PositionMs > maxWatchMediaMs ||
		event.DurationMs != nil && (*event.DurationMs <= 0 || *event.DurationMs > maxWatchMediaMs || event.PositionMs > *event.DurationMs) ||
		event.Ended && (event.DurationMs == nil || *event.DurationMs-event.PositionMs > 2_000) {
		return progress, ErrInvalid
	}
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		state, err := validateWatchAccess(ctx, tx, access)
		if err != nil {
			return err
		}
		record, err := readWatchRecord(ctx, tx, access)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrRevisionConflict
		}
		if err != nil {
			return err
		}
		if record.hidden || record.session != session || !record.sameTimeline(access, stamp, state.WatchResetRevision) {
			return ErrRevisionConflict
		}
		progress = record.WatchProgress
		if event.Sequence <= record.Sequence {
			return nil
		}
		progress.Sequence, progress.PositionMs = event.Sequence, event.PositionMs
		progress.Completed = progress.Completed || event.Ended
		if event.DurationMs != nil {
			progress.DurationMs = event.DurationMs
		} else if progress.DurationMs != nil && event.PositionMs > *progress.DurationMs {
			progress.DurationMs = nil
		}
		_, err = tx.ExecContext(ctx, `UPDATE user_watch_progress SET sequence=?,position_ms=?,duration_ms=?,completed=?,updated_at=?
WHERE user_id=? AND library_id=? AND item_id=? AND edition_id=? AND variant_id=?`, progress.Sequence, progress.PositionMs, progress.DurationMs, progress.Completed,
			time.Now().UnixMilli(), access.ViewerUserID, access.LibraryID, access.ItemID, access.EditionID, access.VariantID)
		return err
	})
	return progress, err
}
