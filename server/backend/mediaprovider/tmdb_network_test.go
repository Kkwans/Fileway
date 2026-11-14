package mediaprovider

import (
	"context"
	"errors"
	"net"
	"net/http"
	"slices"
	"strings"
	"testing"
)

func TestTMDBHostsUsesProviderAddressesOnly(t *testing.T) {
	hosts, err := ParseTMDBHosts("# comment\n8.8.8.8 api.tmdb.org\r\n1.1.1.1 API.THEMOVIEDB.ORG.\n8.8.8.8 api.tmdb.org\n")
	if err != nil {
		t.Fatal(err)
	}
	var calls []string
	dial := hosts.dialContext(func(_ context.Context, _, addr string) (net.Conn, error) {
		calls = append(calls, addr)
		return nil, errors.New("fixture dial failure")
	})
	_, err = dial(t.Context(), "tcp", tmdbAPIHost+":443")
	_ = requireCode(t, err, Unavailable)
	_, _ = dial(t.Context(), "tcp", tmdbImageHost+":443")
	_, err = dial(t.Context(), "tcp", "elsewhere.invalid:443")
	_ = requireCode(t, err, InvalidRequest)
	if !slices.Equal(calls, []string{"8.8.8.8:443", "1.1.1.1:443", tmdbImageHost + ":443"}) {
		t.Fatalf("dial sequence %v", calls)
	}
	for _, line := range []string{
		"127.0.0.1 api.tmdb.org",
		"::ffff:192.168.0.1 api.tmdb.org",
		"169.254.169.254 api.tmdb.org",
		"8.8.8.8 attacker.invalid",
		"8.8.8.8 api.tmdb.org:443",
	} {
		if _, err := ParseTMDBHosts(line); err == nil {
			t.Errorf("invalid override accepted: %s", line)
		}
	}
}

func TestTMDBDedicatedTransportChecksOriginBeforeDial(t *testing.T) {
	client := NewTMDBHTTPClient(TMDBHosts{})
	for _, url := range []string{"http://api.themoviedb.org/3/configuration", "https://unrelated.invalid/"} {
		req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url, nil)
		if err != nil {
			t.Fatal(err)
		}
		_, err = client.Transport.RoundTrip(req)
		_ = requireCode(t, err, InvalidRequest)
	}
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, "https://image.tmdb.org/t/p/w500/a.jpg", nil)
	req.Header.Set("Authorization", "Bearer fixture-read-token")
	_, err := client.Transport.RoundTrip(req)
	_ = requireCode(t, err, InvalidRequest)
}

func TestTMDBLegacyKeyOnlyGoesToOfficialV3Query(t *testing.T) {
	fixture := strings.Repeat("6", 32)
	client := NewTMDBClient(&http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host != tmdbAPIHost || r.URL.Query().Get("api_key") != fixture || r.Header.Get("Authorization") != "" {
			t.Fatal("incorrect v3 authentication form")
		}
		return nil, errors.New("fixture upstream failure")
	})}, func(context.Context) (string, error) { return fixture, nil })
	_, err := client.Configuration(t.Context())
	_ = requireCode(t, err, Unavailable)
	if strings.Contains(err.Error(), fixture) {
		t.Fatal("key leaked in error")
	}
}
