package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path"
	"strconv"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/risk"
	"github.com/Kkwans/nas-file-browser/backend/search"
)

const (
	searchPingInterval     = 5 * time.Second
	recursiveSearchLimit   = 1000
	recursiveSearchTimeout = 30 * time.Second
	searchCacheTTL         = 5 * time.Second
	searchCacheEntries     = 32
	maxCachedSearchResults = 1000
)

var excludedSearchDirectories = map[string]struct{}{
	"#recycle":                {},
	".recycle":                {},
	".filebrowser-cache":      {},
	".filebrowser-trash":      {},
	".nas-file-browser-cache": {},
	".nas-file-browser-trash": {},
}

type searchResult struct {
	Dir          bool       `json:"dir"`
	Path         string     `json:"path"`
	Name         string     `json:"name"`
	Size         int64      `json:"size"`
	Modified     string     `json:"modified"`
	RiskLevel    risk.Level `json:"riskLevel"`
	WirePath     string     `json:"wirePath,omitempty"`
	PathVerified bool       `json:"pathVerified,omitempty"`
}

type searchStreamEvent struct {
	Type   string        `json:"type"`
	Item   *searchResult `json:"item,omitempty"`
	Reason string        `json:"reason,omitempty"`
	Count  int           `json:"count,omitempty"`
	Error  string        `json:"error,omitempty"`
}

type cachedSearch struct {
	expires time.Time
	results []searchResult
	reason  string
}

var recentSearches = struct {
	sync.Mutex
	items map[string]cachedSearch
}{items: make(map[string]cachedSearch)}

var searchHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	mode := search.Scope(r.URL.Query().Get("scope"))
	if mode == "" {
		mode = search.ScopeCurrent
	}
	if mode != search.ScopeCurrent && mode != search.ScopeRecursive {
		return http.StatusBadRequest, fmt.Errorf("搜索范围必须是 current 或 recursive")
	}

	limit := 0
	timeout := time.Duration(0)
	if mode == search.ScopeRecursive {
		limit = boundedPositiveInt(r.URL.Query().Get("limit"), recursiveSearchLimit, recursiveSearchLimit)
		timeoutSeconds := boundedPositiveInt(r.URL.Query().Get("timeout"), int(recursiveSearchTimeout/time.Second), int(recursiveSearchTimeout/time.Second))
		timeout = time.Duration(timeoutSeconds) * time.Second
	}

	w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")

	query := r.URL.Query().Get("query")
	scopePath := path.Clean("/" + r.URL.Path)
	cacheKey := fmt.Sprintf("%d\x00%s\x00%s\x00%s\x00%d\x00%d", d.user.ID, mode, scopePath, query, limit, timeout)
	if cached, ok := loadCachedSearch(cacheKey); ok {
		for i := range cached.results {
			if err := writeSearchEvent(w, searchStreamEvent{Type: "result", Item: &cached.results[i]}); err != nil {
				return 0, nil
			}
		}
		_ = writeSearchEvent(w, searchStreamEvent{Type: "summary", Reason: cached.reason, Count: len(cached.results)})
		return 0, nil
	}

	clientCtx, cancelClient := context.WithCancelCause(r.Context())
	defer cancelClient(nil)
	searchCtx := clientCtx
	var cancelTimeout context.CancelFunc = func() {}
	if timeout > 0 {
		searchCtx, cancelTimeout = context.WithTimeout(clientCtx, timeout)
	}
	defer cancelTimeout()

	response := make(chan searchStreamEvent)
	var writer sync.WaitGroup
	writer.Add(1)
	go func() {
		defer writer.Done()
		ticker := time.NewTicker(searchPingInterval)
		defer ticker.Stop()
		for {
			select {
			case event, ok := <-response:
				if !ok {
					return
				}
				if err := writeSearchEvent(w, event); err != nil {
					cancelClient(err)
					return
				}
			case <-ticker.C:
				if _, err := w.Write([]byte("\n")); err != nil {
					cancelClient(err)
					return
				}
				flushResponse(w)
			case <-clientCtx.Done():
				return
			}
		}
	}()

	results := make([]searchResult, 0, min(limit, 128))
	err := search.Search(searchCtx, d.user.Fs, scopePath, query, d, search.Options{
		Scope:               mode,
		Limit:               limit,
		ExcludedDirectories: excludedSearchDirectories,
	}, func(foundPath string, file os.FileInfo) error {
		// Both values still contain the original filesystem bytes here. Never
		// derive the address from JSON's display-safe replacement characters.
		absolutePath := path.Join(scopePath, foundPath)
		result := searchResult{
			Dir: file.IsDir(), Path: foundPath, Name: file.Name(), Size: file.Size(),
			Modified:     file.ModTime().UTC().Format(time.RFC3339),
			RiskLevel:    risk.Classify(absolutePath),
			WirePath:     files.EncodeWirePath(absolutePath),
			PathVerified: true,
		}
		results = append(results, result)
		select {
		case response <- searchStreamEvent{Type: "result", Item: &result}:
			return nil
		case <-searchCtx.Done():
			return context.Cause(searchCtx)
		}
	})

	reason := "completed"
	switch {
	case errors.Is(err, search.ErrLimitReached):
		reason = "limit"
		err = nil
	case errors.Is(err, context.DeadlineExceeded):
		reason = "timeout"
		err = nil
	case errors.Is(err, context.Canceled):
		err = nil
	case err != nil:
		reason = "error"
	}

	if clientCtx.Err() == nil {
		summary := searchStreamEvent{Type: "summary", Reason: reason, Count: len(results)}
		if err != nil {
			summary.Error = err.Error()
		}
		if sendSearchEvent(clientCtx, response, summary) && err == nil {
			storeCachedSearch(cacheKey, results, reason)
		}
	}
	close(response)
	writer.Wait()
	if clientCtx.Err() != nil {
		return 0, nil
	}
	if err != nil {
		log.Printf("search failed for %q in %q: %v", query, scopePath, err)
		return 0, nil
	}
	return 0, nil
})

func sendSearchEvent(ctx context.Context, response chan<- searchStreamEvent, event searchStreamEvent) bool {
	select {
	case response <- event:
		return true
	case <-ctx.Done():
		return false
	}
}

func boundedPositiveInt(raw string, fallback, maximum int) int {
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 1 {
		return fallback
	}
	return min(value, maximum)
}

func writeSearchEvent(w http.ResponseWriter, event searchStreamEvent) error {
	if err := json.NewEncoder(w).Encode(event); err != nil {
		return err
	}
	flushResponse(w)
	return nil
}

func flushResponse(w http.ResponseWriter) {
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
}

func loadCachedSearch(key string) (cachedSearch, bool) {
	recentSearches.Lock()
	defer recentSearches.Unlock()
	cached, ok := recentSearches.items[key]
	if !ok || time.Now().After(cached.expires) {
		delete(recentSearches.items, key)
		return cachedSearch{}, false
	}
	return cached, true
}

func storeCachedSearch(key string, results []searchResult, reason string) {
	if len(results) > maxCachedSearchResults {
		return
	}
	recentSearches.Lock()
	defer recentSearches.Unlock()
	now := time.Now()
	for cacheKey, cached := range recentSearches.items {
		if now.After(cached.expires) {
			delete(recentSearches.items, cacheKey)
		}
	}
	if _, exists := recentSearches.items[key]; !exists && len(recentSearches.items) >= searchCacheEntries {
		var oldestKey string
		var oldestExpiry time.Time
		for cacheKey, cached := range recentSearches.items {
			if oldestKey == "" || cached.expires.Before(oldestExpiry) {
				oldestKey = cacheKey
				oldestExpiry = cached.expires
			}
		}
		delete(recentSearches.items, oldestKey)
	}
	copyResults := append([]searchResult(nil), results...)
	recentSearches.items[key] = cachedSearch{
		expires: now.Add(searchCacheTTL), results: copyResults, reason: reason,
	}
}
