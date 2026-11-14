package fbhttp

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"hash/crc32"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/golang-jwt/jwt/v5"
	"github.com/spf13/afero"
)

type mediaAccessFixture struct {
	f               *mediaScanHTTPFixture
	base            string
	item            mediaItemSummary
	variant         mediaVariantResponse
	artwork         []medialibrary.MetadataArtwork
	libraryRevision int64
	video           []byte
}

type callbackMediaResponse struct {
	*httptest.ResponseRecorder
	once       sync.Once
	afterWrite func()
}

func (writer *callbackMediaResponse) Write(p []byte) (int, error) {
	n, err := writer.ResponseRecorder.Write(p)
	writer.once.Do(writer.afterWrite)
	return n, err
}

func TestMediaAccessRevokesDuringStreamAndNeverRevivesLease(t *testing.T) {
	f := newMediaAccessFixture(t)
	resolution := f.resolve(t)
	libID := strings.Split(f.base, "/")[5]
	var revoke *medialibrary.MemberGrant
	var revokeErr error
	response := &callbackMediaResponse{ResponseRecorder: httptest.NewRecorder(), afterWrite: func() {
		revoke, revokeErr = f.f.store.RevokeMember(t.Context(), f.f.owner.ID, libID, f.libraryRevision, f.f.viewer.ID)
	}}
	request := httptest.NewRequest(http.MethodGet, resolution.URL, nil)
	request.Header.Set("X-Auth", signedTrashHTTPToken(t, f.f.viewer.ID))
	f.f.router.ServeHTTP(response, request)
	if revokeErr != nil || revoke == nil || response.Body.Len() == 0 || response.Body.Len() >= len(f.video) || response.Body.Len() > mediaReadChunk {
		t.Fatalf("stream continued after revoke: bytes=%d revoke=%+v err=%v", response.Body.Len(), revoke, revokeErr)
	}
	if _, err := f.f.store.GrantMember(t.Context(), f.f.owner.ID, libID, revoke.LibraryRevision, f.f.viewer.ID, medialibrary.RoleViewer); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("regranted old lease: %d", r.Code)
	}
}

func TestMediaAccessRechecksSigningKeyDuringStream(t *testing.T) {
	f := newMediaAccessFixture(t)
	resolution := f.resolve(t)
	var updateErr error
	response := &callbackMediaResponse{ResponseRecorder: httptest.NewRecorder(), afterWrite: func() {
		var configuration *settings.Settings
		configuration, updateErr = f.f.h.storage.Settings.Get()
		if updateErr == nil {
			configuration.Key = []byte("media-access-test-rotated-key")
			updateErr = f.f.h.storage.Settings.Save(configuration)
		}
	}}
	request := httptest.NewRequest(http.MethodGet, resolution.URL, nil)
	request.Header.Set("X-Auth", signedTrashHTTPToken(t, f.f.viewer.ID))
	f.f.router.ServeHTTP(response, request)
	if updateErr != nil || response.Body.Len() == 0 || response.Body.Len() >= len(f.video) || response.Body.Len() > mediaReadChunk {
		t.Fatalf("stream continued after signing key changed: bytes=%d err=%v", response.Body.Len(), updateErr)
	}
	configuration, err := f.f.h.storage.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	configuration.Key = trashHTTPKey
	if err := f.f.h.storage.Settings.Save(configuration); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("old lease revived: %d", r.Code)
	}
}

func TestMediaAccessMetadataChangesAndSourceOwnerRevocation(t *testing.T) {
	f := newMediaAccessFixture(t)
	resolution := f.resolve(t)
	libID := strings.Split(f.base, "/")[5]
	detail, err := f.f.store.GetItemDetail(t.Context(), f.f.owner.ID, libID, f.item.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.f.store.ApplyMetadata(t.Context(), f.f.owner.ID, libID, f.item.ID, detail.Item.Revision, "edit-during-playback", medialibrary.MetadataPatch{Fields: map[string]medialibrary.MetadataFieldPatch{"plot": {Values: []string{"new text"}}}}); err != nil {
		t.Fatal(err)
	}
	library, err := f.f.store.GetLibrary(t.Context(), f.f.owner.ID, libID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.f.store.UpdateLibrary(t.Context(), f.f.owner.ID, libID, "Renamed", library.Revision); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"Range": "bytes=0-7"}); r.Code != http.StatusPartialContent {
		t.Fatalf("metadata invalidated stream: %d", r.Code)
	}
	owner, err := f.f.h.storage.Users.Get(f.f.h.server.Root, f.f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	owner.Perm.Download = false
	if err := f.f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("owner revoked: %d", r.Code)
	}
	owner.Perm.Download = true
	if err := f.f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("owner restore revived lease: %d", r.Code)
	}
	newResolution := f.resolve(t)
	video := filepath.Join(f.f.root, "films", "film.mp4")
	info, err := os.Stat(video)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(video, filepath.Join(f.f.root, "films", "before-replacement.mp4")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(video, f.video, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(video, info.ModTime(), info.ModTime()); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, newResolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("native source replacement: %d", r.Code)
	}
}

func TestMediaAccessTVDeviceJWTAndRevocation(t *testing.T) {
	f := newMediaAccessFixture(t)
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
	request, err := f.f.h.storage.Devices.Create(deviceID, "Fixture TV", public)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.f.h.storage.Devices.Approve(request.ID, deviceID, public, f.f.viewer.ID); err != nil {
		t.Fatal(err)
	}
	identity, err := f.f.h.storage.Devices.Identity()
	if err != nil {
		t.Fatal(err)
	}
	nonce := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	sum := sha256.Sum256(deviceauth.Message("exchange", identity.ID, request.ID, request.Challenge, nonce))
	signature, err := ecdsa.SignASN1(rand.Reader, key, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	credential, err := f.f.h.storage.Devices.Exchange(request.ID, nonce, base64.RawURLEncoding.EncodeToString(signature))
	if err != nil {
		t.Fatal(err)
	}
	signing, err := f.f.h.storage.Devices.SigningKey()
	if err != nil {
		t.Fatal(err)
	}
	claims := authToken{User: userInfo{ID: f.f.viewer.ID}, DeviceSession: credential.Session.ID, Scope: "media", RegisteredClaims: jwt.RegisteredClaims{IssuedAt: jwt.NewNumericDate(time.Now()), ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour))}}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	token.Header["kid"] = "fileway.device.v1"
	signed, err := token.SignedString(signing)
	if err != nil {
		t.Fatal(err)
	}
	call := func(method, target string, body []byte) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, target, bytes.NewReader(body))
		req.Header.Set("X-Auth", signed)
		response := httptest.NewRecorder()
		f.f.router.ServeHTTP(response, req)
		return response
	}
	if r := call(http.MethodGet, f.base+"/variants", nil); r.Code != http.StatusOK {
		t.Fatalf("TV variants: %d %s", r.Code, r.Body)
	}
	body, _ := json.Marshal(map[string]string{"editionId": f.variant.EditionID, "variantId": f.variant.VariantID, "bindingId": f.variant.BindingID})
	resolved := call(http.MethodPost, f.base+"/playback-resolutions", body)
	var value testMediaResolution
	if resolved.Code != http.StatusOK || json.Unmarshal(resolved.Body.Bytes(), &value) != nil || value.URL == "" {
		t.Fatalf("TV resolve: %d %s", resolved.Code, resolved.Body)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, value.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("TV lease used without its session: %d", r.Code)
	}
	var revokeErr error
	stream := &callbackMediaResponse{ResponseRecorder: httptest.NewRecorder(), afterWrite: func() { revokeErr = f.f.h.storage.Devices.Revoke(credential.Session.ID, f.f.viewer.ID) }}
	streamRequest := httptest.NewRequest(http.MethodGet, value.URL, nil)
	streamRequest.Header.Set("X-Auth", signed)
	f.f.router.ServeHTTP(stream, streamRequest)
	if revokeErr != nil || stream.Body.Len() == 0 || stream.Body.Len() >= len(f.video) || stream.Body.Len() > mediaReadChunk {
		t.Fatalf("TV revoke during stream: bytes=%d err=%v", stream.Body.Len(), revokeErr)
	}
	if r := call(http.MethodGet, value.URL, nil); r.Code != http.StatusUnauthorized {
		t.Fatalf("revoked TV session: %d", r.Code)
	}
}

func newMediaAccessFixture(t *testing.T) *mediaAccessFixture {
	t.Helper()
	f := newMediaScanHTTPFixture(t)
	video := bytes.Repeat([]byte("0123456789abcdef"), 40000)
	if err := os.WriteFile(filepath.Join(f.root, "films", "film.mp4"), video, 0o600); err != nil {
		t.Fatal(err)
	}
	img := image.NewRGBA(image.Rect(0, 0, 2, 2))
	img.Set(0, 0, color.RGBA{R: 200, A: 255})
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, img); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.root, "films", "poster.png"), encoded.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	nfo := `<movie><title>Film</title><thumb aspect="poster">poster.png</thumb><thumb aspect="poster">https://example.invalid/poster.png</thumb><thumb aspect="poster">../../escape.png</thumb><thumb aspect="poster">file:///private.png</thumb><thumb aspect="poster">bad.svg</thumb></movie>`
	if err := os.WriteFile(filepath.Join(f.root, "films", "film.nfo"), []byte(nfo), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.root, "films", "bad.svg"), []byte(`<svg xmlns="http://www.w3.org/2000/svg"/>`), 0o600); err != nil {
		t.Fatal(err)
	}
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "access-journey")
	base := "/api/media-library/v1/libraries/" + library.ID
	grant := f.call(t, f.owner.ID, http.MethodPut, fmt.Sprintf("%s/members/%d", base, f.viewer.ID), map[string]any{"operationId": "access-share", "expectedRevision": source.LibraryRevision, "role": "viewer"})
	var member struct {
		LibraryRevision int64 `json:"libraryRevision"`
	}
	if grant.Code != http.StatusOK || json.Unmarshal(grant.Body.Bytes(), &member) != nil {
		t.Fatalf("grant: %d %s", grant.Code, grant.Body)
	}
	viewer, err := f.h.storage.Users.Get(f.h.server.Root, f.viewer.ID)
	if err != nil {
		t.Fatal(err)
	}
	viewer.Perm.Download = false
	if err := f.h.storage.Users.Update(viewer, "Perm"); err != nil {
		t.Fatal(err)
	}
	f.h.fs[f.viewer.ID] = afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
	items, _ := fixtureItems(t, f, f.viewer.ID, library.ID, "")
	if len(items) != 1 {
		t.Fatalf("items: %+v", items)
	}
	detail := fixtureDetail(t, f, f.viewer.ID, library.ID, items[0].ID)
	if len(detail.Artwork) != 5 {
		t.Fatalf("artwork declarations: %+v", detail.Artwork)
	}
	base += "/items/" + items[0].ID
	response := f.call(t, f.viewer.ID, http.MethodGet, base+"/variants", nil)
	var variants struct {
		Items []mediaVariantResponse `json:"items"`
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &variants) != nil || len(variants.Items) != 1 {
		t.Fatalf("variants: %d %s", response.Code, response.Body)
	}
	return &mediaAccessFixture{f: f, base: base, item: items[0], variant: variants.Items[0], artwork: detail.Artwork, libraryRevision: member.LibraryRevision, video: video}
}

type testMediaResolution struct {
	SessionID           string `json:"sessionId"`
	LeaseID             string `json:"leaseId"`
	LeaseGeneration     int64  `json:"leaseGeneration"`
	URL                 string `json:"url"`
	WeakIdentity        bool   `json:"weakIdentity"`
	CanResumeAcrossOpen bool   `json:"canResumeAcrossOpen"`
	SourceFileID        string `json:"sourceFileId"`
	FileRevision        int64  `json:"fileRevision"`
	IdentityEpoch       int64  `json:"identityEpoch"`
	ExpiresAt           int64  `json:"expiresAt"`
}

func TestMediaPlaybackSessionRenewKeepsRangeAndRevocation(t *testing.T) {
	f := newMediaAccessFixture(t)
	resolution := f.resolve(t)
	leases := f.f.router.(*archiveCacheHandler).mediaLeases
	start := time.Now()
	leases.now = func() time.Time { return start }
	lease := leases.items[resolution.LeaseID]
	if lease == nil || resolution.SessionID == "" || resolution.LeaseGeneration != 1 {
		t.Fatal("missing playback session")
	}
	// Advance the admission clock after output starts, with the file held by
	// the active request. A new request requires renewal.
	stream := &callbackMediaResponse{ResponseRecorder: httptest.NewRecorder(), afterWrite: func() { start = start.Add(6 * time.Minute) }}
	request := httptest.NewRequest(http.MethodGet, resolution.URL, nil)
	request.Header.Set("X-Auth", signedTrashHTTPToken(t, f.f.viewer.ID))
	f.f.router.ServeHTTP(stream, request)
	if stream.Code != http.StatusOK || !bytes.Equal(stream.Body.Bytes(), f.video) {
		t.Fatalf("active output after admission expired: %d bytes=%d", stream.Code, stream.Body.Len())
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodHead, resolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("expired admission accepted: %d", r.Code)
	}
	path := "/api/media-library/v1/playback-sessions/" + resolution.SessionID
	body := map[string]any{"operationId": "renew-playback", "expectedLeaseGeneration": 1}
	if r := f.f.call(t, f.f.owner.ID, http.MethodPost, path+"/renew", body); r.Code != http.StatusNotFound {
		t.Fatalf("another actor renewed: %d", r.Code)
	}
	r := f.f.call(t, f.f.viewer.ID, http.MethodPost, path+"/renew", body)
	var receipt mediaAdmission
	if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &receipt) != nil || receipt.LeaseGeneration != 2 || receipt.LeaseID != resolution.LeaseID {
		t.Fatalf("renew: %d %s", r.Code, r.Body)
	}
	if retry := f.f.call(t, f.f.viewer.ID, http.MethodPost, path+"/renew", body); retry.Code != http.StatusOK || retry.Body.String() != r.Body.String() {
		t.Fatalf("renew replay: %d %s", retry.Code, retry.Body)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"Range": "bytes=10-30"}); r.Code != http.StatusPartialContent || !bytes.Equal(r.Body.Bytes(), f.video[10:31]) {
		t.Fatalf("range after renewal: %d", r.Code)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodDelete, path, nil); r.Code != http.StatusNoContent {
		t.Fatalf("close: %d", r.Code)
	}
	if r := f.f.call(t, f.f.viewer.ID, http.MethodPost, path+"/renew", body); r.Code != http.StatusNotFound {
		t.Fatalf("closed session revived: %d", r.Code)
	}
}

func (f *mediaAccessFixture) resolve(t *testing.T) testMediaResolution {
	t.Helper()
	response := f.f.call(t, f.f.viewer.ID, http.MethodPost, f.base+"/playback-resolutions", map[string]any{"editionId": f.variant.EditionID, "variantId": f.variant.VariantID, "bindingId": f.variant.BindingID})
	var result testMediaResolution
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.URL == "" || result.SourceFileID == "" {
		t.Fatalf("resolve: %d %s", response.Code, response.Body)
	}
	if strings.Contains(response.Body.String(), "/films/") || strings.Contains(response.Body.String(), "configurationRef") {
		t.Fatalf("private source leaked: %s", response.Body)
	}
	return result
}

func (f *mediaAccessFixture) request(t *testing.T, actor uint, method, target string, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, target, nil)
	if actor != 0 {
		req.Header.Set("X-Auth", signedTrashHTTPToken(t, actor))
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	response := httptest.NewRecorder()
	f.f.router.ServeHTTP(response, req)
	return response
}

func TestMediaAccessHTTPJWTArtworkRangeAndRevocation(t *testing.T) {
	f := newMediaAccessFixture(t)
	if f.variant.Size != int64(len(f.video)) || f.variant.WeakIdentity || f.variant.DurationSeconds != nil || f.variant.Resolution != nil {
		t.Fatalf("variant evidence: %+v", f.variant)
	}
	resolution := f.resolve(t)
	if resolution.WeakIdentity || resolution.CanResumeAcrossOpen || resolution.ExpiresAt <= time.Now().UnixMilli() {
		t.Fatalf("resolution: %+v", resolution)
	}
	if r := f.request(t, 0, http.MethodGet, resolution.URL, nil); r.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous stream: %d", r.Code)
	}
	if r := f.request(t, f.f.owner.ID, http.MethodGet, resolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("cross-user lease: %d", r.Code)
	}
	partial := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"Range": "bytes=10-30"})
	if partial.Code != http.StatusPartialContent || !bytes.Equal(partial.Body.Bytes(), f.video[10:31]) || partial.Header().Get("Content-Range") != fmt.Sprintf("bytes 10-30/%d", len(f.video)) {
		t.Fatalf("range: %d %q %s", partial.Code, partial.Header().Get("Content-Range"), partial.Body)
	}
	etag := partial.Header().Get("ETag")
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"If-None-Match": etag}); r.Code != http.StatusNotModified {
		t.Fatalf("conditional: %d", r.Code)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodHead, resolution.URL, nil); r.Code != http.StatusOK || r.Body.Len() != 0 {
		t.Fatalf("HEAD: %d", r.Code)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"Range": "bytes=999999-"}); r.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Fatalf("416: %d", r.Code)
	}
	started := time.Now()
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"Range": "bytes=10-20", "If-Range": `"stale"`}); r.Code != http.StatusOK || !bytes.Equal(r.Body.Bytes(), f.video) {
		t.Fatalf("If-Range: %d", r.Code)
	}
	t.Logf("full %d-byte isolated response: %s", len(f.video), time.Since(started))
	art := f.request(t, f.f.viewer.ID, http.MethodGet, f.base+"/artwork/"+f.artwork[0].ID, nil)
	if art.Code != http.StatusOK || art.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("artwork: %d %s", art.Code, art.Body)
	}
	if _, err := png.Decode(bytes.NewReader(art.Body.Bytes())); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, f.base+"/artwork/"+f.artwork[0].ID, map[string]string{"If-None-Match": art.Header().Get("ETag")}); r.Code != http.StatusNotModified {
		t.Fatalf("artwork 304: %d", r.Code)
	}
	nfoPath := filepath.Join(f.f.root, "films", "film.nfo")
	nfo, err := os.ReadFile(nfoPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(nfoPath, append(bytes.Clone(nfo), []byte(" ")...), 0o600); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, f.base+"/artwork/"+f.artwork[0].ID, map[string]string{"If-None-Match": art.Header().Get("ETag")}); r.Code != http.StatusNotFound {
		t.Fatalf("changed NFO reused artwork: %d", r.Code)
	}
	if err := os.WriteFile(nfoPath, nfo, 0o600); err != nil {
		t.Fatal(err)
	}
	for _, bad := range f.artwork[1:] {
		if r := f.request(t, f.f.viewer.ID, http.MethodGet, f.base+"/artwork/"+bad.ID, nil); r.Code != http.StatusNotFound {
			t.Fatalf("malicious artwork: %d %s", r.Code, r.Body)
		}
	}
	libID := strings.Split(f.base, "/")[5]
	if _, err := f.f.store.RevokeMember(t.Context(), f.f.owner.ID, libID, f.libraryRevision, f.f.viewer.ID); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"If-None-Match": etag}); r.Code != http.StatusNotFound {
		t.Fatalf("revoked 304: %d", r.Code)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, f.base+"/artwork/"+f.artwork[0].ID, map[string]string{"If-None-Match": art.Header().Get("ETag")}); r.Code != http.StatusNotFound {
		t.Fatalf("revoked artwork 304: %d", r.Code)
	}
}

func TestMediaArtworkBoundsAndRelativePaths(t *testing.T) {
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	header := bytes.Clone(encoded.Bytes())
	// A valid IHDR with an excessive dimension must be rejected before decode
	// allocates its pixel buffer.
	binary.BigEndian.PutUint32(header[16:20], mediaArtworkDimension+1)
	binary.BigEndian.PutUint32(header[29:33], crc32.ChecksumIEEE(header[12:29]))
	if _, _, err := sanitizedArtwork(header); err == nil {
		t.Fatal("oversized bitmap accepted")
	}
	if _, _, err := sanitizedArtwork([]byte(`<svg xmlns="http://www.w3.org/2000/svg"/>`)); err == nil {
		t.Fatal("SVG accepted")
	}
	root, nfo := []byte("/films"), []byte("/films/nested/film.nfo")
	if actual, err := mediaArtworkLocator(root, nfo, []byte("../posters/a%23.png")); err != nil || string(actual) != "/films/posters/a%23.png" {
		t.Fatalf("opaque local reference: %q %v", actual, err)
	}
	for _, ref := range []string{"../../escape.jpg", "file:///secret", "http://localhost/image", "/absolute.png", "D:/private.png"} {
		if _, err := mediaArtworkLocator(root, nfo, []byte(ref)); err == nil {
			t.Fatalf("unsafe reference accepted: %q", ref)
		}
	}
}

func TestMediaAccessWeakIdentityLeaseReplacementAndExpiry(t *testing.T) {
	f := newMediaAccessFixture(t)
	binding, err := f.f.store.GetLibrarySourceByID(t.Context(), f.f.owner.ID, f.variant.BindingID)
	if err != nil {
		t.Fatal(err)
	}
	key := medialibrary.UnverifiedPathObjectKey(binding.ID, binding.ProofEpoch, []byte("/films/film.mp4"))
	if err := f.f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
		_, err := tx.ExecContext(t.Context(), "UPDATE source_file SET identity_kind='path_unverified',object_key=?,identity_epoch=identity_epoch+1 WHERE id=?", key, f.variant.SourceFileID)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	variants := f.f.call(t, f.f.viewer.ID, http.MethodGet, f.base+"/variants", nil)
	var page struct {
		Items []mediaVariantResponse `json:"items"`
	}
	if variants.Code != http.StatusOK || json.Unmarshal(variants.Body.Bytes(), &page) != nil || len(page.Items) != 1 || !page.Items[0].WeakIdentity {
		t.Fatalf("weak variant: %d %s", variants.Code, variants.Body)
	}
	f.variant = page.Items[0]
	resolution := f.resolve(t)
	if !resolution.WeakIdentity || resolution.CanResumeAcrossOpen {
		t.Fatalf("weak identity overstated: %+v", resolution)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, map[string]string{"Range": "bytes=20-30"}); r.Code != http.StatusPartialContent || !bytes.Equal(r.Body.Bytes(), f.video[20:31]) {
		t.Fatalf("weak lease range: %d", r.Code)
	}
	expiring := f.resolve(t)
	leases := f.f.router.(*archiveCacheHandler).mediaLeases
	id := strings.Split(expiring.URL, "/")[5]
	leases.mu.Lock()
	lease := leases.items[id]
	if lease == nil {
		leases.mu.Unlock()
		t.Fatal("missing lease")
	}
	lease.expires = time.Now().Add(-time.Second)
	leases.mu.Unlock()
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, expiring.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("expired: %d", r.Code)
	}
	video := filepath.Join(f.f.root, "films", "film.mp4")
	if err := os.Rename(video, filepath.Join(f.f.root, "films", "old.mp4")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(video, f.video, 0o600); err != nil {
		t.Fatal(err)
	}
	if r := f.request(t, f.f.viewer.ID, http.MethodGet, resolution.URL, nil); r.Code != http.StatusNotFound {
		t.Fatalf("replaced weak file: %d", r.Code)
	}
}
