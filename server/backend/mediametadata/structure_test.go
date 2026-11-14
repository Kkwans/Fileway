package mediametadata

import (
	"strings"
	"testing"
)

func TestResolveEpisodeUsesExplicitNumbersAndReportsConflicts(t *testing.T) {
	parse := func(value string) *Document {
		t.Helper()
		doc, err := ParseNFO(strings.NewReader(value), Limits{})
		if err != nil {
			t.Fatal(err)
		}
		return doc
	}
	season := 1
	tests := []struct {
		name      string
		filename  string
		nfo       string
		context   *int
		state     string
		positions []SeasonEpisode
	}{
		{"filename", "Show.S01E01.mkv", "", &season, "resolved", []SeasonEpisode{{1, 1}}},
		{"combined", "Show.S01E01E02.mkv", `<episodedetails><season>1</season><episode>1</episode></episodedetails><episodedetails><season>1</season><episode>2</episode></episodedetails>`, &season, "resolved", []SeasonEpisode{{1, 1}, {1, 2}}},
		{"special zero", "Show.S00E01.mkv", "", nil, "resolved", []SeasonEpisode{{0, 1}}},
		{"nfo mismatch", "Show.S01E01.mkv", `<episodedetails><season>1</season><episode>2</episode></episodedetails>`, &season, "needs_review", nil},
		{"season mismatch", "Show.S02E01.mkv", "", &season, "needs_review", nil},
		{"absolute remains independent", "Show 第12集.mkv", "", &season, "needs_review", nil},
		{"nfo supplies season", "Show.mkv", `<episodedetails><episode>4</episode></episodedetails>`, &season, "resolved", []SeasonEpisode{{1, 4}}},
		{"filename supplies season", "Show.S01E04.mkv", `<episodedetails><episode>4</episode></episodedetails>`, nil, "resolved", []SeasonEpisode{{1, 4}}},
		{"nfo maps absolute", "Show 第12集.mkv", `<episodedetails><season>1</season><episode>4</episode></episodedetails>`, nil, "resolved", []SeasonEpisode{{1, 4}}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var doc *Document
			if tc.nfo != "" {
				doc = parse(tc.nfo)
			}
			got := ResolveEpisode(ParseName(tc.filename), doc, tc.context)
			if got.State != tc.state || !samePositions(got.Positions, tc.positions) {
				t.Fatalf("got %+v, want %s %+v", got, tc.state, tc.positions)
			}
		})
	}
}

func TestSeasonNumberDistinguishesZeroMissingAndConflicts(t *testing.T) {
	for _, tc := range []struct {
		fields map[string][]string
		want   *int
		issue  string
	}{
		{map[string][]string{"season": {"0"}}, intPointer(0), ""},
		{map[string][]string{}, nil, ""},
		{map[string][]string{"season": {"1"}, "seasonnumber": {"2"}}, nil, "number_conflict"},
		{map[string][]string{"season": {"-1"}}, nil, "invalid_number"},
	} {
		got, issue := SeasonNumber(Item{Kind: Season, Fields: tc.fields})
		if issue != tc.issue || (got == nil) != (tc.want == nil) || got != nil && *got != *tc.want {
			t.Fatalf("got %v %q, want %v %q", got, issue, tc.want, tc.issue)
		}
	}
}

func intPointer(value int) *int { return &value }
