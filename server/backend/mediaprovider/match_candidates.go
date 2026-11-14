package mediaprovider

import (
	"cmp"
	"encoding/json"
	"slices"
	"strings"
	"time"
	"unicode"

	"golang.org/x/text/cases"
	"golang.org/x/text/unicode/norm"
)

// MatchHint carries catalog evidence, never a path. ExplicitIDs must already
// have been reduced to positive, typed identities for the expected work.
type MatchHint struct {
	Kind        Kind
	Title       string
	Year        int
	ExplicitIDs []EntityRef
}

type MatchCandidate struct {
	Entity        EntityRef
	Title         string
	OriginalTitle string
	Year          int
	Evidence      []string
}

type rankedCandidate struct {
	MatchCandidate
	rank int
}

// RankCandidates orders review choices within one provider result page. Even
// a single exact result is a candidate. Confirmation belongs to the caller.
func RankCandidates(hint MatchHint, documents []Document) []MatchCandidate {
	if hint.Kind != Movie && hint.Kind != Series {
		return []MatchCandidate{}
	}
	byProvider := make(map[string]int64)
	idConflict := false
	for _, id := range hint.ExplicitIDs {
		if id.Kind == hint.Kind && id.ID > 0 && id.Provider != "" {
			if previous := byProvider[id.Provider]; previous != 0 && previous != id.ID {
				idConflict = true
			}
			byProvider[id.Provider] = id.ID
		}
	}
	hintTitle := matchTitle(hint.Title)
	titleField, originalField, dateField := "title", "original_title", "release_date"
	if hint.Kind == Series {
		titleField, originalField, dateField = "name", "original_name", "first_air_date"
	}
	ranked := make([]rankedCandidate, 0, len(documents))
	for _, document := range documents {
		if document.Entity.Kind != hint.Kind || document.Entity.ID < 1 || document.Entity.Provider == "" {
			continue
		}
		candidate := rankedCandidate{MatchCandidate: MatchCandidate{
			Entity: document.Entity, Title: textField(document, titleField),
			OriginalTitle: textField(document, originalField), Evidence: []string{},
		}}
		if candidate.Title == "" {
			candidate.Title = candidate.OriginalTitle
		}
		if date, err := time.Parse(time.DateOnly, textField(document, dateField)); err == nil {
			candidate.Year = date.Year()
		}
		if candidate.Title == "" {
			continue
		}
		explicitID := byProvider[candidate.Entity.Provider]
		switch {
		case idConflict:
			candidate.Evidence = append(candidate.Evidence, "explicit_id_conflict")
		case explicitID == candidate.Entity.ID:
			candidate.rank += 10
			candidate.Evidence = append(candidate.Evidence, "explicit_id_match")
		case explicitID != 0:
			candidate.rank -= 10
			candidate.Evidence = append(candidate.Evidence, "explicit_id_difference")
		}
		if hintTitle != "" && (hintTitle == matchTitle(candidate.Title) || hintTitle == matchTitle(candidate.OriginalTitle)) {
			candidate.rank += 3
			candidate.Evidence = append(candidate.Evidence, "title_match")
		}
		if hint.Year > 0 && candidate.Year > 0 {
			if hint.Year == candidate.Year {
				candidate.rank += 2
				candidate.Evidence = append(candidate.Evidence, "year_match")
			} else {
				candidate.rank--
				candidate.Evidence = append(candidate.Evidence, "year_difference")
			}
		}
		ranked = append(ranked, candidate)
	}
	slices.SortStableFunc(ranked, func(a, b rankedCandidate) int { return cmp.Compare(b.rank, a.rank) })
	result := make([]MatchCandidate, 0, len(ranked))
	seen := make(map[EntityRef]struct{}, len(ranked))
	for _, candidate := range ranked {
		if _, exists := seen[candidate.Entity]; exists {
			continue
		}
		seen[candidate.Entity] = struct{}{}
		result = append(result, candidate.MatchCandidate)
	}
	return result
}

func textField(document Document, name string) string {
	var value string
	if json.Unmarshal(document.Fields[name], &value) != nil {
		return ""
	}
	return strings.TrimSpace(value)
}

func matchTitle(value string) string {
	value = cases.Fold().String(norm.NFKC.String(value))
	return strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) || unicode.IsPunct(r) {
			return -1
		}
		return r
	}, value)
}
