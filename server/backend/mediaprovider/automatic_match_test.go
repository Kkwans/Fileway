package mediaprovider

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strconv"
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
					if (query != tc.query && query != tc.otherQuery) || request.URL.Query().Get("primary_release_year") != "2001" {
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
