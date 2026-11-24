package medialibrary

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

func TestProviderMatchUndoRestoresDecisionAudit(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "撤销审计")
	connection := fixtureProvider(t, store, 7, false, "user", "undo-audit-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "undo-audit-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	authority, err := store.CheckProviderUse(ctx, 7, library.ID, connection.ID)
	if err != nil {
		t.Fatal(err)
	}
	item := catalogItem(t, store, 7, library.ID, "本地标题")
	var structureRevision int64
	if err := store.db.QueryRow(`SELECT structure_revision FROM item_structure WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&structureRevision); err != nil {
		t.Fatal(err)
	}
	document := ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {"回声"}}}}
	request := ProviderMatchRequest{LibraryID: library.ID, ItemID: item.ID, ConnectionID: connection.ID,
		Kind: "movie", ExternalID: 42, ItemRevision: item.Revision, StructureRevision: structureRevision}
	first, err := store.ConfirmProviderMatch(ctx, 7, "first-match", request, *authority, "zh-CN", time.Now().UnixMilli(), document,
		ProviderMatchAudit{Source: "automatic", Region: "CN", AdapterVersion: "tmdb-v1", Evidence: []string{"first_evidence"}})
	if err != nil {
		t.Fatal(err)
	}
	request.ItemRevision = first.ItemRevision
	request.ExternalID = 43
	second, err := store.ConfirmProviderMatch(ctx, 7, "second-match", request, *authority, "zh-CN", time.Now().UnixMilli(), document,
		ProviderMatchAudit{Source: "manual", Region: "CN", AdapterVersion: "tmdb-v1", Evidence: []string{"second_evidence"}})
	if err != nil {
		t.Fatal(err)
	}
	undoRevision, err := store.ProviderUndoRevision(ctx, 7, library.ID, item.ID, structureRevision)
	if err != nil {
		t.Fatal(err)
	}
	undone, err := store.UndoProviderMatch(ctx, 7, "undo-second", ProviderUndoRequest{LibraryID: library.ID, ItemID: item.ID,
		ItemRevision: second.ItemRevision, StructureRevision: structureRevision, MatchRevision: undoRevision})
	if err != nil {
		t.Fatal(err)
	}
	var source, evidence string
	if err := store.db.QueryRow(`SELECT decision_source,evidence_json FROM provider_match_provenance WHERE library_id=? AND item_id=? AND match_revision=?`,
		library.ID, item.ID, undone.ItemRevision).Scan(&source, &evidence); err != nil {
		t.Fatal(err)
	}
	if source != "automatic" || evidence != `["first_evidence"]` {
		t.Fatalf("undo restored the wrong audit: source=%s evidence=%s", source, evidence)
	}
}

func TestSchema19MigratesPopulatedV18Catalog(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "迁移保留")
	item := catalogItem(t, store, 7, library.ID, "保留条目")
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, `DROP TABLE provider_match_provenance; PRAGMA user_version=18`); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	var count int
	if err := store.db.QueryRow(`SELECT count(*) FROM library_item WHERE library_id=? AND id=?`, library.ID, item.ID).Scan(&count); err != nil || count != 1 {
		t.Fatalf("populated v18 item was lost: count=%d err=%v", count, err)
	}
	var version int
	if err := store.db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil || version != 19 {
		t.Fatalf("schema was not upgraded: version=%d err=%v", version, err)
	}
}

func TestProviderMatchPersistsDecisionAudit(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "审计")
	connection := fixtureProvider(t, store, 7, false, "user", "audit-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "audit-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	authority, err := store.CheckProviderUse(ctx, 7, library.ID, connection.ID)
	if err != nil {
		t.Fatal(err)
	}
	item := catalogItem(t, store, 7, library.ID, "本地标题")
	var structureRevision int64
	if err := store.db.QueryRow(`SELECT structure_revision FROM item_structure WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&structureRevision); err != nil {
		t.Fatal(err)
	}
	request := ProviderMatchRequest{LibraryID: library.ID, ItemID: item.ID, ConnectionID: connection.ID,
		Kind: "movie", ExternalID: 42, ItemRevision: item.Revision, StructureRevision: structureRevision, Automatic: true}
	document := ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {"回声"}}}}
	if _, err := store.ConfirmProviderMatch(ctx, 7, "audit-match", request, *authority, "zh-CN", time.Now().UnixMilli(), document,
		ProviderMatchAudit{Source: "automatic", Region: "CN", AdapterVersion: "tmdb-v1", Evidence: []string{"title_match", "year_match", "unique_candidate"}}); err != nil {
		t.Fatal(err)
	}
	var source, language, region string
	var evidence string
	if err := store.db.QueryRow(`SELECT decision_source,language,region,evidence_json FROM provider_match_provenance WHERE library_id=? AND item_id=? AND match_revision=?`,
		library.ID, item.ID, item.Revision+1).Scan(&source, &language, &region, &evidence); err != nil {
		t.Fatal(err)
	}
	if source != "automatic" || language != "zh-CN" || region != "CN" || evidence != `["title_match","year_match","unique_candidate"]` {
		t.Fatalf("incomplete audit: source=%s language=%s region=%s evidence=%s", source, language, region, evidence)
	}
}
