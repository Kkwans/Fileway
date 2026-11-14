package mediaprovider

import (
	"context"
	"encoding/json"
	"strings"
)

type ExternalRef struct {
	Provider string
	ID       string
}

// Find preserves all returned entity types. An IMDb identifier resolving to
// multiple types is not an instruction to pick the first movie or series.
func (client *TMDBClient) Find(ctx context.Context, ref ExternalRef, language string) ([]Document, error) {
	var source, digits string
	switch ref.Provider {
	case "imdb":
		source = "imdb_id"
		if strings.HasPrefix(ref.ID, "tt") || strings.HasPrefix(ref.ID, "nm") {
			digits = ref.ID[2:]
		}
	case "tvdb":
		source, digits = "tvdb_id", ref.ID
	case "wikidata":
		source = "wikidata_id"
		if strings.HasPrefix(ref.ID, "Q") {
			digits = ref.ID[1:]
		}
	default:
		return nil, &Error{Code: InvalidRequest}
	}
	if len(digits) == 0 || len(ref.ID) > 128 || strings.IndexFunc(digits, func(r rune) bool { return r < '0' || r > '9' }) >= 0 {
		return nil, &Error{Code: InvalidRequest}
	}
	query, err := languageQuery(language)
	if err != nil {
		return nil, err
	}
	query.Set("external_source", source)
	var response map[string]json.RawMessage
	if err := client.get(ctx, "/find/"+ref.ID, query, &response); err != nil {
		return nil, err
	}
	items := make([]Document, 0)
	sets := 0
	for _, set := range []struct {
		key  string
		kind Kind
	}{{"movie_results", Movie}, {"tv_results", Series}, {"person_results", Person}, {"tv_season_results", Season}, {"tv_episode_results", Episode}} {
		raw, exists := response[set.key]
		if !exists {
			continue
		}
		sets++
		var rows []map[string]json.RawMessage
		if err := json.Unmarshal(raw, &rows); err != nil || rows == nil || len(items)+len(rows) > 100 {
			return nil, &Error{Code: InvalidResponse}
		}
		for _, fields := range rows {
			document, err := tmdbDocument(set.kind, 0, language, fields)
			if err != nil {
				return nil, err
			}
			items = append(items, document)
		}
	}
	if sets == 0 {
		return nil, &Error{Code: InvalidResponse}
	}
	return items, nil
}
