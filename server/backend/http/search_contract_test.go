package fbhttp

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"runtime"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestBoundedPositiveInt(t *testing.T) {
	if got := boundedPositiveInt("", 1000, 1000); got != 1000 {
		t.Fatalf("default = %d", got)
	}
	if got := boundedPositiveInt("2000", 1000, 1000); got != 1000 {
		t.Fatalf("bounded value = %d", got)
	}
	if got := boundedPositiveInt("250", 1000, 1000); got != 250 {
		t.Fatalf("explicit value = %d", got)
	}
}

func TestRecentSearchCacheIsBounded(t *testing.T) {
	recentSearches.Lock()
	recentSearches.items = make(map[string]cachedSearch)
	recentSearches.Unlock()
	t.Cleanup(func() {
		recentSearches.Lock()
		recentSearches.items = make(map[string]cachedSearch)
		recentSearches.Unlock()
	})

	for index := 0; index < searchCacheEntries+5; index++ {
		storeCachedSearch(string(rune(index)), nil, "completed")
	}
	recentSearches.Lock()
	count := len(recentSearches.items)
	recentSearches.Unlock()
	if count != searchCacheEntries {
		t.Fatalf("cache entries = %d, want %d", count, searchCacheEntries)
	}

	storeCachedSearch("too-large", make([]searchResult, maxCachedSearchResults+1), "completed")
	if _, exists := loadCachedSearch("too-large"); exists {
		t.Fatal("oversized search must not be cached")
	}
}

func TestSendSearchEventStopsWhenClientIsCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if sendSearchEvent(ctx, make(chan searchStreamEvent), searchStreamEvent{Type: "summary"}) {
		t.Fatal("canceled client must not accept a summary event")
	}
}

func TestSearchWirePathsPreserveOriginalBytesAndCachedResults(t *testing.T) {
	recentSearches.Lock()
	recentSearches.items = make(map[string]cachedSearch)
	recentSearches.Unlock()
	t.Cleanup(func() {
		recentSearches.Lock()
		recentSearches.items = make(map[string]cachedSearch)
		recentSearches.Unlock()
	})
	h := newTrashHTTPHarness(t, users.User{Username: "search-owner"})
	owner := firstTrashHTTPUser(h)
	const base = "/search%2F+"
	if err := h.fs[owner.ID].MkdirAll(base+"/nested", 0o700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"item\xfe.bin", "item\xff.bin", "literal%2F+.bin", "nested/child.bin"} {
		if err := afero.WriteFile(h.fs[owner.ID], base+"/"+name, []byte("search-fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for _, scope := range []string{"current", "recursive"} {
		expected := map[string]bool{
			"/search%252F%2B/literal%252F%2B.bin": true,
		}
		// The native Windows scope checker intentionally rejects non-UTF-8
		// filenames before filesystem access (UTF-16 would alias U+FFFD).
		// Linux runners exercise the two legal opaque filename identities.
		if runtime.GOOS != "windows" {
			expected["/search%252F%2B/item%FE.bin"] = true
			expected["/search%252F%2B/item%FF.bin"] = true
		}
		if scope == "recursive" {
			expected["/search%252F%2B/nested/child.bin"] = true
		}
		var firstBody string
		for attempt := 0; attempt < 2; attempt++ {
			response := h.request(t, owner.ID, searchHandler, http.MethodGet,
				"/search%252F%2B?query=type%3Abin&scope="+scope, nil, nil)
			if response.Code != http.StatusOK {
				t.Fatalf("search status=%d body=%s", response.Code, response.Body.String())
			}
			body := response.Body.String()
			if attempt == 0 {
				firstBody = body
			} else if body != firstBody {
				t.Fatal("cached search changed the raw path identity")
			}
			decoder := json.NewDecoder(response.Body)
			seen := make(map[string]bool)
			var terminal bool
			var opaqueDisplay string
			for {
				var event searchStreamEvent
				err := decoder.Decode(&event)
				if err == io.EOF {
					break
				}
				if err != nil {
					t.Fatal(err)
				}
				if event.Type == "summary" {
					terminal = event.Reason == "completed" && event.Count == len(expected)
					continue
				}
				if event.Item == nil || !event.Item.PathVerified || !expected[event.Item.WirePath] || seen[event.Item.WirePath] {
					t.Fatalf("unverified, duplicated or incorrect wire identity: %#v", event.Item)
				}
				seen[event.Item.WirePath] = true
				if event.Item.WirePath == "/search%252F%2B/item%FE.bin" || event.Item.WirePath == "/search%252F%2B/item%FF.bin" {
					if opaqueDisplay != "" && event.Item.Path != opaqueDisplay {
						t.Fatal("fixture must exercise two byte identities with the same JSON display")
					}
					opaqueDisplay = event.Item.Path
				}
			}
			if !terminal || len(seen) != len(expected) {
				t.Fatalf("incomplete search: terminal=%v seen=%d want=%d body=%s", terminal, len(seen), len(expected), body)
			}
		}
	}
}
