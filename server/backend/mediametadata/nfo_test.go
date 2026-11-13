package mediametadata

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"reflect"
	"strings"
	"testing"
)

func fixture(t *testing.T, name string) *Document {
	t.Helper()
	file, err := os.Open("testdata/" + name + ".nfo")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	document, err := ParseNFO(file, Limits{})
	if err != nil {
		t.Fatal(err)
	}
	return document
}

func TestNFOEntityFixtures(t *testing.T) {
	for _, test := range []struct {
		file  string
		kind  Kind
		count int
	}{
		{"movie", Movie, 1}, {"tvshow", TVShow, 1}, {"season", Season, 1},
		{"episode", Episode, 1}, {"legacy-multiepisode", Episode, 2},
	} {
		t.Run(test.file, func(t *testing.T) {
			document := fixture(t, test.file)
			if len(document.Items) != test.count {
				t.Fatalf("items: got %d, want %d", len(document.Items), test.count)
			}
			for _, item := range document.Items {
				if item.Kind != test.kind || len(item.Fields["title"]) != 1 {
					t.Fatalf("wrong entity: %#v", item)
				}
			}
		})
	}
	zero := fixture(t, "episode").Items[0]
	if zero.Fields["season"][0] != "0" || zero.Fields["episode"][0] != "0" {
		t.Fatal("S00E00 must not be inferred to be a movie or absent")
	}
	combined := fixture(t, "legacy-multiepisode")
	if combined.Items[0].Fields["episode"][0] != "1" || combined.Items[1].Fields["episode"][0] != "2" {
		t.Fatal("legacy episode order was not retained")
	}
}

func TestFieldPresenceAndIdentityEvidence(t *testing.T) {
	item := fixture(t, "movie").Items[0]
	if _, exists := item.Fields["outline"]; exists {
		t.Fatal("missing field must remain absent")
	}
	if got := item.Fields["tagline"]; !reflect.DeepEqual(got, []string{""}) {
		t.Fatalf("explicit empty field lost: %#v", got)
	}
	if got := item.Fields["genre"]; !reflect.DeepEqual(got, []string{"剧情", "纪录片"}) {
		t.Fatalf("repeated values/order lost: %#v", got)
	}
	if !strings.Contains(item.Fields["plot"][0], "<script>") {
		t.Fatal("CDATA text must remain inert text")
	}
	ids := item.ExternalIDs
	if len(ids) != 4 || ids[0].Provider != "tmdb" || ids[0].Value != "12345" || ids[0].Default == nil || !*ids[0].Default {
		t.Fatalf("structured IDs lost: %#v", ids)
	}
	if ids[1].Default == nil || *ids[1].Default || ids[2].Provider != "douban" || ids[3].Provider != "" {
		t.Fatalf("legacy or default identity was inferred: %#v", ids)
	}
	for _, tag := range []string{"watched", "playcount", "lastplayed", "resume", "userrating"} {
		if _, exists := item.Fields[tag]; exists {
			t.Fatalf("user state imported: %s", tag)
		}
	}
}

func TestRatingsKeepSourceMetricScaleAndCount(t *testing.T) {
	ratings := fixture(t, "movie").Items[0].Ratings
	if len(ratings) != 7 {
		t.Fatalf("got %d ratings", len(ratings))
	}
	for index, want := range []struct {
		provider string
		metric   RatingMetric
		audience string
		value    float64
		scale    float64
		count    *int64
	}{
		{"douban", UserAverage, "", 8.6, 10, countPtr(12345)},
		{"imdb", UserAverage, "", 8.2, 10, nil},
		{"rottentomatoes", CriticApproval, "all", 83, 100, countPtr(209)},
		{"rottentomatoes", AudienceApproval, "all", 91, 100, countPtr(1000)},
		{"rottentomatoes", CriticAverage, "all", 7.8, 10, nil},
		{"rottentomatoes", UnknownRating, "", 90, 0, nil},
		{"", UnknownRating, "", 4.5, 5, countPtr(3)},
	} {
		got := ratings[index]
		if got.Provider != want.provider || got.Metric != want.metric || got.Audience != want.audience || got.Value == nil || *got.Value != want.value {
			t.Errorf("rating %d: unexpected classification/value: %#v", index, got)
		}
		if (want.scale == 0 && got.Scale != nil) || (want.scale != 0 && (got.Scale == nil || *got.Scale != want.scale)) {
			t.Errorf("rating %d: unexpected scale: %#v", index, got.Scale)
		}
		if !reflect.DeepEqual(got.Count, want.count) {
			t.Errorf("rating %d: unexpected count: %#v", index, got.Count)
		}
	}
	if ratings[6].RawSource != "CustomReviewer" || ratings[5].RawSource != "RottenTomatoes" {
		t.Fatal("original source spelling lost")
	}
	if _, exists := ratings[1].Fields["votes"]; exists {
		t.Fatal("missing count became a field")
	}
	if !reflect.DeepEqual(ratings[4].Fields["votes"], []string{""}) {
		t.Fatal("explicit empty count lost")
	}
	encoded, err := json.Marshal(ratings[1])
	if err != nil || !strings.Contains(string(encoded), `"count":null`) {
		t.Fatalf("missing count must serialize as null: %s, %v", encoded, err)
	}
	zero := fixture(t, "tvshow").Items[0].Ratings[0]
	if zero.Count == nil || *zero.Count != 0 {
		t.Fatal("explicit zero votes must differ from missing votes")
	}
}

func TestAmbiguousAndInvalidRatings(t *testing.T) {
	for _, test := range []struct {
		name  string
		xml   string
		issue string
	}{
		{"negative", `<rating name="imdb"><value>-1</value></rating>`, "invalid_value"},
		{"infinite", `<rating name="imdb"><value>Inf</value></rating>`, "invalid_value"},
		{"not-a-number", `<rating name="imdb"><value>NaN</value></rating>`, "invalid_value"},
		{"out-of-scale", `<rating name="imdb"><value>11</value></rating>`, "invalid_value"},
		{"empty-scale", `<rating name="imdb" max=""><value>8</value></rating>`, "invalid_scale"},
		{"zero-scale", `<rating name="imdb" max="0"><value>8</value></rating>`, "invalid_scale"},
		{"ambiguous-count", `<rating name="imdb"><votes>~1,000</votes></rating>`, "invalid_count"},
		{"overflow-count", `<rating name="imdb"><votes>9223372036854775808</votes></rating>`, "invalid_count"},
		{"bad-grouping", `<rating name="imdb"><votes>1,23</votes></rating>`, "invalid_count"},
		{"negative-count", `<rating name="imdb"><votes>-1</votes></rating>`, "invalid_count"},
		{"duplicate-value", `<rating name="imdb"><value>1</value><value>9</value></rating>`, "duplicate_value"},
		{"duplicate-count", `<rating name="imdb"><votes>1</votes><votes>9</votes></rating>`, "duplicate_count"},
	} {
		t.Run(test.name, func(t *testing.T) {
			doc, err := ParseNFO(strings.NewReader("<movie><ratings>"+test.xml+"</ratings></movie>"), Limits{})
			if err != nil {
				t.Fatal(err)
			}
			got := doc.Items[0].Ratings[0]
			if !reflect.DeepEqual(got.Issues, []string{test.issue}) {
				t.Fatalf("unexpected issues: %#v", got)
			}
			if strings.Contains(test.issue, "count") && got.Count != nil {
				t.Fatal("invalid/ambiguous count must not be published")
			}
			if strings.Contains(test.issue, "value") && got.Value != nil {
				t.Fatal("invalid/ambiguous rating must not be published")
			}
		})
	}
	doc, err := ParseNFO(strings.NewReader(`<movie><rating>8.3</rating><ratings><rating name="tomatometerallcritics" max="10"><value>8.3</value></rating></ratings></movie>`), Limits{})
	if err != nil {
		t.Fatal(err)
	}
	legacy, normalized := doc.Items[0].Ratings[0], doc.Items[0].Ratings[1]
	if legacy.Provider != "" || legacy.Metric != UnknownRating || legacy.Scale != nil {
		t.Fatalf("legacy score was guessed: %#v", legacy)
	}
	if normalized.Metric != CriticApproval || *normalized.Value != 8.3 || *normalized.Scale != 10 {
		t.Fatal("declared scale must be retained; 8.3/10 approval must not become 8.3 percent")
	}
}

func TestNFOInputBoundaries(t *testing.T) {
	for _, test := range []struct {
		name   string
		xml    string
		limits Limits
		want   error
	}{
		{"empty", "", Limits{}, ErrMalformed},
		{"unclosed", "<movie><title>oops", Limits{}, ErrMalformed},
		{"unknown-root", "<library/>", Limits{}, ErrUnsupportedRoot},
		{"two-movies", "<movie/><movie/>", Limits{}, ErrMultipleRoots},
		{"mixed-roots", "<episodedetails/><movie/>", Limits{}, ErrMultipleRoots},
		{"outside-text", "<movie/>not XML", Limits{}, ErrMalformed},
		{"external-entity", `<!DOCTYPE movie [<!ENTITY xxe SYSTEM "file:///not-a-real-file">]><movie><title>&xxe;</title></movie>`, Limits{}, ErrDirective},
		{"internal-entity", `<!DOCTYPE movie [<!ENTITY x "x">]><movie/>`, Limits{}, ErrDirective},
		{"unknown-entity", `<movie><title>&unknown;</title></movie>`, Limits{}, ErrMalformed},
		{"instruction", `<?fileway run="ignored"?><movie/>`, Limits{}, ErrDirective},
		{"embedded-declaration", `<movie><?xml version="1.0"?></movie>`, Limits{}, ErrDirective},
		{"duplicate-attribute", `<movie><uniqueid type="imdb" type="tmdb">1</uniqueid></movie>`, Limits{}, ErrMalformed},
		{"namespaced-root", `<movie xmlns="unexpected"/>`, Limits{}, ErrUnsupportedRoot},
		{"too-large", "<movie/>", Limits{MaxBytes: 7}, ErrTooLarge},
		{"too-deep", "<movie><a><b/></a></movie>", Limits{MaxDepth: 2}, ErrTooDeep},
		{"too-many-elements", "<movie><a/><b/></movie>", Limits{MaxElements: 2}, ErrTooManyElements},
		{"invalid-limit", "<movie/>", Limits{MaxBytes: -1}, ErrInvalidLimits},
	} {
		t.Run(test.name, func(t *testing.T) {
			document, err := ParseNFO(strings.NewReader(test.xml), test.limits)
			if !errors.Is(err, test.want) || document != nil {
				t.Fatalf("got document=%#v err=%v; want %v", document, err, test.want)
			}
		})
	}
	for _, input := range []string{"<movie/>", "\xef\xbb\xbf<movie/>", `<movie><title>A &amp; B</title></movie>`, `<movie><title>A <b>bold</b> ending</title></movie>`} {
		if _, err := ParseNFO(strings.NewReader(input), Limits{MaxBytes: int64(len(input))}); err != nil {
			t.Errorf("valid XML rejected: %v", err)
		}
	}
}

func TestReaderFailureDoesNotReturnPartialDocument(t *testing.T) {
	doc, err := ParseNFO(io.MultiReader(strings.NewReader("<movie/>"), brokenReader{}), Limits{})
	if !errors.Is(err, io.ErrUnexpectedEOF) || doc != nil {
		t.Fatalf("got %#v, %v", doc, err)
	}
}

type brokenReader struct{}

func (brokenReader) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

func countPtr(value int64) *int64 { return &value }
