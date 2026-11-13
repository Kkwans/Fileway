package mediametadata

import (
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"testing"
)

func TestAnnotatedNameCorpus(t *testing.T) {
	data, err := os.ReadFile("testdata/names.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Description string `json:"description"`
		Cases       []struct {
			ID        string         `json:"id"`
			Category  string         `json:"category"`
			Rationale string         `json:"rationale"`
			Filename  string         `json:"filename"`
			Expected  map[string]any `json:"expected"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	if len(corpus.Cases) < 300 {
		t.Fatalf("only %d labeled cases", len(corpus.Cases))
	}
	ids, filenames, categories := map[string]bool{}, map[string]bool{}, map[string]int{}
	ambiguous := 0
	for _, fixture := range corpus.Cases {
		if fixture.ID == "" || fixture.Category == "" || fixture.Rationale == "" || ids[fixture.ID] || filenames[fixture.Filename] {
			t.Fatalf("missing/repeated fixture annotation: %#v", fixture)
		}
		ids[fixture.ID], filenames[fixture.Filename] = true, true
		categories[fixture.Category]++
		if _, ok := fixture.Expected["ambiguities"]; ok {
			ambiguous++
		}
		t.Run(fixture.ID, func(t *testing.T) {
			parsed := ParseName(fixture.Filename)
			validateNameEvidence(t, fixture.Filename, parsed)
			encoded, err := json.Marshal(parsed)
			if err != nil {
				t.Fatal(err)
			}
			var actual map[string]any
			if err := json.Unmarshal(encoded, &actual); err != nil {
				t.Fatal(err)
			}
			delete(actual, "originalName")
			delete(actual, "evidence")
			if !reflect.DeepEqual(actual, fixture.Expected) {
				want, _ := json.Marshal(fixture.Expected)
				got, _ := json.Marshal(actual)
				t.Errorf("%s\nfilename: %s\ngot:  %s\nwant: %s", fixture.Rationale, fixture.Filename, got, want)
			}
		})
	}
	if len(categories) < 16 || ambiguous < 60 {
		t.Fatalf("insufficient category/ambiguity distribution: %v, ambiguous=%d", categories, ambiguous)
	}
	t.Logf("synthetic labeled rule coverage: cases=%d, categories=%v, ambiguous=%d; not a recognition-accuracy benchmark", len(corpus.Cases), categories, ambiguous)
}

func validateNameEvidence(t *testing.T, input string, parsed ParsedName) {
	t.Helper()
	if parsed.OriginalName != input {
		t.Fatal("original filename was changed")
	}
	for _, evidence := range parsed.Evidence {
		if evidence.Start < 0 || evidence.End <= evidence.Start || evidence.End > len(input) || input[evidence.Start:evidence.End] != evidence.Raw {
			t.Fatalf("invalid evidence span: %#v", evidence)
		}
	}
	if len(parsed.SeasonEpisodes) > MaxEpisodeHints || len(parsed.AbsoluteEpisodes) > MaxEpisodeHints {
		t.Fatal("episode expansion escaped its bound")
	}
}

func TestNameParserResourceBoundsAndPurity(t *testing.T) {
	for _, input := range []string{strings.Repeat("星", MaxNameBytes), "bad\x00name.mkv", "bad\xffname.mkv", "folder/movie.mkv", `C:\fixture\movie.mkv`} {
		parsed := ParseName(input)
		validateNameEvidence(t, input, parsed)
		if len(parsed.Ambiguities) != 1 || len(parsed.Evidence) != 0 || parsed.Title != "" {
			t.Fatalf("invalid input was parsed: %#v", parsed)
		}
	}
	input := "Long Series S01E0000-E0127 S02E0000-E0127 S03E0000-E0127.mkv"
	parsed := ParseName(input)
	validateNameEvidence(t, input, parsed)
	if len(parsed.SeasonEpisodes) != MaxEpisodeHints {
		t.Fatalf("unexpected episode count: %d", len(parsed.SeasonEpisodes))
	}
	if !reflect.DeepEqual(parsed, ParseName(input)) {
		t.Fatal("parse depends on mutable state")
	}
}

func FuzzParseNameEvidence(f *testing.F) {
	for _, seed := range []string{"三体 S00E00.mkv", "[Group] Anime - 01v2 [1080p].mkv", "2001 A Space Odyssey (1968).mkv", "Part I S01E01-E9999.mkv", "剧 第十二季第一百零一集.mkv"} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, input string) { validateNameEvidence(t, input, ParseName(input)) })
}
