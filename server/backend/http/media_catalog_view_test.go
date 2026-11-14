package fbhttp

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/golang-jwt/jwt/v5"
)

func catalogFixtureWrite(t *testing.T, f *mediaScanHTTPFixture, name, content string) {
	t.Helper()
	full := filepath.Join(f.root, "films", filepath.FromSlash(name))
	if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestCatalogDeviceCredentialDoesNotAdvertiseManagement(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "video.mkv", "video")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-device-scan")
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(spki)
	deviceID := hex.EncodeToString(digest[:16])
	public := base64.RawURLEncoding.EncodeToString(spki)
	pair, err := f.h.storage.Devices.Create(deviceID, "Catalog TV", public)
	if err != nil {
		t.Fatal(err)
	}
	if err = f.h.storage.Devices.Approve(pair.ID, deviceID, public, f.owner.ID); err != nil {
		t.Fatal(err)
	}
	identity, err := f.h.storage.Devices.Identity()
	if err != nil {
		t.Fatal(err)
	}
	nonce := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	message := sha256.Sum256(deviceauth.Message("exchange", identity.ID, pair.ID, pair.Challenge, nonce))
	signature, err := ecdsa.SignASN1(rand.Reader, key, message[:])
	if err != nil {
		t.Fatal(err)
	}
	credential, err := f.h.storage.Devices.Exchange(pair.ID, nonce, base64.RawURLEncoding.EncodeToString(signature))
	if err != nil {
		t.Fatal(err)
	}
	signing, err := f.h.storage.Devices.SigningKey()
	if err != nil {
		t.Fatal(err)
	}
	claims := authToken{User: userInfo{ID: f.owner.ID}, DeviceSession: credential.Session.ID, Scope: "media", RegisteredClaims: jwt.RegisteredClaims{IssuedAt: jwt.NewNumericDate(time.Now()), ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour))}}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	token.Header["kid"] = "fileway.device.v1"
	signed, err := token.SignedString(signing)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodGet, "/api/media-library/v1/catalog/items", nil)
	request.Header.Set("X-Auth", signed)
	response := httptest.NewRecorder()
	f.router.ServeHTTP(response, request)
	var page catalogCardPage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 {
		t.Fatalf("device catalog: %d %s", response.Code, response.Body)
	}
	for _, action := range []string{"editMetadata", "manageSources", "manageMembers"} {
		if page.Items[0].CanActions[action] || page.Items[0].ActionReasons[action] != "not_granted" {
			t.Fatalf("media credential advertised %s", action)
		}
	}
	if !page.Items[0].CanActions["play"] {
		t.Fatal("device lost authorized playback")
	}
	homeRequest := httptest.NewRequest(http.MethodGet, "/api/media-library/v1/home", nil)
	homeRequest.Header.Set("X-Auth", signed)
	homeResponse := httptest.NewRecorder()
	f.router.ServeHTTP(homeResponse, homeRequest)
	var home catalogHome
	if homeResponse.Code != http.StatusOK || json.Unmarshal(homeResponse.Body.Bytes(), &home) != nil || home.CanActions["createLibrary"] || home.ActionReasons["createLibrary"] != "not_granted" {
		t.Fatal("device home advertised library management")
	}
}

func TestCatalogCursorAuthenticatesScopeQueryAndExpiry(t *testing.T) {
	key := []byte("catalog fixture key")
	value := catalogCursor{Version: 1, Actor: 7, Session: "device", Query: "catalog", Scope: "scope", Expires: time.Now().Add(time.Minute).UnixMilli(), AfterID: "private-candidate"}
	token, err := encodeCatalogCursor(key, value)
	if err != nil || strings.Contains(token, value.AfterID) {
		t.Fatal("cursor leaked candidate or failed to encode")
	}
	if decoded, err := decodeCatalogCursor(key, token, 7, "device", "catalog", "scope"); err != nil || decoded.AfterID != value.AfterID {
		t.Fatal("valid cursor rejected", err)
	}
	first := "A"
	if token[0] == 'A' {
		first = "B"
	}
	if _, err = decodeCatalogCursor(key, first+token[1:], 7, "device", "catalog", "scope"); !errors.Is(err, errCatalogCursor) {
		t.Fatal("tampered cursor accepted", err)
	}
	if _, err = decodeCatalogCursor(key, token, 8, "device", "catalog", "scope"); !errors.Is(err, errCatalogCursor) {
		t.Fatal("wrong actor accepted", err)
	}
	if _, err = decodeCatalogCursor(key, token, 7, "other", "catalog", "scope"); !errors.Is(err, errCatalogCursor) {
		t.Fatal("wrong session accepted", err)
	}
	if _, err = decodeCatalogCursor(key, token, 7, "device", "other", "scope"); !errors.Is(err, errCatalogCursor) {
		t.Fatal("wrong query accepted", err)
	}
	if _, err = decodeCatalogCursor(key, token, 7, "device", "catalog", "changed"); !errors.Is(err, errCatalogCursorStale) {
		t.Fatal("changed scope accepted", err)
	}
	value.Expires = time.Now().Add(-time.Second).UnixMilli()
	expired, err := encodeCatalogCursor(key, value)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = decodeCatalogCursor(key, expired, 7, "device", "catalog", "scope"); !errors.Is(err, errCatalogCursorExpired) {
		t.Fatal("expired cursor accepted", err)
	}
}

func TestCatalogHTTPRealOfflineJourneyAndSourceFiltering(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for name, content := range map[string]string{
		"show/tvshow.nfo":                    `<tvshow><title>Visible series</title><plot>Series private plot</plot></tvshow>`,
		"show/Season 1/season.nfo":           `<season><seasonnumber>1</seasonnumber><title>Season one</title></season>`,
		"show/Season 1/Show.S01E01.mkv":      "one",
		"show/Season 1/Show.S01E01.nfo":      `<episodedetails><title>Visible episode</title><season>1</season><episode>1</episode></episodedetails>`,
		"show/Season 1/Show.S01E01.720p.mkv": "copy",
		"show/Season 1/Show.S01E02E03.mkv":   "combined",
		"film.mkv":                           "movie",
		"film.nfo":                           `<movie><title>Visible movie</title></movie>`,
		"unmatched.mkv":                      "unmatched",
	} {
		catalogFixtureWrite(t, f, name, content)
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-local-scan")
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		_, err := tx.Exec(`UPDATE item_structure SET first_added_at=CASE WHEN item_id IN (SELECT episode_id FROM episode_position WHERE episode_number=1) THEN 500 ELSE 200 END WHERE item_id IN (SELECT episode_id FROM episode_position)`)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	grant := f.call(t, f.owner.ID, http.MethodPut, fmt.Sprintf("/api/media-library/v1/libraries/%s/members/%d", library.ID, f.viewer.ID), map[string]any{"operationId": "catalog-share", "expectedRevision": source.LibraryRevision, "role": "viewer"})
	if grant.Code != http.StatusOK {
		t.Fatal(grant.Code, grant.Body)
	}
	homeResponse := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/home", nil)
	var home catalogHome
	if homeResponse.Code != http.StatusOK || json.Unmarshal(homeResponse.Body.Bytes(), &home) != nil {
		t.Fatalf("home: %d %s", homeResponse.Code, homeResponse.Body)
	}
	if home.State != "ready" || len(home.Libraries) != 1 || home.Hero == nil || len(home.Hero.Items) != 2 || home.Capabilities["watchState"] {
		t.Fatalf("unexpected home: %+v", home)
	}
	unsupported := false
	for _, row := range home.Rows {
		if row.ID == "continue" && row.State == "unsupported" {
			unsupported = true
		}
	}
	if !unsupported {
		t.Fatal("unsupported continue-watching state was omitted")
	}
	var seriesID, movieID, unmatchedID string
	for _, row := range home.Rows {
		if row.ID == "recently-added" {
			for _, card := range row.Items {
				switch card.Title {
				case "Visible series":
					seriesID = card.Ref.ItemID
					if card.Episodes == nil || card.Episodes.CountState != "ready" || card.Episodes.Collected == nil || *card.Episodes.Collected != 3 {
						t.Fatalf("episode count: %+v", card)
					}
				case "Visible movie":
					movieID = card.Ref.ItemID
					if card.StructureKind != "movie" || card.MatchState != "local_confirmed" {
						t.Fatalf("movie classification: %+v", card)
					}
				case "unmatched":
					unmatchedID = card.Ref.ItemID
					if card.StructureKind != "video" || card.MatchState != "unmatched" || !card.CanActions["play"] || card.Size == nil || *card.Size != 9 {
						t.Fatalf("unmatched card: %+v", card)
					}
				}
			}
		}
	}
	if seriesID == "" || movieID == "" || unmatchedID == "" {
		t.Fatalf("missing real cards: %+v", home)
	}
	base := fmt.Sprintf("/api/media-library/v1/libraries/%s/items/%s", library.ID, seriesID)
	detailResponse := f.call(t, f.viewer.ID, http.MethodGet, base, nil)
	var detail mediaItemDetail
	if detailResponse.Code != http.StatusOK || json.Unmarshal(detailResponse.Body.Bytes(), &detail) != nil || detail.Card == nil || detail.Item.Title != "Visible series" || detail.Fields["plot"].Values[0] != "Series private plot" {
		t.Fatalf("series detail: %d %s", detailResponse.Code, detailResponse.Body)
	}
	seasonResponse := f.call(t, f.viewer.ID, http.MethodGet, base+"/seasons", nil)
	var seasons catalogStructurePage[catalogSeason]
	if seasonResponse.Code != http.StatusOK || json.Unmarshal(seasonResponse.Body.Bytes(), &seasons) != nil || len(seasons.Items) != 1 || seasons.Items[0].Number != 1 {
		t.Fatalf("seasons: %d %s", seasonResponse.Code, seasonResponse.Body)
	}
	episodeURL := base + "/episodes?seasonId=" + url.QueryEscape(seasons.Items[0].SeasonID) + "&limit=2"
	first := f.call(t, f.viewer.ID, http.MethodGet, episodeURL, nil)
	var episodes catalogStructurePage[catalogEpisode]
	if first.Code != http.StatusOK || json.Unmarshal(first.Body.Bytes(), &episodes) != nil || len(episodes.Items) != 2 || !episodes.HasMore || episodes.NextCursor == "" {
		t.Fatalf("episodes first: %d %s", first.Code, first.Body)
	}
	if episodes.Items[0].EpisodeNumber != 1 || episodes.Items[1].EpisodeNumber != 2 || episodes.Items[0].Card.Title != "Visible episode" {
		t.Fatalf("episode order: %+v", episodes)
	}
	episodeBase := fmt.Sprintf("/api/media-library/v1/libraries/%s/items/%s", library.ID, episodes.Items[0].EpisodeID)
	variantResponse := f.call(t, f.viewer.ID, http.MethodGet, episodeBase+"/variants", nil)
	var variants struct {
		Items []mediaVariantResponse `json:"items"`
	}
	if variantResponse.Code != http.StatusOK || json.Unmarshal(variantResponse.Body.Bytes(), &variants) != nil || len(variants.Items) == 0 {
		t.Fatalf("episode source unavailable: %d", variantResponse.Code)
	}
	f.h.server.BaseURL = "/prefix"
	resolution := f.call(t, f.viewer.ID, http.MethodPost, episodeBase+"/playback-resolutions", map[string]any{"editionId": variants.Items[0].EditionID, "variantId": variants.Items[0].VariantID, "bindingId": variants.Items[0].BindingID})
	var resolved struct {
		URL      string `json:"url"`
		Endpoint string `json:"endpoint"`
	}
	if resolution.Code != http.StatusOK || json.Unmarshal(resolution.Body.Bytes(), &resolved) != nil {
		t.Fatalf("playback resolution failed: %d", resolution.Code)
	}
	parsed, parseErr := url.Parse(resolved.Endpoint)
	if parseErr != nil || parsed.Host != "" || parsed.Scheme != "" || parsed.Fragment != "" || parsed.User != nil || parsed.RawQuery != "" || !strings.HasPrefix(resolved.Endpoint, "/api/media-library/v1/streams/") || resolved.URL != "/prefix"+resolved.Endpoint {
		t.Fatal("invalid origin-relative URL or service-relative endpoint")
	}
	f.h.server.BaseURL = ""
	stream := f.call(t, f.viewer.ID, http.MethodGet, resolved.Endpoint, nil)
	if stream.Code != http.StatusOK || (stream.Body.String() != "one" && stream.Body.String() != "copy") {
		t.Fatalf("original episode stream failed: %d", stream.Code)
	}
	combined := episodes.Items[1].Playback
	if combined == nil || combined.PlayMode != "combined_file" || combined.IndependentStart || len(combined.CoveredEpisodeIDs) != 2 || combined.Label != "第 2–3 集合并文件" {
		t.Fatalf("combined semantics: %+v", combined)
	}
	second := f.call(t, f.viewer.ID, http.MethodGet, episodeURL+"&cursor="+url.QueryEscape(episodes.NextCursor), nil)
	var last catalogStructurePage[catalogEpisode]
	if second.Code != http.StatusOK || json.Unmarshal(second.Body.Bytes(), &last) != nil || len(last.Items) != 1 || last.Items[0].EpisodeNumber != 3 || last.HasMore {
		t.Fatalf("episodes second: %d %s", second.Code, second.Body)
	}
	patch := f.call(t, f.owner.ID, http.MethodPatch, base+"/metadata", map[string]any{"operationId": "series-manual", "expectedRevision": detail.Item.Revision, "fields": map[string]any{"tagline": map[string]any{"values": []string{"Own note"}}}})
	var manual struct {
		Revision         int64 `json:"revision"`
		MetadataRevision int64 `json:"metadataRevision"`
	}
	if patch.Code != http.StatusOK || json.Unmarshal(patch.Body.Bytes(), &manual) != nil {
		t.Fatalf("series manual edit failed: %d %s", patch.Code, patch.Body)
	}
	// Source permissions are evaluated independently for the parent NFO,
	// leaf NFO and video; the authorized episode remains reachable.
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Rules = []rules.Rule{{Path: "/films/show/tvshow.nfo", Allow: false}, {Path: "/films/film.nfo", Allow: false}}
	if err = f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	restricted := f.call(t, f.viewer.ID, http.MethodGet, base, nil)
	if restricted.Code != http.StatusOK || strings.Contains(restricted.Body.String(), "Visible series") || strings.Contains(restricted.Body.String(), "Series private plot") {
		t.Fatalf("parent NFO leaked: %d %s", restricted.Code, restricted.Body)
	}
	var restrictedDetail mediaItemDetail
	if err = json.Unmarshal(restricted.Body.Bytes(), &restrictedDetail); err != nil || restrictedDetail.Item.Title != "剧集" || restrictedDetail.Card == nil || restrictedDetail.Card.MatchState != "unmatched" {
		t.Fatalf("safe parent fallback: %+v %v", restrictedDetail, err)
	}
	undo := f.call(t, f.owner.ID, http.MethodPost, base+"/metadata/undo", map[string]any{"operationId": "series-hidden-undo", "expectedRevision": manual.Revision, "targetRevision": manual.MetadataRevision})
	if undo.Code != http.StatusForbidden {
		t.Fatalf("hidden parent metadata undo was accepted: %d %s", undo.Code, undo.Body)
	}
	movies := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/catalog/items?structureKind=movie", nil)
	var moviePage catalogCardPage
	if movies.Code != http.StatusOK || json.Unmarshal(movies.Body.Bytes(), &moviePage) != nil || len(moviePage.Items) != 0 {
		t.Fatalf("hidden NFO influenced movie filter: %d %s", movies.Code, movies.Body)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, episodeURL, nil); r.Code != http.StatusOK || !strings.Contains(r.Body.String(), "Visible episode") {
		t.Fatalf("visible episode lost: %d %s", r.Code, r.Body)
	}
	owner.Rules = []rules.Rule{{Path: "/films/film.mkv", Allow: false}, {Path: "/films/show/tvshow.nfo", Allow: false}}
	if err = f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, fmt.Sprintf("/api/media-library/v1/libraries/%s/items/%s", library.ID, movieID), nil); r.Code != http.StatusNotFound {
		t.Fatalf("revoked video visible: %d %s", r.Code, r.Body)
	}
	owner.Rules = []rules.Rule{{Path: "/films/show/Season 1/Show.S01E01.mkv", Allow: false}, {Path: "/films/show/Season 1/Show.S01E01.720p.mkv", Allow: false}}
	if err = f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	filteredSeasons := f.call(t, f.viewer.ID, http.MethodGet, base+"/seasons", nil)
	var filtered catalogStructurePage[catalogSeason]
	if filteredSeasons.Code != http.StatusOK || json.Unmarshal(filteredSeasons.Body.Bytes(), &filtered) != nil || len(filtered.Items) != 1 || filtered.Items[0].Card.Episodes == nil || filtered.Items[0].Card.Episodes.Collected == nil || *filtered.Items[0].Card.Episodes.Collected != 2 {
		t.Fatalf("count included denied episode: %d %s", filteredSeasons.Code, filteredSeasons.Body)
	}
	updates := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/home/rows/recently-updated-series", nil)
	var updateRow catalogHomeRow
	if updates.Code != http.StatusOK || json.Unmarshal(updates.Body.Bytes(), &updateRow) != nil || len(updateRow.Items) != 1 || updateRow.Items[0].SeriesUpdatedAt == nil || *updateRow.Items[0].SeriesUpdatedAt != 200 {
		t.Fatalf("hidden episode affected series update: %d %s", updates.Code, updates.Body)
	}
	owner.Rules = nil
	if err = f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	if err = os.Remove(filepath.Join(f.root, "films", "film.nfo")); err != nil {
		t.Fatal(err)
	}
	offlineMetadata := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/catalog/items?structureKind=movie", nil)
	var metadataPage catalogCardPage
	if offlineMetadata.Code != http.StatusOK || json.Unmarshal(offlineMetadata.Body.Bytes(), &metadataPage) != nil || len(metadataPage.Items) != 1 || metadataPage.Items[0].MetadataState != "offline" || metadataPage.Items[0].Availability != "available" {
		t.Fatalf("offline metadata status incorrect: %d %s", offlineMetadata.Code, offlineMetadata.Body)
	}
	offlineDetail := f.call(t, f.viewer.ID, http.MethodGet, fmt.Sprintf("/api/media-library/v1/libraries/%s/items/%s", library.ID, movieID), nil)
	var metadataDetail mediaItemDetail
	if offlineDetail.Code != http.StatusOK || json.Unmarshal(offlineDetail.Body.Bytes(), &metadataDetail) != nil || metadataDetail.PageState != "partial_metadata" || metadataDetail.Card == nil || metadataDetail.Card.MetadataState != "offline" {
		t.Fatalf("offline metadata detail state: %d %s", offlineDetail.Code, offlineDetail.Body)
	}
	if err = os.Remove(filepath.Join(f.root, "films", "film.mkv")); err != nil {
		t.Fatal(err)
	}
	scanFixtureLibrary(t, f, library, source, "catalog-offline-scan")
	offline := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/catalog/items?structureKind=movie", nil)
	var offlinePage catalogCardPage
	if offline.Code != http.StatusOK || json.Unmarshal(offline.Body.Bytes(), &offlinePage) != nil || len(offlinePage.Items) != 1 || offlinePage.Items[0].Availability != "offline" || offlinePage.Items[0].CanActions["play"] {
		t.Fatalf("offline item lost or marked playable: %d %s", offline.Code, offline.Body)
	}
}

func TestCatalogDeviceRouteAndUnauthorizedState(t *testing.T) {
	for _, path := range []string{"/api/media-library/v1/home", "/api/media-library/v1/home/rows/recently-added", "/api/media-library/v1/catalog/items", "/api/media-library/v1/libraries/lib/items/series/seasons", "/api/media-library/v1/libraries/lib/items/series/episodes", "/api/media-library/v1/libraries/lib/items/series/episode-counts"} {
		if !deviceMediaRoute(http.MethodGet, path, 7) {
			t.Fatalf("device route not permitted: %s", path)
		}
		if deviceMediaRoute(http.MethodPost, path, 7) {
			t.Fatalf("device mutation permitted: %s", path)
		}
	}
	f := newMediaScanHTTPFixture(t)
	response := f.call(t, 0, http.MethodGet, "/api/media-library/v1/home", nil)
	if response.Code != http.StatusUnauthorized || !strings.Contains(response.Body.String(), `"code":"unauthorized"`) {
		t.Fatalf("unauthorized state: %d %s", response.Code, response.Body)
	}
	empty := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/home", nil)
	var home catalogHome
	if empty.Code != http.StatusOK || json.Unmarshal(empty.Body.Bytes(), &home) != nil || home.State != "no_libraries" || !home.CanActions["createLibrary"] {
		t.Fatalf("no library state: %d %s", empty.Code, empty.Body)
	}
	invalid := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/catalog/items?sort=title", nil)
	if invalid.Code != http.StatusUnprocessableEntity || !strings.Contains(invalid.Body.String(), `"code":"invalid_request"`) {
		t.Fatalf("unsupported sort accepted: %d %s", invalid.Code, invalid.Body)
	}
	actions, reasons := catalogActions(&medialibrary.Library{Role: medialibrary.RoleOwner}, "series", false, true, "missing")
	for _, action := range []string{"editMetadata", "manageSources", "manageMembers"} {
		if actions[action] || reasons[action] != "not_granted" {
			t.Fatalf("device claimed unavailable action: %s", action)
		}
	}
}

func TestCatalogLongSeriesDefersCardCountAndCompletesSeparateCount(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "long/tvshow.nfo", `<tvshow><title>Long series</title></tvshow>`)
	catalogFixtureWrite(t, f, "long/Long.S01E001-E100.mkv", "first combined file")
	catalogFixtureWrite(t, f, "long/Long.S01E101-E200.mkv", "second combined file")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-long-scan")
	response := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/catalog/items?structureKind=series", nil)
	var page catalogCardPage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 1 {
		t.Fatalf("long series page: %d %s", response.Code, response.Body)
	}
	counts := page.Items[0].Episodes
	if counts == nil || counts.CountState != "deferred" || counts.Collected != nil || counts.Endpoint == "" {
		t.Fatalf("long count was fabricated or blocking: %+v", counts)
	}
	result := f.call(t, f.owner.ID, http.MethodGet, counts.Endpoint, nil)
	var exact catalogEpisodeCounts
	if result.Code != http.StatusOK || json.Unmarshal(result.Body.Bytes(), &exact) != nil || exact.CountState != "ready" || exact.Collected == nil || *exact.Collected != 200 || exact.Available == nil || *exact.Available != 200 {
		t.Fatalf("separate authorized count: %d %s", result.Code, result.Body)
	}
}

func TestCatalogUpdatesSortOnlyVisibleEpisodes(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "a/tvshow.nfo", `<tvshow><title>Series A</title></tvshow>`)
	catalogFixtureWrite(t, f, "a/A.S01E01.mkv", "one")
	catalogFixtureWrite(t, f, "a/A.S01E02.mkv", "hidden newer")
	catalogFixtureWrite(t, f, "b/tvshow.nfo", `<tvshow><title>Series B</title></tvshow>`)
	catalogFixtureWrite(t, f, "b/B.S01E01.mkv", "middle")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-update-sort")
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		_, err := tx.Exec(`UPDATE item_structure SET first_added_at=CASE
WHEN item_id IN (SELECT ep.episode_id FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id JOIN library_item s ON s.id=eo.series_id WHERE s.title='Series A' AND ep.episode_number=1) THEN 100
WHEN item_id IN (SELECT ep.episode_id FROM episode_position ep JOIN episode_order eo ON eo.id=ep.order_id JOIN library_item s ON s.id=eo.series_id WHERE s.title='Series A' AND ep.episode_number=2) THEN 900
ELSE 500 END WHERE item_id IN (SELECT episode_id FROM episode_position)`)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Rules = []rules.Rule{{Path: "/films/a/A.S01E02.mkv", Allow: false}}
	if err = f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	response := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/home/rows/recently-updated-series", nil)
	var row catalogHomeRow
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &row) != nil || len(row.Items) != 2 || row.Items[0].Title != "Series B" || row.Items[1].Title != "Series A" || row.Items[1].SeriesUpdatedAt == nil || *row.Items[1].SeriesUpdatedAt != 100 {
		t.Fatalf("hidden episode changed sort or timestamp: %d %s", response.Code, response.Body)
	}
}

func TestCatalogCursorPartialAndScopeBinding(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "visible.mkv", "visible")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-budget-scan")
	// Hidden candidates have no source mappings. They are never exposed in the
	// encrypted continuation, and cannot cause an empty page to look complete.
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		for i := 0; i < 1001; i++ {
			if _, err := tx.Exec(`INSERT INTO library_item(id,library_id,kind,title,created_at) VALUES(?,?,'video','hidden',1)`, fmt.Sprintf("000hidden%04d", i), library.ID); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	urlPath := "/api/media-library/v1/catalog/items?limit=1"
	first := f.call(t, f.owner.ID, http.MethodGet, urlPath, nil)
	var page catalogCardPage
	if first.Code != http.StatusOK || json.Unmarshal(first.Body.Bytes(), &page) != nil || page.PageState != "partial" || !page.HasMore || page.NextCursor == "" || len(page.Items) != 0 {
		t.Fatalf("partial page: %d %s", first.Code, first.Body)
	}
	if strings.Contains(page.NextCursor, "000hidden") || strings.Contains(first.Body.String(), "000hidden") {
		t.Fatalf("hidden cursor leaked: %s", first.Body)
	}
	second := f.call(t, f.owner.ID, http.MethodGet, urlPath+"&cursor="+url.QueryEscape(page.NextCursor), nil)
	var final catalogCardPage
	if second.Code != http.StatusOK || json.Unmarshal(second.Body.Bytes(), &final) != nil || len(final.Items) != 1 || final.Items[0].Title != "visible" {
		t.Fatalf("partial continuation: %d %s", second.Code, second.Body)
	}
	bad := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/catalog/items?limit=2&cursor="+url.QueryEscape(page.NextCursor), nil)
	if bad.Code != http.StatusBadRequest || !strings.Contains(bad.Body.String(), "cursor_invalid") {
		t.Fatalf("query-mixed cursor accepted: %d %s", bad.Code, bad.Body)
	}
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Rules = []rules.Rule{{Path: "/films/visible.mkv", Allow: false}}
	if err = f.h.storage.Users.Update(owner, "Rules"); err != nil {
		t.Fatal(err)
	}
	stale := f.call(t, f.owner.ID, http.MethodGet, urlPath+"&cursor="+url.QueryEscape(page.NextCursor), nil)
	if stale.Code != http.StatusConflict || !strings.Contains(stale.Body.String(), "cursor_stale") {
		t.Fatalf("scope-changed cursor accepted: %d %s", stale.Code, stale.Body)
	}
}

func TestCatalogPaginationContinuesDuringActiveAppendScan(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for _, name := range []string{"first-a.mkv", "first-b.mkv", "first-c.mkv"} {
		catalogFixtureWrite(t, f, name, "initial")
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-initial-scan")
	for i := 0; i < 300; i++ {
		catalogFixtureWrite(t, f, fmt.Sprintf("added-%03d.mkv", i), "new")
	}
	response := f.call(t, f.owner.ID, http.MethodPost, "/api/media-library/v1/libraries/"+library.ID+"/scans", map[string]any{"bindingId": source.ID, "operationId": "catalog-active-scan", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision})
	var job mediaScanSummary
	if response.Code != http.StatusAccepted || json.Unmarshal(response.Body.Bytes(), &job) != nil {
		t.Fatalf("enqueue: %d %s", response.Code, response.Body)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	waitProgress := func(minimum int64) mediaScanSummary {
		t.Helper()
		for {
			current := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/libraries/"+library.ID+"/scans/"+job.ID, nil)
			var state mediaScanSummary
			if current.Code != http.StatusOK || json.Unmarshal(current.Body.Bytes(), &state) != nil {
				t.Fatalf("scan state: %d %s", current.Code, current.Body)
			}
			if state.Status == "running" && state.Processed >= minimum {
				return state
			}
			if state.Status == "failed" || state.Status == "canceled" || state.Status == "completed" {
				t.Fatalf("scan did not stay active long enough: %+v", state)
			}
			select {
			case <-ctx.Done():
				t.Fatal("scan progress timeout")
			case <-ticker.C:
			}
		}
	}
	initial := waitProgress(1)
	homeResponse := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/home", nil)
	var home catalogHome
	if homeResponse.Code != http.StatusOK || json.Unmarshal(homeResponse.Body.Bytes(), &home) != nil {
		t.Fatalf("home while scanning: %d %s", homeResponse.Code, homeResponse.Body)
	}
	current, err := f.store.GetScan(t.Context(), f.owner.ID, job.ID)
	if err != nil || current.Status != "running" || home.State != "scanning" && home.State != "partial" {
		t.Fatalf("active scan not represented: home=%s job=%+v err=%v", home.State, current, err)
	}
	shown := false
	for _, row := range home.Rows {
		if row.ID == "recently-added" && len(row.Items) > 0 {
			shown = true
		}
	}
	if !shown {
		t.Fatal("existing content disappeared during scan")
	}
	path := "/api/media-library/v1/catalog/items?sort=newest&limit=1"
	first := f.call(t, f.owner.ID, http.MethodGet, path, nil)
	var page catalogCardPage
	if first.Code != http.StatusOK || json.Unmarshal(first.Body.Bytes(), &page) != nil || len(page.Items) != 1 || page.NextCursor == "" {
		t.Fatalf("first page while scanning: %d %s", first.Code, first.Body)
	}
	waitProgress(initial.Processed + 1)
	second := f.call(t, f.owner.ID, http.MethodGet, path+"&cursor="+url.QueryEscape(page.NextCursor), nil)
	var next catalogCardPage
	if second.Code != http.StatusOK || json.Unmarshal(second.Body.Bytes(), &next) != nil || len(next.Items) != 1 || next.Items[0].Ref.ItemID == page.Items[0].Ref.ItemID {
		t.Fatalf("pagination broke during append: %d %s", second.Code, second.Body)
	}
}

func TestSeasonCursorRejectsOrderRevisionChange(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "show/tvshow.nfo", `<tvshow><title>Series</title></tvshow>`)
	catalogFixtureWrite(t, f, "show/Show.S00E01.mkv", "special")
	catalogFixtureWrite(t, f, "show/Show.S01E01.mkv", "regular")
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "catalog-order-scan")
	items := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/catalog/items?structureKind=series", nil)
	var page catalogCardPage
	if items.Code != http.StatusOK || json.Unmarshal(items.Body.Bytes(), &page) != nil || len(page.Items) != 1 {
		t.Fatalf("series: %d %s", items.Code, items.Body)
	}
	path := fmt.Sprintf("/api/media-library/v1/libraries/%s/items/%s/seasons?limit=1", library.ID, page.Items[0].Ref.ItemID)
	first := f.call(t, f.owner.ID, http.MethodGet, path, nil)
	var seasons catalogStructurePage[catalogSeason]
	if first.Code != http.StatusOK || json.Unmarshal(first.Body.Bytes(), &seasons) != nil || len(seasons.Items) != 1 || seasons.NextCursor == "" {
		t.Fatalf("season first page: %d %s", first.Code, first.Body)
	}
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		_, err := tx.Exec(`UPDATE episode_order SET revision=revision+1 WHERE id=?`, seasons.OrderID)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	next := f.call(t, f.owner.ID, http.MethodGet, path+"&cursor="+url.QueryEscape(seasons.NextCursor), nil)
	if next.Code != http.StatusConflict || !strings.Contains(next.Body.String(), "cursor_stale") {
		t.Fatalf("changed order accepted: %d %s", next.Code, next.Body)
	}
}
