package medialibrary

import (
	"context"
)

type MetadataRefreshFieldState struct {
	Source        string
	Locked        bool
	ExplicitClear bool
}

type MetadataRefreshLibraryPreview struct {
	LibraryID                  string         `json:"libraryId"`
	TargetLanguage             string         `json:"targetLanguage"`
	Region                     string         `json:"region"`
	Mode                       string         `json:"mode"`
	PolicyRevision             int64          `json:"policyRevision"`
	MatchedItems               int            `json:"matchedItems"`
	LanguageMismatchItems      int            `json:"languageMismatchItems"`
	AlreadyCurrentItems        int            `json:"alreadyCurrentItems"`
	LockedItems                int            `json:"lockedItems"`
	ExplicitClearItems         int            `json:"explicitClearItems"`
	NFOPreservedItems          int            `json:"nfoPreservedItems"`
	ProtectedFields            map[string]int `json:"protectedFields"`
	PreservedFields            map[string]int `json:"preservedFields"`
	CurrentLanguages           map[string]int `json:"currentLanguages"`
	FieldChangesRequirePreview bool           `json:"fieldChangesRequireItemPreview"`
	CanRefresh                 bool           `json:"canRefresh"`
}

func (store *Store) MetadataRefreshLibraryPreview(ctx context.Context, actor uint, libraryID, targetLanguage, mode string) (*MetadataRefreshLibraryPreview, error) {
	tx, err := store.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	if _, err := editableCatalogLibrary(ctx, tx, actor, libraryID); err != nil {
		return nil, err
	}
	preview := &MetadataRefreshLibraryPreview{
		LibraryID: libraryID, TargetLanguage: targetLanguage, Mode: mode,
		ProtectedFields: map[string]int{}, PreservedFields: map[string]int{}, CurrentLanguages: map[string]int{},
		FieldChangesRequirePreview: true,
	}
	rows, err := tx.QueryContext(ctx, `SELECT pm.language,
	EXISTS(SELECT 1 FROM item_field f WHERE f.library_id=pm.library_id AND f.item_id=pm.item_id AND f.locked=1),
	EXISTS(SELECT 1 FROM item_field f WHERE f.library_id=pm.library_id AND f.item_id=pm.item_id AND f.explicit_clear=1),
	EXISTS(SELECT 1 FROM item_field f WHERE f.library_id=pm.library_id AND f.item_id=pm.item_id AND f.source='nfo')
	FROM item_provider_match pm
	JOIN library_item i ON i.library_id=pm.library_id AND i.id=pm.item_id
	JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
	WHERE pm.library_id=?`, libraryID)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var language string
		var locked, cleared, nfo int
		if err := rows.Scan(&language, &locked, &cleared, &nfo); err != nil {
			rows.Close()
			return nil, err
		}
		preview.MatchedItems++
		if language == targetLanguage {
			preview.AlreadyCurrentItems++
		} else {
			preview.LanguageMismatchItems++
		}
		key := language
		if key == "" {
			key = "未记录"
		}
		preview.CurrentLanguages[key]++
		preview.LockedItems += locked
		preview.ExplicitClearItems += cleared
		if mode != MetadataOnlineFirst {
			preview.NFOPreservedItems += nfo
		}
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	fieldRows, err := tx.QueryContext(ctx, `SELECT f.field,f.locked,f.explicit_clear,f.source FROM item_field f
	WHERE f.library_id=? AND EXISTS (
		SELECT 1 FROM item_provider_match pm
		JOIN item_structure st ON st.library_id=pm.library_id AND st.item_id=pm.item_id AND st.superseded_by IS NULL
		WHERE pm.library_id=f.library_id AND pm.item_id=f.item_id)`, libraryID)
	if err != nil {
		return nil, err
	}
	defer fieldRows.Close()
	for fieldRows.Next() {
		var field, source string
		var locked, cleared int
		if err := fieldRows.Scan(&field, &locked, &cleared, &source); err != nil {
			return nil, err
		}
		if locked != 0 || cleared != 0 {
			preview.ProtectedFields[field]++
		}
		if source == "nfo" && mode != MetadataOnlineFirst {
			preview.PreservedFields[field]++
		}
	}
	if err := fieldRows.Err(); err != nil {
		return nil, err
	}
	preview.CanRefresh = preview.LanguageMismatchItems > 0
	return preview, tx.Commit()
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
