package mediaprovider

import (
	"context"
	"errors"
	"net/http"
	"testing"
)

func TestResolveAutomaticMatchRequiresConvergingExplicitEvidence(t *testing.T) {
	tmdb := WorkIdentifier{Movie, ExternalRef{"tmdb", "42"}}
	imdb := WorkIdentifier{Movie, ExternalRef{"imdb", "tt1234567"}}
	const movie = `{"id":42,"title":"回声","original_title":"Echo","release_date":"2020-09-01","external_ids":{"imdb_id":"tt1234567"}}`
	const find = `{"movie_results":[{"id":42}],"tv_results":[]}`
	for _, tc := range []struct {
		name          string
		input         AutomaticMatchInput
		find, details string
		want          AutomaticMatchState
		reason        string
		calls         int
	}{
		{"year alone", AutomaticMatchInput{Kind: Movie, Year: 2020}, "", "", AutoNeedsReview, "typed_identifier_required", 0},
		{"untyped alone", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{{"", tmdb.ExternalRef}}}, "", "", AutoNeedsReview, "typed_identifier_required", 0},
		{"declared type conflict", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{{Series, tmdb.ExternalRef}}}, "", "", AutoNeedsReview, "entity_type_conflict", 0},
		{"NFO and filename disagree", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{tmdb, {"", ExternalRef{"tmdb", "43"}}}}, "", "", AutoNeedsReview, "identifier_conflict", 0},
		{"incomplete source evidence", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{tmdb}, Incomplete: true}, "", "", AutoNeedsReview, "incomplete_evidence", 0},
		{"episode numbering ambiguity", AutomaticMatchInput{Kind: Series, Ambiguities: []string{"numbering_scheme_conflict"}}, "", "", AutoNeedsReview, "ambiguous_evidence", 0},
		{"typed film", AutomaticMatchInput{Kind: Movie, Year: 2020, Identifiers: []WorkIdentifier{tmdb}}, "", movie, AutoMatched, "", 1},
		{"same name remake year conflict", AutomaticMatchInput{Kind: Movie, Year: 1998, Identifiers: []WorkIdentifier{tmdb}}, "", movie, AutoNeedsReview, "year_conflict", 1},
		{"both IDs resolve", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{tmdb, imdb}}, find, movie, AutoMatched, "", 2},
		{"IMDb crosses media types", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{imdb}}, `{"movie_results":[{"id":42}],"tv_results":[{"id":42}]}`, "", AutoNeedsReview, "identifier_conflict", 1},
		{"IMDb points to other film", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{tmdb, imdb}}, `{"movie_results":[{"id":43}]}`, "", AutoNeedsReview, "identifier_conflict", 1},
		{"IMDb not found", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{imdb}}, `{"movie_results":[]}`, "", AutoNoMatch, "identifier_not_found", 1},
		{"reverse identity missing", AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{imdb}}, find, `{"id":42,"title":"回声"}`, AutoNeedsReview, "identifier_unverified", 2},
		{"typed series", AutomaticMatchInput{Kind: Series, Identifiers: []WorkIdentifier{{Series, tmdb.ExternalRef}}}, "", `{"id":42,"name":"星河"}`, AutoMatched, "", 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls, checks := 0, 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				switch request.URL.Path {
				case "/3/find/tt1234567":
					return response(200, tc.find), nil
				case "/3/movie/42", "/3/tv/42":
					return response(200, tc.details), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL.Path)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), tc.input, "zh-CN", func(context.Context) error { checks++; return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != tc.calls || checks != calls {
				t.Fatalf("state=%s reason=%s checks=%d calls=%d err=%v", result.State, result.Reason, checks, calls, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 42 || result.Document.Entity.Kind != tc.input.Kind || len(result.Projection.Item.Fields["title"]) == 0) {
				t.Fatal("matched result lacks typed detail and normalized metadata")
			}
		})
	}
}

func TestResolveAutomaticMatchPreservesRetryAndStopsAfterRevocation(t *testing.T) {
	input := AutomaticMatchInput{Kind: Movie, Identifiers: []WorkIdentifier{{Movie, ExternalRef{"imdb", "tt1234567"}}}}
	client := fixtureClient(func(*http.Request) (*http.Response, error) {
		r := response(429, "")
		r.Header.Set("Retry-After", "45")
		return r, nil
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), input, "", func(context.Context) error { return nil })
	failure := requireCode(t, err, RateLimited)
	if !failure.Retryable || failure.RetryAfter.IsZero() || result.State != "" {
		t.Fatal("rate limit was treated as a completed match decision")
	}
	var count int
	client = fixtureClient(func(request *http.Request) (*http.Response, error) {
		count++
		if request.URL.Path != "/3/find/tt1234567" {
			t.Fatal("sent details request after revocation")
		}
		return response(200, `{"movie_results":[{"id":42}]}`), nil
	})
	revoked := errors.New("authority changed")
	result, err = client.ResolveAutomaticMatch(t.Context(), input, "", func(context.Context) error {
		if count > 0 {
			return revoked
		}
		return nil
	})
	if !errors.Is(err, revoked) || count != 1 || result.State != "" {
		t.Fatalf("authority failure not propagated: %v %d %s", err, count, result.State)
	}
}
