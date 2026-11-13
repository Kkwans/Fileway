package mediametadata

import (
	"strings"
	"testing"
)

func TestRatingCountPrecisionAndUnits(t *testing.T) {
	doc, err := ParseNFO(strings.NewReader(`<movie><ratings>
 <rating name="douban"><value>8.6</value><votes>1,000+</votes></rating>
 <rating name="imdb"><value>8.2</value><votes>1000</votes></rating>
 <rating name="tomatometerallcritics"><value>90</value></rating>
 </ratings></movie>`), Limits{})
	if err != nil {
		t.Fatal(err)
	}
	lower, exact, missing := doc.Items[0].Ratings[0], doc.Items[0].Ratings[1], doc.Items[0].Ratings[2]
	if lower.Count == nil || *lower.Count != 1000 || lower.CountPrecision != "lower_bound" || lower.CountKind != "user_ratings" || lower.Fields["votes"][0] != "1,000+" {
		t.Fatalf("lost count semantics: %#v", lower)
	}
	if exact.Count == nil || *exact.Count != 1000 || exact.CountPrecision != "exact" || exact.CountKind != "user_votes" {
		t.Fatalf("lost exact votes: %#v", exact)
	}
	if missing.Count != nil || missing.CountPrecision != "" || missing.CountKind != "critic_reviews" {
		t.Fatalf("invented count: %#v", missing)
	}
}
