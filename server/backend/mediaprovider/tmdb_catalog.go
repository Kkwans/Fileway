package mediaprovider

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"
)

type SearchQuery struct {
	Kind     Kind
	Title    string
	Year     int
	Language string
	Region   string // Movie release region; TV has no region search parameter.
	Page     int
}

type CandidatePage struct {
	Page         int
	TotalPages   int
	TotalResults int
	Items        []Document
}

// Search returns all candidates on the requested page. It never selects a
// match, including when TMDB returns only one result.
func (client *TMDBClient) Search(ctx context.Context, input SearchQuery) (CandidatePage, error) {
	var result CandidatePage
	if input.Kind != Movie && input.Kind != Series || strings.TrimSpace(input.Title) == "" || len(input.Title) > 4096 || input.Page < 1 || input.Page > 500 || input.Year != 0 && (input.Year < 1000 || input.Year > 9999) || len(input.Region) > 2 {
		return result, &Error{Code: InvalidRequest}
	}
	query, err := languageQuery(input.Language)
	if err != nil {
		return result, err
	}
	query.Set("query", input.Title)
	query.Set("page", strconv.Itoa(input.Page))
	query.Set("include_adult", "false")
	namespace := "movie"
	yearKey := "primary_release_year"
	if input.Kind == Series {
		namespace, yearKey = "tv", "first_air_date_year"
	}
	if input.Year != 0 {
		query.Set(yearKey, strconv.Itoa(input.Year))
	}
	if input.Kind == Movie && input.Region != "" {
		query.Set("region", input.Region)
	}
	var response struct {
		Page         int                          `json:"page"`
		TotalPages   *int                         `json:"total_pages"`
		TotalResults *int                         `json:"total_results"`
		Results      []map[string]json.RawMessage `json:"results"`
	}
	if err := client.get(ctx, "/search/"+namespace, query, &response); err != nil {
		return result, err
	}
	if response.Page != input.Page || response.TotalPages == nil || *response.TotalPages < 0 || response.TotalResults == nil || *response.TotalResults < 0 || response.Results == nil || len(response.Results) > 100 {
		return result, &Error{Code: InvalidResponse}
	}
	result = CandidatePage{Page: response.Page, TotalPages: *response.TotalPages, TotalResults: *response.TotalResults, Items: make([]Document, 0, len(response.Results))}
	for _, fields := range response.Results {
		document, err := tmdbDocument(input.Kind, 0, input.Language, fields)
		if err != nil {
			return CandidatePage{}, err
		}
		result.Items = append(result.Items, document)
	}
	return result, nil
}

type Part string

const (
	Credits     Part = "credits"
	ExternalIDs Part = "external_ids"
	Images      Part = "images"
)

type FetchOptions struct {
	Language string
	Parts    []Part
}

func fetchQuery(kind Kind, options FetchOptions) (url.Values, error) {
	query, err := languageQuery(options.Language)
	if err != nil {
		return nil, err
	}
	parts := make([]string, 0, len(options.Parts))
	for _, part := range options.Parts {
		name := string(part)
		switch part {
		case Images:
		case ExternalIDs:
			if kind == Collection {
				return nil, &Error{Code: InvalidRequest}
			}
		case Credits:
			if kind == Person || kind == Collection {
				return nil, &Error{Code: InvalidRequest}
			}
			if kind == Series {
				name = "aggregate_credits"
			}
		default:
			return nil, &Error{Code: InvalidRequest}
		}
		parts = append(parts, name)
	}
	slices.Sort(parts)
	parts = slices.Compact(parts)
	if len(parts) != 0 {
		query.Set("append_to_response", strings.Join(parts, ","))
	}
	return query, nil
}

func (client *TMDBClient) Fetch(ctx context.Context, ref EntityRef, options FetchOptions) (Document, error) {
	route, err := entityPath(ref)
	if err != nil {
		return Document{}, err
	}
	query, err := fetchQuery(ref.Kind, options)
	if err != nil {
		return Document{}, err
	}
	var fields map[string]json.RawMessage
	if err := client.get(ctx, route, query, &fields); err != nil {
		return Document{}, err
	}
	return tmdbDocument(ref.Kind, ref.ID, options.Language, fields)
}

// FetchSeason keeps season zero (specials) and preserves the provider's episode
// sequence as evidence. The catalog owns logical episode identity and ordering.
func (client *TMDBClient) FetchSeason(ctx context.Context, series EntityRef, number int, options FetchOptions) (Document, error) {
	if series.Kind != Series || number < 0 {
		return Document{}, &Error{Code: InvalidRequest}
	}
	route, err := entityPath(series)
	if err != nil {
		return Document{}, err
	}
	query, err := fetchQuery(Season, options)
	if err != nil {
		return Document{}, err
	}
	var fields map[string]json.RawMessage
	if err := client.get(ctx, fmt.Sprintf("%s/season/%d", route, number), query, &fields); err != nil {
		return Document{}, err
	}
	var actual *int
	if err := json.Unmarshal(fields["season_number"], &actual); err != nil || actual == nil || *actual != number {
		return Document{}, &Error{Code: InvalidResponse}
	}
	document, err := tmdbDocument(Season, 0, options.Language, fields)
	if err != nil {
		return Document{}, err
	}
	document.Parent, document.Number = &series, &number
	return document, nil
}

func tmdbDocument(kind Kind, expectedID int64, language string, fields map[string]json.RawMessage) (Document, error) {
	var id int64
	if err := json.Unmarshal(fields["id"], &id); err != nil || id <= 0 || expectedID != 0 && id != expectedID {
		return Document{}, &Error{Code: InvalidResponse}
	}
	return Document{Entity: EntityRef{Provider: "tmdb", Kind: kind, ID: id}, Language: language, FetchedAt: time.Now().UTC(), Fields: fields}, nil
}
