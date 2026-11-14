package mediascan

import (
	"database/sql"
	"os"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
)

func TestPersistentScanBuildsOfflineSeriesAndPreservesIdentity(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "Show/tvshow.nfo", `<tvshow><title>Local show</title><plot>Series plot</plot><uniqueid type="tvdb">100</uniqueid></tvshow>`)
	f.write(t, "Show/Season 1/season.nfo", `<season><seasonnumber>1</seasonnumber><title>First season</title></season>`)
	f.write(t, "Show/Season 1/Show.S01E01.1080p.mkv", "one")
	f.write(t, "Show/Season 1/Show.S01E01.1080p.nfo", `<episodedetails><title>First episode</title><season>1</season><episode>1</episode><uniqueid type="tvdb">101</uniqueid></episodedetails>`)
	f.write(t, "Show/Season 1/Show.S01E01.720p.mkv", "another copy")
	f.write(t, "Show/Season 1/Show.S01E01.WEB-DL.mkv", "third copy")
	f.write(t, "Show/Season 1/Show.S01E02E03.mkv", "combined")
	f.write(t, "Show/Season 1/Show.S02E04.mkv", "wrong season")
	f.write(t, "Show/Show.S00E01.mkv", "special")
	f.write(t, "Orphan/season.nfo", `<season><seasonnumber>2</seasonnumber></season>`)
	f.write(t, "Orphan/Show.S02E01.mkv", "orphan")
	run := func() {
		t.Helper()
		if _, err := Run(t.Context(), 7, f.store, f.coord, f.claim(t), f.ownerResolve, Options{ReadBatch: 2}); err != nil {
			t.Fatal(err)
		}
	}
	run()
	type snapshot struct {
		series, episode                                          string
		added, updated, structureRevision                        int64
		seasons, episodes, copies, combined, specials, unmatched int
	}
	inspect := func() snapshot {
		t.Helper()
		var s snapshot
		err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
			if err := tx.QueryRowContext(t.Context(), `SELECT i.id,s.first_added_at,s.series_updated_at,s.structure_revision FROM library_item i JOIN item_structure s ON s.library_id=i.library_id AND s.item_id=i.id WHERE i.library_id=? AND i.kind='series'`, f.binding.LibraryID).Scan(&s.series, &s.added, &s.updated, &s.structureRevision); err != nil {
				return err
			}
			if err := tx.QueryRowContext(t.Context(), `SELECT ep.episode_id FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id WHERE eo.series_id=? AND ep.season_number=1 AND ep.episode_number=1`, s.series).Scan(&s.episode); err != nil {
				return err
			}
			queries := []struct {
				sql  string
				args []any
				out  *int
			}{
				{`SELECT count(*) FROM season_position sp JOIN episode_order eo ON eo.id=sp.order_id WHERE eo.series_id=?`, []any{s.series}, &s.seasons},
				{`SELECT count(*) FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id WHERE eo.series_id=?`, []any{s.series}, &s.episodes},
				{`SELECT count(DISTINCT source_file_id) FROM file_item_mapping WHERE item_id=? AND active=1`, []any{s.episode}, &s.copies},
				{`SELECT count(*) FROM file_item_mapping m JOIN episode_position ep ON ep.episode_id=m.item_id JOIN episode_order eo ON eo.id=ep.order_id WHERE eo.series_id=? AND ep.season_number=1 AND ep.episode_number IN (2,3) AND m.active=1 AND m.role='combined' AND m.start_ms IS NULL AND m.end_ms IS NULL`, []any{s.series}, &s.combined},
				{`SELECT count(*) FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id WHERE eo.series_id=? AND ep.season_number=0`, []any{s.series}, &s.specials},
				{`SELECT count(*) FROM library_item WHERE library_id=? AND kind='video'`, []any{f.binding.LibraryID}, &s.unmatched},
			}
			for _, q := range queries {
				if err := tx.QueryRowContext(t.Context(), q.sql, q.args...).Scan(q.out); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	initial := inspect()
	if initial.seasons != 2 || initial.episodes != 4 || initial.copies != 3 || initial.combined != 2 || initial.specials != 1 || initial.unmatched != 2 {
		t.Fatalf("initial structure: %+v", initial)
	}
	seasons, err := f.store.ListStructureSeasons(t.Context(), 7, f.binding.LibraryID, initial.series, nil, 1)
	if err != nil || len(seasons.Items) != 1 || seasons.Items[0].Number != 0 || seasons.NextNumber == nil || *seasons.NextNumber != 0 {
		t.Fatalf("specials page: %+v %v", seasons, err)
	}
	seasons, err = f.store.ListStructureSeasons(t.Context(), 7, f.binding.LibraryID, initial.series, seasons.NextNumber, 1)
	if err != nil || len(seasons.Items) != 1 || seasons.Items[0].Number != 1 {
		t.Fatalf("season page: %+v %v", seasons, err)
	}
	episodes, err := f.store.ListStructureEpisodes(t.Context(), 7, f.binding.LibraryID, initial.series, seasons.Items[0].SeasonID, nil, 2)
	if err != nil || len(episodes.Items) != 2 || episodes.Items[0].EpisodeNumber != 1 || episodes.Items[1].EpisodeNumber != 2 || episodes.NextNumber == nil {
		t.Fatalf("episode page: %+v %v", episodes, err)
	}
	variants, err := f.store.ListPlaybackVariants(t.Context(), 7, f.binding.LibraryID, episodes.Items[1].EpisodeID, "", 10)
	if err != nil || len(variants.Items) != 1 {
		t.Fatalf("combined variants: %+v %v", variants, err)
	}
	combined, err := f.store.GetStructureFileMapping(t.Context(), 7, f.binding.LibraryID, episodes.Items[1].EpisodeID, variants.Items[0].VariantID, f.binding.ID, f.binding.ProofEpoch)
	if err != nil || combined.Role != "combined" || combined.IndependentStart || len(combined.CoveredEpisodeIDs) != 2 {
		t.Fatalf("combined playback semantics: %+v %v", combined, err)
	}
	detail, err := f.store.GetItemDetail(t.Context(), 7, f.binding.LibraryID, initial.series)
	if err != nil || detail.Item.Title != "Local show" || detail.Fields["plot"].Values[0] != "Series plot" {
		t.Fatalf("series metadata: %+v %v", detail, err)
	}
	if _, err = f.store.ApplyMetadata(t.Context(), 7, f.binding.LibraryID, initial.series, detail.Item.Revision, "structure-lock", medialibrary.MetadataPatch{Fields: map[string]medialibrary.MetadataFieldPatch{"title": {Values: []string{"My locked title"}}}}); err != nil {
		t.Fatal(err)
	}
	f.write(t, "Show/tvshow.nfo", `<tvshow><title>Changed title</title><plot>Changed plot</plot><uniqueid type="tvdb">100</uniqueid></tvshow>`)
	run()
	again := inspect()
	if again.series != initial.series || again.episode != initial.episode || again.added != initial.added || again.updated != initial.updated || again.episodes != initial.episodes || again.copies != initial.copies || again.structureRevision != initial.structureRevision {
		t.Fatalf("rescan changed structure: before=%+v after=%+v", initial, again)
	}
	detail, err = f.store.GetItemDetail(t.Context(), 7, f.binding.LibraryID, initial.series)
	if err != nil || detail.Item.Title != "My locked title" || detail.Fields["plot"].Values[0] != "Changed plot" {
		t.Fatalf("locked title changed: %+v %v", detail, err)
	}

	// A fourth source does not create a fourth episode or advance the series.
	f.write(t, "Show/Season 1/Show.S01E01.HDTV.mkv", "fourth copy")
	run()
	duplicate := inspect()
	if duplicate.episodes != 4 || duplicate.copies != 4 || duplicate.updated != initial.updated || duplicate.structureRevision != initial.structureRevision {
		t.Fatalf("duplicate advanced series: %+v", duplicate)
	}
	// A contradictory external ID remains an independently playable video.
	f.write(t, "Show/Season 1/Show.S01E01.2160p.mkv", "conflicting copy")
	f.write(t, "Show/Season 1/Show.S01E01.2160p.nfo", `<episodedetails><season>1</season><episode>1</episode><uniqueid type="tvdb">999</uniqueid></episodedetails>`)
	run()
	conflicted := inspect()
	if conflicted.episodes != 4 || conflicted.copies != 4 || conflicted.unmatched != 3 {
		t.Fatalf("conflicting copy was merged: %+v", conflicted)
	}
	f.write(t, "Show/Season 1/Show.S01E04.mkv", "new episode")
	run()
	added := inspect()
	if added.episodes != 5 || added.structureRevision <= initial.structureRevision || added.added != initial.added {
		t.Fatalf("new episode did not advance series: %+v", added)
	}
}

func TestHardlinkConflictRemainsReviewableAndPlayable(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "Show/tvshow.nfo", `<tvshow><title>Show</title></tvshow>`)
	f.write(t, "Show/Show.S01E01.a.mkv", "same object")
	base := filepath.Join(f.root, "media", "Show")
	first := filepath.Join(base, "Show.S01E01.a.mkv")
	for _, name := range []string{"Show.S01E02.b.mkv", "Show.S01E01.c.mkv"} {
		if err := os.Link(first, filepath.Join(base, name)); err != nil {
			t.Skipf("hard links unavailable: %v", err)
		}
	}
	handle, err := os.Open(first)
	if err != nil {
		t.Fatal(err)
	}
	_, native := nativeObjectKey(handle)
	if closeErr := handle.Close(); closeErr != nil {
		t.Fatal(closeErr)
	}
	if !native {
		t.Skip("native object identity unavailable")
	}
	run := func() {
		t.Helper()
		if _, err := Run(t.Context(), 7, f.store, f.coord, f.claim(t), f.ownerResolve, Options{ReadBatch: 1}); err != nil {
			t.Fatal(err)
		}
	}
	run()
	var episode, video, fileID string
	inspect := func() {
		t.Helper()
		err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
			var files, episodes, videos int
			if err := tx.QueryRow(`SELECT count(*),min(id) FROM source_file`).Scan(&files, &fileID); err != nil {
				return err
			}
			if err := tx.QueryRow(`SELECT count(*),min(id) FROM library_item WHERE kind='episode'`).Scan(&episodes, &episode); err != nil {
				return err
			}
			if err := tx.QueryRow(`SELECT count(*),min(id) FROM library_item WHERE kind='video'`).Scan(&videos, &video); err != nil {
				return err
			}
			if files != 1 || episodes != 1 || videos != 1 {
				t.Fatalf("hard links duplicated objects/items: files=%d episodes=%d videos=%d", files, episodes, videos)
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
		identification, err := f.store.GetFileIdentification(t.Context(), 7, f.binding.LibraryID, fileID, f.binding.ID, f.binding.ProofEpoch)
		if err != nil || identification.MatchState != "needs_review" {
			t.Fatalf("conflict disappeared: %+v %v", identification, err)
		}
		episodeItem, err := f.store.GetCatalogItem(t.Context(), 7, f.binding.LibraryID, episode)
		if err != nil || episodeItem.FileCount != 0 {
			t.Fatalf("conflicting episode counted: %+v %v", episodeItem, err)
		}
		videoItem, err := f.store.GetCatalogItem(t.Context(), 7, f.binding.LibraryID, video)
		if err != nil || videoItem.FileCount != 1 {
			t.Fatalf("unmatched file unavailable: %+v %v", videoItem, err)
		}
		variants, err := f.store.ListPlaybackVariants(t.Context(), 7, f.binding.LibraryID, video, "", 10)
		if err != nil || len(variants.Items) != 1 {
			t.Fatalf("unmatched file not playable: %+v %v", variants, err)
		}
	}
	inspect()
	oldEpisode, oldVideo := episode, video
	run()
	inspect()
	if episode != oldEpisode || video != oldVideo {
		t.Fatalf("repeat changed IDs: %s/%s -> %s/%s", oldEpisode, oldVideo, episode, video)
	}
	// A subsequent explicit manual association survives filename ambiguity.
	item, err := f.store.GetCatalogItem(t.Context(), 7, f.binding.LibraryID, episode)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.AttachCatalogFile(t.Context(), 7, f.binding.LibraryID, episode, item.Revision, fileID); err != nil {
		t.Fatal(err)
	}
	run()
	sources, err := f.store.ListItemSourceAccess(t.Context(), 7, f.binding.LibraryID, episode, "", 10)
	if err != nil || len(sources.Items) != 1 {
		t.Fatalf("manual association was overridden: %+v %v", sources, err)
	}
}

func TestNestedSeriesAndBrokenContextDoNotReparentFiles(t *testing.T) {
	f := newPersistentFixture(t)
	f.write(t, "Outer/tvshow.nfo", `<tvshow><title>Outer</title></tvshow>`)
	f.write(t, "Outer/Outer.S01E01.mkv", "outer")
	f.write(t, "Outer/Nested/tvshow.nfo", `<tvshow><title>Inner</title></tvshow>`)
	f.write(t, "Outer/Nested/Inner.S01E01.mkv", "inner")
	run := func() {
		t.Helper()
		if _, err := Run(t.Context(), 7, f.store, f.coord, f.claim(t), f.ownerResolve, Options{}); err != nil {
			t.Fatal(err)
		}
	}
	run()
	var outerID, innerID string
	err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		if err := tx.QueryRow(`SELECT id FROM library_item WHERE kind='series' AND title='Outer'`).Scan(&outerID); err != nil {
			return err
		}
		return tx.QueryRow(`SELECT id FROM library_item WHERE kind='series' AND title='Inner'`).Scan(&innerID)
	})
	if err != nil || outerID == innerID {
		t.Fatal(outerID, innerID, err)
	}
	var innerEpisode string
	if err = f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		return tx.QueryRow(`SELECT ep.episode_id FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id WHERE eo.series_id=? AND ep.season_number=1 AND ep.episode_number=1`, innerID).Scan(&innerEpisode)
	}); err != nil {
		t.Fatal(err)
	}
	f.write(t, "Outer/Nested/tvshow.nfo", `<tvshow><title>broken`)
	f.write(t, "Outer/Nested/Inner.S01E02.mkv", "unclassified")
	run()
	err = f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		var retained, outerCount, innerCount, unmatched int
		if err := tx.QueryRow(`SELECT count(*) FROM item_structure WHERE item_id=? AND series_id=?`, innerEpisode, innerID).Scan(&retained); err != nil {
			return err
		}
		if err := tx.QueryRow(`SELECT count(*) FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id WHERE eo.series_id=?`, outerID).Scan(&outerCount); err != nil {
			return err
		}
		if err := tx.QueryRow(`SELECT count(*) FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id WHERE eo.series_id=?`, innerID).Scan(&innerCount); err != nil {
			return err
		}
		if err := tx.QueryRow(`SELECT count(*) FROM library_item WHERE kind='video'`).Scan(&unmatched); err != nil {
			return err
		}
		if retained != 1 || outerCount != 1 || innerCount != 1 || unmatched != 1 {
			t.Fatalf("broken context changed mapping: %d %d %d %d", retained, outerCount, innerCount, unmatched)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
