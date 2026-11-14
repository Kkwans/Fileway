package medialibrary

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// UserState contains only this viewer's explicit choices for one library item.
// A nil ManualWatched means no manual choice; it is not an inferred watch state.
type UserState struct {
	Favorite           bool  `json:"favorite"`
	ManualWatched      *bool `json:"manualWatched"`
	Revision           int64 `json:"revision"`
	WatchResetRevision int64 `json:"watchResetRevision"`
}

type UserStatePatch struct {
	Favorite      *bool `json:"favorite,omitempty"`
	ManualWatched *bool `json:"manualWatched,omitempty"`
}

// GetUserState applies catalog membership and archive visibility in the same
// read as the state. HTTP callers must also check live source authority.
func (store *Store) GetUserState(ctx context.Context, actor uint, libraryID, itemID string) (*UserState, error) {
	return readUserState(ctx, store.db, actor, libraryID, itemID)
}

func readUserState(ctx context.Context, reader schemaReader, actor uint, libraryID, itemID string) (*UserState, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	var favorite, watched, reset, revision sql.NullInt64
	err := reader.QueryRowContext(ctx, `SELECT s.favorite,s.manual_watched,s.watch_reset_revision,s.revision
FROM library_item i
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id
JOIN library l ON l.id=i.library_id
LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
LEFT JOIN user_item_state s ON s.library_id=i.library_id AND s.item_id=i.id AND s.user_id=?
WHERE i.library_id=? AND i.id=? AND `+libraryVisibility+` AND l.archived_at IS NULL AND st.superseded_by IS NULL`,
		actor, actor, libraryID, itemID, actor).Scan(&favorite, &watched, &reset, &revision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	result := &UserState{Favorite: favorite.Valid && favorite.Int64 != 0, Revision: revision.Int64, WatchResetRevision: reset.Int64}
	if watched.Valid {
		value := watched.Int64 != 0
		result.ManualWatched = &value
	}
	return result, nil
}

// SetUserState commits the state and idempotency receipt together. Receipt
// replay still checks current catalog membership and item visibility first.
// Explicit unwatched always advances the reset fence, even if already false;
// retrying the same operation returns its original receipt without advancing it.
func (store *Store) SetUserState(ctx context.Context, actor uint, libraryID, itemID string, expectedRevision int64, operationID string, patch UserStatePatch) (*UserState, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	if expectedRevision < 0 || !validOperationID(operationID) || (patch.Favorite == nil && patch.ManualWatched == nil) {
		return nil, fmt.Errorf("%w: user state", ErrInvalid)
	}
	input := struct {
		LibraryID, ItemID string
		ExpectedRevision  int64
		Patch             UserStatePatch
	}{libraryID, itemID, expectedRevision, patch}
	digest, err := operationDigest(input)
	if err != nil {
		return nil, err
	}
	var result UserState
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		current, err := readUserState(ctx, tx, actor, libraryID, itemID)
		if err != nil {
			return err
		}
		previous, found, err := lookupOperation[UserState](ctx, tx, actor, "user.state.patch", operationID, digest)
		if err != nil {
			return err
		}
		if found {
			result = previous
			return nil
		}
		if current.Revision != expectedRevision {
			return ErrRevisionConflict
		}
		changed := false
		if patch.Favorite != nil && current.Favorite != *patch.Favorite {
			current.Favorite = *patch.Favorite
			changed = true
		}
		if patch.ManualWatched != nil {
			if current.ManualWatched == nil || *current.ManualWatched != *patch.ManualWatched {
				changed = true
			}
			value := *patch.ManualWatched
			current.ManualWatched = &value
			if !value {
				current.WatchResetRevision++
				changed = true
			}
		}
		if changed {
			current.Revision++
			var watched any
			if current.ManualWatched != nil {
				watched = *current.ManualWatched
			}
			_, err = tx.ExecContext(ctx, `INSERT INTO user_item_state(user_id,library_id,item_id,favorite,manual_watched,watch_reset_revision,revision,updated_at)
VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,library_id,item_id) DO UPDATE SET favorite=excluded.favorite,manual_watched=excluded.manual_watched,watch_reset_revision=excluded.watch_reset_revision,revision=excluded.revision,updated_at=excluded.updated_at`,
				actor, libraryID, itemID, current.Favorite, watched, current.WatchResetRevision, current.Revision, time.Now().UnixMilli())
			if err != nil {
				return err
			}
		}
		result = *current
		ack, err := json.Marshal(result)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO operation_receipt(actor_user_id,kind,operation_id,input_digest,result_json,created_at) VALUES(?,?,?,?,?,?)`,
			actor, "user.state.patch", operationID, digest, string(ack), time.Now().UnixMilli())
		return err
	})
	if err != nil {
		return nil, err
	}
	return &result, nil
}
