package mediaprovider

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strconv"
	"strings"
	"unicode"
)

const (
	maxAutomaticTitleQueries = 6
	maxAutomaticTitlePages   = 3
)

// WorkIdentifier is a declaration from an authorized input. An empty Kind
// can contradict a typed declaration but cannot establish a work identity.
type WorkIdentifier struct {
	Kind Kind
	ExternalRef
}

type AutomaticMatchInput struct {
	Kind            Kind
	Year            int
	Title           string
	Aliases         []string
	FallbackTitle   string
	FallbackYear    int
	DurationMs      int64
	Region          string
	Identifiers     []WorkIdentifier
	Ambiguities     []string
	AllowTitleMatch bool
	// Incomplete is set if any relevant evidence remains unread or invisible.
	Incomplete bool
}

type AutomaticMatchState string

const (
	AutoMatched     AutomaticMatchState = "matched"
	AutoNeedsReview AutomaticMatchState = "needs_review"
	AutoNoMatch     AutomaticMatchState = "no_match"
)

type AutomaticMatch struct {
	State      AutomaticMatchState
	Reason     string
	Evidence   []string
	Document   Document
	Projection TMDBProjection
}

// ResolveAutomaticMatch verifies explicit, typed identifiers before a worker
// may obtain a document for persistence. A title or year is never sufficient to
// confirm identity. The caller revalidates authority and governing policy before
// each request through authorize and again in the transaction that saves it.
func (client *TMDBClient) ResolveAutomaticMatch(ctx context.Context, input AutomaticMatchInput, language string,
	authorize func(context.Context) error) (AutomaticMatch, error) {
	review := func(reason string) (AutomaticMatch, error) {
		return AutomaticMatch{State: AutoNeedsReview, Reason: reason}, nil
	}
	if input.Kind != Movie && input.Kind != Series {
		return review("unsupported_structure")
	}
	if input.Incomplete {
		return review("incomplete_evidence")
	}
	for _, ambiguity := range input.Ambiguities {
		if ambiguity != "missing_title" {
			return review("ambiguous_evidence")
		}
	}
	seen := make(map[string]string)
	var tmdbID int64
	var external []ExternalRef
	for _, id := range input.Identifiers {
		if id.Kind != "" && id.Kind != input.Kind {
			return review("entity_type_conflict")
		}
		value := strings.TrimSpace(id.ID)
		if value == "" {
			continue
		}
		if id.Provider == "tmdb" {
			number, err := strconv.ParseInt(value, 10, 64)
			if err != nil || number < 1 || strings.HasPrefix(value, "+") {
				return review("invalid_identifier")
			}
			value = strconv.FormatInt(number, 10)
			if id.Kind != "" {
				tmdbID = number
			}
		}
		if previous := seen[id.Provider]; previous != "" && previous != value {
			return review("identifier_conflict")
		}
		seen[id.Provider] = value
		if id.Kind == "" {
			continue
		}
		ref := ExternalRef{Provider: id.Provider, ID: value}
		if id.Provider == "imdb" && !validTMDBIMDbWorkID(value) {
			return review("invalid_identifier")
		}
		if !slices.Contains(external, ref) && (id.Provider == "imdb" || id.Provider == "wikidata" || id.Provider == "tvdb" && input.Kind == Series) {
			external = append(external, ref)
		}
	}
	if authorize == nil {
		return AutomaticMatch{}, &Error{Code: InvalidRequest}
	}
	if tmdbID == 0 && len(external) == 0 {
		if !input.AllowTitleMatch {
			return review("typed_identifier_required")
		}
		result, err := client.resolveTitleMatch(ctx, input, language, authorize)
		if err != nil {
			return AutomaticMatch{}, err
		}
		if fallback := strings.TrimSpace(input.FallbackTitle); fallback != "" && (result.State == AutoNoMatch || result.Reason == "title_year_conflict") {
			fallbackInput := input
			fallbackInput.Title, fallbackInput.Year = fallback, input.FallbackYear
			fallbackInput.FallbackTitle, fallbackInput.FallbackYear = "", 0
			fallbackInput.Aliases = append([]string{input.Title}, input.Aliases...)
			result, err = client.resolveTitleMatch(ctx, fallbackInput, language, authorize)
			if err != nil {
				return AutomaticMatch{}, err
			}
			if result.State == AutoMatched {
				result.Evidence = append(result.Evidence, "embedded_title_match")
			}
		}
		if result.State == AutoNoMatch || result.Reason == "title_year_conflict" {
			if recovered, handled, err := client.resolveIncompleteMovieTitle(ctx, input, language, authorize); err != nil {
				return AutomaticMatch{}, err
			} else if handled {
				return recovered, nil
			}
		}
		return result, nil
	}
	entity := EntityRef{Provider: "tmdb", Kind: input.Kind, ID: tmdbID}
	for _, ref := range external {
		if err := authorize(ctx); err != nil {
			return AutomaticMatch{}, err
		}
		found, err := client.Find(ctx, ref, language)
		if err != nil {
			if providerErrorIs(err, NotFound) {
				return AutomaticMatch{State: AutoNoMatch, Reason: "identifier_not_found"}, nil
			}
			return AutomaticMatch{}, err
		}
		if len(found) == 0 {
			return AutomaticMatch{State: AutoNoMatch, Reason: "identifier_not_found"}, nil
		}
		if len(found) != 1 || found[0].Entity.Kind != input.Kind || entity.ID != 0 && entity != found[0].Entity {
			return review("identifier_conflict")
		}
		entity = found[0].Entity
	}
	if untypedTMDB := seen["tmdb"]; untypedTMDB != "" && untypedTMDB != strconv.FormatInt(entity.ID, 10) {
		return review("identifier_conflict")
	}
	if err := authorize(ctx); err != nil {
		return AutomaticMatch{}, err
	}
	document, err := client.Fetch(ctx, entity, FetchOptions{Language: language, Parts: []Part{Credits, ExternalIDs}})
	if err != nil {
		if providerErrorIs(err, NotFound) {
			return AutomaticMatch{State: AutoNoMatch, Reason: "identifier_not_found"}, nil
		}
		return AutomaticMatch{}, err
	}
	projection, err := ProjectTMDB(document)
	if err != nil {
		return AutomaticMatch{}, err
	}
	if len(projection.Item.Fields["title"]) == 0 {
		return review("missing_provider_title")
	}
	if declared := seen["imdb"]; declared != "" {
		verified := false
		for _, id := range projection.Item.ExternalIDs {
			verified = verified || id.Provider == "imdb" && id.Value == declared
		}
		if !verified {
			return review("identifier_unverified")
		}
	}
	if year := projection.Item.Fields["year"]; input.Year > 0 && len(year) != 0 && year[0] != strconv.Itoa(input.Year) {
		return review("year_conflict")
	}
	return AutomaticMatch{State: AutoMatched, Document: document, Projection: projection}, nil
}

func (client *TMDBClient) resolveTitleMatch(ctx context.Context, input AutomaticMatchInput, language string,
	authorize func(context.Context) error) (AutomaticMatch, error) {
	review := func(reason string) (AutomaticMatch, error) {
		return AutomaticMatch{State: AutoNeedsReview, Reason: reason}, nil
	}
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return review("title_required")
	}
	queries := []string{title}
	for _, alias := range input.Aliases {
		alias = strings.TrimSpace(alias)
		if alias != "" && !slices.Contains(queries, alias) && len(queries) < maxAutomaticTitleQueries {
			queries = append(queries, alias)
		}
	}
	fetchParts := []Part{Credits, ExternalIDs}
	alternativeSearch := alternativeTitleSearchTitle(title)
	for _, query := range queries {
		if alternativeTitleSearchTitle(query) {
			fetchParts = append(fetchParts, AlternativeTitles)
			break
		}
	}
	var documents []Document
	var ranked []MatchCandidate
	var yearCandidates, directCandidates []MatchCandidate
	eligible := make([]MatchCandidate, 0)
	unfilteredSearch := false
	searchComplete := true
queries:
	for _, query := range queries {
		pageLimit := 1
		if input.Year <= 0 {
			pageLimit = maxAutomaticTitlePages
		}
		for pageNumber := 1; pageNumber <= pageLimit; pageNumber++ {
			unfilteredSearch = false
			if err := authorize(ctx); err != nil {
				return AutomaticMatch{}, err
			}
			page, err := client.Search(ctx, SearchQuery{Kind: input.Kind, Title: query, Year: input.Year,
				Language: language, Region: input.Region, Page: pageNumber})
			if err != nil {
				return AutomaticMatch{}, err
			}
			if len(page.Items) == 0 && input.Year > 0 {
				if err := authorize(ctx); err != nil {
					return AutomaticMatch{}, err
				}
				page, err = client.Search(ctx, SearchQuery{Kind: input.Kind, Title: query,
					Language: language, Region: input.Region, Page: 1})
				if err != nil {
					return AutomaticMatch{}, err
				}
				unfilteredSearch = len(page.Items) != 0
			}
			documents = append(documents, page.Items...)
			searchComplete = searchComplete && page.TotalPages <= maxAutomaticTitlePages
			ranked = RankCandidates(MatchHint{Kind: input.Kind, Title: title, Year: input.Year}, documents)
			eligible = eligible[:0]
			for _, candidate := range ranked {
				direct := matchesAnyTitle(candidate, queries)
				if direct {
					directCandidates = append(directCandidates, candidate)
				}
				if input.Year > 0 && candidate.Year == input.Year {
					if direct {
						eligible = append(eligible, candidate)
					} else {
						yearCandidates = append(yearCandidates, candidate)
					}
				}
			}
			if len(eligible) != 0 {
				break queries
			}
			if input.Year > 0 || pageNumber >= page.TotalPages {
				break
			}
		}
	}
	if len(eligible) == 0 && len(directCandidates) == 0 && input.Year > 0 && alternativeSearch && len(yearCandidates) != 0 {
		eligible = append(eligible, yearCandidates[:min(len(yearCandidates), maxAutomaticTitleQueries)]...)
	}
	if len(documents) == 0 {
		return AutomaticMatch{State: AutoNoMatch, Reason: "title_not_found"}, nil
	}
	numberedTitle := false
	if input.Year <= 0 {
		ranked = RankCandidates(MatchHint{Kind: input.Kind, Title: title}, documents)
		exact := exactTitleCandidates(ranked, queries)
		if len(exact) == 0 {
			exact = numberedTitleCandidates(ranked, queries)
			numberedTitle = len(exact) != 0
		}
		if len(exact) == 0 || input.Kind != Movie || input.DurationMs <= 0 || !searchComplete {
			return review("year_required")
		}
		eligible = append(eligible, exact...)
	}
	if len(eligible) == 0 {
		exact := exactTitleCandidates(ranked, queries)
		if len(exact) == 0 {
			return review("title_year_conflict")
		}
		if len(exact) > 1 {
			if input.DurationMs <= 0 {
				return review("ambiguous_candidates")
			}
		} else if len(exact) == 0 {
			return review("title_year_conflict")
		}
		// Search result dates can be stale. Exact-title candidates may be
		// fetched, but the detail's own release year remains a hard fence below.
		eligible = append(eligible, exact...)
	}
	var document Document
	var projection TMDBProjection
	var candidate MatchCandidate
	runtimeDisambiguated := false
	var closestRuntimeDifference int64
	if len(eligible) > 1 {
		if input.DurationMs <= 0 {
			return review("ambiguous_candidates")
		}
		type compatibleCandidate struct {
			candidate  MatchCandidate
			document   Document
			projection TMDBProjection
			difference int64
		}
		var compatible []compatibleCandidate
		for _, candidate := range eligible {
			if err := authorize(ctx); err != nil {
				return AutomaticMatch{}, err
			}
			detail, err := client.Fetch(ctx, candidate.Entity, FetchOptions{Language: language, Parts: fetchParts})
			if err != nil {
				if providerErrorIs(err, NotFound) {
					continue
				}
				return AutomaticMatch{}, err
			}
			projected, err := ProjectTMDB(detail)
			if err != nil {
				return AutomaticMatch{}, err
			}
			direct, alternative := documentMatchesAnyQuery(detail, queries)
			if !direct && !alternative {
				continue
			}
			if year := projected.Item.Fields["year"]; input.Year > 0 && len(year) != 0 && year[0] != strconv.Itoa(input.Year) {
				continue
			}
			runtime := documentRuntimeMinutes(detail)
			if runtime == 0 {
				continue
			}
			compatible = append(compatible, compatibleCandidate{candidate: candidate, document: detail, projection: projected,
				difference: runtimeDifferenceMs(input.DurationMs, runtime)})
		}
		if len(compatible) > 1 {
			best := compatible[0]
			tied := false
			for _, candidate := range compatible[1:] {
				switch {
				case candidate.difference < best.difference:
					best, tied = candidate, false
				case candidate.difference == best.difference:
					tied = true
				}
			}
			if tied {
				return review("ambiguous_candidates")
			}
			compatible = []compatibleCandidate{best}
		}
		if len(compatible) == 0 {
			return review("runtime_unknown")
		}
		candidate = compatible[0].candidate
		document, projection = compatible[0].document, compatible[0].projection
		runtimeDisambiguated = true
		closestRuntimeDifference = compatible[0].difference
	} else {
		if len(eligible) != 1 {
			return review("ambiguous_candidates")
		}
		candidate = eligible[0]
		if err := authorize(ctx); err != nil {
			return AutomaticMatch{}, err
		}
		var err error
		document, err = client.Fetch(ctx, candidate.Entity, FetchOptions{Language: language, Parts: fetchParts})
		if err != nil {
			if providerErrorIs(err, NotFound) {
				return AutomaticMatch{State: AutoNoMatch, Reason: "identifier_not_found"}, nil
			}
			return AutomaticMatch{}, err
		}
		projection, err = ProjectTMDB(document)
		if err != nil {
			return AutomaticMatch{}, err
		}
	}
	titleField, originalField := "title", "original_title"
	if input.Kind == Series {
		titleField, originalField = "name", "original_name"
	}
	matchedQuery := ""
	matchedAlternative := false
	for _, query := range queries {
		direct, alternative := documentQueryTitleMatch(query, document)
		matches := direct || alternative
		if input.Year <= 0 && !matches {
			matches = numberedTitleMatches(query, textField(document, titleField)) || numberedTitleMatches(query, textField(document, originalField))
		}
		if matches {
			matchedQuery = query
			matchedAlternative = alternative && !direct
			break
		}
	}
	if matchedQuery == "" {
		if input.Year <= 0 {
			return review("title_conflict")
		}
		return review("title_year_conflict")
	}
	yearDifferenceVerified := false
	if input.Year > 0 && len(projection.Item.Fields["year"]) != 0 && projection.Item.Fields["year"][0] != strconv.Itoa(input.Year) {
		providerYear, _ := strconv.Atoi(projection.Item.Fields["year"][0])
		yearDifference := providerYear - input.Year
		if yearDifference < 0 {
			yearDifference = -yearDifference
		}
		runtime := documentRuntimeMinutes(document)
		if providerYear == 0 || yearDifference != 1 || input.DurationMs <= 0 || runtime == 0 || !runtimeMatches(input.DurationMs, runtime) {
			return review("title_year_conflict")
		}
		yearDifferenceVerified = true
	}
	evidence := append([]string(nil), candidate.Evidence...)
	if yearDifferenceVerified {
		if !slices.Contains(evidence, "year_difference") {
			evidence = append(evidence, "year_difference")
		}
		evidence = append(evidence, "year_difference_runtime_verified")
	}
	if input.Year <= 0 && !runtimeDisambiguated {
		runtime := documentRuntimeMinutes(document)
		if runtime == 0 {
			return review("runtime_unknown")
		}
		if runtimeMatches(input.DurationMs, runtime) {
			evidence = append(evidence, "runtime_match")
		} else {
			evidence = append(evidence, "runtime_difference")
		}
	}
	if runtimeDisambiguated {
		evidence = append(evidence, "runtime_disambiguation", "closest_runtime")
		if closestRuntimeDifference <= int64(runtimeToleranceMinutes(documentRuntimeMinutes(document)))*60_000 {
			evidence = append(evidence, "runtime_match")
		} else {
			evidence = append(evidence, "runtime_difference")
		}
	}
	if input.Year <= 0 {
		if numberedTitle {
			evidence = append(evidence, "numbered_title_without_year")
		} else {
			evidence = append(evidence, "exact_title_without_year")
		}
	}
	if unfilteredSearch {
		evidence = append(evidence, "unfiltered_title_search")
	}
	if !slices.Contains(evidence, "title_match") {
		if matchedAlternative {
			evidence = append(evidence, "alternative_title_match")
		} else if matchedQuery == title {
			evidence = append(evidence, "title_match")
		} else {
			evidence = append(evidence, "alias_title_match")
		}
	}
	evidence = append(evidence, "unique_candidate")
	return AutomaticMatch{State: AutoMatched, Evidence: evidence, Document: document, Projection: projection}, nil
}

func documentMatchesAnyQuery(document Document, queries []string) (bool, bool) {
	direct, alternative := false, false
	for _, query := range queries {
		queryDirect, queryAlternative := documentQueryTitleMatch(query, document)
		direct = direct || queryDirect
		alternative = alternative || queryAlternative
	}
	return direct, alternative
}

func documentQueryTitleMatch(query string, document Document) (bool, bool) {
	titleField, originalField := "title", "original_title"
	if document.Entity.Kind == Series {
		titleField, originalField = "name", "original_name"
	}
	query = matchTitle(query)
	direct := query != "" && (query == matchTitle(textField(document, titleField)) || query == matchTitle(textField(document, originalField)))
	return direct, !direct && alternativeTitleMatches(document, query)
}

func alternativeTitleMatches(document Document, query string) bool {
	if query == "" {
		return false
	}
	var payload struct {
		Titles []struct {
			Title string `json:"title"`
		} `json:"titles"`
	}
	if json.Unmarshal(document.Fields["alternative_titles"], &payload) != nil || len(payload.Titles) > 1000 {
		return false
	}
	for _, title := range payload.Titles {
		if matchTitle(title.Title) == query {
			return true
		}
	}
	return false
}

func alternativeTitleSearchTitle(title string) bool {
	words := strings.Fields(title)
	if len(words) < 2 {
		return false
	}
	for _, r := range title {
		if unicode.Is(unicode.Han, r) || unicode.IsDigit(r) {
			return false
		}
		if !unicode.IsSpace(r) && !unicode.IsLetter(r) && r != '-' && r != '\'' && r != '’' {
			return false
		}
		if unicode.IsLetter(r) && r > unicode.MaxASCII {
			return false
		}
	}
	return true
}

func numberedTitleCandidates(candidates []MatchCandidate, queries []string) []MatchCandidate {
	result := make([]MatchCandidate, 0, 1)
	seen := make(map[EntityRef]struct{}, len(candidates))
	for _, candidate := range candidates {
		matched := false
		for _, query := range queries {
			matched = matched || numberedTitleMatches(query, candidate.Title) || numberedTitleMatches(query, candidate.OriginalTitle)
		}
		if !matched {
			continue
		}
		if _, exists := seen[candidate.Entity]; exists {
			continue
		}
		seen[candidate.Entity] = struct{}{}
		result = append(result, candidate)
	}
	return result
}

func numberedTitleMatches(query, candidate string) bool {
	query = strings.TrimSpace(query)
	candidate = strings.TrimSpace(candidate)
	queryRunes := []rune(query)
	if len(queryRunes) < 2 || !unicode.IsDigit(queryRunes[len(queryRunes)-1]) || !strings.HasPrefix(candidate, query) {
		return false
	}
	remainder := []rune(strings.TrimPrefix(candidate, query))
	if len(remainder) == 0 {
		return false
	}
	switch remainder[0] {
	case ':', '：', '-', '—':
		return true
	default:
		return unicode.IsSpace(remainder[0])
	}
}

func matchesAnyTitle(candidate MatchCandidate, queries []string) bool {
	for _, query := range queries {
		if matchTitle(query) == matchTitle(candidate.Title) || matchTitle(query) == matchTitle(candidate.OriginalTitle) {
			return true
		}
	}
	return false
}

func exactTitleCandidates(candidates []MatchCandidate, queries []string) []MatchCandidate {
	result := make([]MatchCandidate, 0, 1)
	seen := make(map[EntityRef]struct{}, len(candidates))
	for _, candidate := range candidates {
		if !matchesAnyTitle(candidate, queries) {
			continue
		}
		if _, exists := seen[candidate.Entity]; exists {
			continue
		}
		seen[candidate.Entity] = struct{}{}
		result = append(result, candidate)
	}
	return result
}

func providerErrorIs(err error, code ErrorCode) bool {
	var providerError *Error
	return errors.As(err, &providerError) && providerError.Code == code
}
