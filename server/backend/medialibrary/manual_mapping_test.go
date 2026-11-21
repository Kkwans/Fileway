package medialibrary

import (
	"database/sql"
	"encoding/json"
	"errors"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

func manualMappingFixture(t *testing.T) (*Store, *Library, *LibrarySource, SeriesMappingRequest, *PlaybackAccess) {
	t.Helper()
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Mapping")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "manual-scan")
	observation := testObservation("native-manual", "/videos/manual.mkv")
	first, err := store.CommitScanObservation(t.Context(), 7, *lease, observation, testCheckpoint(observation, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 1)
	item, err := store.GetCatalogItem(t.Context(), 7, library.ID, first.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	structure, err := store.GetItemStructure(t.Context(), 7, library.ID, item.ID)
	if err != nil {
		t.Fatal(err)
	}
	page, err := store.ListPlaybackVariants(t.Context(), 7, library.ID, item.ID, "", 10)
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	variant := page.Items[0]
	access, err := store.ResolvePlayback(t.Context(), 7, library.ID, item.ID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil {
		t.Fatal(err)
	}
	return store, library, binding, SeriesMappingRequest{LibraryID: library.ID, ItemID: item.ID, ItemRevision: item.Revision, StructureRevision: structure.Revision, NewSeriesTitle: "Explicit series", SeasonNumber: 0, EpisodeNumbers: []int{1, 2}}, access
}

func TestManualSeriesMappingPreservesTimelineStateAndConditionalUndo(t *testing.T) {
	store, library, binding, request, access := manualMappingFixture(t)
	truth := true
	if _, err := store.SetUserState(t.Context(), 7, library.ID, request.ItemID, 0, "original-state", UserStatePatch{Favorite: &truth, ManualWatched: &truth}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.BeginWatchProgress(t.Context(), access, "native-stamp", "session-before-map", false, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := store.RecordWatchProgress(t.Context(), access, "native-stamp", "session-before-map", WatchEvent{Sequence: 1, PositionMs: 12345}); err != nil {
		t.Fatal(err)
	}
	preview, err := store.PreviewSeriesMapping(t.Context(), 7, "preview", request, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if preview.PlayMode != "combined_file" || preview.IndependentStart {
		t.Fatal(preview)
	}
	result, err := store.ConfirmSeriesMapping(t.Context(), 7, "confirm", library.ID, request.ItemID, preview.PreviewID)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.EpisodeIDs) != 2 || result.EpisodeIDs[0] != request.ItemID {
		t.Fatal(result)
	}
	if replay, err := store.ConfirmSeriesMapping(t.Context(), 7, "confirm", library.ID, request.ItemID, preview.PreviewID); err != nil || replay.MappingRevision != result.MappingRevision {
		t.Fatal(replay, err)
	}
	after, err := store.ResolvePlayback(t.Context(), 7, library.ID, request.ItemID, access.EditionID, access.VariantID, binding.ID)
	if err != nil || !SamePlaybackAccess(access, after) {
		t.Fatal(after, err)
	}
	progress, err := store.BeginWatchProgress(t.Context(), after, "native-stamp", "session-after-map", false, "")
	if err != nil || progress.PositionMs != 12345 {
		t.Fatal(progress, err)
	}
	state, err := store.GetUserState(t.Context(), 7, library.ID, result.EpisodeIDs[1])
	if err != nil || state.Favorite || state.ManualWatched != nil {
		t.Fatal("copied original state", state, err)
	}
	coverage, err := store.GetStructureFileMapping(t.Context(), 7, library.ID, request.ItemID, access.VariantID, binding.ID, binding.ProofEpoch)
	if err != nil || coverage.IndependentStart || len(coverage.CoveredEpisodeIDs) != 2 {
		t.Fatal(coverage, err)
	}
	undone, err := store.UndoSeriesMapping(t.Context(), 7, "undo", library.ID, request.ItemID, result.MappingRevision)
	if err != nil || undone.ItemRevision <= result.ItemRevision {
		t.Fatal(undone, err)
	}
	item, err := store.GetCatalogItem(t.Context(), 7, library.ID, request.ItemID)
	if err != nil || item.Kind != "video" {
		t.Fatal(item, err)
	}
	state, err = store.GetUserState(t.Context(), 7, library.ID, request.ItemID)
	if err != nil || !state.Favorite || state.ManualWatched == nil || !*state.ManualWatched {
		t.Fatal(state, err)
	}
	if count := tableCount(t, store, "user_watch_progress"); count != 1 {
		t.Fatal("changed timeline rows", count)
	}
	var violations int
	if err = store.db.QueryRow(`SELECT count(*) FROM pragma_foreign_key_check`).Scan(&violations); err != nil || violations != 0 {
		t.Fatal(violations, err)
	}
}

func TestManualSeriesMappingAtomicRollbackAndFences(t *testing.T) {
	for _, scenario := range []string{"rollback", "changed_file", "changed_mapping", "changed_field", "expired", "changed_child", "changed_parent", "occupied", "viewer"} {
		t.Run(scenario, func(t *testing.T) {
			store, library, _, request, _ := manualMappingFixture(t)
			preview, err := store.PreviewSeriesMapping(t.Context(), 7, "preview", request, nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			before := tableCount(t, store, "library_item")
			switch scenario {
			case "rollback":
				_, err = store.db.Exec(`CREATE TRIGGER reject_mapping_outbox BEFORE INSERT ON outbox WHEN NEW.kind='catalog.mapping.confirmed' BEGIN SELECT RAISE(ABORT,'injected mapping failure'); END`)
			case "changed_file":
				_, err = store.db.Exec(`UPDATE source_file SET revision=revision+1`)
			case "changed_mapping":
				_, err = store.db.Exec(`UPDATE file_item_mapping SET mapping_revision=mapping_revision+1`)
			case "changed_field":
				_, err = store.db.Exec(`UPDATE item_field SET revision=revision+1 WHERE item_id=?`, request.ItemID)
			case "expired":
				_, err = store.db.Exec(`UPDATE manual_mapping_preview SET expires_at=0`)
			case "viewer":
				current, loadErr := store.GetLibrary(t.Context(), 7, library.ID)
				if loadErr != nil {
					t.Fatal(loadErr)
				}
				if _, err = store.GrantMember(t.Context(), 7, library.ID, current.Revision, 8, RoleViewer); err != nil {
					t.Fatal(err)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			if scenario == "changed_child" || scenario == "changed_parent" || scenario == "occupied" {
				result, err := store.ConfirmSeriesMapping(t.Context(), 7, "confirm", library.ID, request.ItemID, preview.PreviewID)
				if err != nil {
					t.Fatal(err)
				}
				if scenario == "occupied" {
					_, err = store.PreviewSeriesMapping(t.Context(), 7, "wrong-shape", request, nil, nil)
					if !errors.Is(err, ErrRevisionConflict) {
						t.Fatal(err)
					}
					return
				}
				id := result.SeriesID
				if scenario == "changed_child" {
					id = result.EpisodeIDs[1]
				}
				if _, err = store.db.Exec(`UPDATE library_item SET revision=revision+1 WHERE id=?`, id); err != nil {
					t.Fatal(err)
				}
				if _, err = store.UndoSeriesMapping(t.Context(), 7, "undo", library.ID, request.ItemID, result.MappingRevision); !errors.Is(err, ErrRevisionConflict) {
					t.Fatal("undo erased later edits", err)
				}
				return
			}
			actor := uint(7)
			if scenario == "viewer" {
				actor = 8
			}
			if _, err = store.ConfirmSeriesMapping(t.Context(), actor, "confirm", library.ID, request.ItemID, preview.PreviewID); err == nil {
				t.Fatal("accepted stale/unprivileged mapping")
			}
			if count := tableCount(t, store, "library_item"); count != before {
				t.Fatal("partial mapping escaped", count, before)
			}
			item, err := store.GetCatalogItem(t.Context(), 7, library.ID, request.ItemID)
			if err != nil || item.Kind != "video" {
				t.Fatal(item, err)
			}
		})
	}
}

func TestManualMappingManifestAndValidationBoundaries(t *testing.T) {
	store, library, _, request, _ := manualMappingFixture(t)
	for _, numbers := range [][]int{nil, {1, 1}, {2, 1}, {-1}, {10000}, make([]int, 101)} {
		bad := request
		bad.EpisodeNumbers = numbers
		if _, err := store.PreviewSeriesMapping(t.Context(), 7, "bad", bad, nil, nil); !errors.Is(err, ErrInvalid) {
			t.Fatal(numbers, err)
		}
	}
	preview, err := store.PreviewSeriesMapping(t.Context(), 7, "valid", request, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	result, err := store.ConfirmSeriesMapping(t.Context(), 7, "confirm", library.ID, request.ItemID, preview.PreviewID)
	if err != nil {
		t.Fatal(err)
	}
	var raw string
	if err = store.db.QueryRow(`SELECT payload_json FROM outbox WHERE kind='catalog.mapping.confirmed'`).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var event struct {
		SeriesID       string   `json:"seriesId"`
		EpisodeIDs     []string `json:"episodeIds"`
		EpisodeNumbers []int    `json:"episodeNumbers"`
	}
	if err = json.Unmarshal([]byte(raw), &event); err != nil || event.SeriesID != result.SeriesID || len(event.EpisodeIDs) != 2 || len(event.EpisodeNumbers) != 2 {
		t.Fatal(raw, err)
	}
	var revision int64
	err = store.WithTx(t.Context(), func(tx *sql.Tx) error {
		return tx.QueryRow(`SELECT count(*) FROM manual_mapping_revision WHERE item_id=?`, request.ItemID).Scan(&revision)
	})
	if err != nil || revision != 1 {
		t.Fatal(revision, err)
	}
}

func TestManualMappingExpiredDraftCleanup(t *testing.T) {
	store, _, _, request, _ := manualMappingFixture(t)
	old, err := store.PreviewSeriesMapping(t.Context(), 7, "expired-draft", request, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.db.Exec(`UPDATE manual_mapping_preview SET expires_at=0 WHERE id=?`, old.PreviewID); err != nil {
		t.Fatal(err)
	}
	if _, err = store.PreviewSeriesMapping(t.Context(), 7, "fresh-draft", request, nil, nil); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = store.db.QueryRow(`SELECT count(*) FROM manual_mapping_preview WHERE id=?`, old.PreviewID).Scan(&count); err != nil || count != 0 {
		t.Fatal("expired uncommitted preview retained", count, err)
	}
}

func TestManualSeriesMappingExistingParentAndRescanPriority(t *testing.T) {
	store, library, binding, request, _ := manualMappingFixture(t)
	preview, err := store.PreviewSeriesMapping(t.Context(), 7, "first-preview", request, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	first, err := store.ConfirmSeriesMapping(t.Context(), 7, "first-confirm", library.ID, request.ItemID, preview.PreviewID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.db.Exec(`UPDATE manual_mapping_preview SET expires_at=0 WHERE id=?`, preview.PreviewID); err != nil {
		t.Fatal(err)
	}
	_, lease := testScan(t, store, binding, "add-second-file")
	observation := testObservation("second-native", "/videos/another.mkv")
	second, err := store.CommitScanObservation(t.Context(), 7, *lease, observation, testCheckpoint(observation, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	// A new conflicting filename is evidence, never authority to move the
	// original manually confirmed multi-episode relationship.
	original := testObservation("native-manual", "/videos/manual.mkv")
	version, err := store.GetObservationRevision(t.Context(), 7, binding.Ref(), original.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	original.ExpectedRevision = version.FileRevision
	original.ExpectedLocatorRevision = version.LocatorRevision
	parsed := mediametadata.ParseName("Another.S09E09.mkv")
	original.ParsedName = &parsed
	if _, err = store.CommitScanObservation(t.Context(), 7, *lease, original, testCheckpoint(original, 2, 2)); err != nil {
		t.Fatal(err)
	}
	finishEmptyReconcile(t, store, 7, *lease, 2)
	seasons, err := store.ListStructureSeasons(t.Context(), 7, library.ID, first.SeriesID, nil, 100)
	if err != nil || len(seasons.Items) != 1 || seasons.Items[0].Number != 0 {
		t.Fatal(seasons, err)
	}
	episodes, err := store.ListStructureEpisodes(t.Context(), 7, library.ID, first.SeriesID, first.SeasonID, nil, 100)
	if err != nil || len(episodes.Items) != 2 {
		t.Fatal(episodes, err)
	}
	item, err := store.GetCatalogItem(t.Context(), 7, library.ID, second.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	structure, err := store.GetItemStructure(t.Context(), 7, library.ID, item.ID)
	if err != nil {
		t.Fatal(err)
	}
	next := SeriesMappingRequest{LibraryID: library.ID, ItemID: item.ID, ItemRevision: item.Revision, StructureRevision: structure.Revision, SeriesID: first.SeriesID, SeasonNumber: 0, EpisodeNumbers: []int{1}}
	if _, err = store.PreviewSeriesMapping(t.Context(), 7, "occupied-position", next, nil, nil); !errors.Is(err, ErrMappingUnsupported) {
		t.Fatal("silently merged occupied episode", err)
	}
	next.EpisodeNumbers = []int{3}
	preview, err = store.PreviewSeriesMapping(t.Context(), 7, "next-preview", next, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	var committed int
	if err = store.db.QueryRow(`SELECT count(*) FROM manual_mapping_preview WHERE committed=1`).Scan(&committed); err != nil || committed != 1 {
		t.Fatal("committed preview needed for receipt replay was removed", committed, err)
	}
	result, err := store.ConfirmSeriesMapping(t.Context(), 7, "next-confirm", library.ID, item.ID, preview.PreviewID)
	if err != nil || result.SeriesID != first.SeriesID || result.SeasonID != first.SeasonID {
		t.Fatal(result, err)
	}
	if _, err = store.UndoSeriesMapping(t.Context(), 7, "next-undo", library.ID, item.ID, result.MappingRevision); err != nil {
		t.Fatal(err)
	}
	episodes, err = store.ListStructureEpisodes(t.Context(), 7, library.ID, first.SeriesID, first.SeasonID, nil, 100)
	if err != nil || len(episodes.Items) != 2 {
		t.Fatal(episodes, err)
	}
}
