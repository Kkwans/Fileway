package mediaprovider

import (
	"context"
	"errors"
	"net/http"
	"slices"
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

func TestResolveAutomaticMatchTitleEvidenceIsConservative(t *testing.T) {
	for _, tc := range []struct {
		name, search, details string
		input                 AutomaticMatchInput
		want                  AutomaticMatchState
		reason                string
		calls                 int
	}{
		{
			name:    "exact title and year unique",
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"回声","original_title":"Echo","release_date":"2020-09-01"}]}`,
			details: `{"id":42,"title":"回声","original_title":"Echo","release_date":"2020-09-01"}`,
			input:   AutomaticMatchInput{Kind: Movie, Title: "回声", Year: 2020, AllowTitleMatch: true},
			want:    AutoMatched, calls: 2,
		},
		{
			name:   "same title remake is review",
			search: `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":42,"title":"回声","release_date":"2020-09-01"},{"id":43,"title":"回声","release_date":"2020-09-01"}]}`,
			input:  AutomaticMatchInput{Kind: Movie, Title: "回声", Year: 2020, AllowTitleMatch: true},
			want:   AutoNeedsReview, reason: "ambiguous_candidates", calls: 1,
		},
		{
			name:   "missing year is review",
			search: `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"回声","release_date":"2020-09-01"}]}`,
			input:  AutomaticMatchInput{Kind: Movie, Title: "回声", AllowTitleMatch: true},
			want:   AutoNeedsReview, reason: "year_required", calls: 1,
		},
		{
			name:   "no candidate",
			search: `{"page":1,"total_pages":0,"total_results":0,"results":[]}`,
			input:  AutomaticMatchInput{Kind: Movie, Title: "回声", Year: 2020, AllowTitleMatch: true},
			want:   AutoNoMatch, reason: "title_not_found", calls: 2,
		},
		{
			name:    "unique exact candidate verifies detail year after search mismatch",
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"回声","original_title":"Echo","release_date":"2021-09-01"}]}`,
			details: `{"id":42,"title":"回声","original_title":"Echo","release_date":"2020-09-01"}`,
			input:   AutomaticMatchInput{Kind: Movie, Title: "回声", Year: 2020, AllowTitleMatch: true},
			want:    AutoMatched, calls: 2,
		},
		{
			name:    "unique exact candidate still rejects detail year mismatch",
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"回声","original_title":"Echo","release_date":"2021-09-01"}]}`,
			details: `{"id":42,"title":"回声","original_title":"Echo","release_date":"2021-09-01"}`,
			input:   AutomaticMatchInput{Kind: Movie, Title: "回声", Year: 2020, AllowTitleMatch: true},
			want:    AutoNeedsReview, reason: "title_year_conflict", calls: 2,
		},
		{
			name:  "title matching disabled",
			input: AutomaticMatchInput{Kind: Movie, Title: "回声", Year: 2020},
			want:  AutoNeedsReview, reason: "typed_identifier_required", calls: 0,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls, checks := 0, 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				if request.URL.Query().Get("language") != "zh-CN" {
					t.Error("title lookup lost metadata language", request.URL)
				}
				switch request.URL.Path {
				case "/3/search/movie":
					wantYear := "2020"
					if tc.input.Year == 0 || tc.name == "no candidate" && calls > 1 {
						wantYear = ""
					}
					if request.URL.Query().Get("query") != "回声" || request.URL.Query().Get("primary_release_year") != wantYear {
						t.Error("wrong title lookup", request.URL)
					}
					return response(200, tc.search), nil
				case "/3/movie/42":
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
			if tc.want == AutoMatched && (result.Document.Entity.ID != 42 || len(result.Evidence) == 0) {
				t.Fatalf("title match lacks typed evidence: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchRetriesWithoutYearOnlyAfterEmptySearch(t *testing.T) {
	for _, tc := range []struct {
		name, details string
		want          AutomaticMatchState
		reason        string
	}{
		{"detail year verifies", `{"id":42,"title":"Echo","release_date":"2020-09-01"}`, AutoMatched, ""},
		{"detail year rejects", `{"id":42,"title":"Echo","release_date":"2021-09-01"}`, AutoNeedsReview, "title_year_conflict"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("primary_release_year") != "" {
						return response(200, `{"page":1,"total_pages":0,"total_results":0,"results":[]}`), nil
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"Echo","release_date":"2020-09-01"}]}`), nil
				case "/3/movie/42":
					return response(200, tc.details), nil
				default:
					t.Fatalf("unexpected request %s", request.URL.Path)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "Echo", Year: 2020, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != 3 {
				t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 42 || !slices.Contains(result.Evidence, "unfiltered_title_search")) {
				t.Fatalf("fallback evidence missing: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchUsesAliasAfterUnqualifiedPrimaryResults(t *testing.T) {
	calls := 0
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		calls++
		switch request.URL.Path {
		case "/3/search/movie":
			if request.URL.Query().Get("query") == "The Notebook 恋恋笔记本" {
				return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":99,"title":"Unrelated","release_date":"2004-01-01"}]}`), nil
			}
			if request.URL.Query().Get("query") != "The Notebook" {
				t.Fatalf("unexpected alias query: %s", request.URL)
			}
			return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"The Notebook","release_date":"2004-01-01"}]}`), nil
		case "/3/movie/42":
			return response(200, `{"id":42,"title":"The Notebook","release_date":"2004-01-01"}`), nil
		default:
			t.Fatalf("unexpected request: %s", request.URL.Path)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "The Notebook 恋恋笔记本",
		Aliases: []string{"The Notebook", "恋恋笔记本"}, Year: 2004, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 42 || calls != 3 {
		t.Fatalf("alias fallback failed: state=%s calls=%d err=%v", result.State, calls, err)
	}
	if !slices.Contains(result.Evidence, "alias_title_match") {
		t.Fatalf("alias evidence missing: %v", result.Evidence)
	}
}

func TestResolveAutomaticMatchUsesFourthAliasWithinBoundedQueries(t *testing.T) {
	calls := 0
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		calls++
		switch request.URL.Path {
		case "/3/search/movie":
			if request.URL.Query().Get("query") == "Thor" {
				return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"Thor","original_title":"Thor","release_date":"2011-01-01"}]}`), nil
			}
			return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":99,"title":"Unrelated","release_date":"2011-01-01"}]}`), nil
		case "/3/movie/42":
			return response(200, `{"id":42,"title":"Thor","original_title":"Thor","release_date":"2011-01-01"}`), nil
		default:
			t.Fatalf("unexpected request: %s", request.URL.Path)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie,
		Title: "Noise 123 Movie Thor", Aliases: []string{"Noise", "123", "Movie", "Thor"}, Year: 2011, AllowTitleMatch: true},
		"zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 42 || calls != 6 {
		t.Fatalf("fourth alias fallback failed: state=%s calls=%d err=%v", result.State, calls, err)
	}
	if !slices.Contains(result.Evidence, "alias_title_match") {
		t.Fatalf("alias evidence missing: %v", result.Evidence)
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
