package transport

import (
	"context"
	"encoding/base64"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
)

func principalFixtureToken(id uint64, name string) string {
	return "fixture." + base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"user":{"id":%d,"username":%q}}`, id, name))) + ".signature"
}

func TestRenewalCannotPublishDifferentAccountToConcurrentReaders(t *testing.T) {
	for _, viaLogin := range []bool{false, true} {
		t.Run(fmt.Sprintf("login-%t", viaLogin), func(t *testing.T) {
			old := principalFixtureToken(7, "original")
			wrong := principalFixtureToken(8, "other")
			var wrongRequests, writes atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/login" {
					fmt.Fprint(w, old)
					return
				}
				if r.Header.Get("X-Auth") != old {
					wrongRequests.Add(1)
				}
				if r.URL.Path == "/api/renew" {
					fmt.Fprint(w, wrong)
					return
				}
				if r.Method == http.MethodPut {
					writes.Add(1)
				}
				w.Header().Set("X-Renew-Token", "true")
				fmt.Fprint(w, "{}")
			}))
			defer server.Close()
			broker, err := New()
			if err != nil {
				t.Fatal(err)
			}
			defer broker.Close()
			initial := old
			if viaLogin {
				initial = ""
			}
			id, err := broker.Open(server.URL, initial, nil)
			if err != nil {
				t.Fatal(err)
			}
			if viaLogin {
				if _, err := broker.Login(context.Background(), id, "fixture", "fixture"); err != nil {
					t.Fatal(err)
				}
			}
			var workers sync.WaitGroup
			for range 12 {
				workers.Go(func() {
					if _, err := broker.Request(context.Background(), id, http.MethodGet, "/api/resources/owned", nil); err == nil {
						t.Error("cross-account renewal was accepted")
					}
				})
			}
			workers.Wait()
			response, err := broker.Request(context.Background(), id, http.MethodPut, "/api/resources/owned", []byte("{}"))
			if err != nil || response.Status != http.StatusOK || writes.Load() != 1 {
				t.Fatal("confirmed write was hidden or replayed", response.Status, err, writes.Load())
			}
			if current, _ := broker.Token(id); current != old || wrongRequests.Load() != 0 {
				t.Fatal("another account's credential escaped the identity fence")
			}
		})
	}
}

func TestRenewalPreservesPrincipalAndRejectsInvalidReplacement(t *testing.T) {
	for _, test := range []struct {
		name, replacement string
		accepted          bool
	}{
		{"same-user-new-claims", principalFixtureToken(7, "updated"), true},
		{"different-user", principalFixtureToken(8, "other"), false},
		{"missing-principal", "fixture.e30.signature", false},
		{"invalid-json", "fixture.eA.signature", false},
	} {
		t.Run(test.name, func(t *testing.T) {
			old := principalFixtureToken(7, "original")
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/renew" {
					fmt.Fprint(w, test.replacement)
					return
				}
				w.Header().Set("X-Renew-Token", "true")
				fmt.Fprint(w, "{}")
			}))
			defer server.Close()
			broker, err := New()
			if err != nil {
				t.Fatal(err)
			}
			defer broker.Close()
			id, err := broker.Open(server.URL, old, nil)
			if err != nil {
				t.Fatal(err)
			}
			_, err = broker.Request(context.Background(), id, http.MethodGet, "/api/resources/owned", nil)
			if (err == nil) != test.accepted {
				t.Fatal("unexpected renewal result", err)
			}
			expected := old
			if test.accepted {
				expected = test.replacement
			}
			if current, _ := broker.Token(id); current != expected {
				t.Fatal("unexpected credential replacement")
			}
		})
	}
}
