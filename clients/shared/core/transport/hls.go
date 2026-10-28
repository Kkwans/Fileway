package transport

import (
	"errors"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

var hlsTaskID = regexp.MustCompile(`^[a-f0-9]{64}$`)
var hlsAsset = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$`)
var hlsURI = regexp.MustCompile(`URI="([^"]*)"`)

func validHLSAsset(asset string) bool {
	return hlsAsset.MatchString(asset) && !strings.Contains(asset, "..")
}

// HLSLease grants read access only to one immutable account's playback task.
// Relative and same-task absolute manifest links are mapped back through this
// capability. No token appears in a player URI and revocation closes all assets.
func (b *Broker) HLSLease(session, task string) (string, error) {
	if !hlsTaskID.MatchString(task) {
		return "", errors.New("invalid playback task")
	}
	root := "/api/media/hls/" + task + "/"
	value, err := b.lease(session, root+"index.m3u8", false, nil)
	if err != nil {
		return "", err
	}
	key := strings.TrimPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	b.mu.Lock()
	if lease := b.leases[key]; lease != nil {
		lease.hlsRoot = root
	}
	b.mu.Unlock()
	return value + "/index.m3u8", nil
}

func rewriteHLS(body []byte, upstream, scope, local *url.URL) ([]byte, error) {
	if !utf8.Valid(body) || !strings.HasPrefix(string(body), "#EXTM3U") {
		return nil, errors.New("invalid playback manifest")
	}
	rewrite := func(value string) (string, error) {
		u, err := url.Parse(value)
		if err != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return "", errors.New("invalid playback asset")
		}
		u = upstream.ResolveReference(u)
		if u.Scheme != scope.Scheme || u.Host != scope.Host || !strings.HasPrefix(u.EscapedPath(), scope.EscapedPath()) {
			return "", errors.New("playback asset outside task")
		}
		asset := strings.TrimPrefix(u.EscapedPath(), scope.EscapedPath())
		if !validHLSAsset(asset) {
			return "", errors.New("invalid playback asset")
		}
		return local.ResolveReference(&url.URL{Path: asset}).String(), nil
	}
	lines := strings.Split(string(body), "\n")
	for index, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if !strings.HasPrefix(line, "#") {
			value, err := rewrite(line)
			if err != nil {
				return nil, err
			}
			lines[index] = value
		} else {
			var failure error
			lines[index] = hlsURI.ReplaceAllStringFunc(line, func(attribute string) string {
				value, err := rewrite(hlsURI.FindStringSubmatch(attribute)[1])
				if err != nil {
					failure = err
					return attribute
				}
				return `URI="` + value + `"`
			})
			if failure != nil {
				return nil, failure
			}
		}
	}
	return []byte(strings.Join(lines, "\n")), nil
}

func (b *Broker) serveHLSManifest(w http.ResponseWriter, r *http.Request, res *http.Response, lease *Lease, key, endpoint string) {
	const maxManifest = 1 << 20
	body, err := io.ReadAll(io.LimitReader(res.Body, maxManifest+1))
	if err != nil || len(body) > maxManifest {
		http.Error(w, "invalid playback manifest", http.StatusBadGateway)
		return
	}
	upstream, _ := Endpoint(lease.session.base, endpoint)
	scope, _ := Endpoint(lease.session.base, lease.hlsRoot)
	local, _ := url.Parse("http://" + b.listener.Addr().String() + "/stream/" + key + "/")
	body, err = rewriteHLS(body, upstream, scope, local)
	if err != nil {
		http.Error(w, "invalid playback manifest", http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	if r.Method != http.MethodHead {
		_, _ = w.Write(body)
	}
}
