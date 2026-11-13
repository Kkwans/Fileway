package fbhttp

import (
	"bytes"
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
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/golang-jwt/jwt/v5"
	"github.com/spf13/afero"
)

type mediaScanHTTPFixture struct {
	h           *trashHTTPHarness
	owner       *users.User
	viewer      *users.User
	store       *medialibrary.Store
	catalogPath string
	root        string
	router      http.Handler
}

func newMediaScanHTTPFixture(t *testing.T) *mediaScanHTTPFixture {
	t.Helper()
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}}, users.User{Username: "viewer", Perm: users.Permissions{Download: true}})
	owner, viewer := trashHTTPUserByName(t, h, "owner"), trashHTTPUserByName(t, h, "viewer")
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "films"), 0o700); err != nil {
		t.Fatal(err)
	}
	h.server.Root = root
	h.fs[owner.ID] = afero.NewBasePathFs(afero.NewOsFs(), root)
	h.fs[viewer.ID] = afero.NewBasePathFs(afero.NewOsFs(), root)
	catalogPath := filepath.Join(t.TempDir(), "catalog.db")
	store, err := medialibrary.Open(t.Context(), catalogPath)
	if err != nil {
		t.Fatal(err)
	}
	h.storage.MediaLibrary = store
	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	})
	router, err := NewHandler(nil, noopTrashFileCache{}, nil, h.storage, h.server, fstest.MapFS{}, 1, newHTTPHLSService(t, false), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := router.(io.Closer).Close(); err != nil {
			t.Error(err)
		}
	})
	return &mediaScanHTTPFixture{h: h, owner: owner, viewer: viewer, store: store, catalogPath: catalogPath, root: root, router: router}
}

func (f *mediaScanHTTPFixture) call(t *testing.T, actor uint, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		reader = bytes.NewReader(encoded)
	}
	request := httptest.NewRequest(method, path, reader)
	if actor != 0 {
		request.Header.Set("X-Auth", signedTrashHTTPToken(t, actor))
	}
	response := httptest.NewRecorder()
	f.router.ServeHTTP(response, request)
	return response
}

func (f *mediaScanHTTPFixture) libraryAndSource(t *testing.T) (mediaLibrarySummary, mediaLibrarySourceSummary) {
	t.Helper()
	libraries := "/api/media-library/v1/libraries"
	create := map[string]any{"operationId": "create-library-1", "name": "Fixture cinema"}
	response := f.call(t, f.owner.ID, http.MethodPost, libraries, create)
	if response.Code != http.StatusCreated {
		t.Fatalf("create library: %d %s", response.Code, response.Body)
	}
	var library mediaLibrarySummary
	if err := json.Unmarshal(response.Body.Bytes(), &library); err != nil {
		t.Fatal(err)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, libraries, create); replay.Code != http.StatusCreated || replay.Body.String() != response.Body.String() {
		t.Fatalf("library replay: %d %s", replay.Code, replay.Body)
	}
	conflict := f.call(t, f.owner.ID, http.MethodPost, libraries, map[string]any{"operationId": "create-library-1", "name": "Other"})
	if conflict.Code != http.StatusConflict {
		t.Fatalf("library operation collision: %d %s", conflict.Code, conflict.Body)
	}
	sources := libraries + "/" + library.ID + "/sources/local"
	sourceBody := map[string]any{"operationId": "create-source-1", "expectedRevision": library.Revision, "wireRoot": "/films"}
	response = f.call(t, f.owner.ID, http.MethodPost, sources, sourceBody)
	if response.Code != http.StatusCreated {
		t.Fatalf("create source: %d %s", response.Code, response.Body)
	}
	var source mediaLibrarySourceSummary
	if err := json.Unmarshal(response.Body.Bytes(), &source); err != nil {
		t.Fatal(err)
	}
	if source.ID == "" || source.WireRoot != "/films" || source.LibraryRevision <= library.Revision {
		t.Fatalf("source binding: %+v", source)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, sources, sourceBody); replay.Code != http.StatusCreated || replay.Body.String() != response.Body.String() {
		t.Fatalf("source replay: %d %s", replay.Code, replay.Body)
	}
	if forged := f.call(t, f.owner.ID, http.MethodPost, sources, map[string]any{"operationId": "forged", "expectedRevision": source.LibraryRevision, "wireRoot": "/films", "ownerId": f.viewer.ID}); forged.Code != http.StatusBadRequest {
		t.Fatalf("unexpected source parameter accepted: %d %s", forged.Code, forged.Body)
	}
	return library, source
}

func (f *mediaScanHTTPFixture) waitJob(t *testing.T, libraryID, jobID string, accepted ...string) mediaScanSummary {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
	defer cancel()
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		response := f.call(t, f.owner.ID, http.MethodGet, "/api/media-library/v1/libraries/"+libraryID+"/scans/"+jobID, nil)
		if response.Code != http.StatusOK {
			t.Fatalf("read scan: %d %s", response.Code, response.Body)
		}
		var job mediaScanSummary
		if err := json.Unmarshal(response.Body.Bytes(), &job); err != nil {
			t.Fatal(err)
		}
		for _, status := range accepted {
			if job.Status == status {
				return job
			}
		}
		if job.Status == "failed" || job.Status == "canceled" {
			t.Fatalf("unexpected job state: %+v", job)
		}
		select {
		case <-ctx.Done():
			t.Fatalf("scan timed out: %+v", job)
		case <-ticker.C:
		}
	}
}

func TestMediaLibraryHTTPRealScanJourneyAndOwnerBoundary(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for name, content := range map[string]string{"a.mp4": "video A", "b.mkv": "video B", "b.nfo": "<movie><title>"} {
		if err := os.WriteFile(filepath.Join(f.root, "films", name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID
	sources := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil)
	if sources.Code != http.StatusOK || !strings.Contains(sources.Body.String(), source.ID) || strings.Contains(sources.Body.String(), localMediaWorkspaceConfiguration) {
		t.Fatalf("owner source listing: %d %s", sources.Code, sources.Body)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, base+"/sources", nil); r.Code != http.StatusForbidden && r.Code != http.StatusNotFound {
		t.Fatalf("source leak: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, 0, http.MethodPost, base+"/scans", map[string]any{}); r.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous scan: %d", r.Code)
	}
	body := map[string]any{"bindingId": source.ID, "operationId": "scan-1", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision}
	response := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", body)
	if response.Code != http.StatusAccepted {
		t.Fatalf("scan enqueue: %d %s", response.Code, response.Body)
	}
	var job mediaScanSummary
	if err := json.Unmarshal(response.Body.Bytes(), &job); err != nil || job.ID == "" {
		t.Fatalf("scan response: %+v %v", job, err)
	}
	if strings.Contains(response.Body.String(), "/films") || strings.Contains(response.Body.String(), "lastObjectKey") || strings.Contains(response.Body.String(), localMediaWorkspaceConfiguration) {
		t.Fatalf("scan response leaked internal identity: %s", response.Body)
	}
	completed := f.waitJob(t, library.ID, job.ID, "completed")
	if completed.Processed != 2 {
		t.Fatalf("scan counts: %+v", completed)
	}
	page, err := f.store.ListCatalogItems(t.Context(), f.owner.ID, library.ID, medialibrary.CatalogListOptions{})
	if err != nil || len(page.Items) != 2 {
		t.Fatalf("catalog: %+v %v", page, err)
	}
	replay := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", body)
	var same mediaScanSummary
	if replay.Code != http.StatusAccepted || json.Unmarshal(replay.Body.Bytes(), &same) != nil || same.ID != job.ID {
		t.Fatalf("scan replay: %d %s", replay.Code, replay.Body)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, base+"/scans/"+job.ID, nil); r.Code != http.StatusNotFound {
		t.Fatalf("foreign job visible: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", map[string]any{"bindingId": source.ID, "operationId": "forged-scan", "expectedRevision": source.Revision, "rulesRevision": 1, "root": "/"}); r.Code != http.StatusBadRequest {
		t.Fatalf("arbitrary root accepted: %d %s", r.Code, r.Body)
	}
}

func TestMediaLibraryHTTPSourceRulesAndRevision(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID
	if r := f.call(t, f.owner.ID, http.MethodPost, base+"/sources/local", map[string]any{"operationId": "private-root", "expectedRevision": source.LibraryRevision, "wireRoot": "/.fileway-media"}); r.Code != http.StatusForbidden {
		t.Fatalf("private root: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", map[string]any{"bindingId": source.ID, "operationId": "stale-scan", "expectedRevision": source.Revision + 1, "rulesRevision": mediascan.LocalRulesRevision}); r.Code != http.StatusConflict {
		t.Fatalf("stale binding revision accepted: %d %s", r.Code, r.Body)
	}
	before := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil)
	if before.Code != 200 || !strings.Contains(before.Body.String(), fmt.Sprintf(`"id":"%s"`, source.ID)) {
		t.Fatal("unexpected sources")
	}
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Perm.Download = false
	if err := f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	after := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil)
	if after.Code != 200 || strings.Contains(after.Body.String(), "/films") || !strings.Contains(after.Body.String(), `"access":"unavailable"`) {
		t.Fatalf("revoked source leaked: %d %s", after.Code, after.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", map[string]any{"bindingId": source.ID, "operationId": "revoked-scan", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision}); r.Code != http.StatusForbidden {
		t.Fatalf("revoked source scanned: %d %s", r.Code, r.Body)
	}
}

func TestMediaLibraryHTTPRejectsDeviceManagementCredentials(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
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
	request, err := f.h.storage.Devices.Create(deviceID, "Fixture TV", public)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.h.storage.Devices.Approve(request.ID, deviceID, public, f.owner.ID); err != nil {
		t.Fatal(err)
	}
	identity, err := f.h.storage.Devices.Identity()
	if err != nil {
		t.Fatal(err)
	}
	nonce := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	sum := sha256.Sum256(deviceauth.Message("exchange", identity.ID, request.ID, request.Challenge, nonce))
	signature, err := ecdsa.SignASN1(rand.Reader, key, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	credential, err := f.h.storage.Devices.Exchange(request.ID, nonce, base64.RawURLEncoding.EncodeToString(signature))
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
	for _, target := range []struct{ method, path string }{
		{http.MethodPost, "/api/media-library/v1/libraries"},
		{http.MethodGet, "/api/media-library/v1/libraries/owned/sources"},
		{http.MethodPost, "/api/media-library/v1/libraries/owned/sources/local"},
		{http.MethodPost, "/api/media-library/v1/libraries/owned/scans"},
		{http.MethodGet, "/api/media-library/v1/libraries/owned/scans/job"},
		{http.MethodPost, "/api/media-library/v1/libraries/owned/scans/job/cancel"},
	} {
		req := httptest.NewRequest(target.method, target.path, strings.NewReader("{}"))
		req.Header.Set("X-Auth", signed)
		response := httptest.NewRecorder()
		f.router.ServeHTTP(response, req)
		if response.Code != http.StatusForbidden {
			t.Fatalf("device management accepted: %s %s -> %d", target.method, target.path, response.Code)
		}
	}
}

func TestMediaLibraryHTTPManagementReceiptsAndMemberVisibility(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID
	update := map[string]any{"operationId": "rename", "expectedRevision": source.LibraryRevision, "name": "Renamed"}
	r := f.call(t, f.owner.ID, http.MethodPatch, base, update)
	var renamed mediaLibrarySummary
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &renamed) != nil || renamed.Name != "Renamed" {
		t.Fatalf("rename: %d %s", r.Code, r.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPatch, base, update); replay.Code != http.StatusOK || replay.Body.String() != r.Body.String() {
		t.Fatalf("rename replay: %d %s", replay.Code, replay.Body)
	}
	members := fmt.Sprintf("%s/members/%d", base, f.viewer.ID)
	grant := map[string]any{"operationId": "grant", "expectedRevision": renamed.Revision, "role": "viewer"}
	r = f.call(t, f.owner.ID, http.MethodPut, members, grant)
	var member struct {
		LibraryRevision int64 `json:"libraryRevision"`
	}
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &member) != nil || member.LibraryRevision <= renamed.Revision {
		t.Fatalf("grant: %d %s", r.Code, r.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPut, members, grant); replay.Code != http.StatusOK || replay.Body.String() != r.Body.String() {
		t.Fatalf("grant replay: %d %s", replay.Code, replay.Body)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, base, nil); r.Code != http.StatusOK {
		t.Fatalf("shared library inaccessible: %d", r.Code)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, base+"/sources", nil); r.Code != http.StatusNotFound && r.Code != http.StatusForbidden {
		t.Fatalf("viewer received source settings: %d %s", r.Code, r.Body)
	}
	revoke := map[string]any{"operationId": "revoke", "expectedRevision": member.LibraryRevision}
	r = f.call(t, f.owner.ID, http.MethodDelete, members, revoke)
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &member) != nil {
		t.Fatalf("revoke: %d %s", r.Code, r.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodDelete, members, revoke); replay.Code != http.StatusOK || replay.Body.String() != r.Body.String() {
		t.Fatalf("revoke replay: %d %s", replay.Code, replay.Body)
	}
	archive := map[string]any{"operationId": "archive", "expectedRevision": member.LibraryRevision}
	r = f.call(t, f.owner.ID, http.MethodPost, base+"/archive", archive)
	var archived mediaLibrarySummary
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &archived) != nil || !archived.Archived {
		t.Fatalf("archive: %d %s", r.Code, r.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, base+"/archive", archive); replay.Code != http.StatusOK || replay.Body.String() != r.Body.String() {
		t.Fatalf("archive replay: %d %s", replay.Code, replay.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil); r.Code != http.StatusConflict {
		t.Fatalf("archived source listing: %d %s", r.Code, r.Body)
	}
	restore := map[string]any{"operationId": "restore", "expectedRevision": archived.Revision}
	r = f.call(t, f.owner.ID, http.MethodPost, base+"/restore", restore)
	var restored mediaLibrarySummary
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &restored) != nil || restored.Archived {
		t.Fatalf("restore: %d %s", r.Code, r.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, base+"/restore", restore); replay.Code != http.StatusOK || replay.Body.String() != r.Body.String() {
		t.Fatalf("restore replay: %d %s", replay.Code, replay.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodGet, base+"/sources", nil); r.Code != http.StatusOK || !strings.Contains(r.Body.String(), source.ID) {
		t.Fatalf("restored source listing: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, base, nil); r.Code != http.StatusNotFound {
		t.Fatalf("restore revived revoked access: %d %s", r.Code, r.Body)
	}
}

func TestMediaLibraryHTTPCancelAndRetryReceipts(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for i := range 30 {
		name := fmt.Sprintf("%02d.mp4", i)
		if err := os.WriteFile(filepath.Join(f.root, "films", name), []byte("fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID + "/scans"
	start := map[string]any{"bindingId": source.ID, "operationId": "cancelable-start", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision}
	r := f.call(t, f.owner.ID, http.MethodPost, base, start)
	var job mediaScanSummary
	if r.Code != http.StatusAccepted || json.Unmarshal(r.Body.Bytes(), &job) != nil {
		t.Fatalf("enqueue: %d %s", r.Code, r.Body)
	}
	running := f.waitJob(t, library.ID, job.ID, "running")
	cancel := map[string]any{"operationId": "cancel-once", "expectedGeneration": running.Generation}
	r = f.call(t, f.owner.ID, http.MethodPost, base+"/"+job.ID+"/cancel", cancel)
	var canceled mediaScanSummary
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &canceled) != nil || canceled.Status != "canceled" {
		t.Fatalf("cancel: %d %s", r.Code, r.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, base+"/"+job.ID+"/cancel", cancel); replay.Code != http.StatusOK || replay.Body.String() != r.Body.String() {
		t.Fatalf("cancel replay: %d %s", replay.Code, replay.Body)
	}
	if foreign := f.call(t, f.viewer.ID, http.MethodPost, base+"/"+job.ID+"/retry", map[string]any{"operationId": "viewer-retry", "expectedGeneration": canceled.Generation}); foreign.Code != http.StatusNotFound {
		t.Fatalf("foreign retry accepted: %d %s", foreign.Code, foreign.Body)
	}
	retry := map[string]any{"operationId": "retry-once", "expectedGeneration": canceled.Generation}
	r = f.call(t, f.owner.ID, http.MethodPost, base+"/"+job.ID+"/retry", retry)
	var retried mediaScanSummary
	if r.Code != http.StatusAccepted || json.Unmarshal(r.Body.Bytes(), &retried) != nil || retried.ID == "" || retried.ID == job.ID {
		t.Fatalf("retry: %d %s", r.Code, r.Body)
	}
	if replay := f.call(t, f.owner.ID, http.MethodPost, base+"/"+job.ID+"/retry", retry); replay.Code != http.StatusAccepted || replay.Body.String() != r.Body.String() {
		t.Fatalf("retry replay: %d %s", replay.Code, replay.Body)
	}
	if conflict := f.call(t, f.owner.ID, http.MethodPost, base+"/"+job.ID+"/retry", map[string]any{"operationId": "retry-once", "expectedGeneration": canceled.Generation + 1}); conflict.Code != http.StatusConflict {
		t.Fatalf("retry operation collision: %d %s", conflict.Code, conflict.Body)
	}
	completed := f.waitJob(t, library.ID, retried.ID, "completed")
	if completed.Processed != 30 {
		t.Fatalf("retry counts: %+v", completed)
	}
	page, err := f.store.ListCatalogItems(t.Context(), f.owner.ID, library.ID, medialibrary.CatalogListOptions{Limit: 100})
	if err != nil || len(page.Items) != 30 {
		t.Fatalf("retry duplicated or lost catalog items: %+v %v", page, err)
	}
}

func TestMediaLibraryHTTPHandlerCloseAndRecovery(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for i := range 20 {
		if err := os.WriteFile(filepath.Join(f.root, "films", fmt.Sprintf("%02d.mp4", i)), []byte("fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID + "/scans"
	start := map[string]any{"bindingId": source.ID, "operationId": "restart-scan", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision}
	r := f.call(t, f.owner.ID, http.MethodPost, base, start)
	var queued mediaScanSummary
	if r.Code != http.StatusAccepted || json.Unmarshal(r.Body.Bytes(), &queued) != nil {
		t.Fatalf("enqueue: %d %s", r.Code, r.Body)
	}
	running := f.waitJob(t, library.ID, queued.ID, "running")
	if err := f.router.(io.Closer).Close(); err != nil {
		t.Fatal(err)
	}
	interrupted, err := f.store.GetScan(t.Context(), f.owner.ID, queued.ID)
	if err != nil || interrupted.Status != "interrupted" || interrupted.Generation <= running.Generation {
		t.Fatalf("handler close did not preserve interrupted job: %+v %v", interrupted, err)
	}
	newRouter, err := NewHandler(nil, noopTrashFileCache{}, nil, f.h.storage, f.h.server, fstest.MapFS{}, 1, newHTTPHLSService(t, false), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = newRouter.(io.Closer).Close() })
	f.router = newRouter
	completed := f.waitJob(t, library.ID, queued.ID, "completed")
	if completed.Processed != 20 || completed.Generation <= interrupted.Generation {
		t.Fatalf("resumed job: %+v", completed)
	}
	page, err := f.store.ListCatalogItems(t.Context(), f.owner.ID, library.ID, medialibrary.CatalogListOptions{Limit: 100})
	if err != nil || len(page.Items) != 20 {
		t.Fatalf("recovered catalog: %+v %v", page, err)
	}
}

func TestMediaLibraryHTTPArchiveStopsRunningScan(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for i := range 20 {
		if err := os.WriteFile(filepath.Join(f.root, "films", fmt.Sprintf("%02d.mp4", i)), []byte("fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID
	r := f.call(t, f.owner.ID, http.MethodPost, base+"/scans", map[string]any{"bindingId": source.ID, "operationId": "archive-scan", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision})
	var queued mediaScanSummary
	if r.Code != http.StatusAccepted || json.Unmarshal(r.Body.Bytes(), &queued) != nil {
		t.Fatalf("enqueue: %d %s", r.Code, r.Body)
	}
	_ = f.waitJob(t, library.ID, queued.ID, "running")
	r = f.call(t, f.owner.ID, http.MethodPost, base+"/archive", map[string]any{"operationId": "archive-running", "expectedRevision": source.LibraryRevision})
	if r.Code != http.StatusOK {
		t.Fatalf("archive: %d %s", r.Code, r.Body)
	}
	failed := f.waitJob(t, library.ID, queued.ID, "failed")
	if failed.ErrorCode != medialibrary.ScanErrorPermissionDenied || failed.Processed >= 20 {
		t.Fatalf("archived scan continued: %+v", failed)
	}
	var missing int
	if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		return tx.QueryRowContext(t.Context(), "SELECT count(*) FROM library_file WHERE missing_since IS NOT NULL").Scan(&missing)
	}); err != nil || missing != 0 {
		t.Fatalf("archived partial scan published missing: %d %v", missing, err)
	}
}

func TestMediaLibraryHTTPSecondHandlerCannotFenceCoordinator(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	for i := range 25 {
		if err := os.WriteFile(filepath.Join(f.root, "films", fmt.Sprintf("%02d.mp4", i)), []byte("fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID + "/scans"
	r := f.call(t, f.owner.ID, http.MethodPost, base, map[string]any{"bindingId": source.ID, "operationId": "first-handler", "expectedRevision": source.Revision, "rulesRevision": mediascan.LocalRulesRevision})
	var queued mediaScanSummary
	if r.Code != http.StatusAccepted || json.Unmarshal(r.Body.Bytes(), &queued) != nil {
		t.Fatalf("enqueue: %d %s", r.Code, r.Body)
	}
	running := f.waitJob(t, library.ID, queued.ID, "running")
	otherStore, err := medialibrary.Open(t.Context(), f.catalogPath)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = otherStore.Close() })
	otherStorage := *f.h.storage
	otherStorage.MediaLibrary = otherStore
	otherServer := *f.h.server
	otherRouter, err := NewHandler(nil, noopTrashFileCache{}, nil, &otherStorage, &otherServer, fstest.MapFS{}, 1, newHTTPHLSService(t, false), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = otherRouter.(io.Closer).Close() })
	health := httptest.NewRecorder()
	otherRouter.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/health", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("existing service disabled by scan lock: %d", health.Code)
	}
	request := httptest.NewRequest(http.MethodPost, base+"/"+queued.ID+"/cancel", strings.NewReader(`{"operationId":"other-cancel","expectedGeneration":1}`))
	request.Header.Set("X-Auth", signedTrashHTTPToken(t, f.owner.ID))
	response := httptest.NewRecorder()
	otherRouter.ServeHTTP(response, request)
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("non-coordinator accepted scan mutation: %d %s", response.Code, response.Body)
	}
	current, err := f.store.GetScan(t.Context(), f.owner.ID, queued.ID)
	if err != nil || current.Generation != running.Generation || current.Status != "running" && current.Status != "completed" {
		t.Fatalf("second handler fenced first: %+v %v", current, err)
	}
}

func TestMediaLibraryHTTPInstanceSharingPolicy(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	library, source := f.libraryAndSource(t)
	base := "/api/media-library/v1/libraries/" + library.ID
	members := fmt.Sprintf("%s/members/%d", base, f.viewer.ID)
	grant := map[string]any{"operationId": "share-policy-grant", "expectedRevision": source.LibraryRevision, "role": "viewer"}
	r := f.call(t, f.owner.ID, http.MethodPut, members, grant)
	var member struct {
		LibraryRevision int64 `json:"libraryRevision"`
	}
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &member) != nil {
		t.Fatalf("grant: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, base, nil); r.Code != http.StatusOK {
		t.Fatalf("shared access before policy change: %d", r.Code)
	}
	f.h.server.DisableMediaLibrarySharing = true
	if r := f.call(t, f.viewer.ID, http.MethodGet, base, nil); r.Code != http.StatusNotFound {
		t.Fatalf("disabled sharing still visible: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.viewer.ID, http.MethodGet, "/api/media-library/v1/libraries?limit=1", nil); r.Code != http.StatusOK || strings.Contains(r.Body.String(), library.ID) {
		t.Fatalf("disabled sharing leaked in list: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodPut, members, map[string]any{"operationId": "denied-role-change", "expectedRevision": member.LibraryRevision, "role": "editor"}); r.Code != http.StatusForbidden {
		t.Fatalf("grant accepted while disabled: %d %s", r.Code, r.Body)
	}
	if r := f.call(t, f.owner.ID, http.MethodGet, base, nil); r.Code != http.StatusOK {
		t.Fatalf("owner lost access under policy: %d", r.Code)
	}
	f.h.server.DisableMediaLibrarySharing = false
	if r := f.call(t, f.viewer.ID, http.MethodGet, base, nil); r.Code != http.StatusOK {
		t.Fatalf("retained grant not restored: %d", r.Code)
	}
	f.h.server.DisableMediaLibrarySharing = true
	revoke := map[string]any{"operationId": "revoke-while-disabled", "expectedRevision": member.LibraryRevision}
	if r := f.call(t, f.owner.ID, http.MethodDelete, members, revoke); r.Code != http.StatusOK {
		t.Fatalf("revoke blocked by policy: %d %s", r.Code, r.Body)
	}
	f.h.server.DisableMediaLibrarySharing = false
	if r := f.call(t, f.viewer.ID, http.MethodGet, base, nil); r.Code != http.StatusNotFound {
		t.Fatalf("re-enabled policy restored revoked grant: %d", r.Code)
	}
}
