package mediaprovider

import (
	"encoding/json"
	"slices"
	"testing"
)

func fixtureCandidate(t *testing.T, kind Kind, id int64, fields string) Document {
	t.Helper()
	document := Document{Entity: EntityRef{Provider: "tmdb", Kind: kind, ID: id}}
	if err := json.Unmarshal([]byte(fields), &document.Fields); err != nil {
		t.Fatal(err)
	}
	return document
}

func TestRankCandidatesDistinguishesRemakesAndProviderNamespaces(t *testing.T) {
	original := fixtureCandidate(t, Movie, 1, `{"title":"回声","original_title":"Echo","release_date":"1998-09-01"}`)
	remake := fixtureCandidate(t, Movie, 2, `{"title":"回声","original_title":"Echo","release_date":"2020-09-01"}`)
	series := fixtureCandidate(t, Series, 2, `{"name":"回声","original_name":"Echo","first_air_date":"2020-09-01"}`)
	list := RankCandidates(MatchHint{Kind: Movie, Title: "ＥcHo!", Year: 2020}, []Document{original, series, remake, remake})
	if len(list) != 2 || list[0].Entity != remake.Entity || list[1].Entity != original.Entity {
		t.Fatalf("candidates: %+v", list)
	}
	if !slices.Equal(list[0].Evidence, []string{"title_match", "year_match"}) ||
		!slices.Equal(list[1].Evidence, []string{"title_match", "year_difference"}) {
		t.Fatalf("evidence: %+v", list)
	}
}

func TestRankCandidatesKeepsConflictAndMissingYearExplicit(t *testing.T) {
	first := fixtureCandidate(t, Series, 9, `{"name":"星河","first_air_date":null}`)
	second := fixtureCandidate(t, Series, 10, `{"name":"星河","first_air_date":"2024-07-01"}`)
	hint := MatchHint{Kind: Series, Title: "星河", Year: 2024, ExplicitIDs: []EntityRef{first.Entity, second.Entity}}
	list := RankCandidates(hint, []Document{first, second})
	if len(list) != 2 || list[0].Entity != second.Entity || !slices.Contains(list[0].Evidence, "explicit_id_conflict") ||
		!slices.Contains(list[1].Evidence, "explicit_id_conflict") || list[1].Year != 0 ||
		slices.Contains(list[1].Evidence, "year_difference") {
		t.Fatalf("conflict and evidence: %+v", list)
	}
	hint.ExplicitIDs = []EntityRef{first.Entity, first.Entity}
	list = RankCandidates(hint, []Document{second, first})
	if len(list) != 2 || list[0].Entity != first.Entity || !slices.Contains(list[0].Evidence, "explicit_id_match") ||
		!slices.Contains(list[1].Evidence, "explicit_id_difference") {
		t.Fatalf("explicit evidence: %+v", list)
	}
}
