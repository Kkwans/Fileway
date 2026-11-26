package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

func TestFetchProviderEpisodeDocumentsUsesOnlyLocalEpisodePositions(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Show.S01E01.mkv", "video")
	catalogFixtureWrite(t, f, "Show.S01E03.mkv", "video")
	catalogFixtureWrite(t, f, "tvshow.nfo", `<tvshow><title>本地剧</title><year>2024</year></tvshow>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "episode-metadata-fixture")
	page, err := f.store.ListCatalogItems(t.Context(), f.owner.ID, library.ID, medialibrary.CatalogListOptions{Kind: "series"})
	if err != nil || len(page.Items) != 1 {
		t.Fatalf("series fixture: %+v err=%v", page, err)
	}
	seriesID := page.Items[0].ID
	calls := 0
	client := mediaprovider.NewTMDBClient(&http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Path != "/3/tv/55/season/1" || r.URL.Query().Get("language") != "zh-CN" {
			t.Fatalf("unexpected season route: %s", r.URL.String())
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
			`{"id":101,"season_number":1,"episodes":[
				{"id":201,"episode_number":1,"name":"第一集","overview":"简介一","air_date":"2024-03-01","episode_runtime":52,"still_path":"/still1.jpg"},
				{"id":202,"episode_number":2,"name":"第二集","episode_runtime":50},
				{"id":203,"episode_number":3,"name":"第三集","episode_runtime":48}
			]}`))}, nil
	})}, func(context.Context) (string, error) { return "fixture-token", nil })
	documents, err := fetchProviderEpisodeDocuments(t.Context(), f.store, f.owner.ID, library.ID, seriesID,
		mediaprovider.EntityRef{Provider: "tmdb", Kind: mediaprovider.Series, ID: 55}, client, "zh-CN")
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 || len(documents) != 2 || documents[0].EpisodeNumber != 1 || documents[1].EpisodeNumber != 3 {
		t.Fatalf("episode documents: %+v calls=%d", documents, calls)
	}
	first := documents[0]
	if first.SeasonNumber != 1 || first.StillPath != "/still1.jpg" || first.Item.Kind != "episodedetails" ||
		first.Item.Fields["title"][0] != "第一集" || first.Item.Fields["runtime"][0] != "52" ||
		first.Item.Fields["aired"][0] != "2024-03-01" || first.Item.ExternalIDs[0].Value != "201" {
		t.Fatalf("first episode metadata: %+v", first)
	}
}

func TestMetadataRefreshJobBackfillsEpisodesForSameLanguageSeries(t *testing.T) {
	var testVault *mediaprovider.CredentialVault
	f := newMediaScanHTTPFixture(t, func(h *trashHTTPHarness) {
		var err error
		testVault, err = mediaprovider.NewCredentialVault(bytes.Repeat([]byte{11}, 32))
		if err != nil {
			t.Fatal(err)
		}
		h.storage.MediaProviderVault = testVault
		client := &http.Client{Transport: matchTestTransport(func(r *http.Request) (*http.Response, error) {
			switch r.URL.Path {
			case "/3/tv/55":
				return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
					`{"id":55,"name":"本地剧","first_air_date":"2024-01-01"}`))}, nil
			case "/3/tv/55/season/1":
				return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
					`{"id":101,"season_number":1,"episodes":[{"id":201,"episode_number":1,"name":"第一集","episode_runtime":52}]}`))}, nil
			default:
				t.Fatalf("unexpected refresh route: %s", r.URL.Path)
				return nil, nil
			}
		})}
		h.storage.TMDBHTTPClient = client
	})
	catalogFixtureWrite(t, f, "Show.S01E01.mkv", "video")
	catalogFixtureWrite(t, f, "tvshow.nfo", `<tvshow><title>本地剧</title><year>2024</year></tvshow>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "episode-refresh-job-fixture")
	seriesPage, err := f.store.ListCatalogItems(t.Context(), f.owner.ID, library.ID, medialibrary.CatalogListOptions{Kind: "series"})
	if err != nil || len(seriesPage.Items) != 1 {
		t.Fatalf("series fixture: %+v err=%v", seriesPage, err)
	}
	seriesID := seriesPage.Items[0].ID
	seasons, err := f.store.ListStructureSeasons(t.Context(), f.owner.ID, library.ID, seriesID, nil, 20)
	if err != nil || len(seasons.Items) != 1 {
		t.Fatalf("season fixture: %+v err=%v", seasons, err)
	}
	episodes, err := f.store.ListStructureEpisodes(t.Context(), f.owner.ID, library.ID, seriesID, seasons.Items[0].SeasonID, nil, 20)
	if err != nil || len(episodes.Items) != 1 {
		t.Fatalf("episode fixture: %+v err=%v", episodes, err)
	}
	connection, err := medialibrary.ProviderConnectionID(f.owner.ID, "episode-refresh-job")
	if err != nil {
		t.Fatal(err)
	}
	vault := testVault
	scope, err := medialibrary.ProviderCredentialContext(connection)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "episode-refresh-job-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.PutProviderConnection(t.Context(), f.owner.ID, false, connection, "user", "episode-refresh-job", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	policy, err := f.store.SetLibraryMetadataPolicy(t.Context(), f.owner.ID, library.ID, "episode-refresh-job-policy", 0,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataOnlineFirst, ConnectionID: connection, Language: "zh-CN", Region: "CN"})
	if err != nil {
		t.Fatal(err)
	}
	authority, err := f.store.CheckProviderUse(t.Context(), f.owner.ID, library.ID, connection)
	if err != nil {
		t.Fatal(err)
	}
	structure, err := f.store.GetItemStructure(t.Context(), f.owner.ID, library.ID, seriesID)
	if err != nil {
		t.Fatal(err)
	}
	initial := medialibrary.ProviderMatchDocument{Item: mediametadata.Item{Kind: mediametadata.TVShow, Fields: map[string][]string{
		"title": {"本地剧"}, "year": {"2024"},
	}}}
	request := medialibrary.ProviderMatchRequest{LibraryID: library.ID, ItemID: seriesID, ConnectionID: connection,
		Kind: "series", ExternalID: 55, ItemRevision: seriesPage.Items[0].Revision, StructureRevision: structure.Revision}
	if _, err := f.store.ConfirmProviderMatch(t.Context(), f.owner.ID, "episode-refresh-initial", request, *authority, "zh-CN", time.Now().UnixMilli(), initial,
		medialibrary.ProviderMatchAudit{Source: "automatic", Region: "CN", AdapterVersion: "tmdb-v1"}); err != nil {
		t.Fatal(err)
	}
	base := "/api/media-library/v1/libraries/" + library.ID
	enqueue := f.call(t, f.owner.ID, http.MethodPost, base+"/metadata-refresh-jobs", map[string]any{
		"operationId": "episode-refresh-enqueue", "mode": "refresh_unlocked", "expectedPolicyRevision": policy.Revision,
	})
	var job metadataRefreshJobResponse
	if enqueue.Code != http.StatusAccepted || json.Unmarshal(enqueue.Body.Bytes(), &job) != nil || job.Total != 1 {
		t.Fatalf("enqueue: %d %s", enqueue.Code, enqueue.Body)
	}
	deadline := time.Now().Add(12 * time.Second)
	for {
		response := f.call(t, f.owner.ID, http.MethodGet, base+"/metadata-refresh-jobs/"+job.JobID, nil)
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &job) != nil {
			t.Fatal(response.Code, response.Body)
		}
		if job.Status != "queued" && job.Status != "running" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("refresh did not finish: %+v", job)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if job.Status != "completed" || job.Refreshed != 1 || job.AlreadyCurrent != 0 {
		t.Fatalf("episode refresh job: %+v", job)
	}
	detail := fixtureDetail(t, f, f.owner.ID, library.ID, episodes.Items[0].EpisodeID)
	if detail.Item.Title != "第一集" || detail.Fields["runtime"].Values[0] != "52" {
		t.Fatalf("backfilled episode detail: %+v", detail)
	}
}

func TestEpisodeDetailUsesSeriesProviderEpisodeMetadata(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "Show.S01E01.mkv", "video")
	catalogFixtureWrite(t, f, "tvshow.nfo", `<tvshow><title>本地剧</title><year>2024</year></tvshow>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "episode-detail-fixture")
	seriesPage, err := f.store.ListCatalogItems(t.Context(), f.owner.ID, library.ID, medialibrary.CatalogListOptions{Kind: "series"})
	if err != nil || len(seriesPage.Items) != 1 {
		t.Fatalf("series fixture: %+v err=%v", seriesPage, err)
	}
	seriesID := seriesPage.Items[0].ID
	seasons, err := f.store.ListStructureSeasons(t.Context(), f.owner.ID, library.ID, seriesID, nil, 20)
	if err != nil || len(seasons.Items) != 1 {
		t.Fatalf("season fixture: %+v err=%v", seasons, err)
	}
	episodes, err := f.store.ListStructureEpisodes(t.Context(), f.owner.ID, library.ID, seriesID, seasons.Items[0].SeasonID, nil, 20)
	if err != nil || len(episodes.Items) != 1 {
		t.Fatalf("episode fixture: %+v err=%v", episodes, err)
	}
	connection, err := medialibrary.ProviderConnectionID(f.owner.ID, "episode-metadata")
	if err != nil {
		t.Fatal(err)
	}
	vault, err := mediaprovider.NewCredentialVault(bytes.Repeat([]byte{9}, 32))
	if err != nil {
		t.Fatal(err)
	}
	scope, err := medialibrary.ProviderCredentialContext(connection)
	if err != nil {
		t.Fatal(err)
	}
	sealed, fingerprint, err := vault.Seal(scope, "episode-fixture-token")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.PutProviderConnection(t.Context(), f.owner.ID, false, connection, "user", "episode-metadata", 0,
		medialibrary.SealedProviderCredential{Ciphertext: sealed, Fingerprint: fingerprint}); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.SetLibraryMetadataPolicy(t.Context(), f.owner.ID, library.ID, "episode-policy", 0,
		medialibrary.LibraryMetadataPolicy{Mode: medialibrary.MetadataOnlineFirst, ConnectionID: connection, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	authority, err := f.store.CheckProviderUse(t.Context(), f.owner.ID, library.ID, connection)
	if err != nil {
		t.Fatal(err)
	}
	structure, err := f.store.GetItemStructure(t.Context(), f.owner.ID, library.ID, seriesID)
	if err != nil {
		t.Fatal(err)
	}
	document := medialibrary.ProviderMatchDocument{
		Item: mediametadata.Item{Kind: mediametadata.TVShow, Fields: map[string][]string{"title": {"在线剧"}}},
		Episodes: []medialibrary.ProviderEpisodeDocument{{
			SeasonNumber: 1, EpisodeNumber: 1,
			Item: mediametadata.Item{Kind: mediametadata.Episode, Fields: map[string][]string{
				"title": {"第一集"}, "runtime": {"52"}, "aired": {"2024-03-01"},
			}},
		}},
	}
	request := medialibrary.ProviderMatchRequest{LibraryID: library.ID, ItemID: seriesID, ConnectionID: connection,
		Kind: "series", ExternalID: 55, ItemRevision: seriesPage.Items[0].Revision, StructureRevision: structure.Revision}
	if _, err := f.store.ConfirmProviderMatch(t.Context(), f.owner.ID, "episode-match", request, *authority, "zh-CN", time.Now().UnixMilli(), document,
		medialibrary.ProviderMatchAudit{Source: "automatic", Region: "CN", AdapterVersion: "tmdb-v1"}); err != nil {
		t.Fatal(err)
	}
	response := f.call(t, f.owner.ID, http.MethodGet,
		"/api/media-library/v1/libraries/"+library.ID+"/items/"+episodes.Items[0].EpisodeID, nil)
	var detail mediaItemDetail
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &detail) != nil {
		t.Fatalf("episode detail: %d %s", response.Code, response.Body)
	}
	if detail.Item.Title != "第一集" || detail.Fields["runtime"].Source != "online" ||
		len(detail.Fields["runtime"].Values) != 1 || detail.Fields["runtime"].Values[0] != "52" || detail.OnlineMatch != nil {
		t.Fatalf("episode provider projection: %+v", detail)
	}
}
