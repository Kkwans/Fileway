// Package mediaprovider reads descriptive metadata from explicitly configured
// providers. Results are evidence, not permission grants or match decisions.
package mediaprovider

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const tmdbAPI = "https://api.themoviedb.org/3"
const maxResponseBytes = 4 << 20

type Kind string

const (
	Movie      Kind = "movie"
	Series     Kind = "series"
	Season     Kind = "season"
	Episode    Kind = "episode"
	Person     Kind = "person"
	Collection Kind = "collection"
)

// EntityRef is provider-scoped. It must never replace a catalog's internal ID.
type EntityRef struct {
	Provider string
	Kind     Kind
	ID       int64
}

type ErrorCode string

const (
	InvalidRequest   ErrorCode = "invalid_request"
	NeedsConfig      ErrorCode = "needs_configuration"
	Unauthorized     ErrorCode = "provider_unauthorized"
	NotFound         ErrorCode = "entity_not_found"
	RateLimited      ErrorCode = "rate_limited"
	Unavailable      ErrorCode = "provider_unavailable"
	InvalidResponse  ErrorCode = "invalid_response"
	ResponseTooLarge ErrorCode = "response_too_large"
	RedirectRefused  ErrorCode = "redirect_refused"
)

// Error excludes tokens, request URLs and upstream response bodies. The job
// scheduler owns retry budgets, configuration recovery and RetryAfter handling.
type Error struct {
	Code       ErrorCode
	Status     int
	Retryable  bool
	RetryAfter time.Time
}

func (err *Error) Error() string { return "TMDB: " + string(err.Code) }

// TokenSource resolves the configured application read token for each request.
// Its caller owns connection authorization and quota admission. Never pass a
// Fileway user JWT. No credential is stored in a document, URL or error.
type TokenSource func(context.Context) (string, error)

type TMDBClient struct {
	http  *http.Client
	token TokenSource
}

func NewTMDBClient(client *http.Client, token TokenSource) *TMDBClient {
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	copy := *client
	copy.Jar = nil
	copy.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if token == nil {
		token = func(context.Context) (string, error) { return "", &Error{Code: NeedsConfig} }
	}
	return &TMDBClient{http: &copy, token: token}
}

// Document retains provider field presence, including null and empty values.
// Projection into a library applies its own field priority and match revision;
// this payload is not a client-facing catalog DTO or a local NFO path.
type Document struct {
	Entity    EntityRef
	Parent    *EntityRef
	Number    *int
	Language  string
	FetchedAt time.Time
	Fields    map[string]json.RawMessage `json:"-"`
}

func (client *TMDBClient) get(ctx context.Context, route string, query url.Values, target any) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	token, err := client.token(ctx)
	if canceled := ctx.Err(); canceled != nil {
		return canceled
	}
	if err != nil || token == "" || strings.ContainsAny(token, "\r\n") {
		return &Error{Code: NeedsConfig}
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, tmdbAPI+route+"?"+query.Encode(), nil)
	if err != nil {
		return &Error{Code: InvalidRequest}
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Accept", "application/json")
	response, err := client.http.Do(request)
	if err != nil {
		if canceled := ctx.Err(); canceled != nil {
			return canceled
		}
		return &Error{Code: Unavailable, Retryable: true}
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusOK {
		failure := &Error{Status: response.StatusCode, RetryAfter: parseRetryAfter(response.Header.Get("Retry-After"), time.Now())}
		switch {
		case response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden:
			failure.Code = Unauthorized
		case response.StatusCode == http.StatusNotFound:
			failure.Code = NotFound
		case response.StatusCode == http.StatusTooManyRequests:
			failure.Code, failure.Retryable = RateLimited, true
		case response.StatusCode >= 300 && response.StatusCode < 400:
			failure.Code = RedirectRefused
		case response.StatusCode >= 500 || response.StatusCode == http.StatusRequestTimeout:
			failure.Code, failure.Retryable = Unavailable, true
		default:
			failure.Code = InvalidResponse
			if response.StatusCode >= 400 {
				failure.Code = InvalidRequest
			}
		}
		return failure
	}
	// net/http's standard transport decompresses gzip before this byte limit.
	body, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		if canceled := ctx.Err(); canceled != nil {
			return canceled
		}
		return &Error{Code: Unavailable, Retryable: true}
	}
	if len(body) > maxResponseBytes {
		return &Error{Code: ResponseTooLarge}
	}
	if err := json.Unmarshal(body, target); err != nil {
		return &Error{Code: InvalidResponse}
	}
	return ctx.Err()
}

func parseRetryAfter(value string, now time.Time) time.Time {
	if seconds, err := strconv.ParseInt(value, 10, 64); err == nil && seconds >= 0 && seconds <= (1<<63-1)/int64(time.Second) {
		return now.Add(time.Duration(seconds) * time.Second)
	}
	parsed, err := http.ParseTime(value)
	if err == nil && parsed.After(now) {
		return parsed
	}
	return time.Time{}
}

func languageQuery(language string) (url.Values, error) {
	if len(language) > 64 || strings.ContainsAny(language, "\r\n\x00") {
		return nil, &Error{Code: InvalidRequest}
	}
	query := make(url.Values)
	if language != "" {
		query.Set("language", language)
	}
	return query, nil
}

func entityPath(ref EntityRef) (string, error) {
	if ref.Provider != "tmdb" || ref.ID <= 0 {
		return "", &Error{Code: InvalidRequest}
	}
	namespace := string(ref.Kind)
	switch ref.Kind {
	case Series:
		namespace = "tv"
	case Movie, Person, Collection:
	default:
		return "", &Error{Code: InvalidRequest}
	}
	return fmt.Sprintf("/%s/%d", namespace, ref.ID), nil
}
