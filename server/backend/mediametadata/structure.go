package mediametadata

import (
	"sort"
	"strconv"
	"strings"
)

// EpisodeResolution is structural evidence only. It is meaningful only under
// a separately established series context; it never identifies a work by title.
type EpisodeResolution struct {
	Positions []SeasonEpisode `json:"positions,omitempty"`
	State     string          `json:"state"` // resolved, unmatched, needs_review
	Issues    []string        `json:"issues,omitempty"`
}

// SeasonNumber extracts an explicitly declared season. Absence and season 0 are
// distinct. Conflicting/repeated invalid values are not silently discarded.
func SeasonNumber(item Item) (number *int, issue string) {
	if item.Kind != Season {
		return nil, "wrong_nfo_kind"
	}
	return uniqueNumber(item.Fields, []string{"season", "seasonnumber"}, 999)
}

func uniqueNumber(fields map[string][]string, names []string, maximum int) (*int, string) {
	var number *int
	for _, name := range names {
		for _, value := range fields[name] {
			value = strings.TrimSpace(value)
			if value == "" {
				return nil, "invalid_number"
			}
			n, err := strconv.Atoi(value)
			if err != nil || n < 0 || n > maximum {
				return nil, "invalid_number"
			}
			if number != nil && *number != n {
				return nil, "number_conflict"
			}
			copy := n
			number = &copy
		}
	}
	return number, ""
}

// ResolveEpisode reconciles explicit NFO numbers, filename evidence and the
// nearest season context. Date, absolute and SP/OVA numbering is never
// converted to season/episode numbering. A multi-episode file has no implied
// playback cuts; the caller must retain all resolved positions as one file.
func ResolveEpisode(name ParsedName, document *Document, contextSeason *int) EpisodeResolution {
	result := EpisodeResolution{State: "unmatched"}
	filename := canonicalPositions(name.SeasonEpisodes)
	addIssue := func(code string) {
		for _, existing := range result.Issues {
			if existing == code {
				return
			}
		}
		result.Issues = append(result.Issues, code)
	}
	for _, ambiguity := range name.Ambiguities {
		switch ambiguity {
		case "missing_title", "combined_episode_file":
			// A numbered filename need not contain a title; combined files are
			// represented as mappings without manufacturing playback cuts.
		default:
			addIssue(ambiguity)
		}
	}

	var nfoPositions []SeasonEpisode
	if document != nil {
		if len(document.Items) == 0 || len(document.Items) > MaxEpisodeHints {
			addIssue("invalid_episode_nfo")
		}
		for _, item := range document.Items {
			if item.Kind != Episode {
				addIssue("wrong_nfo_kind")
				continue
			}
			season, seasonIssue := uniqueNumber(item.Fields, []string{"season", "seasonnumber"}, 999)
			episode, episodeIssue := uniqueNumber(item.Fields, []string{"episode"}, 9999)
			if seasonIssue != "" || episodeIssue != "" {
				addIssue("invalid_nfo_number")
				continue
			}
			if season != nil && contextSeason != nil && *season != *contextSeason {
				addIssue("season_context_conflict")
			}
			if season == nil {
				season = contextSeason
			}
			if season == nil && len(filename) > 0 {
				candidate := filename[0].Season
				same := true
				for _, position := range filename {
					if position.Season != candidate {
						same = false
					}
				}
				if same {
					season = &candidate
				}
			}
			if season != nil && episode != nil {
				nfoPositions = append(nfoPositions, SeasonEpisode{Season: *season, Episode: *episode})
			} else if episode == nil && len(document.Items) == 1 && len(filename) > 0 {
				for _, position := range filename {
					if season != nil && position.Season != *season {
						addIssue("filename_nfo_conflict")
					}
				}
				if len(filename) > 1 && len(item.ExternalIDs) > 0 {
					addIssue("ambiguous_episode_nfo")
				}
			} else if len(document.Items) > 1 || season != nil || episode != nil {
				addIssue("incomplete_nfo_number")
			}
		}
	}

	nfo := canonicalPositions(nfoPositions)
	if len(nfo) > 0 {
		// A numbered episode NFO explicitly maps this file; do not infer this
		// conversion from an absolute or special filename by itself.
		filtered := result.Issues[:0]
		for _, issue := range result.Issues {
			if issue != "absolute_episode_requires_mapping" && issue != "special_requires_mapping" {
				filtered = append(filtered, issue)
			}
		}
		result.Issues = filtered
	}
	if len(nfo) != len(nfoPositions) {
		addIssue("duplicate_nfo_position")
	}
	if contextSeason != nil {
		for _, position := range filename {
			if position.Season != *contextSeason {
				addIssue("season_context_conflict")
			}
		}
	}
	if len(nfo) > 0 && len(filename) > 0 && !samePositions(nfo, filename) {
		addIssue("filename_nfo_conflict")
	}
	if len(result.Issues) > 0 {
		sort.Strings(result.Issues)
		result.State = "needs_review"
		return result
	}
	if len(nfo) > 0 {
		result.Positions = nfo
	} else {
		result.Positions = filename
	}
	if len(result.Positions) > 0 {
		result.State = "resolved"
	}
	return result
}

func canonicalPositions(positions []SeasonEpisode) []SeasonEpisode {
	result := append([]SeasonEpisode(nil), positions...)
	sort.Slice(result, func(i, j int) bool {
		if result[i].Season != result[j].Season {
			return result[i].Season < result[j].Season
		}
		return result[i].Episode < result[j].Episode
	})
	out := result[:0]
	for _, p := range result {
		if len(out) == 0 || out[len(out)-1] != p {
			out = append(out, p)
		}
	}
	return out
}

func samePositions(left, right []SeasonEpisode) bool {
	if len(left) != len(right) {
		return false
	}
	for i := range left {
		if left[i] != right[i] {
			return false
		}
	}
	return true
}
