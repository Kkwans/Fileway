package tvupdate

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type testSource struct {
	releases    []githubRelease
	metadata    map[int64][]byte
	apk         []byte
	fetches     atomic.Int32
	unblock     chan struct{}
	listFailure *Failure
	readFailure *Failure
}

func (source *testSource) list(context.Context) ([]githubRelease, *Failure) {
	return source.releases, source.listFailure
}
func (source *testSource) readAsset(_ context.Context, asset githubAsset, _ int64) ([]byte, *Failure) {
	return source.metadata[asset.ID], source.readFailure
}
func (source *testSource) openAsset(ctx context.Context, _ int64) (*http.Response, *Failure) {
	source.fetches.Add(1)
	if source.unblock != nil {
		select {
		case <-source.unblock:
		case <-ctx.Done():
			return nil, unavailable
		}
	}
	return &http.Response{ContentLength: int64(len(source.apk)), Body: io.NopCloser(bytes.NewReader(source.apk))}, nil
}

func testSignedRelease(t *testing.T, source *testSource, key *rsa.PrivateKey, version string, code int64, preview bool, abi string) {
	t.Helper()
	channel := "stable"
	if preview {
		channel = "preview"
	}
	digest := fmt.Sprintf("%x", sha256.Sum256(source.apk))
	m := manifest{Schema: 1, Product: "android-tv", ApplicationID: "io.github.kkwans.fileway.tv", Tag: "android-tv-" + version,
		Channel: channel, VersionName: version, VersionCode: code, MinSDK: 28, ABIs: []string{abi}, APKName: "Fileway-TV-" + version + ".apk",
		Size: int64(len(source.apk)), SHA256: digest, SignerSHA256: originalSigner, SourceRevision: strings.Repeat("a", 40)}
	payload, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(append([]byte(signingDomain), payload...))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, hash[:])
	if err != nil {
		t.Fatal(err)
	}
	source.metadata[code*3] = payload
	source.metadata[code*3+1] = sig
	source.releases = append(source.releases, githubRelease{Tag: m.Tag, Prerelease: preview, Assets: []githubAsset{
		{ID: code * 3, Name: manifestName, State: "uploaded", Size: int64(len(payload))},
		{ID: code*3 + 1, Name: signatureName, State: "uploaded", Size: int64(len(sig))},
		{ID: code*3 + 2, Name: m.APKName, State: "uploaded", Size: m.Size, Digest: "sha256:" + digest},
	}})
}

func waitReady(t *testing.T, s *Service, owner Owner, id string) Status {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		status, failure := s.Status(t.Context(), owner, id)
		if failure != nil {
			t.Fatalf("status: %+v", failure)
		}
		if status.State != "preparing" {
			return status
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("update did not finish")
	return Status{}
}

func TestTVUpdateOwnerDedupAndVerifiedRange(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	source := &testSource{metadata: map[int64][]byte{}, apk: []byte("official fixture bytes"), unblock: make(chan struct{})}
	testSignedRelease(t, source, key, "0.5.0", 8, false, "arm64-v8a")
	testSignedRelease(t, source, key, "0.6.0", 9, true, "x86")
	// A public legacy release without a signed manifest must not become an offer.
	source.releases = append(source.releases, githubRelease{Tag: "android-tv-9.0.0"})
	s := newService(source, trustRoot{&key.PublicKey, originalSigner})
	t.Cleanup(s.Close)
	device := Device{VersionCode: 7, API: 28, ABIs: []string{"arm64-v8a"}}
	a, b := Owner{3, "device-a"}, Owner{3, "device-b"}
	offersA, failure := s.Check(t.Context(), a, device)
	if failure != nil || len(offersA.Offers) != 1 || source.fetches.Load() != 0 {
		t.Fatalf("check: offers %d, failure %+v, APK fetches %d", len(offersA.Offers), failure, source.fetches.Load())
	}
	offersB, failure := s.Check(t.Context(), b, device)
	if failure != nil || len(offersB.Offers) != 1 || offersA.Offers[0].ID == offersB.Offers[0].ID {
		t.Fatalf("offer binding failed: %+v", failure)
	}
	if _, failure := s.Start(t.Context(), b, offersA.Offers[0].ID); failure != expired {
		t.Fatalf("cross-device offer: %+v", failure)
	}
	var downloads [2]Status
	var failures [2]*Failure
	var wait sync.WaitGroup
	for i, owner := range []Owner{a, b} {
		wait.Go(func() {
			downloads[i], failures[i] = s.Start(t.Context(), owner, [][]Offer{offersA.Offers, offersB.Offers}[i][0].ID)
		})
	}
	wait.Wait()
	if failures[0] != nil || failures[1] != nil || downloads[0].ID == downloads[1].ID {
		t.Fatalf("prepare %+v %+v", downloads, failures)
	}
	if _, failure := s.Status(t.Context(), Owner{4, "device-a"}, downloads[0].ID); failure != expired {
		t.Fatalf("cross-account status: %+v", failure)
	}
	if _, _, _, failure := s.open(t.Context(), a, downloads[0].ID); failure != notReady {
		t.Fatalf("unverified content available: %+v", failure)
	}
	close(source.unblock)
	if got := waitReady(t, s, a, downloads[0].ID); got.State != "ready" || got.Total != int64(len(source.apk)) || got.Received != got.Total || source.fetches.Load() != 1 {
		t.Fatalf("shared verified preparation: %+v fetches=%d", got, source.fetches.Load())
	}
	_, _, releaseA, failure := s.open(t.Context(), a, downloads[0].ID)
	if failure != nil {
		t.Fatal(failure)
	}
	if _, _, _, failure := s.open(t.Context(), a, downloads[0].ID); failure != busy {
		t.Fatalf("the same device occupied two stream slots: %+v", failure)
	}
	_, _, releaseB, failure := s.open(t.Context(), b, downloads[1].ID)
	if failure != nil {
		t.Fatalf("a second device on the same account could not stream: %+v", failure)
	}
	third := Owner{Account: 4}
	offersC, failure := s.Check(t.Context(), third, device)
	if failure != nil || len(offersC.Offers) != 1 {
		t.Fatalf("third owner check: %+v %+v", offersC, failure)
	}
	thirdDownload, failure := s.Start(t.Context(), third, offersC.Offers[0].ID)
	if failure != nil || thirdDownload.State != "ready" {
		t.Fatalf("third owner preparation: %+v %+v", thirdDownload, failure)
	}
	if _, _, _, failure := s.open(t.Context(), third, thirdDownload.ID); failure != busy {
		t.Fatalf("global stream limit was bypassed: %+v", failure)
	}
	releaseB()
	_, _, releaseC, failure := s.open(t.Context(), third, thirdDownload.ID)
	if failure != nil {
		t.Fatalf("another account could not use a released slot: %+v", failure)
	}
	if _, _, _, failure := s.open(t.Context(), third, thirdDownload.ID); failure != busy {
		t.Fatalf("plain JWT owner bypassed its single stream: %+v", failure)
	}
	releaseC()
	releaseA()
	id := downloads[0].ID
	etag := fmt.Sprintf(`"%x"`, sha256.Sum256(source.apk))
	for _, test := range []struct {
		method, rangeHeader, ifRange string
		status                       int
		body                         string
	}{
		{http.MethodHead, "", "", 200, ""},
		{http.MethodGet, "bytes=2-5", etag, 206, "fici"},
		{http.MethodGet, "bytes=2-5", `"different"`, 200, string(source.apk)},
		{http.MethodGet, "bytes=999-", etag, 416, ""},
	} {
		req := httptest.NewRequest(test.method, "/", nil)
		req.Header.Set("Range", test.rangeHeader)
		req.Header.Set("If-Range", test.ifRange)
		response := httptest.NewRecorder()
		if failure := s.ServeContent(response, req, a, id); failure != nil {
			t.Fatalf("content: %+v", failure)
		}
		if response.Code != test.status || (test.status != 416 && (response.Body.String() != test.body || response.Header().Get("ETag") != etag)) {
			t.Fatalf("%s %s: status %d, ETag %q, body %q", test.method, test.rangeHeader, response.Code, response.Header().Get("ETag"), response.Body.String())
		}
		if test.status == 206 && response.Header().Get("Content-Range") != "bytes 2-5/22" {
			t.Fatal(response.Header())
		}
	}
	if failure := s.ServeContent(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil), b, id); failure != expired {
		t.Fatalf("cross-owner content: %+v", failure)
	}
	device.VersionCode = 8
	if offers, failure := s.Check(t.Context(), a, device); failure != nil || offers.Incomplete || len(offers.Offers) != 0 {
		t.Fatalf("current/ABI mismatch: offers %d failure %+v", len(offers.Offers), failure)
	}
	// Both handles and cached bytes belong to the original process.
	restarted := newService(source, s.trust)
	t.Cleanup(restarted.Close)
	if _, failure := restarted.Status(t.Context(), a, id); failure != expired {
		t.Fatalf("cross-instance handle: %+v", failure)
	}
	device.VersionCode = 7
	offers, failure := restarted.Check(t.Context(), a, device)
	if failure != nil || len(offers.Offers) != 1 {
		t.Fatalf("restart check: %+v", failure)
	}
	status, failure := restarted.Start(t.Context(), a, offers.Offers[0].ID)
	if failure != nil {
		t.Fatal(failure)
	}
	if got := waitReady(t, restarted, a, status.ID); got.State != "ready" || source.fetches.Load() != 2 {
		t.Fatalf("restart did not fetch fresh bytes: %+v, reads %d", got, source.fetches.Load())
	}
}

func TestTVUpdateRejectsCorruptOrUnsignedArtifacts(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	source := &testSource{metadata: map[int64][]byte{}, apk: []byte("signed")}
	testSignedRelease(t, source, key, "0.5.0", 8, false, "arm64-v8a")
	// Missing GitHub digest is allowed because the signed SHA-256 remains authoritative.
	source.releases[0].Assets[2].Digest = ""
	source.apk = []byte("forged")
	s := newService(source, trustRoot{&key.PublicKey, originalSigner})
	t.Cleanup(s.Close)
	owner := Owner{Account: 1}
	device := Device{7, 28, []string{"arm64-v8a"}}
	offers, failure := s.Check(t.Context(), owner, device)
	if failure != nil || len(offers.Offers) != 1 {
		t.Fatalf("signed manifest: %+v", failure)
	}
	status, failure := s.Start(t.Context(), owner, offers.Offers[0].ID)
	if failure != nil {
		t.Fatal(failure)
	}
	if result := waitReady(t, s, owner, status.ID); result.State != "failed" || result.Code != badArtifact.Code {
		t.Fatalf("corrupt APK: %+v", result)
	}
	if len(s.freeBlocks) != s.allocatedBlocks {
		t.Fatalf("unverified data retained reserved blocks: free %d allocated %d", len(s.freeBlocks), s.allocatedBlocks)
	}
	source.apk = []byte("signed")
	status, failure = s.Start(t.Context(), owner, offers.Offers[0].ID)
	if failure != nil {
		t.Fatal(failure)
	}
	if result := waitReady(t, s, owner, status.ID); result.State != "ready" {
		t.Fatalf("retry: %+v", result)
	}
	source.metadata[8*3+1][0] ^= 1
	s.catalogAt = time.Time{}
	if directory, failure := s.Check(t.Context(), owner, device); failure != nil || !directory.Incomplete || len(directory.Offers) != 0 {
		t.Fatalf("forged manifest: %+v %+v", directory, failure)
	}
	if _, failure := s.Status(t.Context(), owner, status.ID); failure != expired {
		t.Fatalf("verified-invalid publication kept an existing handle: %+v", failure)
	}
}

func TestTVUpdateIsolatesInvalidAndAmbiguousCandidates(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	source := &testSource{metadata: map[int64][]byte{}, apk: []byte("published bytes")}
	testSignedRelease(t, source, key, "0.5.0", 8, false, "arm64-v8a")
	testSignedRelease(t, source, key, "0.6.0", 9, true, "arm64-v8a")
	testSignedRelease(t, source, key, "0.7.0", 10, false, "arm64-v8a")
	stable, preview, collision := source.releases[0], source.releases[1], source.releases[2]
	legacy := githubRelease{Tag: "android-tv-0.4.0", Prerelease: true}
	s := newService(source, trustRoot{&key.PublicKey, originalSigner})
	t.Cleanup(s.Close)
	device := Device{7, 28, []string{"arm64-v8a"}}
	owner := Owner{1, "device-a"}
	check := func(releases []githubRelease, wantTag string) {
		t.Helper()
		source.releases = releases
		s.catalogAt = time.Time{}
		directory, failure := s.Check(t.Context(), owner, device)
		if failure != nil || !directory.Incomplete || len(directory.Offers) != 1 {
			t.Fatalf("isolating invalid candidate for %s: %+v %+v", wantTag, directory, failure)
		}
		var m manifest
		if err := json.Unmarshal(directory.Offers[0].Manifest, &m); err != nil || m.Tag != wantTag {
			t.Fatalf("wrong surviving offer: %q, err %v", m.Tag, err)
		}
		encoded, err := json.Marshal(directory)
		if err != nil || !bytes.Contains(encoded, []byte(`"incomplete":true`)) {
			t.Fatalf("missing optional wire marker: %s %v", encoded, err)
		}
	}
	previewSignature := source.metadata[preview.Assets[1].ID]
	previewSignature[0] ^= 1
	check([]githubRelease{stable, preview, legacy}, stable.Tag)
	previewSignature[0] ^= 1
	check([]githubRelease{stable, preview, preview}, stable.Tag)
	assetID := collision.Assets[0].ID
	var m manifest
	if err := json.Unmarshal(source.metadata[assetID], &m); err != nil {
		t.Fatal(err)
	}
	m.VersionCode = 8
	payload, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(append([]byte(signingDomain), payload...))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	source.metadata[assetID] = payload
	source.metadata[collision.Assets[1].ID] = signature
	check([]githubRelease{stable, preview, collision}, preview.Tag)
}

func TestTVUpdateWithdrawsSingleAndLastPublishedCandidate(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	source := &testSource{metadata: map[int64][]byte{}, apk: []byte("published bytes")}
	testSignedRelease(t, source, key, "0.5.0", 8, false, "arm64-v8a")
	testSignedRelease(t, source, key, "0.6.0", 9, true, "arm64-v8a")
	stable, preview := source.releases[0], source.releases[1]
	legacy := githubRelease{Tag: "android-tv-0.4.0", Prerelease: true}
	s := newService(source, trustRoot{&key.PublicKey, originalSigner})
	t.Cleanup(s.Close)
	device := Device{7, 28, []string{"arm64-v8a"}}
	a, b := Owner{1, "device-a"}, Owner{2, "device-b"}
	offersA, failure := s.Check(t.Context(), a, device)
	if failure != nil || len(offersA.Offers) != 2 {
		t.Fatalf("initial offers: %d %+v", len(offersA.Offers), failure)
	}
	offersB, failure := s.Check(t.Context(), b, device)
	if failure != nil || len(offersB.Offers) != 2 {
		t.Fatalf("initial offers for B: %d %+v", len(offersB.Offers), failure)
	}
	stableA, failure := s.Start(t.Context(), a, offersA.Offers[0].ID)
	if failure != nil || waitReady(t, s, a, stableA.ID).State != "ready" {
		t.Fatalf("stable preparation: %+v", failure)
	}
	stableB, failure := s.Start(t.Context(), b, offersB.Offers[0].ID)
	if failure != nil || stableB.State != "ready" {
		t.Fatalf("stable shared cache: %+v", failure)
	}
	previewA, failure := s.Start(t.Context(), a, offersA.Offers[1].ID)
	if failure != nil || waitReady(t, s, a, previewA.ID).State != "ready" {
		t.Fatalf("preview preparation: %+v", failure)
	}
	pinned, _, release, failure := s.open(t.Context(), a, stableA.ID)
	if failure != nil {
		t.Fatal(failure)
	}
	defer release()
	if _, _, _, failure := s.open(t.Context(), a, previewA.ID); failure != busy {
		t.Fatalf("one account occupied two stream slots: %+v", failure)
	}
	_, _, releaseB, failure := s.open(t.Context(), b, stableB.ID)
	if failure != nil {
		t.Fatalf("another account could not use the second slot: %+v", failure)
	}
	releaseB()

	source.releases = []githubRelease{preview, legacy}
	s.catalogAt = time.Time{}
	if _, failure := s.Status(t.Context(), a, stableA.ID); failure != expired {
		t.Fatalf("status did not detect withdrawal: %+v", failure)
	}
	if _, failure := s.Status(t.Context(), b, stableB.ID); failure != expired {
		t.Fatalf("another account's old handle survived: %+v", failure)
	}
	if status, failure := s.Status(t.Context(), a, previewA.ID); failure != nil || status.State != "ready" {
		t.Fatalf("other candidate became unavailable: %+v %+v", status, failure)
	}
	if got, err := io.ReadAll(pinned); err != nil || !bytes.Equal(got, source.apk) {
		t.Fatalf("open stream was disturbed: %q %v", got, err)
	}

	source.releases = []githubRelease{legacy}
	s.catalogAt = time.Time{}
	if directory, failure := s.Check(t.Context(), a, device); failure != nil || directory.Incomplete || len(directory.Offers) != 0 {
		t.Fatalf("legacy-only catalog result: %+v %+v", directory, failure)
	}
	if _, failure := s.Status(t.Context(), a, previewA.ID); failure != expired {
		t.Fatalf("last candidate's old status survived: %+v", failure)
	}
	if failure := s.ServeContent(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil), a, previewA.ID); failure != expired {
		t.Fatalf("last candidate's old content survived: %+v", failure)
	}
	if _, failure := s.Start(t.Context(), a, offersA.Offers[1].ID); failure != expired {
		t.Fatalf("last candidate's old offer survived: %+v", failure)
	}

	source.releases = nil
	s.catalogAt = time.Time{}
	if offers, failure := s.Check(t.Context(), a, device); failure != nil || offers.Incomplete || len(offers.Offers) != 0 {
		t.Fatalf("authoritative empty catalog: offers %d, failure %+v", len(offers.Offers), failure)
	}
	source.releases = []githubRelease{stable, preview}
	s.catalogAt = time.Time{}
	if offers, failure := s.Check(t.Context(), a, device); failure != nil || len(offers.Offers) != 2 || offers.Offers[1].ID == offersA.Offers[1].ID {
		t.Fatalf("republication did not issue new offers: %+v %+v", offers, failure)
	}
	if _, failure := s.Status(t.Context(), a, previewA.ID); failure != expired {
		t.Fatalf("old handle revived after republication: %+v", failure)
	}
}

func TestTVUpdateRequestWindowsBoundDeviceAndAccount(t *testing.T) {
	s := newService(nil, trustRoot{})
	t.Cleanup(s.Close)
	now := time.Now()
	first, second := Owner{1, "device-a"}, Owner{1, "device-b"}
	for _, owner := range []Owner{first, second} {
		for range deviceRequests {
			if failure := s.admitRequest(owner, now); failure != nil {
				t.Fatalf("device blocked before its budget: %+v", failure)
			}
		}
		if failure := s.admitRequest(owner, now); failure != rateLimited {
			t.Fatalf("device budget failed to apply: %+v", failure)
		}
	}
	if failure := s.admitRequest(Owner{1, "device-c"}, now); failure != rateLimited {
		t.Fatalf("new device bypassed account budget: %+v", failure)
	}
	if failure := s.admitRequest(Owner{2, "device-d"}, now); failure != nil {
		t.Fatalf("unrelated account was limited: %+v", failure)
	}
	if failure := s.admitRequest(first, now.Add(requestWindow)); failure != nil {
		t.Fatalf("quota did not recover at window boundary: %+v", failure)
	}
}

func TestTVUpdateRefreshFailuresAndWithdrawnPreparation(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	source := &testSource{metadata: map[int64][]byte{}, apk: []byte("signed"), unblock: make(chan struct{})}
	testSignedRelease(t, source, key, "0.5.0", 8, false, "arm64-v8a")
	s := newService(source, trustRoot{&key.PublicKey, originalSigner})
	t.Cleanup(s.Close)
	device := Device{7, 28, []string{"arm64-v8a"}}
	owners := []Owner{{1, "device-a"}, {1, "device-b"}}
	ids := make([]string, len(owners))
	for i, owner := range owners {
		offers, failure := s.Check(t.Context(), owner, device)
		if failure != nil || len(offers.Offers) != 1 {
			t.Fatalf("check for owner %d: %d offers, %+v", i, len(offers.Offers), failure)
		}
		status, failure := s.Start(t.Context(), owner, offers.Offers[0].ID)
		if failure != nil || status.State != "preparing" {
			t.Fatalf("shared preparation for owner %d: %+v %+v", i, status, failure)
		}
		ids[i] = status.ID
	}
	for deadline := time.Now().Add(time.Second); source.fetches.Load() == 0 && time.Now().Before(deadline); {
		time.Sleep(time.Millisecond)
	}
	if source.fetches.Load() != 1 {
		t.Fatalf("expected one shared download, got %d", source.fetches.Load())
	}
	resetRefresh := func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.catalogAt = time.Time{}
		s.failedUntil = time.Time{}
	}
	limited := &Failure{Status: http.StatusTooManyRequests, Code: "UPSTREAM_RATE_LIMITED", RetryAfter: time.Minute}
	for _, outage := range []struct{ list, manifest *Failure }{{unavailable, nil}, {limited, nil}, {nil, unavailable}, {nil, limited}} {
		source.listFailure, source.readFailure = outage.list, outage.manifest
		resetRefresh()
		expected := outage.list
		if expected == nil {
			expected = outage.manifest
		}
		if _, failure := s.Check(t.Context(), owners[0], device); failure != expected {
			t.Fatalf("outage was treated as an empty catalog: %+v", failure)
		}
		for i, owner := range owners {
			if status, failure := s.Status(t.Context(), owner, ids[i]); failure != nil || status.State != "preparing" {
				t.Fatalf("outage revoked existing owner %d: %+v %+v", i, status, failure)
			}
		}
	}
	// A complete listing omits the selected release. A temporary error reading
	// a different release's signed metadata must not undo that observation.
	source.releases = nil
	testSignedRelease(t, source, key, "0.6.0", 9, true, "arm64-v8a")
	source.listFailure, source.readFailure = nil, unavailable
	resetRefresh()
	if _, failure := s.Check(t.Context(), owners[0], device); failure != unavailable {
		t.Fatalf("unavailable remaining metadata: %+v", failure)
	}
	for i, owner := range owners {
		if _, failure := s.Status(t.Context(), owner, ids[i]); failure != expired {
			t.Fatalf("withdrawn owner %d handle survived: %+v", i, failure)
		}
	}
	stopped := make(chan struct{})
	go func() { s.workers.Wait(); close(stopped) }()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("withdrawn shared upstream download was not canceled")
	}
	if len(s.freeBlocks) != s.allocatedBlocks {
		t.Fatalf("withdrawn preparation retained blocks: free %d allocated %d", len(s.freeBlocks), s.allocatedBlocks)
	}
}

func TestTVUpdateMemoryBudgetIncludesWithdrawnReadersAndKeepsFilesPrivate(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	runtimeDir := t.TempDir()
	t.Setenv("TMPDIR", runtimeDir)
	t.Setenv("TEMP", runtimeDir)
	t.Setenv("TMP", runtimeDir)
	alias := filepath.Join(t.TempDir(), "cache-alias")
	aliasErr := os.Symlink(runtimeDir, alias)
	original := []byte("verified pinned artifact")
	source := &testSource{metadata: map[int64][]byte{}, apk: original}
	testSignedRelease(t, source, key, "0.5.0", 8, false, "arm64-v8a")
	s := newService(source, trustRoot{&key.PublicKey, originalSigner})
	s.blockLimit = 2
	t.Cleanup(s.Close)
	owner, device := Owner{1, "device-a"}, Device{7, 28, []string{"arm64-v8a"}}
	offer := func() string {
		t.Helper()
		directory, failure := s.Check(t.Context(), owner, device)
		if failure != nil || len(directory.Offers) != 1 {
			t.Fatalf("directory: %+v %+v", directory, failure)
		}
		return directory.Offers[0].ID
	}
	first, failure := s.Start(t.Context(), owner, offer())
	if failure != nil || waitReady(t, s, owner, first.ID).State != "ready" {
		t.Fatalf("first download: %+v %+v", first, failure)
	}
	pinned, _, release, failure := s.open(t.Context(), owner, first.ID)
	if failure != nil {
		t.Fatal(failure)
	}
	defer release()
	if s.allocatedBlocks != 1 {
		t.Fatalf("small APK unexpectedly allocated %d blocks", s.allocatedBlocks)
	}
	larger := bytes.Repeat([]byte("A7b"), cacheBlockSize/3+12)
	source.apk = larger
	source.releases = nil
	testSignedRelease(t, source, key, "0.6.0", 9, false, "arm64-v8a")
	s.catalogAt = time.Time{}
	nextOffer := offer()
	if _, failure := s.Status(t.Context(), owner, first.ID); failure != expired {
		t.Fatalf("withdrawn handle survived: %+v", failure)
	}
	if _, failure := s.Start(t.Context(), owner, nextOffer); failure != cacheSpace || source.fetches.Load() != 1 {
		t.Fatalf("pinned bytes did not count against the memory budget: %+v", failure)
	}
	if got, err := io.ReadAll(pinned); err != nil || !bytes.Equal(got, original) {
		t.Fatalf("withdrawn reader was disturbed: %q %v", got, err)
	}
	release()
	second, failure := s.Start(t.Context(), owner, nextOffer)
	if failure != nil || waitReady(t, s, owner, second.ID).State != "ready" {
		t.Fatalf("second download: %+v %+v", second, failure)
	}
	if s.allocatedBlocks != s.blockLimit {
		t.Fatalf("memory pool exceeded or missed its exact block budget: %d", s.allocatedBlocks)
	}
	request := httptest.NewRequest(http.MethodGet, "/", nil)
	request.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", cacheBlockSize-3, cacheBlockSize+4))
	response := httptest.NewRecorder()
	if failure := s.ServeContent(response, request, owner, second.ID); failure != nil || response.Code != http.StatusPartialContent ||
		!bytes.Equal(response.Body.Bytes(), larger[cacheBlockSize-3:cacheBlockSize+5]) {
		t.Fatalf("range across cached blocks: %d %q %+v", response.Code, response.Body.Bytes(), failure)
	}
	source.apk = []byte("preview replacement")
	testSignedRelease(t, source, key, "0.7.0", 10, true, "arm64-v8a")
	s.catalogAt = time.Time{}
	directory, failure := s.Check(t.Context(), owner, device)
	if failure != nil || len(directory.Offers) != 2 {
		t.Fatalf("eviction directory: %+v %+v", directory, failure)
	}
	third, failure := s.Start(t.Context(), owner, directory.Offers[1].ID)
	if failure != nil || waitReady(t, s, owner, third.ID).State != "ready" || s.allocatedBlocks != s.blockLimit {
		t.Fatalf("eviction failed to reuse cached blocks: %+v %+v", third, failure)
	}
	if _, failure := s.Status(t.Context(), owner, second.ID); failure != expired {
		t.Fatalf("evicted artifact's download handle survived: %+v", failure)
	}
	if entries, err := os.ReadDir(runtimeDir); err != nil || len(entries) != 0 {
		t.Fatalf("runtime directory exposed files: %v %v", entries, err)
	}
	if aliasErr == nil {
		if entries, err := os.ReadDir(alias); err != nil || len(entries) != 0 {
			t.Fatalf("symlink into runtime directory exposed files: %v %v", entries, err)
		}
	} else {
		t.Logf("native symlink unavailable on test host: %v", aliasErr)
	}
}

func TestTVUpdateCloseCancelsPreparationAndKeepsOpenedReader(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	source := &testSource{metadata: map[int64][]byte{}, apk: []byte("verified before close")}
	testSignedRelease(t, source, key, "0.5.0", 8, false, "arm64-v8a")
	testSignedRelease(t, source, key, "0.6.0", 9, true, "arm64-v8a")
	s := newService(source, trustRoot{&key.PublicKey, originalSigner})
	t.Cleanup(s.Close)
	owner, device := Owner{1, "device-a"}, Device{7, 28, []string{"arm64-v8a"}}
	directory, failure := s.Check(t.Context(), owner, device)
	if failure != nil || len(directory.Offers) != 2 {
		t.Fatalf("check: %+v %+v", directory, failure)
	}
	first, failure := s.Start(t.Context(), owner, directory.Offers[0].ID)
	if failure != nil || waitReady(t, s, owner, first.ID).State != "ready" {
		t.Fatalf("first download: %+v %+v", first, failure)
	}
	pinned, _, release, failure := s.open(t.Context(), owner, first.ID)
	if failure != nil {
		t.Fatal(failure)
	}
	defer release()
	source.unblock = make(chan struct{})
	if second, failure := s.Start(t.Context(), owner, directory.Offers[1].ID); failure != nil || second.State != "preparing" {
		t.Fatalf("second download: %+v %+v", second, failure)
	}
	for deadline := time.Now().Add(time.Second); source.fetches.Load() < 2 && time.Now().Before(deadline); {
		time.Sleep(time.Millisecond)
	}
	if source.fetches.Load() != 2 {
		t.Fatal("upstream worker did not start")
	}
	s.Close()
	if got, err := io.ReadAll(pinned); err != nil || !bytes.Equal(got, source.apk) {
		t.Fatalf("open reader was disturbed by shutdown: %q %v", got, err)
	}
	if _, failure := s.Status(t.Context(), owner, first.ID); failure != expired {
		t.Fatalf("closed service allowed a new read: %+v", failure)
	}
	if _, failure := s.Check(t.Context(), owner, device); failure != unavailable {
		t.Fatalf("closed service issued a new offer: %+v", failure)
	}
	if len(s.freeBlocks) != 0 || len(s.downloads) != 0 {
		t.Fatal("closed service kept an unpinned cache")
	}
}
