package mediametadata

import (
	"math"
	"regexp"
	"strconv"
	"strings"
)

type RatingMetric string

const (
	UnknownRating    RatingMetric = "unknown"
	UserAverage      RatingMetric = "user_average"
	CriticApproval   RatingMetric = "critic_approval"
	AudienceApproval RatingMetric = "audience_approval"
	CriticAverage    RatingMetric = "critic_average"
)

// Rating keeps source spelling, declared scale and field presence for audit.
// Value is in Scale units, not automatically converted to /10 or percent.
// In particular, a critic approval rate is not an audience average rating.
// Issues contain stable codes only, never potentially sensitive source text.
type Rating struct {
	RawSource      string              `json:"rawSource"`
	RawScale       *string             `json:"rawScale,omitempty"`
	Fields         map[string][]string `json:"fields"`
	Provider       string              `json:"provider,omitempty"`
	Metric         RatingMetric        `json:"metric"`
	Audience       string              `json:"audience,omitempty"`
	Value          *float64            `json:"value"`
	Scale          *float64            `json:"scale"`
	Count          *int64              `json:"count"`
	CountKind      string              `json:"countKind,omitempty"`
	CountPrecision string              `json:"countPrecision,omitempty"`
	Default        *bool               `json:"default,omitempty"`
	Issues         []string            `json:"issues,omitempty"`
}

func parseRating(node *element) Rating {
	rating := newRating(node)
	for _, child := range node.children() {
		if child.name.Local == "value" || child.name.Local == "votes" {
			rating.Fields[child.name.Local] = append(rating.Fields[child.name.Local], child.text())
		}
	}
	rating.parseNumbers()
	return rating
}

func parseLegacyRating(node *element) Rating {
	rating := newRating(node)
	rating.Fields["value"] = []string{node.text()}
	rating.parseNumbers()
	return rating
}

func newRating(node *element) Rating {
	rating := Rating{
		RawSource: node.attrs["name"], Fields: make(map[string][]string), Metric: UnknownRating,
		Default: parseBoolean(node.attrs["default"]),
	}
	var defaultScale float64
	switch strings.ToLower(strings.TrimSpace(rating.RawSource)) {
	case "douban", "imdb", "tmdb", "themoviedb":
		rating.Provider = strings.ToLower(strings.TrimSpace(rating.RawSource))
		if rating.Provider == "themoviedb" {
			rating.Provider = "tmdb"
		}
		rating.Metric, rating.CountKind, defaultScale = UserAverage, "user_votes", 10
		if rating.Provider == "douban" {
			rating.CountKind = "user_ratings"
		}
	case "tomatometerallcritics":
		rating.Provider, rating.Metric, rating.Audience = "rottentomatoes", CriticApproval, "all"
		rating.CountKind, defaultScale = "critic_reviews", 100
	case "tomatometerallaudience":
		rating.Provider, rating.Metric, rating.Audience = "rottentomatoes", AudienceApproval, "all"
		rating.CountKind, defaultScale = "audience_ratings", 100
	case "tomatometeravgcritics":
		rating.Provider, rating.Metric, rating.Audience = "rottentomatoes", CriticAverage, "all"
		rating.CountKind, defaultScale = "critic_reviews", 10
	case "rottentomatoes":
		rating.Provider = "rottentomatoes"
	}
	if raw, exists := node.attrs["max"]; exists {
		rating.RawScale = &raw
		if scale, ok := finiteNumber(raw); ok && scale > 0 {
			rating.Scale = &scale
		} else {
			rating.Issues = append(rating.Issues, "invalid_scale")
		}
	} else if defaultScale > 0 {
		rating.Scale = &defaultScale
	}
	return rating
}

func (rating *Rating) parseNumbers() {
	if fields := rating.Fields["value"]; len(fields) > 1 {
		rating.Issues = append(rating.Issues, "duplicate_value")
	} else if len(fields) == 1 && fields[0] != "" {
		value, ok := finiteNumber(fields[0])
		if !ok || value < 0 || (rating.Scale != nil && value > *rating.Scale) {
			rating.Issues = append(rating.Issues, "invalid_value")
		} else {
			rating.Value = &value
		}
	}
	if fields := rating.Fields["votes"]; len(fields) > 1 {
		rating.Issues = append(rating.Issues, "duplicate_count")
	} else if len(fields) == 1 && fields[0] != "" {
		raw := fields[0]
		precision := "exact"
		if strings.HasSuffix(raw, "+") {
			raw = strings.TrimSuffix(raw, "+")
			precision = "lower_bound"
		}
		if count, ok := exactCount(raw); ok {
			rating.Count = &count
			rating.CountPrecision = precision
		} else {
			rating.Issues = append(rating.Issues, "invalid_count")
		}
	}
}

func finiteNumber(raw string) (float64, bool) {
	value, err := strconv.ParseFloat(strings.TrimSpace(raw), 64)
	return value, err == nil && !math.IsNaN(value) && !math.IsInf(value, 0)
}

var exactCountPattern = regexp.MustCompile(`^(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)$`)

func exactCount(raw string) (int64, bool) {
	if !exactCountPattern.MatchString(raw) {
		return 0, false
	}
	value, err := strconv.ParseInt(strings.ReplaceAll(raw, ",", ""), 10, 64)
	return value, err == nil
}
