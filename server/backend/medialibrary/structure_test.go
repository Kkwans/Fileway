package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

func TestStructureMigrationPreservesVariantsAndOriginalAddedTime(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v8.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(context.Background(), func(tx *sql.Tx) error {
		if _, err := tx.Exec(schemaV1 + schemaV2 + schemaV3 + schemaV4 + schemaV5BeforeIDs + schemaV5AfterIDs + schemaV6 + schemaV7 + schemaV8); err != nil {
			return err
		}
		_, err := tx.Exec(`INSERT INTO library(id,owner_user_id,name,created_at,updated_at) VALUES('old',7,'Legacy',10,10);
INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES('source',7,'local','config',10);
INSERT INTO source_file(id,source_id,object_key,locator,source_version,observed_at) VALUES('file','source',X'01',X'2F66696C65','v1',10);
INSERT INTO library_item(id,library_id,kind,title,created_at) VALUES('item','old','video','Old',1234);
INSERT INTO edition(id,library_id,item_id,name,is_default) VALUES('edition1','old','item','a',1),('edition2','old','item','b',0);
INSERT INTO variant(id,library_id,edition_id,source_file_id) VALUES('variant1','old','edition1','file'),('variant2','old','edition2','file');` + fmt.Sprintf("PRAGMA application_id=%d; PRAGMA user_version=8", applicationID))
		return err
	})
	if closeErr := old.Close(); err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	var added int64
	var variants, mappings, violations int
	if err = store.db.QueryRow(`SELECT first_added_at FROM item_structure WHERE library_id='old' AND item_id='item'`).Scan(&added); err != nil {
		t.Fatal(err)
	}
	if err = store.db.QueryRow(`SELECT count(*) FROM variant WHERE id IN ('variant1','variant2')`).Scan(&variants); err != nil {
		t.Fatal(err)
	}
	if err = store.db.QueryRow(`SELECT count(*) FROM file_item_mapping WHERE library_id='old' AND item_id='item' AND source_file_id='file' AND variant_id IN ('variant1','variant2') AND role='single' AND confirmed_by='legacy'`).Scan(&mappings); err != nil {
		t.Fatal(err)
	}
	if err = store.db.QueryRow(`SELECT count(*) FROM pragma_foreign_key_check`).Scan(&violations); err != nil {
		t.Fatal(err)
	}
	if added != 1234 || variants != 2 || mappings != 2 || violations != 0 {
		t.Fatal(added, variants, mappings, violations)
	}
}

func TestStructureMigrationBindsOnlyFirstProvenEpoch(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v8-bindings.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(t.Context(), func(tx *sql.Tx) error {
		if _, err := tx.Exec(schemaV1 + schemaV2 + schemaV3 + schemaV4 + schemaV5BeforeIDs + schemaV5AfterIDs + schemaV6 + schemaV7 + schemaV8); err != nil {
			return err
		}
		_, err := tx.Exec(`INSERT INTO library(id,owner_user_id,name,created_at,updated_at) VALUES('old',7,'Legacy',1,1);
INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES('source',7,'local','config',1);
INSERT INTO library_source(library_id,source_id,root_locator,binding_id) VALUES
('old','source',X'2F6E','b_native'),('old','source',X'2F70','b_path'),('old','source',X'2F7A','b_zero'),('old','source',X'2F72','b_rebound');
UPDATE library_source SET active_proof_version=1,proof_version=1,proof_epoch=1,mapping_digest=zeroblob(32),root_identity_kind='native',root_identity=X'0102',attested_at=1,revision=revision+1;
UPDATE library_source SET proof_epoch=2,revision=revision+1 WHERE binding_id='b_rebound';
INSERT INTO source_file(id,source_id,object_key,locator,source_version,observed_at,identity_kind) VALUES
('native','source',X'01',X'2F6E2F61','v1',1,'native'),('path','source',X'02',X'2F702F61','v1',1,'path_unverified'),
('zero','source',X'03',X'2F7A2F61','v1',1,'native'),('rebound','source',X'04',X'2F722F61','v1',1,'native');
INSERT INTO library_file(library_id,source_file_id,source_id,root_locator,locator,observed_at,binding_epoch) VALUES
('old','native','source',X'2F6E',X'2F6E2F61',1,1),('old','path','source',X'2F70',X'2F702F61',1,1),
('old','zero','source',X'2F7A',X'2F7A2F61',1,0),('old','rebound','source',X'2F72',X'2F722F61',1,2);
INSERT INTO library_item(id,library_id,kind,title,created_at) VALUES
('i_native','old','video','n',10),('i_path','old','video','p',11),('i_zero','old','video','z',12),('i_rebound','old','video','r',13);
INSERT INTO edition(id,library_id,item_id,name) VALUES('e_native','old','i_native',''),('e_path','old','i_path',''),('e_zero','old','i_zero',''),('e_rebound','old','i_rebound','');
INSERT INTO variant(id,library_id,edition_id,source_file_id) VALUES('v_native','old','e_native','native'),('v_path','old','e_path','path'),('v_zero','old','e_zero','zero'),('v_rebound','old','e_rebound','rebound');` + fmt.Sprintf("PRAGMA application_id=%d; PRAGMA user_version=8", applicationID))
		if err != nil {
			return err
		}
		_, err = tx.Exec(`UPDATE source_file SET object_key=? WHERE id='path'`, UnverifiedPathObjectKey("b_path", 1, []byte("/p/a")))
		return err
	})
	if closeErr := old.Close(); err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	for _, tc := range []struct {
		item, binding string
		want          int
	}{{"i_native", "b_native", 1}, {"i_path", "b_path", 1}, {"i_zero", "b_zero", 0}, {"i_rebound", "b_rebound", 0}} {
		var got int
		if err = store.db.QueryRow(`SELECT count(*) FROM file_item_mapping WHERE item_id=? AND binding_id=? AND active=1`, tc.item, tc.binding).Scan(&got); err != nil || got != tc.want {
			t.Fatal(tc, got, err)
		}
	}
	for _, tc := range []struct {
		item, binding, edition, variant string
		want                            int
	}{
		{"i_native", "b_native", "e_native", "v_native", 1},
		{"i_path", "b_path", "e_path", "v_path", 1},
		{"i_zero", "b_zero", "e_zero", "v_zero", 0},
		{"i_rebound", "b_rebound", "e_rebound", "v_rebound", 0},
	} {
		item, err := store.GetCatalogItem(t.Context(), 7, "old", tc.item)
		if err != nil || item.FileCount != tc.want {
			t.Fatalf("catalog visibility %+v: %+v %v", tc, item, err)
		}
		_, err = store.ResolvePlayback(t.Context(), 7, "old", tc.item, tc.edition, tc.variant, tc.binding)
		if tc.want == 0 && !errors.Is(err, ErrNotFound) || tc.want == 1 && err != nil {
			t.Fatalf("playback visibility %+v: %v", tc, err)
		}
	}
	var retained, violations int
	if err = store.db.QueryRow(`SELECT count(*) FROM file_item_mapping WHERE confirmed_by='legacy' AND binding_id IS NULL`).Scan(&retained); err != nil {
		t.Fatal(err)
	}
	if err = store.db.QueryRow(`SELECT count(*) FROM pragma_foreign_key_check`).Scan(&violations); err != nil || retained != 4 || violations != 0 {
		t.Fatal(retained, violations, err)
	}
}

func TestScanContextAtomicReplayAndRevisionFence(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Fixture")
	binding := createBoundSource(t, store, library)
	job, lease := testScan(t, store, binding, "context")
	nfo := testNFO(t, `<tvshow><title>Series</title></tvshow>`)
	nfo.SourceKey = []byte("/videos/tvshow.nfo")
	input := DirectoryContextObservation{Locator: []byte("/videos/"), TVShow: DirectorySidecar{Status: "parsed", NFO: nfo}, Season: DirectorySidecar{Status: "missing"}}
	cp := ScanCheckpoint{Version: 1, Sequence: 1, Phase: "enumerate"}
	if _, err := store.db.Exec(`CREATE TRIGGER fail_context_outbox BEFORE INSERT ON outbox WHEN NEW.kind='media.scan.context' BEGIN SELECT RAISE(ABORT,'injected'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := store.CommitScanContext(t.Context(), 7, *lease, input, cp); err == nil {
		t.Fatal("expected rollback")
	}
	if tableCount(t, store, "directory_context") != 0 || tableCount(t, store, "library_item") != 0 {
		t.Fatal("partial context survived")
	}
	if _, err := store.db.Exec(`DROP TRIGGER fail_context_outbox`); err != nil {
		t.Fatal(err)
	}
	ref, err := store.CommitScanContext(t.Context(), 7, *lease, input, cp)
	if err != nil {
		t.Fatal(err)
	}
	replay, err := store.CommitScanContext(t.Context(), 7, *lease, input, cp)
	if err != nil || *ref != *replay {
		t.Fatal(ref, replay, err)
	}
	changed := testNFO(t, `<tvshow><title>Renamed</title></tvshow>`)
	changed.SourceKey = []byte("/videos/tvshow.nfo")
	input.TVShow.NFO = changed
	cp.Sequence++
	updated, err := store.CommitScanContext(t.Context(), 7, *lease, input, cp)
	if err != nil || updated.ID != ref.ID || updated.Revision <= ref.Revision {
		t.Fatal(updated, ref, err)
	}
	name := mediametadata.ParseName("Series.S01E01.mkv")
	file := testObservation("native", "/videos/Series.S01E01.mkv")
	file.DirectoryContext = *ref
	file.ParsedName = &name
	cp = testCheckpoint(file, 3, 1)
	if _, err = store.CommitScanObservation(t.Context(), 7, *lease, file, cp); !errors.Is(err, ErrRevisionConflict) {
		t.Fatalf("stale context accepted: %v", err)
	}
	if tableCount(t, store, "source_file") != 0 {
		t.Fatal("stale file transaction did not roll back")
	}
	current, err := store.GetScan(t.Context(), 7, job.ID)
	if err != nil || current.Checkpoint.Sequence != 2 || current.Checkpoint.Processed != 0 {
		t.Fatal(current, err)
	}
}

func TestCompleteLocalSeasonDirectoryCreatesSeriesAndMapsEpisode(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Fixture")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "local-season")
	directory := "韩剧【信号】(2016) 1080P韩语中字 李帝勋 ／ 赵震雄 ／ 金惠秀"
	year := 2016
	locator := "/videos/" + directory
	root, err := store.CommitScanContext(t.Context(), 7, *lease, DirectoryContextObservation{
		Locator: []byte("/videos/"), TVShow: DirectorySidecar{Status: "missing"}, Season: DirectorySidecar{Status: "missing"},
	}, ScanCheckpoint{Version: 1, Sequence: 1, Phase: "enumerate"})
	if err != nil {
		t.Fatal(err)
	}
	ref, err := store.CommitScanContext(t.Context(), 7, *lease, DirectoryContextObservation{
		Locator: []byte(locator), Parent: *root, TVShow: DirectorySidecar{Status: "missing"}, Season: DirectorySidecar{Status: "missing"},
		Episodes: &DirectoryEpisodeEvidence{Title: "信号", Year: &year, Numbers: []int{1, 2}},
	}, ScanCheckpoint{Version: 1, Sequence: 2, Phase: "enumerate"})
	if err != nil {
		t.Fatal(err)
	}
	current, err := readDirectoryContext(store.db.QueryRowContext(t.Context(), `SELECT `+directoryContextColumns+` FROM directory_context WHERE id=?`, ref.ID))
	if err != nil || current.SeriesID == "" || current.SeasonID == "" || !current.SeasonNumber.Valid || current.SeasonNumber.Int64 != 1 {
		t.Fatalf("local series context missing: %+v err=%v", current, err)
	}
	filename := "信号.E01【微信公众号：浪漫的西红柿】可以加115交流群.mkv"
	name := mediametadata.ParseNameInDirectory(filename, directory)
	file := testObservation("native", locator+"/"+filename)
	file.NFOStatus = "missing"
	file.InitialTitle = name.Title
	file.DirectoryContext = *ref
	file.ParsedName = &name
	commit, err := store.CommitScanObservation(t.Context(), 7, *lease, file, testCheckpoint(file, 3, 1))
	if err != nil || commit == nil || commit.ItemID == "" {
		t.Fatalf("episode was not mapped: commit=%+v err=%v", commit, err)
	}
	var kind, parent string
	if err := store.db.QueryRow(`SELECT kind,COALESCE(parent_id,'') FROM library_item WHERE library_id=? AND id=?`, library.ID, commit.ItemID).Scan(&kind, &parent); err != nil || kind != "episode" || parent != current.SeasonID {
		t.Fatalf("mapped item shape: kind=%s parent=%s err=%v", kind, parent, err)
	}
	if tableCount(t, store, "library_item") != 3 {
		t.Fatalf("expected series, season and episode only: %d", tableCount(t, store, "library_item"))
	}
}

func TestLocalDirectorySeasonsShareOneSeries(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Fixture")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "local-multi-season")
	root, err := store.CommitScanContext(t.Context(), 7, *lease, DirectoryContextObservation{
		Locator: []byte("/videos/"), TVShow: DirectorySidecar{Status: "missing"}, Season: DirectorySidecar{Status: "missing"},
	}, ScanCheckpoint{Version: 1, Sequence: 1, Phase: "enumerate"})
	if err != nil {
		t.Fatal(err)
	}
	year := 2016
	for sequence, season := range []int{1, 2} {
		_, err := store.CommitScanContext(t.Context(), 7, *lease, DirectoryContextObservation{
			Locator: []byte(fmt.Sprintf("/videos/信号 S%02d", season)), Parent: *root,
			TVShow: DirectorySidecar{Status: "missing"}, Season: DirectorySidecar{Status: "missing"},
			Episodes: &DirectoryEpisodeEvidence{Title: "信号", Year: &year, Season: season, Numbers: []int{1, 2}},
		}, ScanCheckpoint{Version: 1, Sequence: int64(sequence + 2), Phase: "enumerate"})
		if err != nil {
			t.Fatal(err)
		}
	}
	var series, seasons int
	if err := store.db.QueryRow(`SELECT count(*) FROM library_item WHERE library_id=? AND kind='series'`, library.ID).Scan(&series); err != nil {
		t.Fatal(err)
	}
	if err := store.db.QueryRow(`SELECT count(*) FROM season_position sp JOIN episode_order eo ON eo.id=sp.order_id JOIN library_item s ON s.library_id=eo.library_id AND s.id=eo.series_id WHERE s.library_id=? AND s.kind='series'`, library.ID).Scan(&seasons); err != nil {
		t.Fatal(err)
	}
	if series != 1 || seasons != 2 {
		t.Fatalf("local seasons did not share a series: series=%d seasons=%d", series, seasons)
	}
}

func TestSeriesFilenameAliasesComeFromMappedEpisodes(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Fixture")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "series-aliases")
	root, err := store.CommitScanContext(t.Context(), 7, *lease, DirectoryContextObservation{
		Locator: []byte("/videos/"), TVShow: DirectorySidecar{Status: "missing"}, Season: DirectorySidecar{Status: "missing"},
	}, ScanCheckpoint{Version: 1, Sequence: 1, Phase: "enumerate"})
	if err != nil {
		t.Fatal(err)
	}
	year := 2019
	ref, err := store.CommitScanContext(t.Context(), 7, *lease, DirectoryContextObservation{
		Locator: []byte("/videos/迷醉.第一季.Euphoria.S01"), Parent: *root,
		TVShow: DirectorySidecar{Status: "missing"}, Season: DirectorySidecar{Status: "missing"},
		Episodes: &DirectoryEpisodeEvidence{Title: "迷醉", Year: &year, Season: 1, Numbers: []int{1, 2}},
	}, ScanCheckpoint{Version: 1, Sequence: 2, Phase: "enumerate"})
	if err != nil {
		t.Fatal(err)
	}
	name := mediametadata.ParseNameInDirectory("Euphoria.S01E01.mkv", "迷醉.第一季.Euphoria.S01")
	file := testObservation("native", "/videos/迷醉.第一季.Euphoria.S01/Euphoria.S01E01.mkv")
	file.NFOStatus = "missing"
	file.InitialTitle = name.Title
	file.DirectoryContext = *ref
	file.ParsedName = &name
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, file, testCheckpoint(file, 3, 1)); err != nil {
		t.Fatal(err)
	}
	current, err := readDirectoryContext(store.db.QueryRowContext(t.Context(), `SELECT `+directoryContextColumns+` FROM directory_context WHERE id=?`, ref.ID))
	if err != nil {
		t.Fatal(err)
	}
	aliases, err := store.GetSeriesFilenameAliases(t.Context(), 7, library.ID, current.SeriesID)
	if err != nil || len(aliases) != 1 || aliases[0] != "Euphoria" {
		t.Fatalf("series filename aliases: %v err=%v", aliases, err)
	}
}

func TestSeriesFilenameAliasNormalizationRemovesReleaseTags(t *testing.T) {
	for alias, want := range map[string]string{
		"Euphoria US BDE4":             "Euphoria",
		"人生切割术 Severance ATVP -MiniTV": "人生切割术 Severance",
	} {
		if got := normalizeSeriesFilenameAlias(alias); got != want {
			t.Fatalf("alias %q: got %q want %q", alias, got, want)
		}
	}
}

func TestBindingEpochFenceDoesNotReviveOldMapping(t *testing.T) {
	store, library, first, lease, file, variant := playbackFixture(t)
	ctx := t.Context()
	currentLibrary, err := store.GetLibrary(ctx, 7, library.ID)
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.BindLocalSource(ctx, 7, library.ID, currentLibrary.Revision, first.Source.ID, []byte("/other"))
	if err != nil {
		t.Fatal(err)
	}
	second = testAttestBinding(t, store, second)
	beforeFile, err := store.GetSourceFile(ctx, 7, first.Source.ID, []byte("verified-native"))
	if err != nil {
		t.Fatal(err)
	}
	zero := int64(0)
	if _, err = store.ObserveSourceFile(ctx, 7, second.Ref(), FileObservation{ObjectKey: beforeFile.ObjectKey, Locator: []byte("/other/one.mkv"), SourceVersion: beforeFile.SourceVersion, SizeBytes: beforeFile.SizeBytes, ExpectedRevision: beforeFile.Revision, ExpectedLocatorRevision: &zero}); err != nil {
		t.Fatal(err)
	}
	item, err := store.GetCatalogItem(ctx, 7, library.ID, file.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.AttachCatalogFile(ctx, 7, library.ID, item.ID, item.Revision, file.SourceFileID); err != nil {
		t.Fatal(err)
	}
	if _, err = store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, second.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = store.AttestSourceBindingWithOperation(ctx, 7, first.ID, first.Revision, "new-binding-epoch", proofForTest(9)); err != nil {
		t.Fatal(err)
	}
	newBinding, err := store.GetLibrarySourceByID(ctx, 7, first.ID)
	if err != nil {
		t.Fatal(err)
	}
	current, err := store.GetSourceFile(ctx, 7, first.Source.ID, []byte("verified-native"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.ObserveSourceFile(ctx, 7, newBinding.Ref(), FileObservation{ObjectKey: current.ObjectKey, Locator: []byte("/videos/one.mkv"), SourceVersion: current.SourceVersion, SizeBytes: current.SizeBytes, ExpectedRevision: current.Revision, ExpectedLocatorRevision: &zero}); err != nil {
		t.Fatal(err)
	}
	if _, err = store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, first.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("old item revived under new epoch: %v", err)
	}
	if _, err = store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, second.ID); err != nil {
		t.Fatalf("unchanged binding became unavailable: %v", err)
	}
	sources, err := store.ListItemSourceAccess(ctx, 7, library.ID, file.ItemID, "", 100)
	if err != nil || len(sources.Items) != 1 || sources.Items[0].BindingID != second.ID {
		t.Fatal(sources, err)
	}
	metadata, err := store.ListItemMetadataSourceAccess(ctx, 7, library.ID, file.ItemID, "", 100)
	if err != nil || len(metadata.Items) != 0 {
		t.Fatal(metadata, err)
	}
	if _, err = store.FinishScan(ctx, 7, *lease, ScanFinish{Status: "failed", ExpectedSequence: 1}); err != nil {
		t.Fatal(err)
	}
	_, newLease := testScan(t, store, newBinding, "post-rebind")
	observation := testObservation("verified-native", "/videos/one.mkv")
	rev, err := store.GetObservationRevision(ctx, 7, newBinding.Ref(), observation.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	observation.ExpectedRevision, observation.ExpectedLocatorRevision = rev.FileRevision, rev.LocatorRevision
	newFile, err := store.CommitScanObservation(ctx, 7, *newLease, observation, testCheckpoint(observation, 1, 1))
	if err != nil || newFile.ItemID == file.ItemID {
		t.Fatalf("ambiguous old association reused: %+v %v", newFile, err)
	}
	if _, err = store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, first.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("scan revived stale old mapping: %v", err)
	}
	if _, err = store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, second.ID); err != nil {
		t.Fatal(err)
	}
}
