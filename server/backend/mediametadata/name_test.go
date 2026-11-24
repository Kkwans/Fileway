package mediametadata

import (
	"encoding/json"
	"os"
	"reflect"
	"slices"
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

func TestParseNameInDirectoryRetainsIndependentYearEvidence(t *testing.T) {
	parsed := ParseNameInDirectory("film.mkv", "Echo (2020) 1080p")
	if parsed.Directory == nil || parsed.Directory.Title != "Echo" || parsed.Directory.Year == nil || *parsed.Directory.Year != 2020 {
		t.Fatalf("directory evidence missing: %+v", parsed)
	}
	if parsed.Year != nil || parsed.Title != "film" {
		t.Fatalf("directory evidence overwrote filename: %+v", parsed)
	}
	conflict := ParseNameInDirectory("film (1998).mkv", "Echo (2020) 1080p")
	if !slices.Contains(conflict.Ambiguities, "directory_year_conflict") {
		t.Fatalf("conflicting years were not retained: %+v", conflict)
	}
	middle := ParseNameInDirectory("film.mkv", "Captain.Marvel.2019.IMAX.2160p.HDR")
	if middle.Directory == nil || middle.Directory.Year == nil || *middle.Directory.Year != 2019 {
		t.Fatalf("single directory year token was lost: %+v", middle)
	}
	leading := ParseNameInDirectory("film.mkv", "2001 A Space Odyssey")
	if leading.Directory != nil && leading.Directory.Year != nil {
		t.Fatalf("title number became a year: %+v", leading)
	}
	multiple := ParseNameInDirectory("film.mkv", "Pirates.2003-2017.Bluray")
	if multiple.Directory != nil && multiple.Directory.Year != nil {
		t.Fatalf("year range became one year: %+v", multiple)
	}
}

func TestParseNameCleansReleaseAndSubtitleNoise(t *testing.T) {
	for _, tc := range []struct {
		filename, title string
		year            int
	}{
		{"The Wolf of Wall Street 2013 MNHD-FRDS.mkv", "The Wolf of Wall Street", 2013},
		{"华尔街之狼 2013 BD1080p 国英双语 中英双字.mkv", "华尔街之狼", 2013},
		{"Spider-Man Far from Home 2019 IMAX UHD 7 1 -RARBG.mkv", "Spider-Man Far from Home", 2019},
		{"[www dbmp4 com]后天 2004 BD1080p 国英双语 中英双字.mkv", "后天", 2004},
		{"[www.domp4.com]华尔街之狼 2013 BD1080p 中英双字.mkv", "华尔街之狼", 2013},
		{"阿黛尔的生活 2013 法语中字.mkv", "阿黛尔的生活", 2013},
		{"Captain Marvel 2019 DSNP.mkv", "Captain Marvel", 2019},
		{"Barbie 2023 iTunes.mkv", "Barbie", 2023},
		{"Blade Runner 2049 2017 4Audio -SSDSSE.mkv", "Blade Runner 2049", 2017},
		{"The Martian 2015 TC TrueHD7 1.mkv", "The Martian", 2015},
		{"Love Actually 2003 DTS-HD MA5 1.mkv", "Love Actually", 2003},
		{"Venom 2018 English.mkv", "Venom", 2018},
	} {
		parsed := ParseName(tc.filename)
		if parsed.Title != tc.title || parsed.Year == nil || *parsed.Year != tc.year || len(parsed.Ambiguities) != 0 {
			t.Fatalf("%s: title=%q year=%v ambiguities=%v", tc.filename, parsed.Title, parsed.Year, parsed.Ambiguities)
		}
	}
}

func TestTitleAliasesSeparateChineseAndOriginalTitles(t *testing.T) {
	aliases := TitleAliases("The Notebook 恋恋笔记本")
	if !reflect.DeepEqual(aliases, []string{"The Notebook", "恋恋笔记本"}) {
		t.Fatalf("unexpected aliases: %v", aliases)
	}
	cleaned := TitleAliases("美国队长2：冬日战士 Captain America 2 2014 AAC6 2Audios -DiaosMan")
	if !reflect.DeepEqual(cleaned, []string{"美国队长2：冬日战士", "Captain America 2"}) {
		t.Fatalf("unclean aliases: %v", cleaned)
	}
	mixed := TitleAliases("地心引力(蓝光国英双音轨特效 幕) Gravity 2013 BD")
	if !slices.Contains(mixed, "Gravity") {
		t.Fatalf("mixed alias retained source tags: %v", mixed)
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
