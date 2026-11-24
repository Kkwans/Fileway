package medialibrary

import (
	"context"
)

type MetadataRefreshFieldState struct {
	Source        string
	Locked        bool
	ExplicitClear bool
}

func (store *Store) MetadataRefreshFieldStates(ctx context.Context, actor uint, libraryID, itemID string) (map[string]MetadataRefreshFieldState, error) {
	if _, err := editableCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, `SELECT field,source,locked,explicit_clear FROM item_field WHERE library_id=? AND item_id=?`, libraryID, itemID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	states := map[string]MetadataRefreshFieldState{}
	for rows.Next() {
		var name, source string
		var locked, cleared int
		if err := rows.Scan(&name, &source, &locked, &cleared); err != nil {
			return nil, err
		}
		states[name] = MetadataRefreshFieldState{Source: source, Locked: locked != 0, ExplicitClear: cleared != 0}
	}
	return states, rows.Err()
}
