package medialibrary

import (
	"database/sql"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

func fixtureRefreshMatch(t *testing.T, store *Store, library *Library, connection *ProviderConnection, title, language string) (*CatalogItem, int64) {
	t.Helper()
	item := catalogItem(t, store, library.OwnerUserID, library.ID, title)
	var structureRevision int64
	if err := store.db.QueryRow(`SELECT structure_revision FROM item_structure WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&structureRevision); err != nil {
		t.Fatal(err)
	}
	document, err := json.Marshal(ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {title}}}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO item_provider_match(library_id,item_id,connection_id,provider,entity_kind,external_id,language,document_json,fetched_at,revision)
VALUES(?,?,?,'tmdb','movie',42,?,?,?,?)`, library.ID, item.ID, connection.ID, language, string(document), time.Now().UnixMilli(), item.Revision); err != nil {
		t.Fatal(err)
	}
	return item, structureRevision
}

func TestSchema21MigratesPopulatedV20Catalog(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "迁移保留")
	item := catalogItem(t, store, 7, library.ID, "保留条目")
	connection := fixtureProvider(t, store, 7, false, "user", "schema21-connection")
	fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "schema21-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, `DROP TABLE metadata_refresh_target; DROP TABLE metadata_refresh_job; PRAGMA user_version=20`); err != nil {
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
	if count := tableCount(t, store, "library_item"); count != 2 {
		t.Fatalf("populated v20 items were lost: count=%d", count)
	}
	if count := tableCount(t, store, "item_provider_match"); count != 1 {
		t.Fatalf("populated v20 match was lost: count=%d", count)
	}
	if count := tableCount(t, store, "operation_receipt"); count != 2 {
		t.Fatalf("populated v20 receipts were lost: count=%d", count)
	}
	rows, err := store.db.Query(`PRAGMA foreign_key_check`)
	if err != nil {
		t.Fatal(err)
	}
	if rows.Next() {
		rows.Close()
		t.Fatal("foreign key check failed after migration")
	}
	if err := rows.Close(); err != nil {
		t.Fatal(err)
	}
	var version int
	if err := store.db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil || version != 21 {
		t.Fatalf("schema was not upgraded: version=%d err=%v", version, err)
	}
	if item.ID == "" || library.ID == "" {
		t.Fatal("fixture identity missing")
	}
}

func TestMetadataRefreshEnqueueSnapshotsMismatchedMatchesAndReplays(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "批量刷新")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-job-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-job-english", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "en-US", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	english, englishStructure := fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	fixtureRefreshMatch(t, store, library, connection, "Chinese", "zh-CN")
	legacy, _ := fixtureRefreshMatch(t, store, library, connection, "Legacy", "")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-job-chinese", 1,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	job, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-enqueue", library.ID, policy.Revision)
	if err != nil {
		t.Fatal(err)
	}
	if job.Status != "queued" || job.Language != "zh-CN" || job.Total != 2 || job.Pending != 2 {
		t.Fatalf("unexpected refresh job: %+v", job)
	}
	replayed, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-enqueue", library.ID, policy.Revision)
	if err != nil || replayed.ID != job.ID {
		t.Fatalf("enqueue replay: %+v %v", replayed, err)
	}
	if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-enqueue-other", library.ID, policy.Revision); !errors.Is(err, ErrMetadataRefreshActive) {
		t.Fatalf("concurrent active refresh was accepted: %v", err)
	}
	if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-enqueue-stale", library.ID, policy.Revision-1); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("stale policy revision was accepted: %v", err)
	}
	if _, err := store.db.Exec(`UPDATE library_item SET revision=revision+1 WHERE library_id=? AND id=?`, library.ID, english.ID); err != nil {
		t.Fatal(err)
	}
	var revision, structure int64
	if err := store.db.QueryRow(`SELECT item_revision,structure_revision FROM metadata_refresh_target WHERE job_id=? AND item_id=?`, job.ID, english.ID).Scan(&revision, &structure); err != nil {
		t.Fatal(err)
	}
	if revision != english.Revision || structure != englishStructure {
		t.Fatalf("target snapshot changed: %d/%d want %d/%d", revision, structure, english.Revision, englishStructure)
	}
	var found bool
	if err := store.db.QueryRow(`SELECT EXISTS(SELECT 1 FROM metadata_refresh_target WHERE job_id=? AND item_id=?)`, job.ID, legacy.ID).Scan(&found); err != nil || !found {
		t.Fatalf("legacy language target missing: %v", err)
	}
}

func TestMetadataRefreshJobCancelRetryAndGenerationFence(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "刷新状态")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-state-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-state-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	item, _ := fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	if _, err = store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-state-enqueue", library.ID, policy.Revision); err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	claimed, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	target, err := coordinator.NextMetadataRefreshJobTarget(ctx, claimed)
	if err != nil || target.ItemID != item.ID {
		t.Fatal(target, err)
	}
	if err := coordinator.CancelMetadataRefreshJob(ctx, 7, library.ID, claimed.ID, claimed.Generation); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.ResolveMetadataRefreshJobTarget(ctx, claimed, target.ItemID, "refreshed", ""); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("canceled generation resolved a target: %v", err)
	}
	progress, err := store.MetadataRefreshJob(ctx, 7, library.ID, claimed.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := coordinator.RetryMetadataRefreshJob(ctx, 7, progress, progress.Generation); err != nil {
		t.Fatal(err)
	}
	retried, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	pending, err := coordinator.NextMetadataRefreshJobTarget(ctx, retried)
	if err != nil || pending.ItemID != item.ID {
		t.Fatal(pending, err)
	}
	if err := coordinator.ResolveMetadataRefreshJobTarget(ctx, retried, pending.ItemID, "refreshed", ""); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.FinishMetadataRefreshJob(ctx, retried, "completed", "", nil); err != nil {
		t.Fatal(err)
	}
	progress, err = store.MetadataRefreshJob(ctx, 7, library.ID, retried.ID)
	if err != nil || progress.Status != "completed" || progress.Refreshed != 1 || progress.Pending != 0 {
		t.Fatalf("completed progress: %+v %v", progress, err)
	}
}

func TestMetadataRefreshJobRecoversAfterReopen(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "刷新恢复")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-recover-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-recover-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	item, _ := fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	job, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-recover-enqueue", library.ID, policy.Revision)
	if err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	claimed, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Close(); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	recovered := testCoordinator(t, reopened)
	if err := recovered.RecoverMetadataJobs(ctx); err != nil {
		t.Fatal(err)
	}
	resumed, err := recovered.ClaimNextMetadataRefreshJob(ctx)
	if err != nil || resumed.ID != job.ID || resumed.Generation <= claimed.Generation {
		t.Fatalf("resumed job: %+v %v", resumed, err)
	}
	target, err := recovered.NextMetadataRefreshJobTarget(ctx, resumed)
	if err != nil || target.ItemID != item.ID {
		t.Fatalf("resumed target: %+v %v", target, err)
	}
}
