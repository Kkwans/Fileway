package fbhttp

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func TestAutomaticMatchInputCarriesTitleWithoutEnablingUnbenchmarkedMatches(t *testing.T) {
	year := 2020
	item := mediaIdentificationResponse{StructureKind: "video", File: &mediaIdentificationFile{ParsedTitle: "Echo 2020 CC", Year: &year, DirectoryTitle: "Echo"}}
	input := automaticMatchInput(item, false)
	if input.Title != "Echo 2020 CC" || input.Year != 2020 || input.AllowTitleMatch || len(input.Aliases) != 1 || input.Aliases[0] != "Echo" {
		t.Fatalf("unexpected automatic input: %+v", input)
	}
}

func TestAutomaticMatchInputIgnoresUnusedDirectoryIssues(t *testing.T) {
	year := 2017
	item := mediaIdentificationResponse{StructureKind: "video", File: &mediaIdentificationFile{
		ParsedTitle: "Blade Runner 2049", Year: &year, DirectoryTitle: "银翼杀手2049",
		DirectoryIssues: []string{"directory_multiple_years"},
	}}
	input := automaticMatchInput(item, true)
	if len(input.Ambiguities) != 0 || input.Title != "Blade Runner 2049" || input.Year != 2017 {
		t.Fatalf("complete filename inherited unused directory issues: %+v", input)
	}
	item.File.ParsedTitle = ""
	input = automaticMatchInput(item, true)
	if len(input.Ambiguities) != 1 || input.Ambiguities[0] != "directory_multiple_years" || input.Title != "银翼杀手2049" {
		t.Fatalf("required directory evidence lost its issues: %+v", input)
	}
}

func TestAutomaticTitleMatchRequiresExplicitServerGate(t *testing.T) {
	t.Setenv("FILEWAY_MEDIA_AUTO_TITLE_MATCH", "")
	if mediaAutoTitleMatchEnabled() {
		t.Fatal("title matching must default off")
	}
	t.Setenv("FILEWAY_MEDIA_AUTO_TITLE_MATCH", "1")
	year := 2020
	item := mediaIdentificationResponse{StructureKind: "video", File: &mediaIdentificationFile{ParsedTitle: "Echo", Year: &year}}
	input := automaticMatchInput(item, mediaAutoTitleMatchEnabled())
	if !input.AllowTitleMatch {
		t.Fatal("explicit gate did not enable conservative title input")
	}
}

func TestAutomaticMatchInputAddsMixedLanguageAliases(t *testing.T) {
	item := mediaIdentificationResponse{StructureKind: "video", File: &mediaIdentificationFile{ParsedTitle: "The Notebook 恋恋笔记本", DirectoryTitle: "Noisy folder"}}
	input := automaticMatchInput(item, false)
	if len(input.Aliases) != 3 || input.Aliases[0] != "The Notebook" || input.Aliases[1] != "恋恋笔记本" || input.Aliases[2] != "Noisy folder" {
		t.Fatalf("mixed-language aliases missing: %+v", input)
	}
}

func TestMediaIdentificationUsesDirectoryYearWithoutOverwritingFilename(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Echo (2020) 1080p/film.mkv", "owned video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "directory-year-scan")
	items, _ := fixtureItems(t, f, f.owner.ID, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("fixture item count: %d", len(items))
	}
	base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + items[0].ID
	response := f.call(t, f.owner.ID, http.MethodGet, base+"/identification", nil)
	var identified mediaIdentificationResponse
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &identified) != nil || identified.File == nil {
		t.Fatalf("identification: %d %s", response.Code, response.Body)
	}
	input := automaticMatchInput(identified, false)
	if identified.File.Year != nil || identified.File.DirectoryYear == nil || *identified.File.DirectoryYear != 2020 || input.Year != 2020 {
		t.Fatalf("directory year was not retained independently: file=%+v input=%+v", identified.File, input)
	}
}

func TestMediaMatchFillFiltersByProbedDurationBeforeProviderTraffic(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Short [tmdb-movie-42].mkv", "owned video")
	catalogFixtureWrite(t, f, "Boundary [tmdb-movie-43].mkv", "owned video")
	catalogFixtureWrite(t, f, "Unknown [tmdb-movie-44].mkv", "owned video")
	catalogFixtureWrite(t, f, "Eligible [tmdb-movie-45].mkv", "owned video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "duration-filter-scan")
	owner := f.owner.ID
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 4 {
		t.Fatalf("fixture item count: %d", len(items))
	}
	durations := map[string]int64{"Short": 59*60_000 + 59_999, "Boundary": 60 * 60_000, "Eligible": 120 * 60_000}
	for _, item := range items {
		detail := fixtureDetail(t, f, owner, library.ID, item.ID)
		if strings.HasPrefix(detail.Item.Title, "Unknown") {
			if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
				_, err := tx.ExecContext(t.Context(), `UPDATE identification_evidence SET evidence_json=json_remove(evidence_json,'$.durationMs','$.durationSourceVersion')
WHERE source_file_id IN (SELECT v.source_file_id FROM variant v JOIN edition e ON e.id=v.edition_id AND e.library_id=v.library_id WHERE e.library_id=? AND e.item_id=?)`, library.ID, item.ID)
				return err
			}); err != nil {
				t.Fatal(err)
			}
		}
		for prefix, duration := range durations {
			if !strings.HasPrefix(detail.Item.Title, prefix) {
				continue
			}
			if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
				_, err := tx.ExecContext(t.Context(), `UPDATE identification_evidence SET evidence_json=json_set(evidence_json,'$.durationMs',?,'$.durationSourceVersion',(SELECT source_version FROM source_file WHERE id=identification_evidence.source_file_id))
WHERE source_file_id IN (SELECT v.source_file_id FROM variant v JOIN edition e ON e.id=v.edition_id AND e.library_id=v.library_id WHERE e.library_id=? AND e.item_id=?)`, duration, library.ID, item.ID)
				return err
			}); err != nil {
				t.Fatal(err)
			}
		}
	}
	conn, err := medialibrary.ProviderConnectionID(owner, "duration-filter-provider")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{13}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(conn)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "fixture-duration-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.PutProviderConnection(t.Context(), owner, false, conn, "user", "duration-filter-provider", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	outbound := 0
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		outbound++
		if r.URL.Path != "/3/movie/45" || r.URL.Query().Has("query") {
			t.Errorf("filtered item reached provider: %s", r.URL)
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"id":45,"title":"Eligible","release_date":"2020-09-01","overview":"Allowed by duration"}`))}, nil
	})}
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "duration-filter-policy", 0,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: conn, MovieMinMinutes: 90, SeriesMinMinutes: 15}); err != nil {
		t.Fatal(err)
	}
	for _, item := range items {
		base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + item.ID
		identified := f.call(t, owner, http.MethodGet, base+"/identification", nil)
		var identification mediaIdentificationResponse
		if identified.Code != http.StatusOK || json.Unmarshal(identified.Body.Bytes(), &identification) != nil || identification.File == nil {
			t.Fatalf("identification: %d %s", identified.Code, identified.Body)
		}
		body := map[string]any{"operationId": "duration-fill-" + item.ID, "itemRevision": identification.ItemRevision, "structureRevision": identification.StructureRevision}
		response := f.call(t, owner, http.MethodPost, base+"/match-fill", body)
		if response.Code != http.StatusOK {
			t.Fatalf("fill: %d %s", response.Code, response.Body)
		}
		title := identification.File.ParsedTitle
		switch {
		case strings.HasPrefix(title, "Short"), strings.HasPrefix(title, "Boundary"):
			if !strings.Contains(response.Body.String(), `"state":"skipped"`) || !strings.Contains(response.Body.String(), `"reason":"movie_below_minimum_duration"`) {
				t.Fatalf("short movie was not filtered: %s", response.Body)
			}
		case strings.HasPrefix(title, "Unknown"):
			if !strings.Contains(response.Body.String(), `"state":"needs_review"`) || !strings.Contains(response.Body.String(), `"reason":"duration_unknown"`) {
				t.Fatalf("unknown duration was not held for review: %s", response.Body)
			}
		case strings.HasPrefix(title, "Eligible"):
			if !strings.Contains(response.Body.String(), `"state":"matched"`) {
				t.Fatalf("eligible movie was filtered: %s", response.Body)
			}
		default:
			t.Fatalf("unexpected fixture title: %q", title)
		}
	}
	if outbound != 1 {
		t.Fatalf("provider request count: %d", outbound)
	}
}

func TestMediaMatchFillRespectsExplicitIdentityAndReplays(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Echo (2020) [tmdb-movie-42].mkv", "owned video")
	catalogFixtureWrite(t, f, "Remake (1998).mkv", "owned video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "auto-fixture-scan")
	owner := f.owner.ID
	items, _ := fixtureItems(t, f, owner, library.ID, "")
	if len(items) != 2 {
		t.Fatalf("fixture item count: %d", len(items))
	}
	conn, err := medialibrary.ProviderConnectionID(owner, "fill-fixture")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{12}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(conn)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "fixture-fill-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.PutProviderConnection(t.Context(), owner, false, conn, "user", "fill-fixture", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	f.h.storage.MediaProviderVault = vault
	var outbound int
	f.h.storage.TMDBHTTPClient = &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		outbound++
		if r.URL.Path != "/3/movie/42" || r.URL.Query().Has("query") || r.Header.Get("Authorization") != "Bearer fixture-fill-token" {
			t.Error("unexpected automatic provider request")
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"id":42,"title":"Echo","release_date":"2020-09-01","overview":"Saved automatically"}`))}, nil
	})}
	for _, item := range items {
		base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + item.ID
		identified := f.call(t, owner, http.MethodGet, base+"/identification", nil)
		var identification mediaIdentificationResponse
		if identified.Code != http.StatusOK || json.Unmarshal(identified.Body.Bytes(), &identification) != nil || identification.File == nil {
			t.Fatalf("identification: %d %s", identified.Code, identified.Body)
		}
		input := map[string]any{"operationId": "fill-" + item.ID, "itemRevision": identification.ItemRevision, "structureRevision": identification.StructureRevision}
		if len(identification.File.ProviderIDs) == 0 {
			if response := f.call(t, owner, http.MethodPost, base+"/match-fill", input); response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"state":"local_only"`) {
				t.Fatalf("offline fill: %d %s", response.Code, response.Body)
			}
		}
	}
	policy := medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataLocalFirst, ConnectionID: conn}
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), owner, library.ID, "allow-fill", 0, policy); err != nil {
		t.Fatal(err)
	}
	for _, item := range items {
		base := "/api/media-library/v1/libraries/" + library.ID + "/items/" + item.ID
		identified := f.call(t, owner, http.MethodGet, base+"/identification", nil)
		var identification mediaIdentificationResponse
		if identified.Code != http.StatusOK || json.Unmarshal(identified.Body.Bytes(), &identification) != nil {
			t.Fatalf("identification: %d %s", identified.Code, identified.Body)
		}
		input := map[string]any{"operationId": "fill-" + item.ID, "itemRevision": identification.ItemRevision, "structureRevision": identification.StructureRevision}
		response := f.call(t, owner, http.MethodPost, base+"/match-fill", input)
		if response.Code != http.StatusOK {
			t.Fatalf("fill: %d %s", response.Code, response.Body)
		}
		if strings.HasPrefix(identification.File.ParsedTitle, "Remake") {
			if !strings.Contains(response.Body.String(), `"reason":"typed_identifier_required"`) {
				t.Fatalf("title-only item: %s", response.Body)
			}
			continue
		}
		if !strings.Contains(response.Body.String(), `"state":"matched"`) {
			t.Fatalf("typed movie: %s", response.Body)
		}
		if replay := f.call(t, owner, http.MethodPost, base+"/match-fill", input); replay.Code != http.StatusOK || replay.Body.String() != response.Body.String() {
			t.Fatalf("typed movie replay: %d %s", replay.Code, replay.Body)
		}
		if detail := fixtureDetail(t, f, owner, library.ID, item.ID); detail.OnlineMatch == nil || detail.Fields["plot"].Values[0] != "Saved automatically" {
			t.Fatalf("automatic match was not persisted: %+v", detail.OnlineMatch)
		}
		var matched mediaIdentificationResponse
		current := f.call(t, owner, http.MethodGet, base+"/identification", nil)
		if current.Code != http.StatusOK || json.Unmarshal(current.Body.Bytes(), &matched) != nil || matched.UndoMatchRevision < 1 {
			t.Fatalf("undo capability: %d %s", current.Code, current.Body)
		}
		undo := map[string]any{"operationId": "undo-match", "itemRevision": matched.ItemRevision, "structureRevision": matched.StructureRevision, "matchRevision": matched.UndoMatchRevision}
		path := base + "/match-confirmation/undo"
		if rejected := f.call(t, f.viewer.ID, http.MethodPost, path, undo); rejected.Code != http.StatusNotFound {
			t.Fatalf("unauthorized undo: %d", rejected.Code)
		}
		undone := f.call(t, owner, http.MethodPost, path, undo)
		if undone.Code != http.StatusOK || !strings.Contains(undone.Body.String(), `"restored":false`) {
			t.Fatalf("undo: %d %s", undone.Code, undone.Body)
		}
		if replay := f.call(t, owner, http.MethodPost, path, undo); replay.Code != http.StatusOK || replay.Body.String() != undone.Body.String() {
			t.Fatalf("undo replay: %d %s", replay.Code, replay.Body)
		}
		undo["operationId"] = "stale-undo"
		if stale := f.call(t, owner, http.MethodPost, path, undo); stale.Code != http.StatusConflict {
			t.Fatalf("stale undo: %d %s", stale.Code, stale.Body)
		}
		detail := fixtureDetail(t, f, owner, library.ID, item.ID)
		if detail.OnlineMatch != nil || detail.Item.Revision <= matched.ItemRevision {
			t.Fatalf("undone item: %+v", detail.Item)
		}
		input["operationId"], input["itemRevision"] = "fill-after-undo", detail.Item.Revision
		if fill := f.call(t, owner, http.MethodPost, base+"/match-fill", input); fill.Code != http.StatusOK || !strings.Contains(fill.Body.String(), "manual_review_required") {
			t.Fatalf("would rematch manually undone item: %d %s", fill.Code, fill.Body)
		}
	}
	if outbound != 1 {
		t.Fatalf("provider request count: %d", outbound)
	}
}
