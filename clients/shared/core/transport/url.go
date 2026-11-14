package transport

import (
	"errors"
	"net/url"
	"strings"
)

// BaseURL accepts a service root, including its base path. Credentials and
// queries are not part of a server profile URL.
func BaseURL(value string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(value))
	if err != nil || u == nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return nil, errors.New("invalid service URL; use a complete HTTP(S) URL without credentials or query")
	}
	if _, err := url.PathUnescape(u.EscapedPath()); err != nil {
		return nil, errors.New("invalid encoded base path")
	}
	return u, nil
}

// Endpoint appends an already escaped API path without dropping base paths or
// encoding opaque legacy wirePath bytes again. It never changes the origin.
func Endpoint(base *url.URL, relative string) (*url.URL, error) {
	r, err := url.Parse(relative)
	// /media/hls/ is the server's compatibility-playback family; playlists and
	// segments are leased like other media and must never bypass their session.
	if err != nil || r.IsAbs() || r.Host != "" || r.Fragment != "" ||
		(!strings.HasPrefix(r.Path, "/api/") && !strings.HasPrefix(r.Path, "/media/hls/") && r.Path != "/health") {
		return nil, errors.New("invalid service endpoint")
	}
	for _, segment := range strings.Split(r.Path, "/") {
		if segment == "." || segment == ".." {
			return nil, errors.New("path traversal is not a service endpoint")
		}
	}
	escaped := strings.TrimRight(base.EscapedPath(), "/") + r.EscapedPath()
	decoded, err := url.PathUnescape(escaped)
	if err != nil {
		return nil, errors.New("invalid endpoint encoding")
	}
	u := *base
	u.Path, u.RawPath, u.RawQuery = decoded, escaped, r.RawQuery
	return &u, nil
}

func RawEndpoint(path, wirePath string) (string, error) {
	if wirePath != "" {
		u, err := url.Parse(wirePath)
		if err != nil || u.IsAbs() || u.Host != "" || u.RawQuery != "" || u.Fragment != "" || !strings.HasPrefix(wirePath, "/") {
			return "", errors.New("invalid wire path")
		}
		return "/api/raw" + u.EscapedPath(), nil
	}
	if !strings.HasPrefix(path, "/") {
		return "", errors.New("resource path must be absolute within the service")
	}
	segments := strings.Split(path, "/")
	for i, s := range segments {
		segments[i] = url.PathEscape(s)
	}
	return "/api/raw" + strings.Join(segments, "/"), nil
}

// PreviewEndpoint reuses the NAS's existing thumbnail route, keeping opaque
// wire bytes intact. Preview generation is separate from native playback.
func PreviewEndpoint(path, wirePath string) (string, error) {
	raw, err := RawEndpoint(path, wirePath)
	if err != nil {
		return "", err
	}
	return "/api/preview/thumb" + strings.TrimPrefix(raw, "/api/raw"), nil
}
