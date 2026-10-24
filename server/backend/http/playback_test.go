package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestPlaybackHTTPIsPrivateExactAndInvalidatesReplacement(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "owner", Perm: users.Permissions{Download: true}},
		users.User{Username: "other", Perm: users.Permissions{Download: true}},
	)
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	if err := afero.WriteFile(h.fs[owner.ID], "/film.mp4", []byte("first"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(h.fs[other.ID], "/film.mp4", []byte("other"), 0o600); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, playbackPutHandler, http.MethodPut, "/media/playback", bytes.NewBufferString(`{"path":"/film.mp4","position":99.875,"duration":100}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("save status = %d body=%s", response.Code, response.Body.String())
	}
	var saved playbackResponse
	if err := json.Unmarshal(response.Body.Bytes(), &saved); err != nil {
		t.Fatal(err)
	}
	if !saved.Exists || saved.Position != 99.875 || saved.Identity == "" {
		t.Fatalf("saved = %#v", saved)
	}

	response = h.request(t, owner.ID, playbackGetHandler, http.MethodGet, "/media/playback?path=/film.mp4", nil, nil)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"position":99.875`) {
		t.Fatalf("owner get status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, other.ID, playbackGetHandler, http.MethodGet, "/media/playback?path=/film.mp4", nil, nil)
	if response.Code != http.StatusOK || strings.Contains(response.Body.String(), `"position":99.875`) {
		t.Fatalf("other get status = %d body=%s", response.Code, response.Body.String())
	}

	if err := afero.WriteFile(h.fs[owner.ID], "/film.mp4", []byte("replacement with a different identity"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := h.fs[owner.ID].Chtimes("/film.mp4", time.Now().Add(time.Second), time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, owner.ID, playbackGetHandler, http.MethodGet, "/media/playback?path=/film.mp4", nil, nil)
	if response.Code != http.StatusOK || strings.Contains(response.Body.String(), `"exists":true`) {
		t.Fatalf("replacement get status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestPlaybackHTTPValidationAndExplicitClear(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/film.mp4", []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(h.fs[owner.ID], "/notes.txt", []byte("text"), 0o600); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, playbackPutHandler, http.MethodPut, "/media/playback", bytes.NewBufferString(`{"path":"/film.mp4","position":-1,"duration":10}`), nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("negative status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, playbackGetHandler, http.MethodGet, "/media/playback?path=/notes.txt", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("text status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, playbackPutHandler, http.MethodPut, "/media/playback", bytes.NewBufferString(`{"path":"/film.mp4","position":20,"duration":10}`), nil)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"position":10`) {
		t.Fatalf("clamped status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, playbackDeleteHandler, http.MethodDelete, "/media/playback?path=/film.mp4", nil, nil)
	if response.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, playbackGetHandler, http.MethodGet, "/media/playback?path=/film.mp4", nil, nil)
	if response.Code != http.StatusOK || strings.Contains(response.Body.String(), `"exists":true`) {
		t.Fatalf("cleared status = %d body=%s", response.Code, response.Body.String())
	}
}

type playbackCountingFs struct {
	afero.Fs
	openCalls int
}

func (fs *playbackCountingFs) Open(name string) (afero.File, error) {
	fs.openCalls++
	return fs.Fs.Open(name)
}

func TestPlaybackHTTPKnownVideoExtensionDoesNotReadHeaderOrScanParent(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/film.mp4", []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	counting := &playbackCountingFs{Fs: h.fs[owner.ID]}
	h.fs[owner.ID] = counting

	response := h.request(t, owner.ID, playbackGetHandler, http.MethodGet, "/media/playback?path=/film.mp4", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("known video status = %d body=%s", response.Code, response.Body.String())
	}
	if counting.openCalls != 0 {
		t.Fatalf("known video opened %d times while reading playback identity", counting.openCalls)
	}
}

func TestPlaybackHTTPAudioIdentityIsReadOnlyWithoutBodyReadsAndTextRemainsRejected(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "audio-owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	for _, name := range []string{"/tone.wav", "/tone.mp3", "/tone.flac", "/notes.txt"} {
		if err := afero.WriteFile(h.fs[owner.ID], name, []byte("owned metadata fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	counting := &playbackCountingFs{Fs: h.fs[owner.ID]}
	h.fs[owner.ID] = counting
	for _, name := range []string{"/tone.wav", "/tone.mp3", "/tone.flac"} {
		var identity string
		for attempt := 0; attempt < 2; attempt++ {
			response := h.request(t, owner.ID, playbackGetHandler, http.MethodGet, "/media/playback?path="+name, nil, nil)
			if response.Code != http.StatusOK {
				t.Fatalf("audio metadata status=%d body=%s", response.Code, response.Body.String())
			}
			var result playbackResponse
			if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
				t.Fatal(err)
			}
			if result.Path != name || result.Identity == "" || result.Exists || result.Position != 0 || result.Duration != 0 {
				t.Fatalf("read-only audio identity = %#v", result)
			}
			if attempt == 0 {
				identity = result.Identity
			} else if identity != result.Identity {
				t.Fatal("unchanged audio identity drifted")
			}
		}
	}
	response := h.request(t, owner.ID, playbackGetHandler, http.MethodGet, "/media/playback?path=/notes.txt", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("text accepted as playable media: %d body=%s", response.Code, response.Body.String())
	}
	if counting.openCalls != 0 {
		t.Fatalf("audio/text identity checks opened file bodies %d times", counting.openCalls)
	}
}

func TestPlaybackSelectedDeleteProtectsRevisionAndOwner(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"}, users.User{Username: "other"})
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	first, err := h.storage.Playback.Save(owner.ID, "/gone.mp4", "first", 1, 10)
	if err != nil {
		t.Fatal(err)
	}
	_, err = h.storage.Playback.Save(other.ID, "/gone.mp4", "first", 2, 10)
	if err != nil {
		t.Fatal(err)
	}
	request := func(identity string, stamp int64) []byte {
		b, _ := json.Marshal(map[string]interface{}{"items": []map[string]interface{}{{"wirePath": "/gone.mp4", "identity": identity, "updatedAt": stamp}}})
		return b
	}
	response := h.request(t, owner.ID, playbackSelectedDeleteHandler, http.MethodPost, "/media/playback/delete", bytes.NewReader(request("stale", first.UpdatedAt)), nil)
	if response.Code != http.StatusConflict {
		t.Fatal(response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, playbackSelectedDeleteHandler, http.MethodPost, "/media/playback/delete", bytes.NewReader(request(first.Identity, first.UpdatedAt)), nil)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"deleted":1`) {
		t.Fatal(response.Code, response.Body.String())
	}
	// Deleting metadata still works after its media has gone; no filesystem permission bypass is involved.
	response = h.request(t, owner.ID, playbackClearHandler, http.MethodDelete, "/media/playback/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatal(response.Code, response.Body.String())
	}
	if _, err := h.storage.Playback.Get(other.ID, "/gone.mp4", "first"); err != nil {
		t.Fatal(err)
	}
}
