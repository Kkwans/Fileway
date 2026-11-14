package medialibrary

import "testing"

func TestCatalogSourceFenceRejectsMappingAndContextChanges(t *testing.T) {
	t.Run("file mapping", func(t *testing.T) {
		store, library, binding, _, file, _ := playbackFixture(t)
		page, err := store.ListItemSourceAccess(t.Context(), 7, library.ID, file.ItemID, "", 10)
		if err != nil || len(page.Items) != 1 {
			t.Fatal(page, err)
		}
		entry := page.Items[0]
		valid, err := store.CheckItemSourceRevision(t.Context(), 7, library.ID, file.ItemID, entry)
		if err != nil || !valid {
			t.Fatal(valid, err)
		}
		if _, err = store.db.Exec(`UPDATE file_item_mapping SET active=0 WHERE library_id=? AND item_id=? AND binding_id=? AND binding_epoch=?`, library.ID, file.ItemID, binding.ID, binding.ProofEpoch); err != nil {
			t.Fatal(err)
		}
		valid, err = store.CheckItemSourceRevision(t.Context(), 7, library.ID, file.ItemID, entry)
		if err != nil || valid {
			t.Fatal("stale mapping accepted", valid, err)
		}
	})
	t.Run("directory context", func(t *testing.T) {
		store := openTestStore(t)
		library := createLibrary(t, store, 7, "Fixture")
		binding := createBoundSource(t, store, library)
		_, lease := testScan(t, store, binding, "context-fence")
		nfo := testNFO(t, `<tvshow><title>Series</title></tvshow>`)
		nfo.SourceKey = []byte("/videos/tvshow.nfo")
		input := DirectoryContextObservation{Locator: []byte("/videos/"), TVShow: DirectorySidecar{Status: "parsed", NFO: nfo}, Season: DirectorySidecar{Status: "missing"}}
		if _, err := store.CommitScanContext(t.Context(), 7, *lease, input, ScanCheckpoint{Version: 1, Sequence: 1, Phase: "enumerate"}); err != nil {
			t.Fatal(err)
		}
		items, err := store.ListCatalogItems(t.Context(), 7, library.ID, CatalogListOptions{Kind: "series", Limit: 10})
		if err != nil || len(items.Items) != 1 {
			t.Fatal(items, err)
		}
		sources, err := store.ListDirectoryContextSources(t.Context(), 7, library.ID, items.Items[0].ID, "", 10)
		if err != nil || len(sources.Items) != 1 {
			t.Fatal(sources, err)
		}
		entry := sources.Items[0]
		valid, err := store.CheckDirectoryContextSource(t.Context(), 7, library.ID, entry)
		if err != nil || !valid {
			t.Fatal(valid, err)
		}
		if _, err = store.db.Exec(`UPDATE directory_context SET tv_status='invalid',revision=revision+1 WHERE id=?`, entry.ID); err != nil {
			t.Fatal(err)
		}
		valid, err = store.CheckDirectoryContextSource(t.Context(), 7, library.ID, entry)
		if err != nil || valid {
			t.Fatal("stale context accepted", valid, err)
		}
	})
}
