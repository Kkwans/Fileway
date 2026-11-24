package mediaprovider

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

type identificationCorpusDoc struct {
	id       int64
	kind     Kind
	title    string
	original string
	year     int
}

type identificationCorpusCase struct {
	category string
	input    AutomaticMatchInput
	want     AutomaticMatchState
	reason   string
	entityID int64
}

func TestIdentificationCorpus300ConservativeDecisions(t *testing.T) {
	cases, documents := buildIdentificationCorpus()
	if len(cases) != 300 {
		t.Fatalf("identification corpus size = %d", len(cases))
	}
	client := fixtureClient(func(request *http.Request) (*http.Response, error) {
		if request.URL.Query().Get("language") != "zh-CN" {
			t.Fatalf("corpus request lost language: %s", request.URL)
		}
		if strings.HasPrefix(request.URL.Path, "/3/search/") {
			kind := Movie
			if request.URL.Path == "/3/search/tv" {
				kind = Series
			} else if request.URL.Path != "/3/search/movie" {
				t.Fatalf("unexpected search route: %s", request.URL.Path)
			}
			query := request.URL.Query().Get("query")
			items := make([]map[string]any, 0)
			for _, document := range documents {
				if document.kind != kind || matchTitle(query) != matchTitle(document.title) && matchTitle(query) != matchTitle(document.original) {
					continue
				}
				items = append(items, document.searchFields())
			}
			return response(200, mustCorpusJSON(map[string]any{"page": 1, "total_pages": 1, "total_results": len(items), "results": items})), nil
		}
		id, err := strconv.ParseInt(strings.TrimPrefix(request.URL.Path, "/3/movie/"), 10, 64)
		if err != nil {
			id, err = strconv.ParseInt(strings.TrimPrefix(request.URL.Path, "/3/tv/"), 10, 64)
		}
		if err != nil {
			t.Fatalf("unexpected detail route: %s", request.URL.Path)
		}
		document, ok := documents[id]
		if !ok {
			t.Fatalf("unknown corpus entity: %d", id)
		}
		return response(200, mustCorpusJSON(document.detailFields())), nil
	})
	counts := map[AutomaticMatchState]int{}
	wrongAutomatic := 0
	for _, test := range cases {
		result, err := client.ResolveAutomaticMatch(t.Context(), test.input, "zh-CN", func(context.Context) error { return nil })
		if err != nil {
			t.Fatalf("%s: %v", test.category, err)
		}
		counts[result.State]++
		if result.State != test.want || result.Reason != test.reason {
			t.Fatalf("%s: state=%s reason=%s want=%s/%s", test.category, result.State, result.Reason, test.want, test.reason)
		}
		if result.State == AutoMatched {
			if result.Document.Entity.ID != test.entityID {
				wrongAutomatic++
				t.Fatalf("%s: automatic entity=%d want=%d", test.category, result.Document.Entity.ID, test.entityID)
			}
			if !slices.Contains(result.Evidence, "year_match") || !slices.Contains(result.Evidence, "unique_candidate") ||
				!slices.Contains(result.Evidence, "title_match") && !slices.Contains(result.Evidence, "alias_title_match") {
				t.Fatalf("%s: incomplete automatic evidence %v", test.category, result.Evidence)
			}
		}
	}
	if wrongAutomatic != 0 {
		t.Fatalf("wrong automatic matches = %d", wrongAutomatic)
	}
	if counts[AutoMatched] != 220 || counts[AutoNeedsReview] != 60 || counts[AutoNoMatch] != 20 {
		t.Fatalf("unexpected corpus decisions: %v", counts)
	}
	t.Logf("synthetic identification corpus: cases=300 automatic=220 review=60 no_match=20 wrong_automatic=0; not a live-provider accuracy benchmark")
}

func buildIdentificationCorpus() ([]identificationCorpusCase, map[int64]identificationCorpusDoc) {
	cases := make([]identificationCorpusCase, 0, 300)
	documents := map[int64]identificationCorpusDoc{}
	add := func(category, filename string, kind Kind, want AutomaticMatchState, reason string, entityID int64) {
		parsed := mediametadata.ParseNameInDirectory(filename, "")
		input := AutomaticMatchInput{Kind: kind, Title: parsed.Title, Aliases: mediametadata.TitleAliases(parsed.Title), AllowTitleMatch: true}
		if parsed.Year != nil {
			input.Year = *parsed.Year
		}
		cases = append(cases, identificationCorpusCase{category: category, input: input, want: want, reason: reason, entityID: entityID})
	}
	for i := range 100 {
		id, year := int64(1000+i), 1980+i%40
		title, original := fmt.Sprintf("样本电影%03d", i), fmt.Sprintf("Sample Movie %03d", i)
		documents[id] = identificationCorpusDoc{id: id, kind: Movie, title: title, original: original, year: year}
		add("clean_movie", fmt.Sprintf("%s (%d).mkv", title, year), Movie, AutoMatched, "", id)
	}
	for i := range 40 {
		id, year := int64(2000+i), 1980+i%40
		title := fmt.Sprintf("噪声电影%03d", i)
		documents[id] = identificationCorpusDoc{id: id, kind: Movie, title: title, original: title, year: year}
		add("noisy_movie", fmt.Sprintf("%s %d BD1080p 国英双语 x265-MNHD-FRDS.mkv", title, year), Movie, AutoMatched, "", id)
	}
	for i := range 30 {
		id, year := int64(3000+i), 1980+i%40
		title, original := fmt.Sprintf("混合电影%03d", i), fmt.Sprintf("Mixed Movie %03d", i)
		documents[id] = identificationCorpusDoc{id: id, kind: Movie, title: title, original: original, year: year}
		add("mixed_alias", fmt.Sprintf("%s %s (%d) 1080p.mkv", title, original, year), Movie, AutoMatched, "", id)
	}
	for i := range 30 {
		first, second, year := int64(4000+i*2), int64(4001+i*2), 1980+i%30
		title := fmt.Sprintf("重拍电影%03d", i)
		documents[first] = identificationCorpusDoc{id: first, kind: Movie, title: title, original: title, year: year}
		documents[second] = identificationCorpusDoc{id: second, kind: Movie, title: title, original: title, year: year + 10}
		add("remake_year", fmt.Sprintf("%s (%d).mkv", title, year), Movie, AutoMatched, "", first)
	}
	for i := range 20 {
		first, second, year := int64(5000+i*2), int64(5001+i*2), 1980+i%30
		title := fmt.Sprintf("同年重复%03d", i)
		documents[first] = identificationCorpusDoc{id: first, kind: Movie, title: title, original: title, year: year}
		documents[second] = identificationCorpusDoc{id: second, kind: Movie, title: title, original: title, year: year}
		add("duplicate_same_year", fmt.Sprintf("%s (%d).mkv", title, year), Movie, AutoNeedsReview, "ambiguous_candidates", 0)
	}
	for i := range 20 {
		first, second := int64(6000+i*2), int64(6001+i*2)
		cases = append(cases, identificationCorpusCase{category: "identifier_conflict", input: AutomaticMatchInput{Kind: Movie,
			Identifiers: []WorkIdentifier{{Kind: Movie, ExternalRef: ExternalRef{Provider: "tmdb", ID: strconv.FormatInt(first, 10)}},
				{Kind: Movie, ExternalRef: ExternalRef{Provider: "tmdb", ID: strconv.FormatInt(second, 10)}}}},
			want: AutoNeedsReview, reason: "identifier_conflict"})
	}
	for range 20 {
		cases = append(cases, identificationCorpusCase{category: "local_ambiguity", input: AutomaticMatchInput{Kind: Series,
			Ambiguities: []string{"numbering_scheme_conflict"}}, want: AutoNeedsReview, reason: "ambiguous_evidence"})
	}
	for i := range 20 {
		add("no_match", fmt.Sprintf("不存在作品%03d (2000).mkv", i), Movie, AutoNoMatch, "title_not_found", 0)
	}
	for i := range 20 {
		id, year := int64(8000+i), 1990+i%30
		title := fmt.Sprintf("样本剧集%03d", i)
		documents[id] = identificationCorpusDoc{id: id, kind: Series, title: title, original: title, year: year}
		add("series", fmt.Sprintf("%s (%d).mkv", title, year), Series, AutoMatched, "", id)
	}
	return cases, documents
}

func (d identificationCorpusDoc) detailFields() map[string]any {
	fields := map[string]any{"id": d.id, "overview": "fixture"}
	if d.kind == Series {
		fields["name"], fields["original_name"], fields["first_air_date"] = d.title, d.original, fmt.Sprintf("%d-01-01", d.year)
	} else {
		fields["title"], fields["original_title"], fields["release_date"] = d.title, d.original, fmt.Sprintf("%d-01-01", d.year)
	}
	return fields
}

func (d identificationCorpusDoc) searchFields() map[string]any {
	fields := map[string]any{"id": d.id}
	if d.kind == Series {
		fields["name"], fields["original_name"], fields["first_air_date"] = d.title, d.original, fmt.Sprintf("%d-01-01", d.year)
	} else {
		fields["title"], fields["original_title"], fields["release_date"] = d.title, d.original, fmt.Sprintf("%d-01-01", d.year)
	}
	return fields
}

func mustCorpusJSON(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return string(encoded)
}
