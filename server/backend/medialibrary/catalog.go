package medialibrary

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

type CatalogItem struct {
	ID        string
	LibraryID string
	Kind      string
	ParentID  string
	Title     string
	Revision  int64
	CreatedAt int64
	FileCount int
}

type NewCatalogItem struct {
	Kind           string
	ParentID       string
	Title          string
	initialSource  string
	initialBinding *SourceBindingRef
}

type CatalogListOptions struct {
	Limit   int
	AfterID string
	// Empty includes all kinds/parents. These filters do not change visibility.
	Kind     string
	ParentID string
	// A file may belong to multiple episodes; this is a filter, not a promise
	// that source-file identity and content identity are interchangeable.
	SourceFileID string
}

type CatalogPage struct {
	Items      []CatalogItem
	NextCursor string
}

// CatalogFileLink identifies an association, not a playback URL or source path.
type CatalogFileLink struct {
	LibraryID    string
	ItemID       string
	EditionID    string
	VariantID    string
	SourceFileID string
	ItemRevision int64
}

func (store *Store) CreateCatalogItem(ctx context.Context, actor uint, libraryID string, input NewCatalogItem) (*CatalogItem, error) {
	var item *CatalogItem
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		item, err = createCatalogItemTx(ctx, tx, actor, libraryID, input)
		return err
	})
	if err != nil {
		return nil, err
	}
	return item, nil
}

// createCatalogItemTx is shared by interactive creation and the scanner so a
// new unmatched item can commit together with its source observation.
func createCatalogItemTx(ctx context.Context, tx *sql.Tx, actor uint, libraryID string, input NewCatalogItem) (*CatalogItem, error) {
	if !catalogKind(input.Kind) {
		return nil, fmt.Errorf("%w: catalog kind", ErrInvalid)
	}
	title, err := catalogTitle(input.Title)
	if err != nil {
		return nil, err
	}
	if _, err := editableCatalogLibrary(ctx, tx, actor, libraryID); err != nil {
		return nil, err
	}
	if input.ParentID != "" {
		var parentID string
		if err := tx.QueryRowContext(ctx, "SELECT id FROM library_item WHERE library_id = ? AND id = ?", libraryID, input.ParentID).Scan(&parentID); errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		} else if err != nil {
			return nil, err
		}
	}
	id, err := randomID("item_")
	if err != nil {
		return nil, err
	}
	item := &CatalogItem{ID: id, LibraryID: libraryID, Kind: input.Kind, ParentID: input.ParentID, Title: title, Revision: 1, CreatedAt: time.Now().UnixMilli()}
	var parent any
	if input.ParentID != "" {
		parent = input.ParentID
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO library_item(id, library_id, kind, parent_id, title, created_at) VALUES (?, ?, ?, ?, ?, ?)", id, libraryID, input.Kind, parent, title, item.CreatedAt); err != nil {
		return nil, err
	}
	source, locked := "manual", 1
	var bindingID any
	var bindingEpoch int64
	if input.initialSource == "filename" {
		source, locked = "filename", 0
		if input.initialBinding == nil {
			return nil, fmt.Errorf("%w: filename binding", ErrInvalid)
		}
		bindingID, bindingEpoch = input.initialBinding.BindingID, input.initialBinding.ProofEpoch
	}
	titleJSON, err := json.Marshal([]string{title})
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO item_field(library_id,item_id,field,value_json,source,source_binding_id,source_binding_epoch,locked,explicit_clear,revision) VALUES (?, ?, 'title', ?, ?,?,?, ?, 0, 1)`, libraryID, id, string(titleJSON), source, bindingID, bindingEpoch, locked); err != nil {
		return nil, err
	}
	if err := publishCatalogChange(ctx, tx, actor, libraryID, "catalog.item.created", catalogChange{ItemID: item.ID, Revision: item.Revision}); err != nil {
		return nil, err
	}
	return item, nil
}

func catalogTitle(value string) (string, error) {
	if !utf8.ValidString(value) {
		return "", fmt.Errorf("%w: title must be valid UTF-8", ErrInvalid)
	}
	value = strings.TrimSpace(value)
	if value == "" || utf8.RuneCountInString(value) > 1024 || strings.IndexFunc(value, unicode.IsControl) >= 0 {
		return "", fmt.Errorf("%w: title must contain 1 to 1024 characters without controls", ErrInvalid)
	}
	return value, nil
}

// AttachCatalogFile can only use a file previously observed under this library's
// owner-controlled source binding. An editor cannot nominate an arbitrary file
// ID from another library, even if both libraries are visible to that editor.
// The default edition uses the existing edition/variant model and is created
// lazily; no source filesystem permission is inferred from this association.
func (store *Store) AttachCatalogFile(ctx context.Context, actor uint, libraryID, itemID string, expectedRevision int64, sourceFileID string) (*CatalogFileLink, error) {
	var link *CatalogFileLink
	err := store.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		link, err = attachCatalogFileTx(ctx, tx, actor, libraryID, itemID, expectedRevision, sourceFileID)
		return err
	})
	if err != nil {
		return nil, err
	}
	return link, nil
}

// attachCatalogFileTx preserves the same library and revision checks for scans
// and interactive calls; it never treats the association as file authorization.
func attachCatalogFileTx(ctx context.Context, tx *sql.Tx, actor uint, libraryID, itemID string, expectedRevision int64, sourceFileID string) (*CatalogFileLink, error) {
	library, err := editableCatalogLibrary(ctx, tx, actor, libraryID)
	if err != nil {
		return nil, err
	}
	var revision int64
	var kind string
	if err := tx.QueryRowContext(ctx, "SELECT revision, kind FROM library_item WHERE library_id = ? AND id = ?", libraryID, itemID).Scan(&revision, &kind); errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	} else if err != nil {
		return nil, err
	}
	if revision != expectedRevision || expectedRevision < 1 {
		return nil, ErrRevisionConflict
	}
	if kind != "movie" && kind != "episode" && kind != "video" {
		return nil, fmt.Errorf("%w: only playable catalog items can have files", ErrInvalid)
	}
	var allowed string
	if err := tx.QueryRowContext(ctx, `SELECT f.id FROM library_file lf JOIN source_file f ON f.id = lf.source_file_id AND f.source_id = lf.source_id JOIN source s ON s.id = f.source_id
JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator
WHERE lf.library_id = ? AND f.id = ? AND s.owner_user_id = ? AND s.kind = 'local' AND lf.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0`, libraryID, sourceFileID, library.OwnerUserID).Scan(&allowed); errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	} else if err != nil {
		return nil, err
	}
	link := &CatalogFileLink{LibraryID: libraryID, ItemID: itemID, SourceFileID: sourceFileID, ItemRevision: revision}
	err = tx.QueryRowContext(ctx, `SELECT e.id, v.id FROM edition e JOIN variant v ON v.edition_id = e.id AND v.library_id = e.library_id
WHERE e.library_id = ? AND e.item_id = ? AND v.source_file_id = ? ORDER BY e.id, v.id LIMIT 1`, libraryID, itemID, sourceFileID).Scan(&link.EditionID, &link.VariantID)
	if err == nil {
		return link, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	err = tx.QueryRowContext(ctx, "SELECT id FROM edition WHERE library_id = ? AND item_id = ? AND is_default = 1", libraryID, itemID).Scan(&link.EditionID)
	if errors.Is(err, sql.ErrNoRows) {
		link.EditionID, err = randomID("edition_")
		if err != nil {
			return nil, err
		}
		_, err = tx.ExecContext(ctx, "INSERT INTO edition(id, library_id, item_id, name, is_default) VALUES (?, ?, ?, '', 1)", link.EditionID, libraryID, itemID)
	}
	if err != nil {
		return nil, err
	}
	link.VariantID, err = randomID("variant_")
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO variant(id, library_id, edition_id, source_file_id) VALUES (?, ?, ?, ?)", link.VariantID, libraryID, link.EditionID, sourceFileID); err != nil {
		return nil, err
	}
	result, err := tx.ExecContext(ctx, "UPDATE library_item SET revision = revision + 1 WHERE library_id = ? AND id = ? AND revision = ?", libraryID, itemID, expectedRevision)
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
	link.ItemRevision++
	if err := publishCatalogChange(ctx, tx, actor, libraryID, "catalog.item.file.attached", catalogChange{SourceFileID: sourceFileID, ItemID: itemID, Revision: link.ItemRevision}); err != nil {
		return nil, err
	}
	return link, nil
}

const catalogSelection = `SELECT i.id, i.library_id, i.kind, COALESCE(i.parent_id, ''),
COALESCE((SELECT CASE WHEN f.explicit_clear=1 THEN '' ELSE json_extract(f.value_json,'$[0]') END FROM item_field f WHERE f.library_id=i.library_id AND f.item_id=i.id AND f.field='title' AND f.source!='legacy' AND (f.source!='filename' OR EXISTS(SELECT 1 FROM library_source fb WHERE fb.binding_id=f.source_binding_id AND fb.library_id=f.library_id AND fb.proof_epoch=f.source_binding_epoch AND fb.proof_epoch>0)) AND (f.input_id IS NULL OR EXISTS(SELECT 1 FROM metadata_input mi JOIN library_source ls ON ls.binding_id=mi.binding_id AND ls.library_id=mi.library_id WHERE mi.id=f.input_id AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0))),'') AS title, i.revision, i.created_at,
(SELECT count(DISTINCT v.source_file_id) FROM edition e JOIN variant v ON v.edition_id = e.id AND v.library_id = e.library_id
 JOIN library_file lf ON lf.library_id = v.library_id AND lf.source_file_id = v.source_file_id
 JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator AND lf.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0
 WHERE e.library_id = i.library_id AND e.item_id = i.id)
FROM library_item i JOIN library l ON l.id = i.library_id
LEFT JOIN library_member m ON m.library_id = l.id AND m.user_id = ? AND m.revoked_at IS NULL`

func (store *Store) GetCatalogItem(ctx context.Context, actor uint, libraryID, itemID string) (*CatalogItem, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	item, err := scanCatalogItem(store.db.QueryRowContext(ctx, catalogSelection+" WHERE i.library_id = ? AND i.id = ? AND "+libraryVisibility+" AND l.archived_at IS NULL", actor, libraryID, itemID, actor))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return item, err
}

func (store *Store) ListCatalogItems(ctx context.Context, actor uint, libraryID string, options CatalogListOptions) (*CatalogPage, error) {
	if options.Limit == 0 {
		options.Limit = 50
	}
	if options.Limit < 1 || options.Limit > 100 || (options.Kind != "" && !catalogKind(options.Kind)) {
		return nil, fmt.Errorf("%w: catalog list options", ErrInvalid)
	}
	if _, err := activeCatalogLibrary(ctx, store.db, actor, libraryID); err != nil {
		return nil, err
	}
	// Reapply visibility in the row query itself: a revoked grant between the
	// existence check and this SELECT must not reveal the catalog.
	rows, err := store.db.QueryContext(ctx, catalogSelection+" WHERE i.library_id = ? AND "+libraryVisibility+` AND l.archived_at IS NULL AND i.id > ?
AND (? = '' OR i.kind = ?) AND (? = '' OR i.parent_id = ?)
AND (? = '' OR EXISTS (SELECT 1 FROM edition fe JOIN variant fv ON fv.edition_id = fe.id AND fv.library_id = fe.library_id
JOIN library_file lf ON lf.library_id = fv.library_id AND lf.source_file_id = fv.source_file_id
JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator AND lf.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0
WHERE fe.library_id = i.library_id AND fe.item_id = i.id AND fv.source_file_id = ?))
ORDER BY i.id LIMIT ?`, actor, libraryID, actor, options.AfterID, options.Kind, options.Kind, options.ParentID, options.ParentID, options.SourceFileID, options.SourceFileID, options.Limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &CatalogPage{Items: make([]CatalogItem, 0, options.Limit)}
	for rows.Next() {
		item, err := scanCatalogItem(rows)
		if err != nil {
			return nil, err
		}
		if len(page.Items) == options.Limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		page.Items = append(page.Items, *item)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

func scanCatalogItem(row rowScanner) (*CatalogItem, error) {
	item := &CatalogItem{}
	err := row.Scan(&item.ID, &item.LibraryID, &item.Kind, &item.ParentID, &item.Title, &item.Revision, &item.CreatedAt, &item.FileCount)
	if err != nil {
		return nil, err
	}
	return item, nil
}

func activeCatalogLibrary(ctx context.Context, reader schemaReader, actor uint, id string) (*Library, error) {
	if actor == 0 {
		return nil, ErrForbidden
	}
	library, err := visibleLibrary(ctx, reader, actor, id)
	if err != nil {
		return nil, err
	}
	if library.ArchivedAt != nil {
		return nil, ErrArchived
	}
	return library, nil
}

func editableCatalogLibrary(ctx context.Context, reader schemaReader, actor uint, id string) (*Library, error) {
	library, err := activeCatalogLibrary(ctx, reader, actor, id)
	if err != nil {
		return nil, err
	}
	if library.Role != RoleOwner && library.Role != RoleEditor {
		return nil, ErrForbidden
	}
	return library, nil
}

func catalogKind(kind string) bool {
	switch kind {
	case "movie", "series", "season", "episode", "video":
		return true
	default:
		return false
	}
}

// Outbox payloads contain opaque IDs and revisions, not paths/configuration or
// credentials. Consumers must still apply current user/library visibility.
type catalogChange struct {
	SourceID     string `json:"sourceId,omitempty"`
	SourceFileID string `json:"sourceFileId,omitempty"`
	ItemID       string `json:"itemId,omitempty"`
	Revision     int64  `json:"revision"`
}

func publishCatalogChange(ctx context.Context, tx *sql.Tx, actor uint, libraryID, kind string, change catalogChange) error {
	id, err := randomID("evt_")
	if err != nil {
		return err
	}
	payload, err := json.Marshal(struct {
		ActorUserID uint   `json:"actorUserId"`
		LibraryID   string `json:"libraryId"`
		catalogChange
	}{actor, libraryID, change})
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO outbox(event_id, library_id, kind, payload_json, created_at) VALUES (?, ?, ?, ?, ?)", id, libraryID, kind, string(payload), time.Now().UnixMilli())
	return err
}
