package mediaprovider

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"time"
)

const tmdbAPIHost = "api.themoviedb.org"
const tmdbImageHost = "image.tmdb.org"

// TMDBHosts is an immutable list of alternate dial addresses. TLS still uses
// the official request hostname and the usual system trust store.
type TMDBHosts struct {
	addresses map[string][]netip.Addr
}

// ParseTMDBHosts accepts standard hosts-file lines for TMDB alone. It does not
// read or modify the system hosts file.
func ParseTMDBHosts(text string) (TMDBHosts, error) {
	if len(text) > 8192 {
		return TMDBHosts{}, fmt.Errorf("TMDB HOST configuration is too large")
	}
	hosts := TMDBHosts{addresses: make(map[string][]netip.Addr)}
	for i, line := range strings.Split(text, "\n") {
		line, _, _ = strings.Cut(line, "#")
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		if len(fields) != 2 {
			return TMDBHosts{}, fmt.Errorf("invalid TMDB HOST entry at line %d", i+1)
		}
		ip, err := netip.ParseAddr(fields[0])
		host := strings.ToLower(strings.TrimSuffix(fields[1], "."))
		if err != nil || ip.Zone() != "" || !allowedTMDBIP(ip.Unmap()) || !allowedTMDBHost(host) {
			return TMDBHosts{}, fmt.Errorf("invalid TMDB HOST address or domain at line %d", i+1)
		}
		if host == "api.tmdb.org" {
			host = tmdbAPIHost
		}
		ip = ip.Unmap()
		if slices.Contains(hosts.addresses[host], ip) {
			continue
		}
		if len(hosts.addresses[host]) == 4 {
			return TMDBHosts{}, fmt.Errorf("too many TMDB addresses at line %d", i+1)
		}
		hosts.addresses[host] = append(hosts.addresses[host], ip)
	}
	return hosts, nil
}

func allowedTMDBHost(host string) bool {
	return host == tmdbAPIHost || host == "api.tmdb.org" || host == tmdbImageHost
}

var specialNetwork = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.0.0/24"), netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"),
	netip.MustParsePrefix("203.0.113.0/24"), netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("64:ff9b::/96"), netip.MustParsePrefix("2001:db8::/32"),
}

func allowedTMDBIP(ip netip.Addr) bool {
	return ip.IsGlobalUnicast() && !ip.IsPrivate() &&
		!slices.ContainsFunc(specialNetwork, func(prefix netip.Prefix) bool { return prefix.Contains(ip) })
}

// NewTMDBHTTPClient scopes the transport to the two official HTTPS origins.
// An explicit HOST list tries those addresses in order on connection failure.
// Empty mappings use ordinary DNS for the corresponding hostname.
func NewTMDBHTTPClient(hosts TMDBHosts) *http.Client {
	dialer := &net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}
	transport := &http.Transport{
		DialContext:       hosts.dialContext(dialer.DialContext),
		ForceAttemptHTTP2: true, TLSHandshakeTimeout: 5 * time.Second,
		ResponseHeaderTimeout: 10 * time.Second, MaxIdleConns: 4,
		IdleConnTimeout: 45 * time.Second,
	}
	return &http.Client{Transport: tmdbOnlyTransport{transport}, Timeout: 15 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

type tmdbOnlyTransport struct{ *http.Transport }

func (t tmdbOnlyTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Scheme != "https" || r.URL.User != nil ||
		(r.URL.Host != tmdbAPIHost && r.URL.Host != tmdbImageHost) || r.Host != r.URL.Host {
		return nil, &Error{Code: InvalidRequest}
	}
	if r.URL.Host == tmdbImageHost && (r.Header.Get("Authorization") != "" || r.URL.RawQuery != "") {
		return nil, &Error{Code: InvalidRequest}
	}
	return t.Transport.RoundTrip(r)
}

func (hosts TMDBHosts) dialContext(dial func(context.Context, string, string) (net.Conn, error)) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil || port != "443" || (host != tmdbAPIHost && host != tmdbImageHost) {
			return nil, &Error{Code: InvalidRequest}
		}
		ips := hosts.addresses[host]
		if len(ips) == 0 {
			return dial(ctx, network, addr)
		}
		for _, ip := range ips {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			connection, err := dial(ctx, network, net.JoinHostPort(ip.String(), port))
			if err == nil {
				return connection, nil
			}
		}
		return nil, &Error{Code: Unavailable, Retryable: true}
	}
}
