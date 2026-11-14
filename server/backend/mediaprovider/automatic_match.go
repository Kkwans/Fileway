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
	Kind        Kind
	Year        int
	Identifiers []WorkIdentifier
	Ambiguities []string
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
	if tmdbID == 0 && len(external) == 0 {
		return review("typed_identifier_required")
	}
	if authorize == nil {
		return AutomaticMatch{}, &Error{Code: InvalidRequest}
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

func providerErrorIs(err error, code ErrorCode) bool {
	var providerError *Error
	return errors.As(err, &providerError) && providerError.Code == code
}
