package transport

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestURLPreservesBaseAndWireBytes(t *testing.T) {
	b, err := BaseURL("https://example.test/nas/")
	if err != nil {
		t.Fatal(err)
	}
	for _, wire := range []string{"/%E4%B8%AD%E6%96%87/%25%23%3F.mkv", "/%D6%D0/film.mkv", "/literal%252Fname.mkv"} {
		raw, err := RawEndpoint("", wire)
		if err != nil {
			t.Fatal(err)
		}
		u, err := Endpoint(b, raw)
		if err != nil {
			t.Fatal(err)
		}
		if got := u.String(); got != "https://example.test/nas/api/raw"+wire {
			t.Fatalf("opaque path changed: %s", got)
		}
	}
	path, _ := RawEndpoint("/中文/a%2F?#.mkv", "")
	u, _ := Endpoint(b, path)
	if !strings.Contains(u.String(), "a%252F%3F%23.mkv") {
		t.Fatal(u)
	}
	for _, bad := range []string{"//evil.test/api/raw/x", "https://evil.test/api/raw/x", "/api/../raw/x", "/api/%2e%2e/raw/x"} {
		if _, err := Endpoint(b, bad); err == nil {
			t.Fatal("accepted", bad)
		}
	}
	for _, bad := range []string{"http://u:p@example.test", "http://example.test?q=1", "file:///tmp/a", "https://"} {
		if _, err := BaseURL(bad); err == nil {
			t.Fatal("accepted", bad)
		}
	}
	preview, err := PreviewEndpoint("/中文/a%2F?#.mkv", "")
	if err != nil || preview != "/api/preview/thumb/%E4%B8%AD%E6%96%87/a%252F%3F%23.mkv" {
		t.Fatal("preview path was not encoded exactly once", preview, err)
	}
}

func TestResourcePatchPreservesAuthenticationWireBytesAndMethodBoundary(t *testing.T) {
	var received atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received.Add(1)
		if r.Method != http.MethodPatch || r.Header.Get("X-Auth") != "owned.test.token" {
			t.Error("resource PATCH lost its method or original account")
		}
		if r.URL.EscapedPath() != "/base/api/resources/%D6%D0/old%25.png" {
			t.Errorf("source bytes changed: %s", r.URL.EscapedPath())
		}
		if r.URL.Query().Get("destination") != "/\xD6\xD0/new%2F +#.png" {
			t.Errorf("destination decoded incorrectly: %q", r.URL.Query().Get("destination"))
		}
		if r.URL.Query().Get("override") != "false" || r.URL.Query().Get("rename") != "false" {
			t.Error("rename unexpectedly allowed overwriting or automatic renaming")
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	sid, err := b.Open(server.URL+"/base", "owned.test.token", nil)
	if err != nil {
		t.Fatal(err)
	}
	endpoint := "/api/resources/%D6%D0/old%25.png?action=rename&destination=%2F%D6%D0%2Fnew%252F%20%2B%23.png&override=false&rename=false"
	response, err := b.Request(context.Background(), sid, http.MethodPatch, endpoint, nil)
	if err != nil || response.Status != http.StatusNoContent {
		t.Fatal(response, err)
	}
	for _, method := range []string{http.MethodTrace, http.MethodConnect, http.MethodOptions} {
		if _, err := b.Request(context.Background(), sid, method, endpoint, nil); err == nil {
			t.Fatalf("unsupported method reached transport: %s", method)
		}
	}
	if received.Load() != 1 {
		t.Fatalf("unexpected request count: %d", received.Load())
	}
}

func TestAuthenticatedRangeAndImmutableLease(t *testing.T) {
	data := strings.Repeat("0123456789", 1000)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/base/api/login" {
			w.Write([]byte("header.payload.signature"))
			return
		}
		if r.Header.Get("X-Auth") != "header.payload.signature" {
			w.WriteHeader(401)
			return
		}
		if r.URL.Path != "/base/api/raw/video.mkv" || r.URL.RawQuery != "" {
			t.Errorf("unexpected path %s", r.URL.String())
			w.WriteHeader(404)
			return
		}
		http.ServeContent(w, r, "video.mkv", time.Unix(0, 0), strings.NewReader(data))
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL+"/base", "", nil)
	login, err := b.Login(context.Background(), sid, "user", "secret")
	if err != nil || login.Status != 200 {
		t.Fatal(login, err)
	}
	lease, _ := b.Lease(sid, "/api/raw/video.mkv", true)
	for _, spec := range []string{"bytes=0-99", "bytes=9900-", "bytes=-100"} {
		r, _ := http.NewRequest("GET", lease, nil)
		r.Header.Set("Range", spec)
		res, err := http.DefaultClient.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		bytes, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 206 || len(bytes) != 100 || !strings.HasSuffix(res.Header.Get("Content-Range"), "/10000") {
			t.Fatal(spec, res.StatusCode, res.Header, len(bytes))
		}
	}
	res, err := http.Head(lease)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 || res.ContentLength != 10000 {
		t.Fatal(res)
	}
	other, _ := b.Open(server.URL+"/base", "different.account.token", nil)
	b.CloseSession(other)
	res, _ = http.Get(lease)
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatal("new session affected old lease")
	}
	b.CloseSession(sid)
	res, _ = http.Get(lease)
	res.Body.Close()
	if res.StatusCode != 410 {
		t.Fatal("revoked session remained available")
	}
}

func TestRenewSingleFlightAndCredentialRedirect(t *testing.T) {
	var renews atomic.Int32
	var leaked atomic.Bool
	foreign := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		leaked.Store(r.Header.Get("X-Auth") != "")
		w.WriteHeader(200)
	}))
	defer foreign.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/renew":
			renews.Add(1)
			w.Write([]byte("new.account.token"))
		case "/api/redirect":
			http.Redirect(w, r, foreign.URL, http.StatusFound)
		default:
			if r.Header.Get("X-Auth") == "old.account.token" {
				w.Header().Set("X-Renew-Token", "true")
			}
			w.Write([]byte("{}"))
		}
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL, "old.account.token", nil)
	var group sync.WaitGroup
	for i := 0; i < 20; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			if _, err := b.Request(context.Background(), sid, "GET", "/api/resources/", nil); err != nil {
				t.Error(err)
			}
		}()
	}
	group.Wait()
	token, _ := b.Token(sid)
	if token != "new.account.token" || renews.Load() != 1 {
		t.Fatal(token, renews.Load())
	}
	r, err := b.Request(context.Background(), sid, "GET", "/api/redirect", nil)
	if err != nil || r.Status != 302 || leaked.Load() {
		t.Fatal(r, err, "credential crossed origin")
	}
}

func TestSuccessfulMutationSurvivesRenewalFailure(t *testing.T) {
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete} {
		for _, renewal := range []string{"rejected", "invalid-token"} {
			t.Run(method+"/"+renewal, func(t *testing.T) {
				status := map[string]int{http.MethodPost: http.StatusAccepted, http.MethodPut: http.StatusCreated,
					http.MethodPatch: http.StatusOK, http.MethodDelete: http.StatusNoContent}[method]
				body := `{"id":"accepted-once"}`
				if status == http.StatusNoContent {
					body = ""
				}
				var mutations, renews atomic.Int32
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					switch r.URL.Path {
					case "/api/renew":
						renews.Add(1)
						if renewal == "rejected" {
							w.WriteHeader(http.StatusUnauthorized)
						} else {
							fmt.Fprint(w, "not-a-token")
						}
					case "/api/resources/owned":
						if r.Method != method || r.Header.Get("X-Auth") != "old.account.token" {
							t.Error("mutation changed method or account")
						}
						mutations.Add(1)
						w.Header().Set("X-Renew-Token", "true")
						w.Header().Set("Content-Type", "application/json")
						w.WriteHeader(status)
						fmt.Fprint(w, body)
					case "/api/resources/":
						w.WriteHeader(http.StatusUnauthorized)
					case "/api/login":
						fmt.Fprint(w, "new.account.token")
					default:
						t.Errorf("unexpected request: %s", r.URL.Path)
						w.WriteHeader(http.StatusNotFound)
					}
				}))
				defer server.Close()
				b, err := New()
				if err != nil {
					t.Fatal(err)
				}
				defer b.Close()
				sid, err := b.Open(server.URL, "old.account.token", nil)
				if err != nil {
					t.Fatal(err)
				}
				other, err := b.Open(server.URL, "other.account.token", nil)
				if err != nil {
					t.Fatal(err)
				}
				response, err := b.Request(context.Background(), sid, method, "/api/resources/owned", nil)
				if err != nil || response.Status != status || response.Body != body || response.ContentType != "application/json" {
					t.Fatalf("known mutation acknowledgement lost: %+v, %v", response, err)
				}
				if mutations.Load() != 1 || renews.Load() != 1 {
					t.Fatal("mutation replayed or renewal not attempted", mutations.Load(), renews.Load())
				}
				if token, _ := b.Token(sid); token != "old.account.token" {
					t.Fatal("failed renewal replaced the original token", token)
				}
				if response, err := b.Request(context.Background(), sid, http.MethodGet, "/api/resources/", nil); err != nil || response.Status != http.StatusUnauthorized {
					t.Fatal("later authentication failure was hidden", response, err)
				}
				if response, err := b.Login(context.Background(), sid, "owned", "fixture-only"); err != nil || response.Status != http.StatusOK {
					t.Fatal(response, err)
				}
				if token, _ := b.Token(sid); token != "new.account.token" {
					t.Fatal("explicit reauthentication failed", token)
				}
				if token, _ := b.Token(other); token != "other.account.token" {
					t.Fatal("another account was changed", token)
				}
			})
		}
	}
}

func TestRenewalFailureDoesNotInventMutationSuccess(t *testing.T) {
	for _, test := range []struct {
		name, method string
		status       int
		incomplete   bool
	}{
		{"read", http.MethodGet, http.StatusOK, false},
		{"failed-write", http.MethodPost, http.StatusConflict, false},
		{"incomplete-acknowledgement", http.MethodPost, http.StatusOK, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			var mutations atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/renew" {
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
				mutations.Add(1)
				w.Header().Set("X-Renew-Token", "true")
				if test.incomplete {
					w.Header().Set("Content-Length", "100")
				}
				w.WriteHeader(test.status)
				fmt.Fprint(w, "{}")
			}))
			defer server.Close()
			b, err := New()
			if err != nil {
				t.Fatal(err)
			}
			defer b.Close()
			sid, err := b.Open(server.URL, "old.account.token", nil)
			if err != nil {
				t.Fatal(err)
			}
			response, err := b.Request(context.Background(), sid, test.method, "/api/resources/owned", nil)
			if test.method == http.MethodGet {
				if err != nil || response.Status != http.StatusUnauthorized {
					t.Fatal("rejected read renewal lost its authentication status", response, err)
				}
			} else if err == nil {
				t.Fatal("unconfirmed success was invented", response)
			}
			if mutations.Load() != 1 {
				t.Fatal("request replayed", mutations.Load())
			}
			if token, _ := b.Token(sid); token != "old.account.token" {
				t.Fatal("failed renewal changed token", token)
			}
		})
	}
}

func TestReadRenewalAuthenticationStatusDoesNotBroadenRecovery(t *testing.T) {
	for _, test := range []struct {
		name                      string
		readStatus, renewStatus   int
		renewBody                 string
		interrupted, unauthorized bool
	}{
		{"unauthorized-renewal", 200, 401, "", false, true},
		{"unauthorized-read-and-renewal", 401, 401, "", false, true},
		{"forbidden-renewal", 200, 403, "", false, false},
		{"unavailable-renewal", 200, 503, "", false, false},
		{"invalid-renewed-token", 200, 200, "not-a-token", false, false},
		{"interrupted-renewal", 200, 200, "", true, false},
		{"forbidden-read", 403, 401, "", false, false},
		{"unavailable-read", 503, 401, "", false, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			var reads, renewals, logins atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/api/renew":
					renewals.Add(1)
					if test.interrupted {
						w.Header().Set("Content-Length", "100")
					}
					w.WriteHeader(test.renewStatus)
					fmt.Fprint(w, test.renewBody)
				case "/api/login":
					logins.Add(1)
					w.WriteHeader(http.StatusUnauthorized)
				case "/api/resources/":
					reads.Add(1)
					w.Header().Set("X-Renew-Token", "true")
					w.WriteHeader(test.readStatus)
					fmt.Fprint(w, "{}")
				default:
					t.Errorf("unexpected request: %s", r.URL.Path)
					w.WriteHeader(http.StatusNotFound)
				}
			}))
			defer server.Close()
			b, err := New()
			if err != nil {
				t.Fatal(err)
			}
			defer b.Close()
			sid, err := b.Open(server.URL, "old.account.token", nil)
			if err != nil {
				t.Fatal(err)
			}
			response, err := b.Request(context.Background(), sid, http.MethodGet, "/api/resources/", nil)
			if test.unauthorized {
				if err != nil || response.Status != http.StatusUnauthorized || response.Body != "" {
					t.Fatal("definitive authentication rejection was lost", response, err)
				}
			} else if err == nil {
				t.Fatal("non-authentication failure became a recoverable response", response)
			}
			if reads.Load() != 1 || renewals.Load() != 1 || logins.Load() != 0 {
				t.Fatal("transport replayed a read or attempted password login", reads.Load(), renewals.Load(), logins.Load())
			}
			if token, _ := b.Token(sid); token != "old.account.token" {
				t.Fatal("failed renewal replaced the original credential", token)
			}
		})
	}
}

func TestRevokeCancelsStreamingAndRejectsUnknownOrigins(t *testing.T) {
	cancelled := make(chan struct{})
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-matroska")
		w.WriteHeader(200)
		fmt.Fprint(w, strings.Repeat("x", 128<<10))
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
		close(cancelled)
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL, "a.b.c", nil)
	lease, _ := b.Lease(sid, "/api/raw/x", true)
	request, _ := http.NewRequest("GET", lease, nil)
	request.Header.Set("Origin", "https://foreign.test")
	res, _ := http.DefaultClient.Do(request)
	res.Body.Close()
	if res.StatusCode != 404 {
		t.Fatal("browser-origin request accepted")
	}
	res, err := http.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	<-started
	b.Revoke(lease)
	select {
	case <-cancelled:
	case <-time.After(2 * time.Second):
		t.Fatal("upstream not cancelled")
	}
}

func TestMediaPermissionAndRangeErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/raw/denied" {
			w.WriteHeader(202)
			return
		}
		http.ServeContent(w, r, "x", time.Time{}, strings.NewReader("123"))
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL, "a.b.c", nil)
	lease, _ := b.Lease(sid, "/api/raw/denied", true)
	res, _ := http.Get(lease)
	res.Body.Close()
	if res.StatusCode != 403 {
		t.Fatal(res.StatusCode)
	}
	lease, _ = b.Lease(sid, "/api/raw/x", true)
	r, _ := http.NewRequest("GET", lease, nil)
	r.Header.Set("Range", "bytes=99-")
	res, _ = http.DefaultClient.Do(r)
	res.Body.Close()
	if res.StatusCode != 416 || res.Header.Get("Content-Range") != "bytes */3" {
		t.Fatal(res.StatusCode, res.Header)
	}
}
