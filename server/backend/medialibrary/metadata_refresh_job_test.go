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
	if err := store.db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil || version != SchemaVersion {
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

func TestMetadataRefreshIncludesSeriesWithoutEpisodeMetadata(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "分集回填")
	connection := fixtureProvider(t, store, 7, false, "user", "episode-refresh-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "episode-refresh-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataOnlineFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	series, err := store.CreateCatalogItem(ctx, 7, library.ID, NewCatalogItem{Kind: "series", Title: "本地剧"})
	if err != nil {
		t.Fatal(err)
	}
	var structureRevision int64
	if err := store.db.QueryRow(`SELECT structure_revision FROM item_structure WHERE library_id=? AND item_id=?`, library.ID, series.ID).Scan(&structureRevision); err != nil {
		t.Fatal(err)
	}
	document, err := json.Marshal(ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.TVShow, Fields: map[string][]string{"title": {"本地剧"}}}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO item_provider_match(library_id,item_id,connection_id,provider,entity_kind,external_id,language,document_json,fetched_at,revision)
VALUES(?,?,?,'tmdb','series',55,'zh-CN',?,?,?)`, library.ID, series.ID, connection.ID, string(document), time.Now().UnixMilli(), series.Revision); err != nil {
		t.Fatal(err)
	}
	preview, err := store.MetadataRefreshLibraryPreview(ctx, 7, library.ID, "zh-CN", MetadataOnlineFirst)
	if err != nil {
		t.Fatal(err)
	}
	if preview.EpisodeMetadataItems != 1 || preview.AlreadyCurrentItems != 0 || !preview.CanRefresh {
		t.Fatalf("episode refresh preview: %+v", preview)
	}
	job, err := store.EnqueueMetadataRefreshJob(ctx, 7, "episode-refresh-enqueue", library.ID, policy.Revision)
	if err != nil {
		t.Fatal(err)
	}
	if job.Total != 1 || job.Pending != 1 {
		t.Fatalf("episode refresh job: %+v", job)
	}
}

func TestMetadataRefreshIncludesSeriesWithPartialEpisodeMetadata(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "部分分集回填")
	connection := fixtureProvider(t, store, 7, false, "user", "partial-episode-refresh-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "partial-episode-refresh-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataOnlineFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	partial := fixtureRefreshSeries(t, store, library, connection, "部分分集剧", 55,
		[]ProviderEpisodeDocument{refreshEpisodeDocument(1)})
	complete := fixtureRefreshSeries(t, store, library, connection, "完整分集剧", 56,
		[]ProviderEpisodeDocument{refreshEpisodeDocument(1), refreshEpisodeDocument(2)})

	preview, err := store.MetadataRefreshLibraryPreview(ctx, 7, library.ID, "zh-CN", MetadataOnlineFirst)
	if err != nil {
		t.Fatal(err)
	}
	if preview.MatchedItems != 2 || preview.EpisodeMetadataItems != 1 || preview.AlreadyCurrentItems != 1 ||
		preview.LanguageMismatchItems != 1 || !preview.CanRefresh {
		t.Fatalf("partial episode refresh preview: %+v", preview)
	}
	job, err := store.EnqueueMetadataRefreshJob(ctx, 7, "partial-episode-refresh-enqueue", library.ID, policy.Revision)
	if err != nil {
		t.Fatal(err)
	}
	if job.Total != 1 || job.Pending != 1 {
		t.Fatalf("partial episode refresh job: %+v", job)
	}
	var target string
	if err := store.db.QueryRow(`SELECT item_id FROM metadata_refresh_target WHERE job_id=?`, job.ID).Scan(&target); err != nil || target != partial.ID {
		t.Fatalf("partial episode target=%s want=%s err=%v", target, partial.ID, err)
	}
	var completeTargeted bool
	if err := store.db.QueryRow(`SELECT EXISTS(SELECT 1 FROM metadata_refresh_target WHERE job_id=? AND item_id=?)`, job.ID, complete.ID).Scan(&completeTargeted); err != nil || completeTargeted {
		t.Fatalf("complete episode document was targeted: %v", err)
	}
}

func fixtureRefreshSeries(t *testing.T, store *Store, library *Library, connection *ProviderConnection,
	title string, externalID int64, episodes []ProviderEpisodeDocument) *CatalogItem {
	t.Helper()
	series, err := store.CreateCatalogItem(t.Context(), 7, library.ID, NewCatalogItem{Kind: "series", Title: title})
	if err != nil {
		t.Fatal(err)
	}
	season, err := store.CreateCatalogItem(t.Context(), 7, library.ID, NewCatalogItem{Kind: "season", ParentID: series.ID, Title: "第一季"})
	if err != nil {
		t.Fatal(err)
	}
	first, err := store.CreateCatalogItem(t.Context(), 7, library.ID, NewCatalogItem{Kind: "episode", ParentID: season.ID, Title: "第一集"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.CreateCatalogItem(t.Context(), 7, library.ID, NewCatalogItem{Kind: "episode", ParentID: season.ID, Title: "第二集"})
	if err != nil {
		t.Fatal(err)
	}
	orderID := "local_" + series.ID
	if _, err := store.db.Exec(`INSERT INTO episode_order(id,library_id,series_id,scheme) VALUES(?,?,?,'local')`, orderID, library.ID, series.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO season_position(library_id,order_id,season_id,season_number) VALUES(?,?,?,1)`, library.ID, orderID, season.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO episode_position(library_id,order_id,season_number,episode_number,episode_id) VALUES(?,?,1,1,?)`, library.ID, orderID, first.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO episode_position(library_id,order_id,season_number,episode_number,episode_id) VALUES(?,?,1,2,?)`, library.ID, orderID, second.ID); err != nil {
		t.Fatal(err)
	}
	document, err := json.Marshal(ProviderMatchDocument{
		Item: mediametadata.Item{Kind: mediametadata.TVShow, Fields: map[string][]string{"title": {title}}},
		Seasons: []ProviderSeasonDocument{{SeasonNumber: 1,
			Item: mediametadata.Item{Kind: mediametadata.Season, Fields: map[string][]string{"title": {"第一季"}}}}},
		Episodes: episodes,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO item_provider_match(library_id,item_id,connection_id,provider,entity_kind,external_id,language,document_json,fetched_at,revision)
VALUES(?,?,?,'tmdb','series',?,'zh-CN',?,?,?)`, library.ID, series.ID, connection.ID, externalID, string(document), time.Now().UnixMilli(), series.Revision); err != nil {
		t.Fatal(err)
	}
	return series
}

func refreshEpisodeDocument(number int) ProviderEpisodeDocument {
	return ProviderEpisodeDocument{SeasonNumber: 1, EpisodeNumber: number,
		Item: mediametadata.Item{Kind: mediametadata.Episode, Fields: map[string][]string{"title": {"分集标题"}}}}
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

func TestMetadataRefreshCommitIsAtomicAndReplayable(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "刷新提交")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-commit-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-commit-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	item, _ := fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-commit-enqueue", library.ID, policy.Revision); err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	job, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	target, err := coordinator.NextMetadataRefreshJobTarget(ctx, job)
	if err != nil {
		t.Fatal(err)
	}
	request := ProviderMatchRequest{LibraryID: library.ID, ItemID: item.ID, Kind: "movie", ExternalID: 42,
		ItemRevision: target.ItemRevision, StructureRevision: target.StructureRevision}
	document := ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {"中文标题"}}}}
	commit := func() (*ProviderMatchResult, error) {
		return coordinator.CommitMetadataRefreshTarget(ctx, job, target, request, "zh-CN", time.Now().UnixMilli(), document,
			ProviderMatchAudit{Source: "refresh", Region: "CN", AdapterVersion: "tmdb-v1", Evidence: []string{"language_refresh"}})
	}
	receipt, err := commit()
	if err != nil {
		t.Fatal(err)
	}
	var language, outcome string
	var applied int64
	if err := store.db.QueryRow(`SELECT language FROM item_provider_match WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&language); err != nil || language != "zh-CN" {
		t.Fatalf("provider language=%s err=%v", language, err)
	}
	if err := store.db.QueryRow(`SELECT outcome,applied_match_revision FROM metadata_refresh_target WHERE job_id=? AND item_id=?`, job.ID, item.ID).Scan(&outcome, &applied); err != nil ||
		outcome != "refreshed" || applied != receipt.ItemRevision {
		t.Fatalf("target receipt outcome=%s applied=%d err=%v", outcome, applied, err)
	}
	replayed, err := commit()
	if err != nil || replayed.ItemRevision != receipt.ItemRevision {
		t.Fatalf("commit replay: %+v %v", replayed, err)
	}
	var revision int64
	if err := store.db.QueryRow(`SELECT revision FROM library_item WHERE library_id=? AND id=?`, library.ID, item.ID).Scan(&revision); err != nil || revision != receipt.ItemRevision {
		t.Fatalf("replay changed item revision: %d %v", revision, err)
	}
}

func TestMetadataRefreshCommitRejectsCanceledOrChangedTarget(t *testing.T) {
	for _, cancel := range []bool{false, true} {
		t.Run(map[bool]string{false: "changed", true: "canceled"}[cancel], func(t *testing.T) {
			store := openTestStore(t)
			ctx := t.Context()
			library := createLibrary(t, store, 7, "刷新栅栏")
			connection := fixtureProvider(t, store, 7, false, "user", "refresh-fence-connection")
			policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-fence-policy", 0,
				LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
			if err != nil {
				t.Fatal(err)
			}
			item, _ := fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
			if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-fence-enqueue", library.ID, policy.Revision); err != nil {
				t.Fatal(err)
			}
			coordinator := testCoordinator(t, store)
			job, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
			if err != nil {
				t.Fatal(err)
			}
			target, err := coordinator.NextMetadataRefreshJobTarget(ctx, job)
			if err != nil {
				t.Fatal(err)
			}
			if cancel {
				if err := coordinator.CancelMetadataRefreshJob(ctx, 7, library.ID, job.ID, job.Generation); err != nil {
					t.Fatal(err)
				}
			} else if _, err := store.db.Exec(`UPDATE library_item SET revision=revision+1 WHERE library_id=? AND id=?`, library.ID, item.ID); err != nil {
				t.Fatal(err)
			}
			request := ProviderMatchRequest{LibraryID: library.ID, ItemID: item.ID, Kind: "movie", ExternalID: 42,
				ItemRevision: target.ItemRevision, StructureRevision: target.StructureRevision}
			document := ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {"中文标题"}}}}
			if _, err := coordinator.CommitMetadataRefreshTarget(ctx, job, target, request, "zh-CN", time.Now().UnixMilli(), document,
				ProviderMatchAudit{Source: "refresh"}); !errors.Is(err, ErrRevisionConflict) {
				t.Fatalf("stale refresh commit was accepted: %v", err)
			}
			var language, outcome string
			if err := store.db.QueryRow(`SELECT language FROM item_provider_match WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&language); err != nil || language != "en-US" {
				t.Fatalf("stale commit changed provider: %s %v", language, err)
			}
			if err := store.db.QueryRow(`SELECT outcome FROM metadata_refresh_target WHERE job_id=? AND item_id=?`, job.ID, item.ID).Scan(&outcome); err != nil || outcome != "pending" {
				t.Fatalf("stale commit changed target: %s %v", outcome, err)
			}
		})
	}
}

func TestMetadataRefreshRetriesCompletedJobWithFailedTargets(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "部分失败重试")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-partial-retry-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-partial-retry-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	item, _ := fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-partial-retry-enqueue", library.ID, policy.Revision); err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	job, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := coordinator.ResolveMetadataRefreshJobTarget(ctx, job, item.ID, "failed", "provider_unavailable"); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.FinishMetadataRefreshJob(ctx, job, "completed", "", nil); err != nil {
		t.Fatal(err)
	}
	progress, err := store.MetadataRefreshJob(ctx, 7, library.ID, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	retried, err := coordinator.RetryMetadataRefreshJobWithOperation(ctx, 7, "refresh-partial-retry", progress, progress.Generation)
	if err != nil || retried.Status != "queued" || retried.Pending != 1 {
		t.Fatalf("partial retry: %+v %v", retried, err)
	}
}

func TestMetadataRefreshRetryOperationIsReplayable(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "刷新重试")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-retry-operation-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-retry-operation-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-retry-operation-enqueue", library.ID, policy.Revision); err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	job, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := coordinator.CancelMetadataRefreshJob(ctx, 7, library.ID, job.ID, job.Generation); err != nil {
		t.Fatal(err)
	}
	progress, err := store.MetadataRefreshJob(ctx, 7, library.ID, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	retried, err := coordinator.RetryMetadataRefreshJobWithOperation(ctx, 7, "refresh-retry-operation", progress, progress.Generation)
	if err != nil || retried.Status != "queued" || retried.Generation != progress.Generation+1 {
		t.Fatalf("retry: %+v %v", retried, err)
	}
	replayed, err := coordinator.RetryMetadataRefreshJobWithOperation(ctx, 7, "refresh-retry-operation", progress, progress.Generation)
	if err != nil || replayed.Generation != retried.Generation {
		t.Fatalf("retry replay: %+v %v", replayed, err)
	}
}

func TestMetadataRefreshJobUndoIsAtomicAndReplayable(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "刷新撤销")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-undo-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-undo-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	first, _ := fixtureRefreshMatch(t, store, library, connection, "First", "en-US")
	second, _ := fixtureRefreshMatch(t, store, library, connection, "Second", "en-US")
	if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-undo-enqueue", library.ID, policy.Revision); err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	job, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		target, err := coordinator.NextMetadataRefreshJobTarget(ctx, job)
		if err != nil {
			t.Fatal(err)
		}
		request := ProviderMatchRequest{LibraryID: library.ID, ItemID: target.ItemID, Kind: "movie", ExternalID: 42,
			ItemRevision: target.ItemRevision, StructureRevision: target.StructureRevision}
		document := ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {"中文"}}}}
		if _, err := coordinator.CommitMetadataRefreshTarget(ctx, job, target, request, "zh-CN", time.Now().UnixMilli(), document,
			ProviderMatchAudit{Source: "refresh"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := coordinator.FinishMetadataRefreshJob(ctx, job, "completed", "", nil); err != nil {
		t.Fatal(err)
	}
	progress, err := store.MetadataRefreshJob(ctx, 7, library.ID, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	undone, err := coordinator.UndoMetadataRefreshJob(ctx, 7, "refresh-undo-operation", progress, progress.Generation)
	if err != nil || undone.Undone != 2 {
		t.Fatalf("undo: %+v %v", undone, err)
	}
	for _, item := range []*CatalogItem{first, second} {
		var language, outcome string
		if err := store.db.QueryRow(`SELECT language FROM item_provider_match WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&language); err != nil || language != "en-US" {
			t.Fatalf("undo language=%s err=%v", language, err)
		}
		if err := store.db.QueryRow(`SELECT outcome FROM metadata_refresh_target WHERE job_id=? AND item_id=?`, job.ID, item.ID).Scan(&outcome); err != nil || outcome != "undone" {
			t.Fatalf("undo outcome=%s err=%v", outcome, err)
		}
	}
	replayed, err := coordinator.UndoMetadataRefreshJob(ctx, 7, "refresh-undo-operation", progress, progress.Generation)
	if err != nil || replayed.Undone != undone.Undone {
		t.Fatalf("undo replay: %+v %v", replayed, err)
	}
}

func TestMetadataRefreshJobUndoRejectsLaterItemChanges(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "刷新撤销冲突")
	connection := fixtureProvider(t, store, 7, false, "user", "refresh-undo-conflict-connection")
	policy, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "refresh-undo-conflict-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	item, _ := fixtureRefreshMatch(t, store, library, connection, "English", "en-US")
	if _, err := store.EnqueueMetadataRefreshJob(ctx, 7, "refresh-undo-conflict-enqueue", library.ID, policy.Revision); err != nil {
		t.Fatal(err)
	}
	coordinator := testCoordinator(t, store)
	job, err := coordinator.ClaimNextMetadataRefreshJob(ctx)
	if err != nil {
		t.Fatal(err)
	}
	target, err := coordinator.NextMetadataRefreshJobTarget(ctx, job)
	if err != nil {
		t.Fatal(err)
	}
	request := ProviderMatchRequest{LibraryID: library.ID, ItemID: item.ID, Kind: "movie", ExternalID: 42,
		ItemRevision: target.ItemRevision, StructureRevision: target.StructureRevision}
	document := ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.Movie, Fields: map[string][]string{"title": {"中文"}}}}
	if _, err := coordinator.CommitMetadataRefreshTarget(ctx, job, target, request, "zh-CN", time.Now().UnixMilli(), document,
		ProviderMatchAudit{Source: "refresh"}); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.FinishMetadataRefreshJob(ctx, job, "completed", "", nil); err != nil {
		t.Fatal(err)
	}
	progress, err := store.MetadataRefreshJob(ctx, 7, library.ID, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	var applied int64
	if err := store.db.QueryRow(`SELECT applied_item_revision FROM metadata_refresh_target WHERE job_id=? AND item_id=?`, job.ID, item.ID).Scan(&applied); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ApplyMetadata(ctx, 7, library.ID, item.ID, applied, "later-edit",
		MetadataPatch{Fields: map[string]MetadataFieldPatch{"plot": {Values: []string{"later"}}}}); err != nil {
		t.Fatal(err)
	}
	if _, err := coordinator.UndoMetadataRefreshJob(ctx, 7, "refresh-undo-conflict-operation", progress, progress.Generation); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("undo accepted a later item change: %v", err)
	}
	var language string
	if err := store.db.QueryRow(`SELECT language FROM item_provider_match WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&language); err != nil || language != "zh-CN" {
		t.Fatalf("conflicting undo changed provider: %s %v", language, err)
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
