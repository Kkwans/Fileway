package transport

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func activityToken(id int, issued, expires int64) string {
	return "owned." + base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"user":{"id":%d},"iat":%d,"exp":%d}`, id, issued, expires))) + ".signature"
}

func TestDeviceTokenRecoveryPreservesLeasesAndRejectsAccountOrSessionSwitch(t *testing.T) {
	makeToken := func(user int, sid string) string {
		return "owned." + base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"user":{"id":%d},"sid":%q,"scope":"media","iat":%d,"exp":%d}`, user, sid, time.Now().Unix(), time.Now().Add(time.Hour).Unix()))) + ".signature"
	}
	old := makeToken(7, "owned-device-session")
	fresh := old + "-new"
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Renew-Token", "false")
		if r.URL.Path == "/api/client-capabilities" {
			if r.Header.Get("X-Auth") != fresh {
				w.WriteHeader(401)
				return
			}
			_, _ = w.Write([]byte(`{}`))
			return
		}
		if r.URL.Path == "/api/raw/movie" {
			if r.Header.Get("X-Auth") != fresh {
				w.WriteHeader(401)
				return
			}
			_, _ = w.Write([]byte("owned media"))
			return
		}
		w.WriteHeader(404)
	}))
	defer source.Close()
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = b.Close() }()
	id, err := b.Open(source.URL, old, nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, candidate := range []string{makeToken(8, "owned-device-session"), makeToken(7, "different-device-session"), activityToken(7, 1, 2)} {
		if _, err = b.AdoptDeviceToken(context.Background(), id, 7, candidate); err == nil {
			t.Fatal("changed authority adopted")
		}
	}
	// Obtain a lease before recovery; the same URL must keep working afterward.
	lease, err := b.Lease(id, "/api/raw/movie", true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = b.AdoptDeviceToken(context.Background(), id, 7, fresh); err != nil {
		t.Fatal(err)
	}
	response, err := http.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	body, _ := io.ReadAll(response.Body)
	if response.StatusCode != 200 || string(body) != "owned media" {
		t.Fatal("existing lease lost on device refresh", response.StatusCode, string(body))
	}
}

type renewalWaitContext struct {
	context.Context
	joined chan struct{}
	once   sync.Once
}

func (c *renewalWaitContext) Done() <-chan struct{} {
	c.once.Do(func() { close(c.joined) })
	return c.Context.Done()
}

func TestRenewalSingleflightSharesSuccessAndFailure(t *testing.T) {
	for _, status := range []int{200, 401, 503} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			started, release := make(chan struct{}), make(chan struct{})
			var calls atomic.Int32
			var announce sync.Once
			old := activityToken(7, 10, 100)
			next := activityToken(7, 20, 200)
			source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/api/renew" {
					t.Error("unexpected business replay")
					w.WriteHeader(404)
					return
				}
				calls.Add(1)
				announce.Do(func() { close(started) })
				<-release
				w.WriteHeader(status)
				if status == 200 {
					fmt.Fprint(w, next)
				}
			}))
			defer source.Close()
			b, _ := New()
			defer b.Close()
			id, _ := b.Open(source.URL, old, nil)
			session, _ := b.session(id)
			results := make(chan error, 7)
			go func() { results <- session.renew(context.Background(), old) }()
			<-started
			for i := 0; i < 6; i++ {
				joined := make(chan struct{})
				ctx := &renewalWaitContext{Context: context.Background(), joined: joined}
				go func() { results <- session.renew(ctx, old) }()
				select {
				case <-joined:
				case <-time.After(3 * time.Second):
					t.Fatal("renewal waiter did not join")
				}
			}
			close(release)
			for i := 0; i < 7; i++ {
				err := <-results
				if (err == nil) != (status == 200) {
					t.Fatal("inconsistent flight result", err)
				}
			}
			want := old
			if status == 200 {
				want = next
			}
			state, _ := b.SessionState(id)
			if calls.Load() != 1 || state.Token != want {
				t.Fatal("renewal was duplicated or credential changed", calls.Load())
			}
		})
	}
}

func TestRenewalRunsBeforeEOFAndKeepsSuccessfulRangeOnFailure(t *testing.T) {
	for _, lease := range []bool{false, true} {
		t.Run(fmt.Sprint(lease), func(t *testing.T) {
			renewed := make(chan struct{})
			var renewCalls atomic.Int32
			source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/renew" {
					renewCalls.Add(1)
					close(renewed)
					w.WriteHeader(401)
					return
				}
				w.Header().Set("X-Renew-Token", "true")
				w.Header().Set("Content-Length", "4")
				status := 200
				if lease {
					status = 206
					w.Header().Set("Content-Range", "bytes 0-3/4")
				}
				w.WriteHeader(status)
				w.(http.Flusher).Flush()
				select {
				case <-renewed:
					fmt.Fprint(w, "data")
				case <-r.Context().Done():
				}
			}))
			defer source.Close()
			b, _ := New()
			defer b.Close()
			id, _ := b.Open(source.URL, "old.account.token", nil)
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if lease {
				url, _ := b.Lease(id, "/api/raw/owned", false)
				request, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
				res, err := http.DefaultClient.Do(request)
				if err != nil {
					t.Fatal(err)
				}
				body, err := io.ReadAll(res.Body)
				res.Body.Close()
				if err != nil || res.StatusCode != 206 || string(body) != "data" {
					t.Fatal("Range lost on renewal failure", res.StatusCode, err)
				}
			} else {
				result, err := b.Request(ctx, id, "GET", "/api/resources/", nil)
				if err != nil || result.Status != 200 || result.Body != "data" {
					t.Fatal("read lost on renewal failure", result, err)
				}
			}
			state, _ := b.SessionState(id)
			if renewCalls.Load() != 1 || state.Health != "renewal_failed" || state.BusinessRevision != 1 {
				t.Fatal("incorrect activity/health", state.Health, state.BusinessRevision, renewCalls.Load())
			}
		})
	}
}

func TestOldServerActivityFallbackAndRenewFalse(t *testing.T) {
	now := time.Now().Unix()
	token := activityToken(7, now-16*86400, now+14*86400)
	for _, test := range []struct {
		hint   string
		status int
		want   bool
	}{
		{"", 200, true}, {"false", 200, false}, {"true", 409, true}, {"", 409, false}, {"true", 401, false},
	} {
		res := &http.Response{StatusCode: test.status, Header: make(http.Header)}
		res.Header.Set("X-Renew-Token", test.hint)
		if got := shouldRenew(res, token); got != test.want {
			t.Fatal(test, got)
		}
	}
	if shouldRenew(&http.Response{StatusCode: 200, Header: make(http.Header)}, activityToken(7, now, now+720*3600)) {
		t.Fatal("same-second token renewed")
	}
	if shouldRenew(&http.Response{StatusCode: 200, Header: make(http.Header)}, activityToken(7, now-720*3600, now-1)) {
		t.Fatal("expired token fallback resurrected")
	}
}

func TestRecoverSessionKeepsHandleAndRejectsForeignCandidate(t *testing.T) {
	for _, user := range []int{7, 8} {
		t.Run(fmt.Sprint(user), func(t *testing.T) {
			old := activityToken(7, 10, 20)
			candidate := activityToken(user, 30, 400)
			var logins, business atomic.Int32
			source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/login" {
					logins.Add(1)
					fmt.Fprint(w, candidate)
					return
				}
				business.Add(1)
				if r.Header.Get("X-Auth") != candidate {
					w.WriteHeader(401)
					return
				}
				w.Header().Set("X-Renew-Token", "false")
				fmt.Fprint(w, "owned")
			}))
			defer source.Close()
			b, _ := New()
			defer b.Close()
			id, _ := b.Open(source.URL, old, nil)
			other, _ := b.Open(source.URL, activityToken(9, 10, 20), nil)
			url, _ := b.Lease(id, "/api/raw/owned", false)
			_, err := b.RecoverSession(context.Background(), id, 7, "owned", "fixture-only")
			current, _ := b.Token(id)
			if user == 8 {
				if err == nil || current != old {
					t.Fatal("foreign token installed", err)
				}
			} else {
				if err != nil || current != candidate {
					t.Fatal("same owner was not recovered", err)
				}
				res, err := http.Get(url)
				if err != nil {
					t.Fatal(err)
				}
				defer res.Body.Close()
				body, _ := io.ReadAll(res.Body)
				if res.StatusCode != 200 || string(body) != "owned" {
					t.Fatal("existing lease was lost")
				}
			}
			if logins.Load() != 1 || business.Load() != int32(map[bool]int{true: 1, false: 0}[user == 7]) {
				t.Fatal("business request replayed", logins.Load(), business.Load())
			}
			token, _ := b.Token(other)
			if token != activityToken(9, 10, 20) {
				t.Fatal("other generation changed")
			}
		})
	}
}

func TestClosedSessionRejectsLateRecovery(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { close(started); <-release }))
	defer source.Close()
	defer close(release)
	b, _ := New()
	defer b.Close()
	old := activityToken(7, 10, 20)
	id, _ := b.Open(source.URL, old, nil)
	session, _ := b.session(id)
	result := make(chan error, 1)
	go func() {
		_, err := b.RecoverSession(context.Background(), id, 7, "owned", "fixture-only")
		result <- err
	}()
	<-started
	b.CloseSession(id)
	if err := <-result; err == nil {
		t.Fatal("closed generation accepted recovery")
	}
	if session.currentToken() != old {
		t.Fatal("late credential installed")
	}
}
