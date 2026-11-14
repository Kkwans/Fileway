package mediaprovider

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) { return fn(request) }

func response(status int, body string) *http.Response {
	return &http.Response{StatusCode: status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
}

func fixtureClient(transport roundTripFunc) *TMDBClient {
	return NewTMDBClient(&http.Client{Transport: transport}, func(context.Context) (string, error) { return "fixture-read-token", nil })
}

func requireCode(t *testing.T, err error, code ErrorCode) *Error {
	t.Helper()
	var failure *Error
	if !errors.As(err, &failure) || failure.Code != code {
		t.Fatalf("wanted %s, got %v", code, err)
	}
	return failure
}

func TestTMDBSearchKeepsEntityTypesAndDoesNotChooseMatch(t *testing.T) {
	requests := 0
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		requests++
		if request.URL.Host != "api.themoviedb.org" || request.URL.Scheme != "https" || request.Method != http.MethodGet || request.Header.Get("Authorization") != "Bearer fixture-read-token" || strings.Contains(request.URL.String(), "fixture-read-token") {
			t.Fatal("wrong transport identity")
		}
		query := request.URL.Query()
		if query.Get("query") != "同名作品 & 第二版" || query.Get("language") != "zh-CN" || query.Get("page") != "2" || query.Get("include_adult") != "false" || query.Has("api_key") {
			t.Fatal("wrong search parameters", query)
		}
		if request.URL.Path == "/3/search/movie" {
			if query.Get("primary_release_year") != "2020" || query.Get("region") != "CN" || query.Has("first_air_date_year") {
				t.Fatal(query)
			}
		} else if request.URL.Path != "/3/search/tv" || query.Get("first_air_date_year") != "2020" || query.Has("region") || query.Has("primary_release_year") {
			t.Fatal("movie and series search mixed", request.URL.Path, query)
		}
		return response(200, `{"page":2,"total_pages":2,"total_results":21,"results":[{"id":7,"title":"候选","poster_path":null}]}`), nil
	})
	query := SearchQuery{Kind: Movie, Title: "同名作品 & 第二版", Year: 2020, Language: "zh-CN", Region: "CN", Page: 2}
	movies, err := client.Search(t.Context(), query)
	if err != nil || len(movies.Items) != 1 || movies.TotalResults != 21 {
		t.Fatal(movies, err)
	}
	query.Kind = Series
	series, err := client.Search(t.Context(), query)
	if err != nil || len(series.Items) != 1 || requests != 2 {
		t.Fatal(series, err)
	}
	if movies.Items[0].Entity == series.Items[0].Entity || movies.Items[0].Entity.ID != series.Items[0].Entity.ID {
		t.Fatal("provider numeric ID was used as a global work identity")
	}
	if string(movies.Items[0].Fields["poster_path"]) != "null" || movies.Items[0].FetchedAt.IsZero() {
		t.Fatal("field presence or provenance was lost")
	}
}

func TestTMDBDetailsPreserveNullsAndCheckRequestedIdentity(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		if request.URL.Path != "/3/tv/7" || request.URL.Query().Get("append_to_response") != "aggregate_credits,external_ids" {
			t.Fatal(request.URL)
		}
		return response(200, `{"id":7,"name":"剧集","overview":"","poster_path":null,"number_of_episodes":0}`), nil
	})
	document, err := client.Fetch(t.Context(), EntityRef{"tmdb", Series, 7}, FetchOptions{Language: "zh-CN", Parts: []Part{ExternalIDs, Credits, Credits}})
	if err != nil || string(document.Fields["overview"]) != `""` || string(document.Fields["poster_path"]) != "null" || document.Fields["backdrop_path"] != nil {
		t.Fatal(document, err)
	}
	mismatch := fixtureClient(func(*http.Request) (*http.Response, error) { return response(200, `{"id":8}`), nil })
	_, err = mismatch.Fetch(t.Context(), EntityRef{"tmdb", Movie, 7}, FetchOptions{})
	_ = requireCode(t, err, InvalidResponse)
}

func TestTMDBSpecialSeasonCarriesRequestedParent(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		if request.URL.Path != "/3/tv/7/season/0" {
			t.Fatal(request.URL.Path)
		}
		return response(200, `{"id":9,"season_number":0,"episodes":[]}`), nil
	})
	parent := EntityRef{"tmdb", Series, 7}
	document, err := client.FetchSeason(t.Context(), parent, 0, FetchOptions{})
	if err != nil || document.Entity.Kind != Season || document.Parent == nil || *document.Parent != parent || document.Number == nil || *document.Number != 0 {
		t.Fatal(document, err)
	}
}

func TestTMDBFailuresAreNotEmptySearchAndDoNotLeakCredentials(t *testing.T) {
	for _, test := range []struct {
		status int
		code   ErrorCode
		retry  bool
	}{{401, Unauthorized, false}, {403, Unauthorized, false}, {404, NotFound, false}, {429, RateLimited, true}, {503, Unavailable, true}, {302, RedirectRefused, false}} {
		t.Run(fmt.Sprint(test.status), func(t *testing.T) {
			requests := 0
			client := fixtureClient(func(*http.Request) (*http.Response, error) {
				requests++
				r := response(test.status, `{"status_message":"fixture-read-token private upstream diagnostic"}`)
				r.Header.Set("Retry-After", "30")
				r.Header.Set("Location", "https://another.invalid/collect")
				return r, nil
			})
			_, err := client.Search(t.Context(), SearchQuery{Kind: Movie, Title: "fixture", Page: 1})
			failure := requireCode(t, err, test.code)
			if requests != 1 || failure.Retryable != test.retry || failure.Status != test.status || time.Until(failure.RetryAfter) < 25*time.Second || strings.Contains(err.Error(), "fixture-read-token") {
				t.Fatal("incorrect retry, redirect, or error disclosure", failure)
			}
		})
	}
	empty := fixtureClient(func(*http.Request) (*http.Response, error) {
		return response(200, `{"page":1,"total_pages":0,"total_results":0,"results":[]}`), nil
	})
	page, err := empty.Search(t.Context(), SearchQuery{Kind: Movie, Title: "fixture", Page: 1})
	if err != nil || page.Items == nil || len(page.Items) != 0 {
		t.Fatal("successful no-match response changed into a failure", page, err)
	}
}

func TestTMDBBoundsAndCancellationBeforeDispatch(t *testing.T) {
	for _, test := range []struct {
		name, body string
		code       ErrorCode
	}{{"oversized", strings.Repeat(" ", maxResponseBytes+1), ResponseTooLarge}, {"malformed", "<html>unavailable</html>", InvalidResponse}, {"no_entity", `{}`, InvalidResponse}} {
		t.Run(test.name, func(t *testing.T) {
			client := fixtureClient(func(*http.Request) (*http.Response, error) { return response(200, test.body), nil })
			_, err := client.Fetch(t.Context(), EntityRef{"tmdb", Movie, 7}, FetchOptions{})
			_ = requireCode(t, err, test.code)
		})
	}
	requests, resolutions := 0, 0
	client := NewTMDBClient(&http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		requests++
		return response(200, `{"id":7}`), nil
	})}, func(context.Context) (string, error) { resolutions++; return "fixture", nil })
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	_, err := client.Fetch(ctx, EntityRef{"tmdb", Movie, 7}, FetchOptions{})
	if !errors.Is(err, context.Canceled) || requests != 0 || resolutions != 0 {
		t.Fatal("canceled request reached the provider", err)
	}
	client = NewTMDBClient(client.http, nil)
	_, err = client.Fetch(t.Context(), EntityRef{"tmdb", Movie, 7}, FetchOptions{})
	_ = requireCode(t, err, NeedsConfig)
	if requests != 0 {
		t.Fatal("unconfigured provider made a request")
	}
}

func TestTMDBFindKeepsTypedResultsAndExternalIdentifier(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		if request.URL.Path != "/3/find/tt000123" || request.URL.Query().Get("external_source") != "imdb_id" {
			t.Fatal(request.URL)
		}
		return response(200, `{"movie_results":[{"id":7}],"tv_results":[{"id":7}],"person_results":[],"tv_season_results":[],"tv_episode_results":[]}`), nil
	})
	items, err := client.Find(t.Context(), ExternalRef{"imdb", "tt000123"}, "zh-CN")
	if err != nil || len(items) != 2 || items[0].Entity.Kind != Movie || items[1].Entity.Kind != Series {
		t.Fatal(items, err)
	}
	_, err = client.Find(t.Context(), ExternalRef{"imdb", "../configuration"}, "")
	_ = requireCode(t, err, InvalidRequest)
}

func TestTMDBArtworkUsesProviderConfigurationNotArbitraryURLs(t *testing.T) {
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		if request.URL.Path != "/3/configuration" {
			t.Fatal(request.URL.Path)
		}
		return response(200, `{"images":{"secure_base_url":"https://image.tmdb.org/t/p/","poster_sizes":["w500","original"],"backdrop_sizes":["w1280"],"profile_sizes":["h632"],"still_sizes":["w300"]}}`), nil
	})
	configuration, err := client.Configuration(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	sizes := configuration.Sizes(Poster)
	sizes[0] = "../override"
	imageURL, err := configuration.ImageURL(Poster, "w500", "/fixture_A-1.jpg")
	if err != nil || imageURL != "https://image.tmdb.org/t/p/w500/fixture_A-1.jpg" {
		t.Fatal(imageURL, err)
	}
	for _, path := range []string{"https://another.invalid/image.jpg", "//another.invalid/a.jpg", "/../a.jpg", "/a%2f..%2fb.jpg", "/a.svg", "/a.jpg?credential=fixture"} {
		_, err := configuration.ImageURL(Poster, "w500", path)
		_ = requireCode(t, err, InvalidRequest)
	}
	_, err = configuration.ImageURL(Poster, "w300", "/a.jpg")
	_ = requireCode(t, err, InvalidRequest)
}
