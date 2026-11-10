package tvupdate

import (
	"net/http"
	"net/netip"
	"net/url"
	"testing"
)

func TestGitHubRedirectsAndAddresses(t *testing.T) {
	start := &http.Request{URL: &url.URL{Path: "/repos/Kkwans/Fileway/releases/assets/1"}}
	valid := "https://release-assets.githubusercontent.com/github-production-release-asset/123?sig=test"
	for _, endpoint := range []string{valid,
		"http://release-assets.githubusercontent.com/github-production-release-asset/123",
		"https://release-assets.githubusercontent.com:80/github-production-release-asset/123",
		"https://release-assets.githubusercontent.com.evil.invalid/github-production-release-asset/123",
		"https://user@release-assets.githubusercontent.com/github-production-release-asset/123",
		"https://api.github.com/repos/Kkwans/Fileway/releases/assets/1",
		"https://127.0.0.1/github-production-release-asset/123",
	} {
		u, err := url.Parse(endpoint)
		if err != nil {
			t.Fatal(err)
		}
		err = githubRedirect(&http.Request{URL: u}, []*http.Request{start})
		if (err == nil) != (endpoint == valid) {
			t.Fatalf("redirect %s: %v", endpoint, err)
		}
	}
	for _, raw := range []string{"127.0.0.1", "169.254.169.254", "10.1.2.3", "100.100.100.100", "192.0.2.1", "240.0.0.10", "::ffff:127.0.0.1", "fc00::1", "fe80::1", "64:ff9b::a01:203", "2001:db8::1"} {
		if publicGitHubIP(netip.MustParseAddr(raw)) {
			t.Fatalf("accepted non-public %s", raw)
		}
	}
	if !publicGitHubIP(netip.MustParseAddr("140.82.112.5")) {
		t.Fatal("rejected public IPv4")
	}
	if _, err := embeddedTrust(); err != nil {
		t.Fatal(err)
	}
}
