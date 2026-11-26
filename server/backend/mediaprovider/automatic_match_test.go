package mediaprovider

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strconv"
	"strings"
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

func TestResolveAutomaticMatchAllowsUniqueExactSeriesWithoutYear(t *testing.T) {
	for _, tc := range []struct {
		name   string
		search string
		want   AutomaticMatchState
		reason string
		calls  int
	}{
		{
			name:   "unique exact series",
			search: `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"秘密森林","original_name":"Stranger","first_air_date":"2017-06-10"}]}`,
			want:   AutoMatched, calls: 2,
		},
		{
			name:   "same title series remains ambiguous",
			search: `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":42,"name":"秘密森林","first_air_date":"2017-06-10"},{"id":43,"name":"秘密森林","first_air_date":"2020-08-15"}]}`,
			want:   AutoNeedsReview, reason: "ambiguous_candidates", calls: 3,
		},
		{
			name:   "incomplete search remains review",
			search: `{"page":1,"total_pages":4,"total_results":80,"results":[{"id":42,"name":"秘密森林","first_air_date":"2017-06-10"}]}`,
			want:   AutoNeedsReview, reason: "year_required", calls: 3,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				switch request.URL.Path {
				case "/3/search/tv":
					if page := request.URL.Query().Get("page"); page != "" && page != "1" {
						return response(200, `{"page":`+page+`,"total_pages":4,"total_results":80,"results":[]}`), nil
					}
					return response(200, tc.search), nil
				case "/3/tv/42":
					return response(200, `{"id":42,"name":"秘密森林","original_name":"Stranger","first_air_date":"2017-06-10"}`), nil
				case "/3/tv/43":
					return response(200, `{"id":43,"name":"秘密森林","original_name":"Secret Forest","first_air_date":"2020-08-15"}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL.Path)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{
				Kind: Series, Title: "秘密森林", AllowTitleMatch: true,
			}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != tc.calls {
				t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID == 0 || !slices.Contains(result.Evidence, "exact_title_without_year")) {
				t.Fatalf("series match lacks unique exact title evidence: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchSeriesVerifiesLocalizedCandidates(t *testing.T) {
	for _, tc := range []struct {
		name, title string
		year        int
		search      string
		details     map[string]string
		want        AutomaticMatchState
		reason      string
		calls       int
	}{
		{
			name: "english title verifies localized year candidate", title: "Meet Yourself", year: 2023,
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"去有风的地方","first_air_date":"2023-01-03"}]}`,
			details: map[string]string{"/3/tv/42": `{"id":42,"name":"去有风的地方","original_name":"Meet Yourself","first_air_date":"2023-01-03"}`},
			want:    AutoMatched, calls: 2,
		},
		{
			name: "year candidate verifies alternative title", title: "Meet Yourself", year: 2023,
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"去有风的地方","original_name":"去有风的地方","first_air_date":"2023-01-03"}]}`,
			details: map[string]string{"/3/tv/42": `{"id":42,"name":"去有风的地方","original_name":"去有风的地方","first_air_date":"2023-01-03","alternative_titles":{"titles":[{"title":"Meet Yourself"}]}}`},
			want:    AutoMatched, calls: 2,
		},
		{
			name: "chinese title verifies original name", title: "人生切割术", year: 2022,
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"Severance","first_air_date":"2022-02-18"}]}`,
			details: map[string]string{"/3/tv/42": `{"id":42,"name":"Severance","original_name":"Severance","first_air_date":"2022-02-18","alternative_titles":{"titles":[{"title":"人生切割术"}]}}`},
			want:    AutoMatched, calls: 2,
		},
		{
			name: "year candidate detail must match", title: "Meet Yourself", year: 2023,
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"去有风的地方","first_air_date":"2023-01-03"}]}`,
			details: map[string]string{"/3/tv/42": `{"id":42,"name":"别的剧","original_name":"Other Show","first_air_date":"2023-01-03"}`},
			want:    AutoNeedsReview, reason: "title_year_conflict", calls: 2,
		},
		{
			name: "alternative title verifies unique yearless candidate", title: "迷醉",
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"Euphoria","first_air_date":"2019-06-16"}]}`,
			details: map[string]string{"/3/tv/42": `{"id":42,"name":"亢奋","original_name":"Euphoria","first_air_date":"2019-06-16","alternative_titles":{"titles":[{"title":"迷醉"}]}}`},
			want:    AutoMatched, calls: 2,
		},
		{
			name: "tv alternative results verify yearless candidate", title: "迷醉",
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"Euphoria","first_air_date":"2019-06-16"}]}`,
			details: map[string]string{"/3/tv/42": `{"id":42,"name":"亢奋","original_name":"Euphoria","first_air_date":"2019-06-16","alternative_titles":{"results":[{"title":"迷醉"}]}}`},
			want:    AutoMatched, calls: 2,
		},
		{
			name: "ambiguous alternative titles remain review", title: "迷醉",
			search: `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":42,"name":"Euphoria","first_air_date":"2019-06-16"},{"id":43,"name":"Euphoria","first_air_date":"2020-01-01"}]}`,
			details: map[string]string{
				"/3/tv/42": `{"id":42,"name":"亢奋","original_name":"Euphoria","alternative_titles":{"titles":[{"title":"迷醉"}]}}`,
				"/3/tv/43": `{"id":43,"name":"另一部","original_name":"Euphoria","alternative_titles":{"titles":[{"title":"迷醉"}]}}`,
			},
			want: AutoNeedsReview, reason: "ambiguous_candidates", calls: 3,
		},
		{
			name: "unrelated yearless candidate remains review", title: "迷醉",
			search:  `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"name":"Other","first_air_date":"2019-06-16"}]}`,
			details: map[string]string{"/3/tv/42": `{"id":42,"name":"Other","original_name":"Other"}`},
			want:    AutoNeedsReview, reason: "year_required", calls: 2,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				if request.URL.Path == "/3/search/tv" {
					return response(200, tc.search), nil
				}
				if detail, ok := tc.details[request.URL.Path]; ok {
					if !strings.Contains(request.URL.Query().Get("append_to_response"), "alternative_titles") {
						t.Error("series detail omitted alternative titles", request.URL)
					}
					return response(200, detail), nil
				}
				t.Fatalf("unexpected outbound request %s", request.URL.Path)
				return nil, nil
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{
				Kind: Series, Title: tc.title, Year: tc.year, AllowTitleMatch: true,
			}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != tc.calls {
				t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
			}
		})
	}
}

func TestResolveAutomaticMatchAllowsUniqueNumericMultipartMovieIdentity(t *testing.T) {
	for _, tc := range []struct {
		name   string
		parts  []int
		want   AutomaticMatchState
		reason string
		calls  int
	}{
		{"numeric disc marker", []int{1}, AutoMatched, "", 2},
		{"unnumbered part label", nil, AutoNeedsReview, "ambiguous_evidence", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				switch request.URL.Path {
				case "/3/search/movie":
					return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":226875,"title":"娼","original_title":"Chang","release_date":"1997-09-13"}]}`), nil
				case "/3/movie/226875":
					return response(200, `{"id":226875,"title":"娼","original_title":"Chang","release_date":"1997-09-13","runtime":105}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL.Path)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{
				Kind: Movie, Title: "Chang", Year: 1997, DurationMs: 51 * 60_000,
				Ambiguities: []string{"part_requires_confirmation"}, Parts: tc.parts, AllowTitleMatch: true,
			}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != tc.calls {
				t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 226875 || !slices.Contains(result.Evidence, "multipart_identity_match")) {
				t.Fatalf("multipart identity evidence missing: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchDoesNotUsePartialRuntimeToChooseMultipartCandidate(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			return response(200, `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":42,"title":"回声","release_date":"2020-09-01"},{"id":43,"title":"回声","release_date":"2020-09-01"}]}`), nil
		case "/3/movie/42", "/3/movie/43":
			id := "42"
			if request.URL.Path == "/3/movie/43" {
				id = "43"
			}
			return response(200, `{"id":`+id+`,"title":"回声","release_date":"2020-09-01","runtime":105}`), nil
		default:
			t.Fatalf("unexpected outbound request %s", request.URL.Path)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{
		Kind: Movie, Title: "回声", Year: 2020, DurationMs: 51 * 60_000,
		Ambiguities: []string{"part_requires_confirmation"}, Parts: []int{1}, AllowTitleMatch: true,
	}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoNeedsReview || result.Reason != "ambiguous_candidates" {
		t.Fatalf("partial runtime selected a multipart candidate: state=%s reason=%s err=%v", result.State, result.Reason, err)
	}
}

func TestResolveAutomaticMatchAdultFallbackVerifiesRomanizedAlternativeTitle(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			if request.URL.Query().Get("include_adult") != "true" {
				return response(200, `{"page":1,"total_pages":1,"total_results":0,"results":[]}`), nil
			}
			return response(200, `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":42,"title":"娼","original_title":"창","release_date":"1997-09-13"},{"id":43,"title":"Other","original_title":"Other","release_date":"1997-09-13"}]}`), nil
		case "/3/movie/42":
			return response(200, `{"id":42,"title":"娼","original_title":"창","release_date":"1997-09-13","runtime":106,"alternative_titles":{"titles":[{"title":"Chang","type":""}]}}`), nil
		case "/3/movie/43":
			return response(200, `{"id":43,"title":"Other","original_title":"Other","release_date":"1997-09-13","runtime":90}`), nil
		default:
			t.Fatalf("unexpected outbound request %s", request.URL)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{
		Kind: Movie, Title: "Chang", Year: 1997, DurationMs: 51 * 60_000,
		Ambiguities: []string{"part_requires_confirmation"}, Parts: []int{1}, AllowTitleMatch: true,
	}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 42 {
		t.Fatalf("adult alternative title was not verified: state=%s reason=%s err=%v", result.State, result.Reason, err)
	}
	for _, evidence := range []string{"adult_search_fallback", "alternative_title_match", "multipart_identity_match"} {
		if !slices.Contains(result.Evidence, evidence) {
			t.Fatalf("missing %s evidence: %+v", evidence, result.Evidence)
		}
	}
}

func TestIncompleteTitlePatternCombinesVisibleHanRuns(t *testing.T) {
	for _, tc := range []struct {
		title   string
		queries []string
	}{
		{"y国b人", []string{"国 人", "国"}},
		{"b杰m·b顿q事", []string{"杰 顿 事", "杰"}},
	} {
		pattern := parseIncompleteTitlePattern(tc.title)
		if !slices.Equal(pattern.queries, tc.queries) {
			t.Fatalf("%s: queries=%v want=%v", tc.title, pattern.queries, tc.queries)
		}
	}
}

func TestResolveAutomaticMatchRecoversMissingHanCharactersWithYearAndRuntime(t *testing.T) {
	for _, tc := range []struct {
		name, damaged, query, otherQuery, title string
		id                                      int64
		runtime, duration                       int
		want                                    AutomaticMatchState
		reason                                  string
	}{
		{"I Am Sam", "w是s姆", "是", "姆", "我是山姆", 10950, 132, 7938645, AutoMatched, ""},
		{"The Piano Teacher", "钢q教s", "钢", "教", "钢琴教师", 1791, 131, 7863274, AutoMatched, ""},
		{"runtime difference remains evidence", "w是s姆", "是", "姆", "我是山姆", 10950, 132, 90 * 60_000, AutoMatched, ""},
		{"visible character conflict", "w是s姆", "是", "姆", "我是山猫", 10950, 132, 7938645, AutoNoMatch, "title_not_found"},
		{"sequel suffix is not ignored", "w是s姆", "是", "姆", "我是山姆2", 10950, 132, 7938645, AutoNoMatch, "title_not_found"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				switch request.URL.Path {
				case "/3/search/movie":
					query := request.URL.Query().Get("query")
					if query == tc.damaged {
						return response(200, `{"page":1,"total_pages":1,"total_results":0,"results":[]}`), nil
					}
					if (query != tc.query && query != tc.otherQuery && query != tc.query+" "+tc.otherQuery) || request.URL.Query().Get("primary_release_year") != "2001" {
						t.Fatalf("unexpected recovery query %s", request.URL)
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":`+jsonNumber(tc.id)+`,"title":"`+tc.title+`","release_date":"2001-09-01"}]}`), nil
				case "/3/movie/" + jsonNumber(tc.id):
					return response(200, `{"id":`+jsonNumber(tc.id)+`,"title":"`+tc.title+`","release_date":"2001-09-01","runtime":`+jsonNumber(int64(tc.runtime))+`}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: tc.damaged, Year: 2001, DurationMs: int64(tc.duration), AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason {
				t.Fatalf("state=%s reason=%s err=%v", result.State, result.Reason, err)
			}
			if tc.want == AutoMatched {
				if result.Document.Entity.ID != tc.id || !slices.Contains(result.Evidence, "incomplete_title_shape") ||
					!slices.Contains(result.Evidence, "closest_runtime") ||
					!slices.Contains(result.Evidence, "runtime_match") && !slices.Contains(result.Evidence, "runtime_difference") ||
					!slices.Contains(result.Evidence, "unique_candidate") {
					t.Fatalf("recovery evidence missing: %+v", result)
				}
			}
		})
	}
}

func TestResolveAutomaticMatchRecoveryRequiresUniqueRuntimeCompatibleCandidate(t *testing.T) {
	for _, tc := range []struct {
		name     string
		runtimes []int
		want     AutomaticMatchState
		reason   string
	}{
		{"runtime disambiguates", []int{90, 132}, AutoMatched, ""},
		{"same runtime remains ambiguous", []int{132, 132}, AutoNeedsReview, "ambiguous_candidates"},
		{"missing runtime remains unknown", []int{0, 132}, AutoMatched, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("query") == "w是s姆" {
						return response(200, `{"page":1,"total_pages":1,"total_results":0,"results":[]}`), nil
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":42,"title":"我是山姆","release_date":"2001-12-28"},{"id":43,"title":"我是山姆","release_date":"2001-12-28"}]}`), nil
				case "/3/movie/42", "/3/movie/43":
					id := int64(42)
					if request.URL.Path == "/3/movie/43" {
						id = 43
					}
					index := id - 42
					return response(200, `{"id":`+jsonNumber(id)+`,"title":"我是山姆","release_date":"2001-12-28","runtime":`+jsonNumber(int64(tc.runtimes[index]))+`}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "w是s姆", Year: 2001, DurationMs: 7_938_645, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason {
				t.Fatalf("state=%s reason=%s err=%v", result.State, result.Reason, err)
			}
			if tc.want == AutoMatched && result.Document.Entity.ID != 43 {
				t.Fatalf("wrong runtime-compatible candidate: %+v", result.Document.Entity)
			}
		})
	}
}

func TestResolveAutomaticMatchDoesNotRecoverIncompleteTitleWithoutDuration(t *testing.T) {
	calls := 0
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		calls++
		if request.URL.Path != "/3/search/movie" || request.URL.Query().Get("query") != "w是s姆" {
			t.Fatalf("unexpected recovery request %s", request.URL)
		}
		return response(200, `{"page":1,"total_pages":1,"total_results":0,"results":[]}`), nil
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "w是s姆", Year: 2001, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoNoMatch || result.Reason != "title_not_found" || calls != 2 {
		t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
	}
}

func jsonNumber(value int64) string {
	encoded, _ := json.Marshal(value)
	return string(encoded)
}

func TestResolveAutomaticMatchMatchesTranslatedTitleWithoutYearUsingRuntime(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			if request.URL.Query().Get("query") != "蜘蛛侠：英雄无归" || request.URL.Query().Has("primary_release_year") {
				t.Fatalf("unexpected title lookup %s", request.URL)
			}
			return response(200, `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":1,"title":"Spider-Man: No Way Home","original_title":"Spider-Man: No Way Home","release_date":"2021-12-17"},{"id":2,"title":"《蜘蛛侠：英雄无归》的幕后特辑","release_date":"2022-01-01"}]}`), nil
		case "/3/movie/1":
			return response(200, `{"id":1,"title":"Spider-Man: No Way Home","original_title":"Spider-Man: No Way Home","release_date":"2021-12-17","runtime":156,"alternative_titles":{"titles":[{"title":"蜘蛛侠：英雄无归","type":""}]}}`), nil
		case "/3/movie/2":
			return response(200, `{"id":2,"title":"《蜘蛛侠：英雄无归》的幕后特辑","release_date":"2022-01-01","runtime":45}`), nil
		default:
			t.Fatalf("unexpected outbound request %s", request.URL)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "蜘蛛侠：英雄无归", DurationMs: 156 * 60_000, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 1 ||
		!slices.Contains(result.Evidence, "translated_title_without_year") || !slices.Contains(result.Evidence, "runtime_disambiguation") {
		t.Fatalf("translated yearless match failed: state=%s reason=%s evidence=%v err=%v", result.State, result.Reason, result.Evidence, err)
	}
}

func TestResolveAutomaticMatchAllowsUniqueExactTitleWithoutYearOnlyWithRuntimeAndCompleteSearch(t *testing.T) {
	for _, tc := range []struct {
		name, search, details string
		duration              int64
		want                  AutomaticMatchState
		reason                string
		calls                 int
	}{
		{
			name:     "unique exact title and runtime",
			search:   `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":380,"title":"雨人","original_title":"Rain Man","release_date":"1988-12-16"}]}`,
			details:  `{"id":380,"title":"雨人","original_title":"Rain Man","release_date":"1988-12-16","runtime":133}`,
			duration: 133 * 60_000, want: AutoMatched, calls: 2,
		},
		{
			name:   "missing duration",
			search: `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":380,"title":"雨人","original_title":"Rain Man","release_date":"1988-12-16"}]}`,
			want:   AutoNeedsReview, reason: "year_required", calls: 1,
		},
		{
			name:     "runtime difference remains evidence",
			search:   `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":380,"title":"雨人","original_title":"Rain Man","release_date":"1988-12-16"}]}`,
			details:  `{"id":380,"title":"雨人","original_title":"Rain Man","release_date":"1988-12-16","runtime":133}`,
			duration: 90 * 60_000, want: AutoMatched, calls: 2,
		},
		{
			name:     "same title candidates",
			search:   `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":380,"title":"雨人","release_date":"1988-12-16"},{"id":381,"title":"雨人","release_date":"2000-01-01"}]}`,
			details:  `{"id":380,"title":"雨人","release_date":"1988-12-16","runtime":133}`,
			duration: 133 * 60_000, want: AutoNeedsReview, reason: "ambiguous_candidates", calls: 3,
		},
		{
			name:     "more than three pages",
			search:   `{"page":1,"total_pages":4,"total_results":80,"results":[{"id":380,"title":"雨人","release_date":"1988-12-16"}]}`,
			duration: 133 * 60_000, want: AutoNeedsReview, reason: "year_required", calls: 3,
		},
		{
			name:     "detail title changed",
			search:   `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":380,"title":"雨人","release_date":"1988-12-16"}]}`,
			details:  `{"id":380,"title":"雨人2","release_date":"1988-12-16","runtime":133}`,
			duration: 133 * 60_000, want: AutoNeedsReview, reason: "title_conflict", calls: 2,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("query") != "雨人" || request.URL.Query().Has("primary_release_year") {
						t.Fatalf("unexpected title lookup %s", request.URL)
					}
					if tc.name == "more than three pages" && request.URL.Query().Get("page") != "1" {
						page := request.URL.Query().Get("page")
						return response(200, `{"page":`+page+`,"total_pages":4,"total_results":80,"results":[]}`), nil
					}
					return response(200, tc.search), nil
				case "/3/movie/380":
					return response(200, tc.details), nil
				case "/3/movie/381":
					return response(200, `{"id":381,"title":"雨人","release_date":"2000-01-01","runtime":133}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "雨人", DurationMs: tc.duration, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != tc.calls {
				t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 380 ||
				!slices.Contains(result.Evidence, "exact_title_without_year") ||
				!slices.Contains(result.Evidence, "runtime_match") && !slices.Contains(result.Evidence, "runtime_difference")) {
				t.Fatalf("yearless evidence missing: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchAllowsNumberedSequelSubtitleWithoutYearOnlyWithRuntime(t *testing.T) {
	for _, tc := range []struct {
		name, query, candidate string
		duration               int64
		want                   AutomaticMatchState
		reason                 string
	}{
		{"numbered subtitle", "博物馆奇妙夜2", "博物馆奇妙夜2：史密森尼之战", 105 * 60_000, AutoMatched, ""},
		{"different sequel number", "博物馆奇妙夜2", "博物馆奇妙夜20：史密森尼之战", 105 * 60_000, AutoNeedsReview, "year_required"},
		{"unrelated making of", "银河护卫队3", "Marvel Studios Assembled: The Making of the Guardians of the Galaxy Vol. 3", 150 * 60_000, AutoNeedsReview, "year_required"},
		{"runtime difference remains evidence", "博物馆奇妙夜3", "博物馆奇妙夜3：墓葬秘密", 90 * 60_000, AutoMatched, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("query") != tc.query || request.URL.Query().Has("primary_release_year") {
						t.Fatalf("unexpected sequel lookup %s", request.URL)
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":18360,"title":"`+tc.candidate+`","release_date":"2009-05-20"}]}`), nil
				case "/3/movie/18360":
					return response(200, `{"id":18360,"title":"`+tc.candidate+`","release_date":"2009-05-20","runtime":105}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: tc.query, DurationMs: tc.duration, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason {
				t.Fatalf("state=%s reason=%s err=%v", result.State, result.Reason, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 18360 ||
				!slices.Contains(result.Evidence, "numbered_title_without_year") ||
				!slices.Contains(result.Evidence, "runtime_match") && !slices.Contains(result.Evidence, "runtime_difference")) {
				t.Fatalf("numbered sequel evidence missing: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchYearlessExactTitleSearchesBoundedLaterPages(t *testing.T) {
	for _, tc := range []struct {
		name       string
		totalPages int
		want       AutomaticMatchState
		reason     string
		calls      int
	}{
		{"candidate completed on page two", 2, AutoMatched, "", 3},
		{"more than three pages remains review", 4, AutoNeedsReview, "year_required", 3},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("query") != "雨人" || request.URL.Query().Has("primary_release_year") {
						t.Fatalf("unexpected title lookup %s", request.URL)
					}
					page := request.URL.Query().Get("page")
					if page == "1" {
						return response(200, `{"page":1,"total_pages":`+jsonNumber(int64(tc.totalPages))+`,"total_results":34,"results":[{"id":380,"title":"雨人","release_date":"1988-12-16"}]}`), nil
					}
					return response(200, `{"page":`+page+`,"total_pages":`+jsonNumber(int64(tc.totalPages))+`,"total_results":34,"results":[{"id":11975,"title":"造雨人","release_date":"1997-11-18"}]}`), nil
				case "/3/movie/380":
					return response(200, `{"id":380,"title":"雨人","release_date":"1988-12-16","runtime":133}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "雨人", DurationMs: 133 * 60_000, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != tc.calls {
				t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 380 || !slices.Contains(result.Evidence, "runtime_match")) {
				t.Fatalf("later-page candidate missing: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchDisambiguatesExactCandidatesByRuntime(t *testing.T) {
	for _, tc := range []struct {
		name, title string
		year        int
		runtimes    []int
		duration    int64
		want        AutomaticMatchState
		reason      string
		calls       int
	}{
		{"unique runtime without year", "隐藏的面孔", 0, []int{0, 115}, 6_921_387, AutoMatched, "", 3},
		{"same runtime remains ambiguous", "隐藏的面孔", 0, []int{115, 115}, 6_921_387, AutoNeedsReview, "ambiguous_candidates", 3},
		{"closest known runtime wins", "隐藏的面孔", 0, []int{90, 92}, 6_921_387, AutoMatched, "", 3},
		{"all runtimes unknown", "隐藏的面孔", 0, []int{0, 0}, 6_921_387, AutoNeedsReview, "runtime_unknown", 3},
		{"missing file duration", "隐藏的面孔", 0, []int{0, 115}, 0, AutoNeedsReview, "year_required", 1},
		{"same year candidates choose closest runtime", "泰迪熊", 2012, []int{92, 107}, 6_735_231, AutoMatched, "", 3},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				calls++
				switch request.URL.Path {
				case "/3/search/movie":
					year := ""
					if tc.year != 0 {
						year = strconv.Itoa(tc.year)
					}
					if request.URL.Query().Get("query") != tc.title || request.URL.Query().Get("primary_release_year") != year {
						t.Fatalf("unexpected candidate lookup %s", request.URL)
					}
					date := "2024-11-20"
					if tc.year != 0 {
						date = "2012-06-29"
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":42,"title":"`+tc.title+`","release_date":"`+date+`"},{"id":43,"title":"`+tc.title+`","release_date":"`+date+`"}]}`), nil
				case "/3/movie/42", "/3/movie/43":
					id := int64(42)
					date := "2024-11-20"
					if request.URL.Path == "/3/movie/43" {
						id = 43
						date = "2007-09-19"
					}
					if tc.year != 0 {
						date = "2012-06-29"
					}
					return response(200, `{"id":`+jsonNumber(id)+`,"title":"`+tc.title+`","release_date":"`+date+`","runtime":`+jsonNumber(int64(tc.runtimes[id-42]))+`}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: tc.title, Year: tc.year, DurationMs: tc.duration, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason || calls != tc.calls {
				t.Fatalf("state=%s reason=%s calls=%d err=%v", result.State, result.Reason, calls, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 43 || !slices.Contains(result.Evidence, "runtime_disambiguation") ||
				!slices.Contains(result.Evidence, "closest_runtime")) {
				t.Fatalf("wrong runtime-disambiguated candidate: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchAllowsOneYearDifferenceWithRuntime(t *testing.T) {
	for _, tc := range []struct {
		name, details string
		duration      int64
		want          AutomaticMatchState
		reason        string
	}{
		{"one year and close runtime", `{"id":41245,"title":"薄荷糖","original_title":"박하사탕","release_date":"2000-01-01","runtime":131}`, 7_814_773, AutoMatched, ""},
		{"one year but distant runtime", `{"id":41245,"title":"薄荷糖","original_title":"박하사탕","release_date":"2000-01-01","runtime":131}`, 90 * 60_000, AutoNeedsReview, "title_year_conflict"},
		{"two year difference", `{"id":41245,"title":"薄荷糖","original_title":"박하사탕","release_date":"2001-01-01","runtime":131}`, 7_814_773, AutoNeedsReview, "title_year_conflict"},
		{"missing duration", `{"id":41245,"title":"薄荷糖","original_title":"박하사탕","release_date":"2000-01-01","runtime":131}`, 0, AutoNeedsReview, "title_year_conflict"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("query") != "薄荷糖" {
						t.Fatalf("unexpected title lookup %s", request.URL)
					}
					if request.URL.Query().Get("primary_release_year") == "" {
						return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":41245,"title":"薄荷糖","original_title":"박하사탕","release_date":"2000-01-01"}]}`), nil
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":0,"results":[]}`), nil
				case "/3/movie/41245":
					return response(200, tc.details), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "薄荷糖", Year: 1999, DurationMs: tc.duration, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason {
				t.Fatalf("state=%s reason=%s err=%v", result.State, result.Reason, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 41245 || !slices.Contains(result.Evidence, "year_difference_runtime_verified")) {
				t.Fatalf("year difference evidence missing: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchUsesProviderAlternativeTitleForRomanizedInput(t *testing.T) {
	for _, tc := range []struct {
		name, alternatives string
		want               AutomaticMatchState
		reason             string
	}{
		{"romaji alternative matches", `,"alternative_titles":{"id":372058,"titles":[{"iso_3166_1":"JP","title":"Kimi no Na wa","type":"Romaji"}]}`, AutoMatched, ""},
		{"unrelated alternatives remain review", `,"alternative_titles":{"id":372058,"titles":[{"iso_3166_1":"JP","title":"君の名は。","type":"Standard"}]}`, AutoNeedsReview, "title_year_conflict"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("query") != "Kimi no Na wa" || request.URL.Query().Get("primary_release_year") != "2016" {
						t.Fatalf("unexpected title lookup %s", request.URL)
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":372058,"title":"你的名字。","original_title":"君の名は。","release_date":"2016-08-26"}]}`), nil
				case "/3/movie/372058":
					return response(200, `{"id":372058,"title":"你的名字。","original_title":"君の名は。","release_date":"2016-08-26","runtime":106`+tc.alternatives+`}`), nil
				default:
					t.Fatalf("unexpected outbound request %s", request.URL)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "Kimi no Na wa", Year: 2016, DurationMs: 6_395_639, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason {
				t.Fatalf("state=%s reason=%s err=%v", result.State, result.Reason, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 372058 || !slices.Contains(result.Evidence, "alternative_title_match")) {
				t.Fatalf("alternative title evidence missing: %+v", result)
			}
		})
	}
}

func TestResolveAutomaticMatchDoesNotUseAlternativeFallbackForMixedTitleAlias(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			return response(200, `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":329,"title":"侏罗纪公园","release_date":"1994-01-01"},{"id":999,"title":"无关电影","release_date":"1993-01-01"}]}`), nil
		case "/3/movie/329":
			return response(200, `{"id":329,"title":"侏罗纪公园","release_date":"1993-06-11","runtime":127}`), nil
		default:
			t.Fatalf("unexpected outbound request %s", request.URL)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie,
		Title: "侏罗纪公园( 国英双音轨 幕) Jurassic Park", Aliases: []string{"侏罗纪公园", "Jurassic Park"}, Year: 1993, AllowTitleMatch: true,
	}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 329 {
		t.Fatalf("mixed-title alias triggered alternative fallback: state=%s id=%d err=%v", result.State, result.Document.Entity.ID, err)
	}
}

func TestResolveAutomaticMatchDoesNotReplaceDirectTitleWithAlternativeFallback(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			return response(200, `{"page":1,"total_pages":1,"total_results":2,"results":[{"id":718930,"title":"Bullet Train","release_date":"2021-08-06"},{"id":999,"title":"Unrelated","release_date":"2022-01-01"}]}`), nil
		case "/3/movie/718930":
			return response(200, `{"id":718930,"title":"Bullet Train","release_date":"2022-08-05","runtime":126}`), nil
		default:
			t.Fatalf("unexpected outbound request %s", request.URL)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "Bullet Train", Year: 2022, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 718930 {
		t.Fatalf("direct title was replaced by alternative fallback: state=%s id=%d err=%v", result.State, result.Document.Entity.ID, err)
	}
}

func TestResolveAutomaticMatchRecoversSingleVisibleHanCharacterWithRuntime(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			query := request.URL.Query().Get("query")
			if query == "推n" {
				return response(200, `{"page":1,"total_pages":1,"total_results":0,"results":[]}`), nil
			}
			if query != "推" || request.URL.Query().Get("primary_release_year") != "2014" {
				t.Fatalf("unexpected recovery query %s", request.URL)
			}
			return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":256106,"title":"推拿","release_date":"2014-11-28"}]}`), nil
		case "/3/movie/256106":
			return response(200, `{"id":256106,"title":"推拿","release_date":"2014-11-28","runtime":114}`), nil
		default:
			t.Fatalf("unexpected outbound request %s", request.URL)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "推n", Year: 2014, DurationMs: 6_890_528, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 256106 ||
		!slices.Contains(result.Evidence, "incomplete_title_shape") || !slices.Contains(result.Evidence, "closest_runtime") {
		t.Fatalf("single-visible-character recovery failed: state=%s reason=%s result=%+v err=%v", result.State, result.Reason, result, err)
	}
}

func TestResolveAutomaticMatchRetriesAdultSearchOnlyAfterNoDirectCandidate(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			if request.URL.Query().Get("query") != "湿濡的女人" || request.URL.Query().Get("primary_release_year") != "2016" {
				t.Fatalf("unexpected title lookup %s", request.URL)
			}
			if request.URL.Query().Get("include_adult") == "true" {
				return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":408270,"title":"湿濡的女人","release_date":"2016-12-17"}]}`), nil
			}
			return response(200, `{"page":1,"total_pages":1,"total_results":3,"results":[{"id":569175,"title":"手指湿濡的女人","release_date":"1984-04-20"},{"id":589262,"title":"女人的小道：濡湿海峡","release_date":"1980-04-12"},{"id":140812,"title":"等待湿润的女人","release_date":"1987-12-19"}]}`), nil
		case "/3/movie/408270":
			return response(200, `{"id":408270,"title":"湿濡的女人","original_title":"風に濡れた女","release_date":"2016-12-17","runtime":78}`), nil
		default:
			t.Fatalf("unexpected outbound request %s", request.URL)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "湿濡的女人", Year: 2016, DurationMs: 4_684_346, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 408270 || !slices.Contains(result.Evidence, "adult_search_fallback") {
		t.Fatalf("adult exact-title fallback failed: state=%s reason=%s result=%+v err=%v", result.State, result.Reason, result, err)
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

func TestResolveAutomaticMatchDoesNotLeakFallbackEvidenceAcrossAliases(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/3/search/movie":
			query := request.URL.Query().Get("query")
			if query == "Noise" {
				if request.URL.Query().Get("primary_release_year") != "" {
					return response(200, `{"page":1,"total_pages":0,"total_results":0,"results":[]}`), nil
				}
				return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":99,"title":"Unrelated","release_date":"2020-01-01"}]}`), nil
			}
			if query != "Echo" || request.URL.Query().Get("primary_release_year") != "2020" {
				t.Fatalf("unexpected alias query %s", request.URL)
			}
			return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"Echo","release_date":"2020-01-01"}]}`), nil
		case "/3/movie/42":
			return response(200, `{"id":42,"title":"Echo","release_date":"2020-01-01"}`), nil
		default:
			t.Fatalf("unexpected request %s", request.URL.Path)
			return nil, nil
		}
	})
	result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "Noise", Aliases: []string{"Echo"}, Year: 2020, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
	if err != nil || result.State != AutoMatched || result.Document.Entity.ID != 42 || slices.Contains(result.Evidence, "unfiltered_title_search") {
		t.Fatalf("fallback evidence leaked across aliases: state=%s evidence=%v err=%v", result.State, result.Evidence, err)
	}
}

func TestResolveAutomaticMatchUsesEmbeddedTitleOnlyAfterPrimaryFailure(t *testing.T) {
	for _, tc := range []struct {
		name, details string
		want          AutomaticMatchState
		reason        string
	}{
		{"detail year verifies", `{"id":42,"title":"本杰明·巴顿奇事","release_date":"2008-12-25"}`, AutoMatched, ""},
		{"detail year rejects", `{"id":42,"title":"本杰明·巴顿奇事","release_date":"2009-12-25"}`, AutoNeedsReview, "title_year_conflict"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := fixtureClient(func(request *http.Request) (*http.Response, error) {
				switch request.URL.Path {
				case "/3/search/movie":
					if request.URL.Query().Get("query") == "损坏文件名" {
						return response(200, `{"page":1,"total_pages":0,"total_results":0,"results":[]}`), nil
					}
					if request.URL.Query().Get("query") != "本杰明·巴顿奇事" {
						t.Fatalf("unexpected embedded title query %s", request.URL)
					}
					return response(200, `{"page":1,"total_pages":1,"total_results":1,"results":[{"id":42,"title":"本杰明·巴顿奇事","release_date":"2008-12-25"}]}`), nil
				case "/3/movie/42":
					return response(200, tc.details), nil
				default:
					t.Fatalf("unexpected request %s", request.URL.Path)
					return nil, nil
				}
			})
			result, err := client.ResolveAutomaticMatch(t.Context(), AutomaticMatchInput{Kind: Movie, Title: "损坏文件名", FallbackTitle: "本杰明·巴顿奇事", Year: 2008, FallbackYear: 2008, AllowTitleMatch: true}, "zh-CN", func(context.Context) error { return nil })
			if err != nil || result.State != tc.want || result.Reason != tc.reason {
				t.Fatalf("state=%s reason=%s err=%v", result.State, result.Reason, err)
			}
			if tc.want == AutoMatched && (result.Document.Entity.ID != 42 || !slices.Contains(result.Evidence, "embedded_title_match")) {
				t.Fatalf("embedded title evidence missing: %+v", result)
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
