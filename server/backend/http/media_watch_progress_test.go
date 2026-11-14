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
	if update := f.f.call(t, actor, http.MethodPost, progressPath, event); update.Code != http.StatusNotFound {
		t.Fatal(update.Code, update.Body)
	}
	second := f.f.call(t, actor, http.MethodPost, f.base+"/playback-resolutions", body)
	var result struct{ WatchProgress *medialibrary.WatchProgress }
	if second.Code != http.StatusOK || json.Unmarshal(second.Body.Bytes(), &result) != nil || result.WatchProgress == nil || result.WatchProgress.PositionMs != 0 {
		t.Fatalf("replacement reused checkpoint: %d %s", second.Code, second.Body)
	}
}
