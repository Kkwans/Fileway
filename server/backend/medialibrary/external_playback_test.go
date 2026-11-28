package medialibrary

import (
	"math"
	"testing"
)

func TestRecordExternalPlaybackWithoutCatalogIsNoop(t *testing.T) {
	var store *Store
	if err := store.RecordExternalPlayback(t.Context(), 7, []byte("/film.mp4"), "version", 1000, 120000); err != nil {
		t.Fatal(err)
	}
	if err := (&Store{}).RecordExternalPlayback(t.Context(), 7, []byte("/film.mp4"), "version", 1000, 120000); err != nil {
		t.Fatal(err)
	}
}

func TestRecordExternalPlaybackMirrorsIntoContinueWatching(t *testing.T) {
	store, library, binding, _, file, variant := playbackFixture(t)
	ctx := t.Context()
	access, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.RecordExternalPlayback(ctx, 7, access.Locator, access.SourceVersion, 42_000, 120_000); err != nil {
		t.Fatal(err)
	}
	items, err := store.ListContinueCandidates(ctx, 7, false, math.MaxInt64, "", 12)
	if err != nil || len(items) != 1 || items[0].PositionMs != 42_000 {
		t.Fatalf("external checkpoint missing from continue watching: %+v %v", items, err)
	}
	if err := store.RecordExternalPlayback(ctx, 7, access.Locator, access.SourceVersion, 50_000, 120_000); err != nil {
		t.Fatal(err)
	}
	items, err = store.ListContinueCandidates(ctx, 7, false, math.MaxInt64, "", 12)
	if err != nil || len(items) != 1 || items[0].PositionMs != 50_000 {
		t.Fatalf("external checkpoint did not update continue watching: %+v %v", items, err)
	}
}
