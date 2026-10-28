package transport

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestHLSLeaseBindsManifestAndSegmentsToAccountAndRevocation(t *testing.T) {
	task := strings.Repeat("a", 64)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Auth") != "owned.token" {
			t.Error("asset lost original account")
		}
		switch r.URL.Path {
		case "/base/api/media/hls/" + task + "/index.m3u8":
			io.WriteString(w, "#EXTM3U\n#EXTINF:4,\nsegment-000001.ts\n")
		case "/base/api/media/hls/" + task + "/segment-000001.ts":
			w.Header().Set("Content-Range", "bytes 0-3/10")
			w.WriteHeader(206)
			io.WriteString(w, "data")
		default:
			t.Error("request escaped task:", r.URL.Path)
			w.WriteHeader(404)
		}
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	session, _ := b.Open(server.URL+"/base", "owned.token", nil)
	lease, err := b.HLSLease(session, task)
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || strings.Contains(string(body), "owned.token") {
		t.Fatalf("manifest: %d %s", res.StatusCode, body)
	}
	segment := strings.Split(strings.TrimSpace(string(body)), "\n")[2]
	if !strings.HasPrefix(segment, strings.TrimSuffix(lease, "index.m3u8")) {
		t.Fatal("segment bypassed lease", segment)
	}
	req, _ := http.NewRequest("GET", segment, nil)
	req.Header.Set("Range", "bytes=0-3")
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 206 || res.Header.Get("Content-Range") != "bytes 0-3/10" {
		t.Fatal(res.Status)
	}
	b.Revoke(lease)
	for _, uri := range []string{lease, segment} {
		res, err = http.Get(uri)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		if res.StatusCode != 410 {
			t.Fatal("revoked task still readable", res.Status)
		}
	}
}

func TestHLSManifestRejectsForeignJobsOriginsQueriesAndTraversal(t *testing.T) {
	base, _ := url.Parse("https://owned.test/base/api/media/hls/" + strings.Repeat("a", 64) + "/")
	upstream := base.ResolveReference(&url.URL{Path: "index.m3u8"})
	local, _ := url.Parse("http://127.0.0.1:1234/stream/capability/")
	for _, bad := range []string{"https://other.test/segment.ts", "../other/index.m3u8", "%2e%2e/segment.ts", "segment.ts?token=secret", "//other.test/a", "a/b.ts", "/api/media/hls/" + strings.Repeat("b", 64) + "/segment.ts"} {
		for _, line := range []string{bad, `#EXT-X-KEY:METHOD=AES-128,URI="` + bad + `"`} {
			if _, err := rewriteHLS([]byte("#EXTM3U\n"+line+"\n"), upstream, base, local); err == nil {
				t.Fatal("unsafe link accepted", line)
			}
		}
	}
}
