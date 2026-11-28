package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
)

const externalPlaybackSelection = `SELECT lf.library_id,e.item_id,e.id,v.id,ls.binding_id,f.source_version
FROM library_file lf
JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator
JOIN variant v ON v.library_id=lf.library_id AND v.source_file_id=lf.source_file_id
JOIN edition e ON e.library_id=v.library_id AND e.id=v.edition_id
JOIN library_item i ON i.library_id=e.library_id AND i.id=e.item_id
JOIN source_file f ON f.id=lf.source_file_id
JOIN library l ON l.id=lf.library_id
LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE lf.locator=? AND f.source_version=? AND l.archived_at IS NULL AND ` + libraryVisibility + `
AND i.kind IN ('movie','episode','video') AND lf.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.proof_version=1
ORDER BY lf.library_id,e.item_id,v.id LIMIT 1`

// RecordExternalPlayback mirrors ordinary file-player checkpoints into the
// catalog timeline when the file already belongs to a visible media item.
func (store *Store) RecordExternalPlayback(ctx context.Context, viewer uint, locator []byte, sourceVersion string, positionMs, durationMs int64) error {
	if store == nil || store.db == nil {
		return nil
	}
	if viewer == 0 || len(locator) == 0 || sourceVersion == "" || positionMs < 0 || durationMs < 0 || durationMs > 0 && positionMs > durationMs {
		return ErrInvalid
	}
	var libraryID, itemID, editionID, variantID, bindingID, resolvedSourceVersion string
	err := store.db.QueryRowContext(ctx, externalPlaybackSelection, viewer, locator, sourceVersion, viewer).Scan(
		&libraryID, &itemID, &editionID, &variantID, &bindingID, &resolvedSourceVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	access, err := store.ResolvePlayback(ctx, viewer, libraryID, itemID, editionID, variantID, bindingID)
	if err != nil {
		return err
	}
	session := fmt.Sprintf("external:%s:%s", resolvedSourceVersion, variantID)
	if _, err := store.BeginWatchProgress(ctx, access, resolvedSourceVersion, session, positionMs == 0, ""); err != nil {
		return err
	}
	var duration *int64
	if durationMs > 0 {
		duration = &durationMs
	}
	_, err = store.RecordWatchProgress(ctx, access, resolvedSourceVersion, session, WatchEvent{
		Sequence: 1, PositionMs: positionMs, DurationMs: duration,
	})
	return err
}
