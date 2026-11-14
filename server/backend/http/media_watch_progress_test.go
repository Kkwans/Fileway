package fbhttp

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
)

func TestWatchProgressReopensExactTimelineAndRejectsOldSessions(t *testing.T) {
	f := newMediaAccessFixture(t)
	actor := f.f.viewer.ID
	resolve := func(fromStart bool) (string, medialibrary.WatchProgress) {
		t.Helper()
		response := f.f.call(t, actor, http.MethodPost, f.base+"/playback-resolutions", map[string]any{
			"editionId": f.variant.EditionID, "variantId": f.variant.VariantID, "bindingId": f.variant.BindingID,
			"watchProgress": true, "fromStart": fromStart,
		})
		var result struct {
			SessionID     string
			WatchProgress *medialibrary.WatchProgress
		}
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.WatchProgress == nil || result.SessionID == "" {
			t.Fatalf("resolve watch session: %d %s", response.Code, response.Body)
		}
		return "/api/media-library/v1/playback-sessions/" + result.SessionID + "/progress", *result.WatchProgress
	}
	duration := int64(120_000)
	send := func(who uint, path string, sequence, position int64, ended bool, want int) medialibrary.WatchProgress {
		t.Helper()
		response := f.f.call(t, who, http.MethodPost, path, medialibrary.WatchEvent{
			Sequence: sequence, PositionMs: position, DurationMs: &duration, Ended: ended,
		})
		if response.Code != want {
			t.Fatalf("watch event: %d %s, wanted %d", response.Code, response.Body, want)
		}
		var progress medialibrary.WatchProgress
		if want == http.StatusOK && json.Unmarshal(response.Body.Bytes(), &progress) != nil {
			t.Fatal("invalid watch receipt")
		}
		return progress
	}
	first, initial := resolve(false)
	if initial.PositionMs != 0 {
		t.Fatal(initial)
	}
	send(f.f.owner.ID, first, 1, 28_000, false, http.StatusNotFound)
	if got := send(actor, first, 2, 28_000, false, http.StatusOK); got.PositionMs != 28_000 || got.Completed {
		t.Fatal(got)
	}
	if got := send(actor, first, 1, 52_000, false, http.StatusOK); got.PositionMs != 28_000 || got.Sequence != 2 {
		t.Fatal(got)
	}
	second, resumed := resolve(false)
	if resumed.PositionMs != 28_000 || resumed.Sequence != 0 {
		t.Fatal(resumed)
	}
	send(actor, first, 3, 40_000, false, http.StatusConflict)
	if got := send(actor, second, 1, duration, false, http.StatusOK); got.Completed {
		t.Fatal("seek was treated as completion")
	}
	send(actor, second, 2, 30_000, true, http.StatusUnprocessableEntity)
	if got := send(actor, second, 2, duration, true, http.StatusOK); !got.Completed {
		t.Fatal(got)
	}
	third, replay := resolve(false)
	if replay.PositionMs != 0 || !replay.Completed {
		t.Fatal(replay)
	}
	response := f.f.call(t, actor, http.MethodPatch, f.base+"/user-state", map[string]any{
		"operationId": "reset-live-watch", "expectedRevision": 0, "manualWatched": false,
	})
	if response.Code != http.StatusOK {
		t.Fatal(response.Code, response.Body)
	}
	send(actor, third, 1, 37_000, false, http.StatusConflict)
	fourth, reset := resolve(false)
	if reset.PositionMs != 0 || reset.Completed {
		t.Fatal(reset)
	}
	send(actor, fourth, 1, 37_000, false, http.StatusOK)
	_, reset = resolve(true)
	if reset.PositionMs != 0 {
		t.Fatal(reset)
	}
	send(actor, fourth, 2, 40_000, false, http.StatusConflict)
	if !deviceMediaRoute(http.MethodPost, first, actor) || deviceMediaRoute(http.MethodGet, first, actor) {
		t.Fatal("device scope for progress route")
	}
}

func TestWatchProgressDoesNotReuseReplacedSource(t *testing.T) {
	f := newMediaAccessFixture(t)
	actor := f.f.viewer.ID
	body := map[string]any{"editionId": f.variant.EditionID, "variantId": f.variant.VariantID,
		"bindingId": f.variant.BindingID, "watchProgress": true}
	first := f.f.call(t, actor, http.MethodPost, f.base+"/playback-resolutions", body)
	var resolution testMediaResolution
	if first.Code != http.StatusOK || json.Unmarshal(first.Body.Bytes(), &resolution) != nil || resolution.SessionID == "" {
		t.Fatal(first.Code, first.Body)
	}
	event := medialibrary.WatchEvent{Sequence: 1, PositionMs: 42_000}
	progressPath := "/api/media-library/v1/playback-sessions/" + resolution.SessionID + "/progress"
	if update := f.f.call(t, actor, http.MethodPost, progressPath, event); update.Code != http.StatusOK {
		t.Fatal(update.Code, update.Body)
	}
	if row := readContinueRow(t, f, actor); len(row.Continuing) != 1 || row.Continuing[0].DurationMs != nil {
		t.Fatalf("expected a checkpoint with unknown duration: %+v", row)
	}
	video := filepath.Join(f.f.root, "films", f.variant.DisplayName)
	info, err := os.Stat(video)
	if err != nil {
		t.Fatal(err)
	}
	// Keep the provider's v1:size:mtime marker identical while making an
	// in-place change. The native change stamp still fences the timeline.
	changed := append([]byte(nil), f.video...)
	changed[0] ^= 1
	if err := os.WriteFile(video, changed, info.Mode().Perm()); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(video, time.Now(), info.ModTime()); err != nil {
		t.Fatal(err)
	}
	if row := readContinueRow(t, f, actor); len(row.Continuing) != 0 {
		t.Fatal("the replaced timeline appeared in continue watching")
	}
	if update := f.f.call(t, actor, http.MethodPost, progressPath, event); update.Code != http.StatusNotFound {
		t.Fatal(update.Code, update.Body)
	}
	second := f.f.call(t, actor, http.MethodPost, f.base+"/playback-resolutions", body)
	var result struct{ WatchProgress *medialibrary.WatchProgress }
	if second.Code != http.StatusOK || json.Unmarshal(second.Body.Bytes(), &result) != nil || result.WatchProgress == nil || result.WatchProgress.PositionMs != 0 {
		t.Fatalf("replacement reused checkpoint: %d %s", second.Code, second.Body)
	}
}

func readContinueRow(t *testing.T, f *mediaAccessFixture, actor uint) catalogHomeRow {
	t.Helper()
	response := f.f.call(t, actor, http.MethodGet, continueEndpoint, nil)
	var row catalogHomeRow
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &row) != nil || row.State != "ready" {
		t.Fatalf("continue row: %d %s", response.Code, response.Body)
	}
	return row
}

func TestWatchContinueReadResumeAndRemove(t *testing.T) {
	f := newMediaAccessFixture(t)
	actor := f.f.viewer.ID
	body := map[string]any{"editionId": f.variant.EditionID, "variantId": f.variant.VariantID,
		"bindingId": f.variant.BindingID, "watchProgress": true}
	resolve := func(expected int) (testMediaResolution, medialibrary.WatchProgress) {
		t.Helper()
		response := f.f.call(t, actor, http.MethodPost, f.base+"/playback-resolutions", body)
		var result struct {
			testMediaResolution
			WatchProgress medialibrary.WatchProgress
		}
		if response.Code != expected || json.Unmarshal(response.Body.Bytes(), &result) != nil {
			t.Fatalf("admission: %d %s", response.Code, response.Body)
		}
		return result.testMediaResolution, result.WatchProgress
	}
	send := func(sessionID string, sequence, position int64, wanted int) {
		t.Helper()
		duration := int64(120_000)
		response := f.f.call(t, actor, http.MethodPost, "/api/media-library/v1/playback-sessions/"+sessionID+"/progress",
			medialibrary.WatchEvent{Sequence: sequence, PositionMs: position, DurationMs: &duration})
		if response.Code != wanted {
			t.Fatalf("progress: %d %s", response.Code, response.Body)
		}
	}
	first, _ := resolve(http.StatusOK)
	send(first.SessionID, 1, 27_000, http.StatusOK)
	item := readContinueRow(t, f, actor).Continuing[0]
	if item.PositionMs != 27_000 || item.DurationMs == nil || *item.DurationMs != 120_000 ||
		item.Variant.VariantID != f.variant.VariantID || !item.Card.CanActions["resume"] {
		t.Fatalf("invalid resume target: %+v", item)
	}
	if row := readContinueRow(t, f, f.f.owner.ID); len(row.Continuing) != 0 {
		t.Fatal("viewer checkpoint was visible to the owner")
	}
	homeResponse := f.f.call(t, actor, http.MethodGet, "/api/media-library/v1/home", nil)
	var home catalogHome
	if homeResponse.Code != http.StatusOK || json.Unmarshal(homeResponse.Body.Bytes(), &home) != nil ||
		len(home.Rows) < 1 || len(home.Rows[0].Continuing) != 1 || home.Rows[0].Continuing[0].Revision != item.Revision {
		t.Fatalf("home lost the exact resume target: %d %s", homeResponse.Code, homeResponse.Body)
	}
	body["continueRevision"] = item.Revision
	second, resumed := resolve(http.StatusOK)
	if resumed.PositionMs != 27_000 {
		t.Fatal(resumed)
	}
	resolve(http.StatusConflict)
	send(second.SessionID, 1, 45_000, http.StatusOK)
	remove := func(revision string, expected int) {
		t.Helper()
		response := f.f.call(t, actor, http.MethodPost, f.base+"/remove-continue", map[string]string{
			"editionId": f.variant.EditionID, "variantId": f.variant.VariantID, "bindingId": f.variant.BindingID, "expectedRevision": revision,
		})
		if response.Code != expected {
			t.Fatalf("remove: %d %s", response.Code, response.Body)
		}
	}
	remove(item.Revision, http.StatusConflict)
	revision := readContinueRow(t, f, actor).Continuing[0].Revision
	remove(revision, http.StatusNoContent)
	remove(revision, http.StatusNoContent)
	send(second.SessionID, 2, 50_000, http.StatusConflict)
	if row := readContinueRow(t, f, actor); len(row.Continuing) != 0 {
		t.Fatal("dismissed checkpoint is still visible")
	}
	body["continueRevision"] = revision
	resolve(http.StatusConflict)
	delete(body, "continueRevision")
	next, progress := resolve(http.StatusOK)
	if progress.PositionMs != 0 {
		t.Fatal("ordinary playback reused a removed checkpoint")
	}
	send(next.SessionID, 1, 11_000, http.StatusOK)
	if _, err := f.f.store.RevokeMember(t.Context(), f.f.owner.ID, item.Card.Ref.LibraryID, f.libraryRevision, actor); err != nil {
		t.Fatal(err)
	}
	if row := readContinueRow(t, f, actor); len(row.Continuing) != 0 {
		t.Fatal("shared checkpoint survived the sharing restriction")
	}
}
