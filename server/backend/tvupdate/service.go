package tvupdate

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	catalogTTL      = 5 * time.Minute
	offerTTL        = 30 * time.Minute
	downloadTTL     = 2 * time.Hour
	downloadTimeout = 10 * time.Minute
	maxHandles      = 512
	requestWindow   = 10 * time.Second
	accountRequests = 40
	deviceRequests  = 20
)

// Failure contains only information that may be returned to clients.
type Failure struct {
	Status     int           `json:"-"`
	Code       string        `json:"code"`
	Message    string        `json:"message"`
	RetryAfter time.Duration `json:"-"`
}

var (
	InvalidRequest      = &Failure{Status: 400, Code: "INVALID_REQUEST", Message: "电视更新请求格式无效"}
	unavailable         = &Failure{Status: 503, Code: "UPDATE_UNAVAILABLE", Message: "暂时无法连接官方更新服务，请稍后重试"}
	metadataUnavailable = &Failure{Status: 503, Code: "UPDATE_METADATA_UNAVAILABLE", Message: "暂未取得可验证的电视更新信息，请稍后重试", RetryAfter: time.Minute}
	expired             = &Failure{Status: 404, Code: "UPDATE_EXPIRED", Message: "更新请求已失效，请重新检查"}
	busy                = &Failure{Status: 503, Code: "UPDATE_BUSY", Message: "服务器正在处理其他更新，请稍后重试", RetryAfter: 5 * time.Second}
	notReady            = &Failure{Status: 409, Code: "UPDATE_NOT_READY", Message: "服务器尚未准备好安装包"}
	cacheSpace          = &Failure{Status: 503, Code: "UPDATE_CACHE_UNAVAILABLE", Message: "服务器更新缓存暂时没有足够空间，请稍后重试", RetryAfter: 5 * time.Second}
	rateLimited         = &Failure{Status: 429, Code: "UPDATE_RATE_LIMITED", Message: "电视更新请求过多，请稍后重试", RetryAfter: requestWindow}
	badArtifact         = &Failure{Status: 502, Code: "UPDATE_ARTIFACT_INVALID", Message: "官方安装包未通过完整性校验，请重新检查"}
	handlePattern       = regexp.MustCompile(`^[A-Za-z0-9_-]{32}$`)
)

type Owner struct {
	Account       uint
	DeviceSession string
}

type Device struct {
	VersionCode int64
	API         int
	ABIs        []string
}

func (device Device) valid() bool {
	return device.VersionCode > 0 && device.API >= 28 && device.API <= 1000 && validABIs(device.ABIs)
}

func (device Device) supports(m manifest) bool {
	if m.VersionCode <= device.VersionCode || m.MinSDK > device.API {
		return false
	}
	for _, abi := range device.ABIs {
		if slices.Contains(m.ABIs, abi) {
			return true
		}
	}
	return false
}

type Offer struct {
	ID        string `json:"offerId"`
	Manifest  []byte `json:"manifestBase64"`
	Signature []byte `json:"signatureBase64"`
}

type Directory struct {
	Offers     []Offer `json:"offers"`
	Incomplete bool    `json:"incomplete,omitempty"`
}

type Status struct {
	ID       string `json:"downloadId"`
	State    string `json:"state"`
	Received int64  `json:"receivedBytes"`
	Total    int64  `json:"totalBytes"`
	Code     string `json:"code,omitempty"`
	Message  string `json:"message,omitempty"`
}

type candidate struct {
	manifest         manifest
	payload          []byte
	signature        []byte
	assetID          int64
	manifestAssetID  int64
	signatureAssetID int64
}

func (c candidate) key() string { return fmt.Sprintf("%d-%s.apk", c.assetID, c.manifest.SHA256) }

type handle struct {
	owner        Owner
	key          string
	manifestHash [32]byte
	expires      time.Time
}

type download struct {
	candidate candidate
	cancel    context.CancelFunc
	received  int64
	failure   *Failure
	ready     bool
	apk       *cachedAPK
}

func (d *download) status(id string) Status {
	status := Status{ID: id, State: "preparing", Received: d.received, Total: d.candidate.manifest.Size}
	if d.failure != nil {
		status.State, status.Code, status.Message = "failed", d.failure.Code, d.failure.Message
	} else if d.ready {
		status.State = "ready"
	}
	return status
}

type upstream interface {
	list(context.Context) ([]githubRelease, *Failure)
	readAsset(context.Context, githubAsset, int64) ([]byte, *Failure)
	openAsset(context.Context, int64) (*http.Response, *Failure)
}

// Service is owned by one running Fileway instance. Handles and in-flight work
// are local to the instance. APK backing bytes are kept in a bounded block pool.
type Service struct {
	blockLimit        int
	allocatedBlocks   int
	freeBlocks        [][]byte
	source            upstream
	trust             trustRoot
	ctx               context.Context
	stop              context.CancelFunc
	refresh           chan struct{}
	streams           chan struct{}
	streamOwners      map[Owner]bool
	workers           sync.WaitGroup
	mu                sync.Mutex
	catalog           map[string]candidate
	catalogAt         time.Time
	catalogIncomplete bool
	failedUntil       time.Time
	catalogFailure    *Failure
	artifactBackoff   time.Time
	artifactFailure   *Failure
	offers            map[string]handle
	handles           map[string]handle
	downloads         map[string]*download
	requestWindowEnd  time.Time
	accountCalls      map[uint]int
	deviceCalls       map[Owner]int
	active            bool
}

func New() (*Service, error) {
	trust, err := embeddedTrust()
	if err != nil {
		return nil, err
	}
	return newService(newGitHubSource(), trust), nil
}

func newService(source upstream, trust trustRoot) *Service {
	ctx, stop := context.WithCancel(context.Background())
	return &Service{blockLimit: cacheBlocks, source: source, trust: trust, ctx: ctx, stop: stop,
		refresh: make(chan struct{}, 1), streams: make(chan struct{}, 2), streamOwners: make(map[Owner]bool),
		offers: make(map[string]handle), handles: make(map[string]handle), downloads: make(map[string]*download),
		accountCalls: make(map[uint]int), deviceCalls: make(map[Owner]int)}
}

func (s *Service) Close() {
	s.mu.Lock()
	s.stop()
	for key := range s.downloads {
		s.dropDownload(key)
	}
	clear(s.offers)
	clear(s.handles)
	s.freeBlocks = nil
	s.mu.Unlock()
	s.workers.Wait()
}

func (s *Service) Check(ctx context.Context, owner Owner, device Device) (Directory, *Failure) {
	if !device.valid() {
		return Directory{}, InvalidRequest
	}
	s.mu.Lock()
	admission := s.admitRequest(owner, time.Now())
	s.mu.Unlock()
	if admission != nil {
		return Directory{}, admission
	}
	if failure := s.refreshCatalog(ctx); failure != nil {
		return Directory{}, failure
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil {
		return Directory{}, unavailable
	}
	now := time.Now()
	s.pruneHandles(now)
	best := make(map[string]candidate, 2)
	for _, c := range s.catalog {
		if device.supports(c.manifest) && c.manifest.VersionCode > best[c.manifest.Channel].manifest.VersionCode {
			best[c.manifest.Channel] = c
		}
	}
	offers := make([]Offer, 0, len(best))
	for _, channel := range []string{"stable", "preview"} {
		if c, ok := best[channel]; ok {
			id, failure := bindHandle(s.offers, owner, c, now, offerTTL)
			if failure != nil {
				return Directory{}, failure
			}
			offers = append(offers, Offer{id, c.payload, c.signature})
		}
	}
	return Directory{Offers: offers, Incomplete: s.catalogIncomplete}, nil
}

func bindHandle(handles map[string]handle, owner Owner, c candidate, now time.Time, ttl time.Duration) (string, *Failure) {
	key, digest := c.key(), sha256.Sum256(c.payload)
	record := handle{owner, key, digest, now.Add(ttl)}
	for id, existing := range handles {
		if existing.key == key && existing.owner == owner && existing.manifestHash == digest {
			handles[id] = record
			return id, nil
		}
	}
	if len(handles) >= maxHandles {
		return "", busy
	}
	var raw [24]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", unavailable
	}
	id := base64.RawURLEncoding.EncodeToString(raw[:])
	handles[id] = record
	return id, nil
}

func (s *Service) pruneHandles(now time.Time) {
	for _, handles := range []map[string]handle{s.offers, s.handles} {
		for id, h := range handles {
			if !h.expires.After(now) {
				delete(handles, id)
			}
		}
	}
}

func (s *Service) authorize(handles map[string]handle, owner Owner, id string, now time.Time) (candidate, bool) {
	h, ok := handles[id]
	if !ok || h.owner != owner || !h.expires.After(now) {
		return candidate{}, false
	}
	c, ok := s.catalog[h.key]
	return c, ok && h.manifestHash == sha256.Sum256(c.payload)
}

// admitRequest is called with the service lock held. A common short window
// bounds memory and leaves room for the TV's one-second preparation polling.
func (s *Service) admitRequest(owner Owner, now time.Time) *Failure {
	if !now.Before(s.requestWindowEnd) {
		clear(s.accountCalls)
		clear(s.deviceCalls)
		s.requestWindowEnd = now.Add(requestWindow)
	}
	accountCount, accountTracked := s.accountCalls[owner.Account]
	if accountCount >= accountRequests || !accountTracked && len(s.accountCalls) >= maxHandles {
		return rateLimited
	}
	if owner.DeviceSession != "" {
		deviceCount, deviceTracked := s.deviceCalls[owner]
		if deviceCount >= deviceRequests || !deviceTracked && len(s.deviceCalls) >= maxHandles {
			return rateLimited
		}
		s.deviceCalls[owner] = deviceCount + 1
	}
	s.accountCalls[owner.Account] = accountCount + 1
	return nil
}

func (s *Service) Start(ctx context.Context, owner Owner, offerID string) (Status, *Failure) {
	if !handlePattern.MatchString(offerID) {
		return Status{}, InvalidRequest
	}
	s.mu.Lock()
	now := time.Now()
	_, allowed := s.authorize(s.offers, owner, offerID, now)
	if !allowed {
		s.mu.Unlock()
		return Status{}, expired
	}
	admission := s.admitRequest(owner, now)
	s.mu.Unlock()
	if admission != nil {
		return Status{}, admission
	}
	refreshFailure := s.refreshCatalog(ctx)
	s.mu.Lock()
	defer s.mu.Unlock()
	now = time.Now()
	s.pruneHandles(now)
	c, allowed := s.authorize(s.offers, owner, offerID, now)
	if !allowed {
		return Status{}, expired
	}
	if refreshFailure != nil {
		return Status{}, refreshFailure
	}
	if s.ctx.Err() != nil {
		return Status{}, unavailable
	}
	key := c.key()
	d := s.downloads[key]
	if d == nil || d.failure != nil {
		if now.Before(s.artifactBackoff) {
			return Status{}, s.artifactFailure
		}
		if s.active {
			return Status{}, busy
		}
	}
	var apk *cachedAPK
	if d == nil || d.failure != nil {
		apk = s.reserveAPK(c.manifest.Size, now)
		if apk == nil {
			return Status{}, cacheSpace
		}
	}
	id, failure := bindHandle(s.handles, owner, c, now, downloadTTL)
	if failure != nil {
		if apk != nil {
			s.retireAPK(apk)
		}
		return Status{}, failure
	}
	if apk != nil {
		ctx, cancel := context.WithTimeout(s.ctx, downloadTimeout)
		d = &download{candidate: c, cancel: cancel, apk: apk}
		s.downloads[key] = d
		s.active = true
		s.workers.Add(1)
		go s.prepare(ctx, d)
	}
	return d.status(id), nil
}

func (s *Service) Status(ctx context.Context, owner Owner, id string) (Status, *Failure) {
	if failure := s.refreshDownload(ctx, owner, id); failure != nil {
		return Status{}, failure
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	c, allowed := s.authorize(s.handles, owner, id, time.Now())
	if !allowed {
		return Status{}, expired
	}
	d := s.downloads[c.key()]
	if d == nil {
		return Status{}, expired
	}
	return d.status(id), nil
}

// ServeContent streams a verified file through an authenticated caller's handle.
func (s *Service) ServeContent(w http.ResponseWriter, r *http.Request, owner Owner, id string) *Failure {
	file, digest, release, failure := s.open(r.Context(), owner, id)
	if failure != nil {
		return failure
	}
	defer release()
	if requested := r.Header.Get("Range"); len(requested) > 96 || strings.Contains(requested, ",") {
		w.Header().Set("Content-Range", "bytes */"+strconv.FormatInt(file.Size(), 10))
		return &Failure{Status: 416, Code: "INVALID_RANGE", Message: "安装包下载范围无效"}
	}
	controller := http.NewResponseController(w)
	if controller.SetWriteDeadline(time.Now().Add(15*time.Minute)) == nil {
		defer func() { _ = controller.SetWriteDeadline(time.Time{}) }()
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("ETag", `"`+digest+`"`)
	http.ServeContent(w, r, "Fileway-TV.apk", time.Time{}, file)
	return nil
}

// open pins verified bytes until the returned release callback is called.
func (s *Service) open(ctx context.Context, owner Owner, id string) (*io.SectionReader, string, func(), *Failure) {
	if failure := s.refreshDownload(ctx, owner, id); failure != nil {
		return nil, "", nil, failure
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	c, allowed := s.authorize(s.handles, owner, id, time.Now())
	if !allowed {
		return nil, "", nil, expired
	}
	key := c.key()
	d := s.downloads[key]
	if d == nil || !d.ready {
		return nil, "", nil, notReady
	}
	if s.streamOwners[owner] {
		return nil, "", nil, busy
	}
	select {
	case s.streams <- struct{}{}:
	default:
		return nil, "", nil, busy
	}
	apk := d.apk
	apk.readers++
	apk.usedAt = time.Now()
	s.streamOwners[owner] = true
	return io.NewSectionReader(apk, 0, apk.size), c.manifest.SHA256, sync.OnceFunc(func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		apk.readers--
		if apk.readers == 0 && apk.retired {
			s.recycleAPK(apk)
		}
		delete(s.streamOwners, owner)
		<-s.streams
	}), nil
}

// Revalidate only owned handles. Transient upstream failures preserve access to
// an already authorized download, and confirmed withdrawals remove the handle.
func (s *Service) refreshDownload(ctx context.Context, owner Owner, id string) *Failure {
	s.mu.Lock()
	now := time.Now()
	_, allowed := s.authorize(s.handles, owner, id, now)
	if !allowed {
		s.mu.Unlock()
		return expired
	}
	admission := s.admitRequest(owner, now)
	s.mu.Unlock()
	if admission != nil {
		return admission
	}
	_ = s.refreshCatalog(ctx)
	return nil
}

func (s *Service) refreshCatalog(ctx context.Context) *Failure {
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	select {
	case s.refresh <- struct{}{}:
		defer func() { <-s.refresh }()
	case <-ctx.Done():
		return unavailable
	case <-s.ctx.Done():
		return unavailable
	}
	s.mu.Lock()
	if s.ctx.Err() != nil {
		s.mu.Unlock()
		return unavailable
	}
	if time.Since(s.catalogAt) < catalogTTL {
		s.mu.Unlock()
		return nil
	}
	if time.Now().Before(s.failedUntil) {
		failure := s.catalogFailure
		s.mu.Unlock()
		return failure
	}
	s.mu.Unlock()
	stop := context.AfterFunc(s.ctx, cancel)
	defer stop()
	releases, failure := s.source.list(ctx)
	listed := failure == nil
	var scan catalogScan
	if listed {
		scan, failure = s.fetchCatalog(ctx, releases)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil {
		return unavailable
	}
	if failure == nil {
		s.catalog = scan.candidates
		s.catalogIncomplete = len(scan.rejected) != 0
		s.catalogAt = time.Now()
	} else if listed {
		// A complete listing can confirm removal independently of a failure
		// fetching or verifying another release's manifest bytes.
		for key, c := range s.catalog {
			if scan.rejected[c.manifest.Tag] || !c.stillPublished(releases) {
				delete(s.catalog, key)
			}
		}
	}
	if listed {
		s.discardWithdrawn()
	}
	if failure != nil {
		s.catalogFailure = failure
		s.failedUntil = time.Now().Add(max(10*time.Second, failure.RetryAfter))
		return failure
	}
	s.catalogFailure = nil
	s.failedUntil = time.Time{}
	return nil
}

func (c candidate) stillPublished(releases []githubRelease) bool {
	for _, release := range releases {
		if release.Tag != c.manifest.Tag || release.Draft || release.Prerelease != (c.manifest.Channel == "preview") {
			continue
		}
		apk, _ := uniqueAsset(release.Assets, c.manifest.APKName)
		manifest, _ := uniqueAsset(release.Assets, manifestName)
		signature, _ := uniqueAsset(release.Assets, signatureName)
		if apk.ID == c.assetID && apk.Size == c.manifest.Size &&
			(apk.Digest == "" || apk.Digest == "sha256:"+c.manifest.SHA256) &&
			manifest.ID == c.manifestAssetID && signature.ID == c.signatureAssetID {
			return true
		}
	}
	return false
}

// Remove handles permanently, even if the same signed artifact is published
// again. An open reader retains its pinned blocks until it finishes.
func (s *Service) discardWithdrawn() {
	for _, handles := range []map[string]handle{s.offers, s.handles} {
		for id, h := range handles {
			if c, exists := s.catalog[h.key]; !exists || h.manifestHash != sha256.Sum256(c.payload) {
				delete(handles, id)
			}
		}
	}
	for key, d := range s.downloads {
		if c, exists := s.catalog[key]; !exists || !slices.Equal(c.payload, d.candidate.payload) {
			s.dropDownload(key)
		}
	}
}

type catalogScan struct {
	candidates map[string]candidate
	rejected   map[string]bool
}

func (s *Service) fetchCatalog(ctx context.Context, releases []githubRelease) (catalogScan, *Failure) {
	scan := catalogScan{candidates: make(map[string]candidate), rejected: make(map[string]bool)}
	tags := make(map[string]int)
	for _, release := range releases {
		if !release.Draft && tvTag.MatchString(release.Tag) {
			tags[release.Tag]++
			if tags[release.Tag] > 1 {
				scan.rejected[release.Tag] = true
			}
		}
	}
	versionCodes := make(map[int64]int)
	assetIDs := make(map[int64]int)
	var parsed []candidate
	var outage *Failure
	for _, release := range releases {
		if release.Draft || tags[release.Tag] != 1 {
			continue
		}
		manifestAsset, hasManifest := uniqueAsset(release.Assets, manifestName)
		signatureAsset, hasSignature := uniqueAsset(release.Assets, signatureName)
		if !hasManifest && !hasSignature {
			if slices.ContainsFunc(release.Assets, func(a githubAsset) bool { return a.Name == manifestName || a.Name == signatureName }) {
				scan.rejected[release.Tag] = true
			}
			continue
		}
		if !hasManifest || !hasSignature {
			scan.rejected[release.Tag] = true
			continue
		}
		payload, failure := s.source.readAsset(ctx, manifestAsset, manifestLimit)
		if failure == metadataUnavailable {
			scan.rejected[release.Tag] = true
			continue
		}
		if failure != nil {
			outage = failure
			break
		}
		signature, failure := s.source.readAsset(ctx, signatureAsset, signatureLimit)
		if failure == metadataUnavailable {
			scan.rejected[release.Tag] = true
			continue
		}
		if failure != nil {
			outage = failure
			break
		}
		m, verified := s.trust.parse(payload, signature)
		if !verified || m.Tag != release.Tag || (m.Channel == "preview") != release.Prerelease {
			scan.rejected[release.Tag] = true
			continue
		}
		versionCodes[m.VersionCode]++
		apk, ok := uniqueAsset(release.Assets, m.APKName)
		if !ok || apk.Size != m.Size || (apk.Digest != "" && apk.Digest != "sha256:"+m.SHA256) {
			scan.rejected[release.Tag] = true
			continue
		}
		assetIDs[apk.ID]++
		parsed = append(parsed, candidate{m, payload, signature, apk.ID, manifestAsset.ID, signatureAsset.ID})
	}
	for _, c := range parsed {
		if versionCodes[c.manifest.VersionCode] != 1 || assetIDs[c.assetID] != 1 {
			scan.rejected[c.manifest.Tag] = true
			continue
		}
		scan.candidates[c.key()] = c
	}
	return scan, outage
}

func uniqueAsset(assets []githubAsset, name string) (githubAsset, bool) {
	var match githubAsset
	for _, asset := range assets {
		if asset.Name == name {
			if match.ID != 0 || asset.ID <= 0 || asset.State != "uploaded" {
				return githubAsset{}, false
			}
			match = asset
		}
	}
	return match, match.ID != 0
}

func (s *Service) prepare(ctx context.Context, d *download) {
	defer s.workers.Done()
	defer d.cancel()
	failure := s.cacheArtifact(ctx, d)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.active = false
	if s.downloads[d.candidate.key()] != d {
		s.retireAPK(d.apk)
		return
	}
	d.failure = failure
	d.ready = failure == nil
	if failure != nil {
		s.retireAPK(d.apk)
		d.apk = nil
		if failure.RetryAfter > 0 {
			s.artifactBackoff = time.Now().Add(failure.RetryAfter)
			s.artifactFailure = failure
		}
	}
}

func (s *Service) cacheArtifact(ctx context.Context, d *download) *Failure {
	c := d.candidate
	response, failure := s.source.openAsset(ctx, c.assetID)
	if failure != nil {
		return failure
	}
	defer func() { _ = response.Body.Close() }()
	if response.ContentLength >= 0 && response.ContentLength != c.manifest.Size {
		return badArtifact
	}
	hash := sha256.New()
	var count int64
	for _, block := range d.apk.blocks {
		if ctx.Err() != nil {
			return unavailable
		}
		target := block[:min(int64(len(block)), c.manifest.Size-count)]
		n, err := io.ReadFull(response.Body, target)
		count += int64(n)
		_, _ = hash.Write(target[:n])
		s.mu.Lock()
		d.received = count
		s.mu.Unlock()
		if err == io.ErrUnexpectedEOF || err == io.EOF {
			return badArtifact
		}
		if err != nil {
			return unavailable
		}
	}
	var extra [1]byte
	_, err := io.ReadFull(response.Body, extra[:])
	if err == nil || hex.EncodeToString(hash.Sum(nil)) != c.manifest.SHA256 {
		return badArtifact
	}
	if err != io.EOF || ctx.Err() != nil {
		return unavailable
	}
	return nil
}
