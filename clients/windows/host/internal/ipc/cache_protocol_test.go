package ipc

import (
	"encoding/json"
	"testing"
)

func TestManagedCacheControlPreservesCanonicalFieldsAndSessionAuthority(t *testing.T) {
	for _, test := range []struct {
		name, op, session, parameters string
		valid                         bool
	}{
		{"managed configuration", "cache_configure", "", `{"cacheConfig":{"directory":"C:\\fixture\\cache","maxBytes":5000000000,"hours":720,"managed":true}}`, true},
		{"owned media lease", "lease", "fixture-session", `{"wirePath":"/movie.mp4","cacheKey":"resource","cacheOwner":"account"}`, true},
		{"logout cleanup without session", "cache_cleanup_owner", "", `{"cacheOwner":"account"}`, true},
		{"cleanup cannot supply session", "cache_cleanup_owner", "fixture-session", `{"cacheOwner":"account"}`, false},
		{"owner field is canonical", "lease", "fixture-session", `{"wirePath":"/movie.mp4","CacheOwner":"account"}`, false},
		{"owner must be a string", "lease", "fixture-session", `{"wirePath":"/movie.mp4","cacheOwner":5}`, false},
		{"managed flag must be boolean", "cache_configure", "", `{"cacheConfig":{"directory":"C:\\fixture\\cache","maxBytes":5000000000,"hours":720,"managed":"true"}}`, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			var parameters map[string]json.RawMessage
			if err := json.Unmarshal([]byte(test.parameters), &parameters); err != nil {
				t.Fatal(err)
			}
			request := Request{RequestID: "fixture-request", Op: test.op, Session: test.session, Parameters: parameters}
			command, err := commandFor(request, "core-request")
			if (err == nil) != test.valid {
				t.Fatalf("command accepted=%v, want %v: %v", err == nil, test.valid, err)
			}
			if !test.valid {
				return
			}
			var fields map[string]json.RawMessage
			if err := json.Unmarshal(command, &fields); err != nil {
				t.Fatal(err)
			}
			for name, value := range parameters {
				if string(fields[name]) != string(value) {
					t.Fatalf("canonical parameter %s was lost", name)
				}
			}
			if _, present := fields["session"]; present != (test.session != "") {
				t.Fatal("session authority changed while encoding the command")
			}
		})
	}
}
