package tvupdate

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"time"
)

const (
	githubAPI         = "https://api.github.com/repos/Kkwans/Fileway/releases"
	assetHost         = "release-assets.githubusercontent.com"
	catalogPageLimit  = 2 << 20
	catalogTotalLimit = 8 << 20
	catalogPages      = 5
)

var errResponseTooLarge = errors.New("response too large")

type githubAsset struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	State     string `json:"state"`
	Size      int64  `json:"size"`
	Digest    string `json:"digest"`
	UpdatedAt string `json:"updated_at"`
}

type githubRelease struct {
	Tag        string        `json:"tag_name"`
	Draft      bool          `json:"draft"`
	Prerelease bool          `json:"prerelease"`
	Assets     []githubAsset `json:"assets"`
}

type catalogPage struct {
	bytes    int64
	etag     string
	more     bool
	releases []githubRelease
}

type githubSource struct {
	client   *http.Client
	api      string
	pages    [catalogPages]catalogPage
	metadata map[githubAsset][]byte
}

func newGitHubSource() *githubSource {
	return &githubSource{api: githubAPI, metadata: make(map[githubAsset][]byte), client: &http.Client{
		Transport: &http.Transport{
			DialContext: githubDial, TLSHandshakeTimeout: 8 * time.Second,
			ResponseHeaderTimeout: 12 * time.Second, IdleConnTimeout: 45 * time.Second,
			MaxIdleConns: 4, MaxConnsPerHost: 3, DisableCompression: true,
			ForceAttemptHTTP2: true,
		},
		CheckRedirect: githubRedirect,
	}}
}

func githubRedirect(req *http.Request, via []*http.Request) error {
	u := req.URL
	if len(via) != 1 || !strings.HasPrefix(via[0].URL.Path, "/repos/Kkwans/Fileway/releases/assets/") ||
		u.Scheme != "https" || u.Hostname() != assetHost || (u.Port() != "" && u.Port() != "443") ||
		u.User != nil || u.Fragment != "" || u.Opaque != "" ||
		!strings.HasPrefix(u.EscapedPath(), "/github-production-release-asset/") {
		return errors.New("unexpected GitHub redirect")
	}
	return nil
}

var nonPublic = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.0.0/24"), netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"),
	netip.MustParsePrefix("203.0.113.0/24"), netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("2001::/32"),
	netip.MustParsePrefix("2002::/16"), netip.MustParsePrefix("3fff::/20"),
}

func publicGitHubIP(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.Zone() != "" ||
		(ip.Is6() && !netip.MustParsePrefix("2000::/3").Contains(ip)) {
		return false
	}
	for _, reserved := range nonPublic {
		if reserved.Contains(ip) {
			return false
		}
	}
	return true
}

func githubDial(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil || (host != "api.github.com" && host != assetHost) || port != "443" {
		return nil, errors.New("unexpected GitHub host")
	}
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return nil, err
	}
	for _, ip := range ips {
		if !publicGitHubIP(ip) {
			return nil, errors.New("unexpected GitHub DNS answer")
		}
	}
	dialer := net.Dialer{Timeout: 4 * time.Second, KeepAlive: 30 * time.Second}
	for _, ip := range ips {
		conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		if err == nil {
			return conn, nil
		}
	}
	return nil, errors.New("cannot connect to GitHub")
}

func (source *githubSource) request(ctx context.Context, endpoint, accept, etag string) (*http.Response, *Failure) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, unavailable
	}
	req.Header.Set("Accept", accept)
	req.Header.Set("User-Agent", "Fileway-TV-Update")
	req.Header.Set("X-GitHub-Api-Version", "2026-03-10")
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	response, err := source.client.Do(req)
	if err != nil {
		return nil, unavailable
	}
	if response.StatusCode == http.StatusOK || response.StatusCode == http.StatusNotModified {
		return response, nil
	}
	_ = response.Body.Close()
	if response.StatusCode == http.StatusTooManyRequests ||
		(response.StatusCode == http.StatusForbidden && (response.Header.Get("X-RateLimit-Remaining") == "0" || response.Header.Get("Retry-After") != "")) {
		return nil, rateLimitFailure(response)
	}
	// An individual asset GET can disagree temporarily with the complete list.
	// Only the latter can establish that a previously published asset is gone.
	return nil, unavailable
}

func rateLimitFailure(response *http.Response) *Failure {
	retry := time.Minute
	raw := response.Header.Get("Retry-After")
	if seconds, err := strconv.ParseInt(raw, 10, 32); err == nil && seconds > 0 {
		retry = time.Duration(seconds) * time.Second
	} else if stamp, err := http.ParseTime(raw); err == nil {
		retry = time.Until(stamp)
	} else if reset, err := strconv.ParseInt(response.Header.Get("X-RateLimit-Reset"), 10, 64); err == nil {
		retry = time.Until(time.Unix(reset, 0))
	}
	return &Failure{Status: http.StatusTooManyRequests, Code: "UPSTREAM_RATE_LIMITED", Message: "官方更新服务暂时限制请求，请稍后重试", RetryAfter: min(15*time.Minute, max(time.Second, retry))}
}

func (source *githubSource) list(ctx context.Context) ([]githubRelease, *Failure) {
	var releases []githubRelease
	remaining := int64(catalogTotalLimit)
	for index := range source.pages {
		page := source.pages[index]
		endpoint := fmt.Sprintf("%s?per_page=100&page=%d", source.api, index+1)
		response, failure := source.request(ctx, endpoint, "application/vnd.github+json", page.etag)
		if failure != nil {
			return nil, failure
		}
		if response.StatusCode == http.StatusOK {
			payload, err := readBounded(response.Body, min(remaining, catalogPageLimit))
			page = catalogPage{bytes: int64(len(payload)), etag: response.Header.Get("ETag"), more: strings.Contains(response.Header.Get("Link"), `rel="next"`)}
			if err != nil || json.Unmarshal(payload, &page.releases) != nil || page.releases == nil {
				_ = response.Body.Close()
				return nil, metadataUnavailable
			}
			for _, release := range page.releases {
				if release.Tag == "" || release.Assets == nil {
					_ = response.Body.Close()
					return nil, metadataUnavailable
				}
			}
			source.pages[index] = page
		} else if page.etag == "" {
			_ = response.Body.Close()
			return nil, metadataUnavailable
		}
		_ = response.Body.Close()
		remaining -= page.bytes
		if remaining < 0 {
			return nil, metadataUnavailable
		}
		releases = append(releases, page.releases...)
		if !page.more {
			clear(source.pages[index+1:])
			return releases, nil
		}
	}
	return nil, metadataUnavailable
}

func (source *githubSource) openAsset(ctx context.Context, id int64) (*http.Response, *Failure) {
	if id <= 0 {
		return nil, metadataUnavailable
	}
	response, failure := source.request(ctx, source.api+"/assets/"+strconv.FormatInt(id, 10), "application/octet-stream", "")
	if failure == nil && (response.StatusCode != http.StatusOK ||
		(response.Header.Get("Content-Encoding") != "" && response.Header.Get("Content-Encoding") != "identity")) {
		_ = response.Body.Close()
		return nil, metadataUnavailable
	}
	return response, failure
}

func readBounded(reader io.Reader, limit int64) ([]byte, error) {
	payload, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if int64(len(payload)) > limit {
		return nil, errResponseTooLarge
	}
	return payload, err
}

func (source *githubSource) readAsset(ctx context.Context, asset githubAsset, limit int64) ([]byte, *Failure) {
	if asset.Size <= 0 || asset.Size > limit {
		return nil, metadataUnavailable
	}
	if payload, ok := source.metadata[asset]; ok {
		return payload, nil
	}
	response, failure := source.openAsset(ctx, asset.ID)
	if failure != nil {
		return nil, failure
	}
	defer func() { _ = response.Body.Close() }()
	payload, err := readBounded(response.Body, asset.Size)
	if err != nil && !errors.Is(err, errResponseTooLarge) {
		return nil, unavailable
	}
	if err != nil || int64(len(payload)) != asset.Size || !matchesDigest(payload, asset.Digest) {
		return nil, metadataUnavailable
	}
	if source.metadata != nil && (asset.Digest != "" || asset.UpdatedAt != "") {
		if len(source.metadata) >= 128 {
			for previous := range source.metadata {
				delete(source.metadata, previous)
				break
			}
		}
		source.metadata[asset] = payload
	}
	return payload, nil
}

func matchesDigest(payload []byte, expected string) bool {
	return expected == "" || expected == "sha256:"+fmt.Sprintf("%x", sha256.Sum256(payload))
}
