package mediaprovider

import (
	"cmp"
	"context"
	"encoding/json"
	"slices"
	"strconv"
	"strings"
	"unicode"

	"golang.org/x/text/unicode/norm"
)

const (
	maxIncompleteTitleQueries = 2
	maxIncompleteTitlePages   = 3
	maxIncompleteTitleMatches = 6
)

type incompleteTitlePattern struct {
	tokens  []rune
	queries []string
}

func parseIncompleteTitlePattern(title string) incompleteTitlePattern {
	var tokens []rune
	var runs []string
	var run strings.Builder
	missing, visible := 0, 0
	flush := func() {
		if run.Len() != 0 {
			runs = append(runs, run.String())
			run.Reset()
		}
	}
	for _, r := range norm.NFKC.String(strings.TrimSpace(title)) {
		switch {
		case unicode.IsSpace(r) || unicode.IsPunct(r):
			flush()
		case unicode.Is(unicode.Han, r):
			tokens = append(tokens, r)
			run.WriteRune(r)
			visible++
		case r >= 'a' && r <= 'z':
			flush()
			tokens = append(tokens, r)
			missing++
		default:
			return incompleteTitlePattern{}
		}
	}
	flush()
	if len(tokens) < 2 || len(tokens) > 24 || visible < 1 || missing == 0 || missing > visible {
		return incompleteTitlePattern{}
	}
	slices.SortStableFunc(runs, func(a, b string) int { return cmp.Compare(len(b), len(a)) })
	queries := make([]string, 0, maxIncompleteTitleQueries)
	for _, query := range runs {
		if len([]rune(query)) >= 2 && !slices.Contains(queries, query) {
			queries = append(queries, query)
		}
	}
	if len(queries) == 0 {
		for _, token := range tokens {
			if !unicode.Is(unicode.Han, token) {
				continue
			}
			query := string(token)
			if !slices.Contains(queries, query) {
				queries = append(queries, query)
			}
		}
	}
	if len(queries) > maxIncompleteTitleQueries {
		queries = queries[:maxIncompleteTitleQueries]
	}
	return incompleteTitlePattern{tokens: tokens, queries: queries}
}

func (pattern incompleteTitlePattern) matches(title string) bool {
	var candidate []rune
	for _, r := range norm.NFKC.String(title) {
		if unicode.Is(unicode.Han, r) {
			candidate = append(candidate, r)
		} else if !unicode.IsSpace(r) && !unicode.IsPunct(r) {
			return false
		}
	}
	if len(candidate) != len(pattern.tokens) {
		return false
	}
	for i, token := range pattern.tokens {
		if unicode.Is(unicode.Han, token) && token != candidate[i] {
			return false
		}
	}
	return true
}

func (client *TMDBClient) resolveIncompleteMovieTitle(ctx context.Context, input AutomaticMatchInput, language string,
	authorize func(context.Context) error) (AutomaticMatch, bool, error) {
	if input.Kind != Movie || input.Year == 0 || input.DurationMs <= 0 {
		return AutomaticMatch{}, false, nil
	}
	pattern := parseIncompleteTitlePattern(input.Title)
	if len(pattern.queries) == 0 {
		return AutomaticMatch{}, false, nil
	}
	matches := make(map[EntityRef]Document)
	for _, query := range pattern.queries {
		for pageNumber := 1; pageNumber <= maxIncompleteTitlePages; pageNumber++ {
			if err := authorize(ctx); err != nil {
				return AutomaticMatch{}, true, err
			}
			page, err := client.Search(ctx, SearchQuery{Kind: Movie, Title: query, Year: input.Year,
				Language: language, Region: input.Region, Page: pageNumber})
			if err != nil {
				return AutomaticMatch{}, true, err
			}
			if page.TotalPages > maxIncompleteTitlePages {
				return AutomaticMatch{State: AutoNeedsReview, Reason: "incomplete_title_search_incomplete"}, true, nil
			}
			for _, document := range page.Items {
				if document.Entity.Kind != Movie || !pattern.matches(textField(document, "title")) &&
					!pattern.matches(textField(document, "original_title")) {
					continue
				}
				matches[document.Entity] = document
				if len(matches) > maxIncompleteTitleMatches {
					return AutomaticMatch{State: AutoNeedsReview, Reason: "ambiguous_candidates"}, true, nil
				}
			}
			if pageNumber >= page.TotalPages {
				break
			}
		}
	}
	if len(matches) == 0 {
		return AutomaticMatch{State: AutoNoMatch, Reason: "title_not_found"}, true, nil
	}
	type runtimeCandidate struct {
		match      AutomaticMatch
		difference int64
	}
	var candidates []runtimeCandidate
	yearConflict, runtimeUnknown := false, false
	for _, document := range matches {
		if err := authorize(ctx); err != nil {
			return AutomaticMatch{}, true, err
		}
		detail, err := client.Fetch(ctx, document.Entity, FetchOptions{Language: language, Parts: []Part{Credits, ExternalIDs}})
		if err != nil {
			if providerErrorIs(err, NotFound) {
				continue
			}
			return AutomaticMatch{}, true, err
		}
		projection, err := ProjectTMDB(detail)
		if err != nil {
			return AutomaticMatch{}, true, err
		}
		if year := projection.Item.Fields["year"]; len(year) == 0 || year[0] != strconv.Itoa(input.Year) {
			yearConflict = true
			continue
		}
		if !pattern.matches(textField(detail, "title")) && !pattern.matches(textField(detail, "original_title")) {
			continue
		}
		runtime := documentRuntimeMinutes(detail)
		if runtime == 0 {
			runtimeUnknown = true
			continue
		}
		candidates = append(candidates, runtimeCandidate{match: AutomaticMatch{State: AutoMatched, Document: detail, Projection: projection,
			Evidence: []string{"incomplete_title_shape", "year_match"}}, difference: runtimeDifferenceMs(input.DurationMs, runtime)})
	}
	if len(candidates) > 1 {
		best := candidates[0]
		tied := false
		for _, candidate := range candidates[1:] {
			switch {
			case candidate.difference < best.difference:
				best, tied = candidate, false
			case candidate.difference == best.difference:
				tied = true
			}
		}
		if tied {
			return AutomaticMatch{State: AutoNeedsReview, Reason: "ambiguous_candidates"}, true, nil
		}
		candidates = []runtimeCandidate{best}
	}
	if len(candidates) == 1 {
		result := candidates[0].match
		result.Evidence = append(result.Evidence, "closest_runtime")
		if candidates[0].difference <= int64(runtimeToleranceMinutes(documentRuntimeMinutes(result.Document)))*60_000 {
			result.Evidence = append(result.Evidence, "runtime_match")
		} else {
			result.Evidence = append(result.Evidence, "runtime_difference")
		}
		result.Evidence = append(result.Evidence, "unique_candidate")
		return result, true, nil
	}
	if yearConflict {
		return AutomaticMatch{State: AutoNeedsReview, Reason: "title_year_conflict"}, true, nil
	}
	if runtimeUnknown {
		return AutomaticMatch{State: AutoNeedsReview, Reason: "runtime_unknown"}, true, nil
	}
	return AutomaticMatch{State: AutoNeedsReview, Reason: "title_not_found"}, true, nil
}

func documentRuntimeMinutes(document Document) int {
	var runtime int
	if json.Unmarshal(document.Fields["runtime"], &runtime) != nil || runtime <= 0 || runtime > 7*24*60 {
		return 0
	}
	return runtime
}

func runtimeDifferenceMs(durationMs int64, runtimeMinutes int) int64 {
	difference := durationMs - int64(runtimeMinutes)*60_000
	if difference < 0 {
		return -difference
	}
	return difference
}

func runtimeToleranceMinutes(runtimeMinutes int) int {
	return max(1, min(3, runtimeMinutes/20))
}

func runtimeMatches(durationMs int64, runtimeMinutes int) bool {
	return runtimeDifferenceMs(durationMs, runtimeMinutes) <= int64(runtimeToleranceMinutes(runtimeMinutes))*60_000
}
