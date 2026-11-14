package medialibrary

import (
	"math"
	"path/filepath"
	"strings"
	"testing"
)

func TestWatchContinueUpgradeAndIndexedPagination(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "Films")
	item := catalogItem(t, store, 7, library.ID, "Fixture")
	// Reproduce the v16 checkpoint shape on a disposable database.
	_, err = store.db.ExecContext(ctx, `DROP TABLE user_watch_progress;`+schemaV16+`
INSERT INTO source(id,kind,owner_user_id,configuration_ref,created_at) VALUES ('s','local',7,'cfg',1);
INSERT INTO source_file(id,source_id,object_key,identity_kind,source_version,locator,observed_at) VALUES ('f','s',X'01','native','fixture',X'2F66',1);
INSERT INTO edition(id,library_id,item_id,name) VALUES ('e',?,?,'Fixture');
INSERT INTO variant(id,library_id,edition_id,source_file_id) VALUES ('v',?,'e','f');
INSERT INTO user_watch_progress(user_id,library_id,item_id,source_file_id,edition_id,variant_id,file_revision,identity_epoch,edition_revision,mapping_revision,native_stamp,session_id,reset_revision,position_ms,updated_at)
VALUES(7,?,?,'f','e','v',1,1,1,1,'stamp','session',0,42000,123);
PRAGMA user_version=16;`, library.ID, item.ID, library.ID, library.ID, item.ID)
	if err != nil {
		_ = store.Close()
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	items, err := store.ListContinueCandidates(ctx, 7, false, math.MaxInt64, "", 12)
	if err != nil || len(items) != 1 || items[0].PositionMs != 42_000 || items[0].UpdatedAt() != 123 {
		t.Fatalf("migration lost checkpoint: %+v %v", items, err)
	}
	next, err := store.ListContinueCandidates(ctx, 7, false, items[0].UpdatedAt(), items[0].VariantID, 12)
	if err != nil || len(next) != 0 {
		t.Fatalf("pagination repeated checkpoint: %+v %v", next, err)
	}
	rows, err := store.db.QueryContext(ctx, "EXPLAIN QUERY PLAN "+continueCandidatesQuery, 7, 7, false, math.MaxInt64, "", 12)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	indexed := false
	for rows.Next() {
		var id, parent, auxiliary int
		var detail string
		if err := rows.Scan(&id, &parent, &auxiliary, &detail); err != nil {
			t.Fatal(err)
		}
		indexed = indexed || strings.Contains(detail, "USING INDEX watch_continue_recent")
		t.Log(detail)
	}
	if err := rows.Err(); err != nil || !indexed {
		t.Fatalf("continue query did not use the scoped recent index: %v", err)
	}
}
