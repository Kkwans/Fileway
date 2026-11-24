package mediaprovider

import (
	"context"
	"errors"
	"slices"
	"strconv"
	"strings"
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
		return client.resolveTitleMatch(ctx, input, language, authorize)
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
		if alias != "" && !slices.Contains(queries, alias) && len(queries) < 4 {
			queries = append(queries, alias)
		}
	}
	var documents []Document
	var ranked []MatchCandidate
	eligible := make([]MatchCandidate, 0)
	for _, query := range queries {
		if err := authorize(ctx); err != nil {
			return AutomaticMatch{}, err
		}
		page, err := client.Search(ctx, SearchQuery{Kind: input.Kind, Title: query, Year: input.Year,
			Language: language, Region: input.Region, Page: 1})
		if err != nil {
			return AutomaticMatch{}, err
		}
		documents = append(documents, page.Items...)
		ranked = RankCandidates(MatchHint{Kind: input.Kind, Title: title, Year: input.Year}, documents)
		eligible = eligible[:0]
		for _, candidate := range ranked {
			exact := false
			for _, candidateQuery := range queries {
				exact = exact || matchTitle(candidateQuery) == matchTitle(candidate.Title) || matchTitle(candidateQuery) == matchTitle(candidate.OriginalTitle)
			}
			if exact && input.Year > 0 && candidate.Year == input.Year {
				eligible = append(eligible, candidate)
			}
		}
		if len(eligible) != 0 {
			break
		}
	}
	if len(documents) == 0 {
		return AutomaticMatch{State: AutoNoMatch, Reason: "title_not_found"}, nil
	}
	if input.Year <= 0 {
		return review("year_required")
	}
	if len(eligible) == 0 {
		return review("title_year_conflict")
	}
	if len(eligible) != 1 {
		return review("ambiguous_candidates")
	}
	candidate := eligible[0]
	if err := authorize(ctx); err != nil {
		return AutomaticMatch{}, err
	}
	document, err := client.Fetch(ctx, candidate.Entity, FetchOptions{Language: language, Parts: []Part{Credits, ExternalIDs}})
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
	titleField, originalField := "title", "original_title"
	if input.Kind == Series {
		titleField, originalField = "name", "original_name"
	}
	matchedQuery := ""
	for _, query := range queries {
		if matchTitle(query) == matchTitle(textField(document, titleField)) || matchTitle(query) == matchTitle(textField(document, originalField)) {
			matchedQuery = query
			break
		}
	}
	if matchedQuery == "" || input.Year > 0 && len(projection.Item.Fields["year"]) != 0 && projection.Item.Fields["year"][0] != strconv.Itoa(input.Year) {
		return review("title_year_conflict")
	}
	evidence := append([]string(nil), candidate.Evidence...)
	if !slices.Contains(evidence, "title_match") {
		if matchedQuery == title {
			evidence = append(evidence, "title_match")
		} else {
			evidence = append(evidence, "alias_title_match")
		}
	}
	evidence = append(evidence, "unique_candidate")
	return AutomaticMatch{State: AutoMatched, Evidence: evidence, Document: document, Projection: projection}, nil
}

func providerErrorIs(err error, code ErrorCode) bool {
	var providerError *Error
	return errors.As(err, &providerError) && providerError.Code == code
}
