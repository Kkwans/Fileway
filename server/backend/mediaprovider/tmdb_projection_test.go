package mediaprovider

import (
	"encoding/json"
	"errors"
	"reflect"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

func projectionDocument(t *testing.T, kind Kind, payload string) Document {
	t.Helper()
	var fields map[string]json.RawMessage
	if err := json.Unmarshal([]byte(payload), &fields); err != nil {
		t.Fatal(err)
	}
	document, err := tmdbDocument(kind, 0, "zh-CN", fields)
	if err != nil {
		t.Fatal(err)
	}
	return document
}

func TestProjectTMDBMovie(t *testing.T) {
	document := projectionDocument(t, Movie, `{
		"id": 123, "title": "海风", "original_title": "Sea Wind", "overview": "一段旅程。",
		"release_date": "2022-04-03", "genres": [{"id": 1, "name": "剧情"}, {"id": 2, "name": "冒险"}],
		"imdb_id": "tt12345678", "external_ids": {"imdb_id": "tt12345678"},
		"vote_average": 7.25, "vote_count": 1345, "poster_path": "/poster.jpg", "backdrop_path": "/backdrop.png",
		"watched": true, "credits": {
			"cast": [{"id": 44, "name": "演员甲", "character": "船长", "order": 0}],
			"crew": [
				{"id": 45, "name": "导演乙", "department": "Directing", "job": "Director"},
				{"id": 46, "name": "编剧丙", "department": "Writing", "job": "Screenplay"},
				{"id": 47, "name": "摄影丁", "department": "Camera", "job": "Director of Photography"}
			]
		}
	}`)
	projection, err := ProjectTMDB(document)
	if err != nil {
		t.Fatal(err)
	}
	item := projection.Item
	wantFields := map[string][]string{"title": {"海风"}, "originaltitle": {"Sea Wind"}, "plot": {"一段旅程。"}, "year": {"2022"}, "genre": {"剧情", "冒险"}}
	if item.Kind != mediametadata.Movie || !reflect.DeepEqual(item.Fields, wantFields) {
		t.Fatalf("descriptive fields = %#v", item)
	}
	wantIDs := []mediametadata.ExternalID{{Provider: "tmdb", Value: "123", Tag: "tmdbid"}, {Provider: "imdb", Value: "tt12345678", Tag: "imdb_id"}}
	if !reflect.DeepEqual(item.ExternalIDs, wantIDs) {
		t.Fatalf("external IDs = %#v", item.ExternalIDs)
	}
	if len(item.Ratings) != 1 {
		t.Fatalf("ratings = %#v", item.Ratings)
	}
	rating := item.Ratings[0]
	if rating.Provider != "tmdb" || rating.Metric != mediametadata.UserAverage || rating.Value == nil || *rating.Value != 7.25 || rating.Scale == nil || *rating.Scale != 10 || rating.Count == nil || *rating.Count != 1345 || rating.CountKind != "user_votes" || rating.CountPrecision != "exact" || !reflect.DeepEqual(rating.Fields, map[string][]string{"value": {"7.25"}, "votes": {"1345"}}) {
		t.Fatalf("rating = %#v", rating)
	}
	if projection.PosterPath != "/poster.jpg" || projection.BackdropPath != "/backdrop.png" {
		t.Fatalf("artwork paths = %#v", projection)
	}
	if len(item.Credits) != 4 {
		t.Fatalf("credits = %#v", item.Credits)
	}
	for i, want := range []struct{ kind, name, role, id string }{
		{"actor", "演员甲", "船长", "44"}, {"director", "导演乙", "Director", "45"},
		{"writer", "编剧丙", "Screenplay", "46"}, {"crew", "摄影丁", "Director of Photography", "47"},
	} {
		credit := item.Credits[i]
		if credit.Kind != want.kind || !reflect.DeepEqual(credit.Fields["name"], []string{want.name}) || !reflect.DeepEqual(credit.Fields["role"], []string{want.role}) || len(credit.ExternalIDs) != 1 || credit.ExternalIDs[0].Provider != "tmdb" || credit.ExternalIDs[0].Value != want.id {
			t.Fatalf("credit %d = %#v", i, credit)
		}
	}
}

func TestProjectTMDBSeriesAggregateCredits(t *testing.T) {
	document := projectionDocument(t, Series, `{
		"id": 987, "name": "星途", "original_name": "Star Road", "overview": "",
		"first_air_date": "2020-10-01", "imdb_id": null, "external_ids": {"imdb_id": "tt7654321"},
		"vote_average": 0, "vote_count": 0, "poster_path": null,
		"credits": {"cast": [{"id": 999, "name": "旧列表", "character": "不应采用"}]},
		"aggregate_credits": {
			"cast": [{"id": 48, "name": "演员戊", "roles": [{"character": "队长"}, {"character": "教授"}], "order": 0}],
			"crew": [{"id": 49, "name": "多职己", "department": "Production", "jobs": [{"job": "Director"}, {"job": "Producer"}, {"job": "Screenplay"}]}]
		}
	}`)
	projection, err := ProjectTMDB(document)
	if err != nil {
		t.Fatal(err)
	}
	item := projection.Item
	if item.Kind != mediametadata.TVShow || !reflect.DeepEqual(item.Fields, map[string][]string{"title": {"星途"}, "originaltitle": {"Star Road"}, "year": {"2020"}}) {
		t.Fatalf("series fields = %#v", item.Fields)
	}
	if len(item.ExternalIDs) != 2 || item.ExternalIDs[1].Value != "tt7654321" {
		t.Fatalf("series external IDs = %#v", item.ExternalIDs)
	}
	if len(item.Ratings) != 1 || item.Ratings[0].Count == nil || *item.Ratings[0].Count != 0 || item.Ratings[0].CountPrecision != "exact" || item.Ratings[0].Value == nil || *item.Ratings[0].Value != 0 {
		t.Fatalf("zero votes = %#v", item.Ratings)
	}
	if projection.PosterPath != "" || projection.BackdropPath != "" {
		t.Fatalf("missing artwork = %#v", projection)
	}
	if len(item.Credits) != 4 || item.Credits[0].Kind != "actor" || !reflect.DeepEqual(item.Credits[0].Fields["role"], []string{"队长", "教授"}) {
		t.Fatalf("aggregate cast = %#v", item.Credits)
	}
	for index, want := range []struct{ kind, job string }{{"director", "Director"}, {"crew", "Producer"}, {"writer", "Screenplay"}} {
		credit := item.Credits[index+1]
		if credit.Kind != want.kind || !reflect.DeepEqual(credit.Fields["role"], []string{want.job}) || len(credit.ExternalIDs) != 1 || credit.ExternalIDs[0].Value != "49" {
			t.Fatalf("aggregate crew %d = %#v", index, credit)
		}
	}
}

func TestProjectTMDBRejectsInvalidEvidence(t *testing.T) {
	for _, test := range []struct {
		name, payload string
	}{
		{"different work IDs", `{"id":1,"imdb_id":"tt1234567","external_ids":{"imdb_id":"tt7654321"}}`},
		{"person ID is not a work ID", `{"id":1,"imdb_id":"nm1234567"}`},
		{"unsafe image", `{"id":1,"poster_path":"/poster.jpg?token=private"}`},
		{"non numeric votes", `{"id":1,"vote_count":"20"}`},
		{"rating out of scale", `{"id":1,"vote_average":11}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := ProjectTMDB(projectionDocument(t, Movie, test.payload))
			var providerErr *Error
			if !errors.As(err, &providerErr) || providerErr.Code != InvalidResponse {
				t.Fatalf("error = %v", err)
			}
		})
	}
	document := projectionDocument(t, Movie, `{"id":1}`)
	document.Entity.ID = 2
	if _, err := ProjectTMDB(document); err == nil {
		t.Fatal("mismatched document ID was accepted")
	}
}

func TestProjectTMDBMissingValuesAreNotClears(t *testing.T) {
	document := projectionDocument(t, Series, `{"id":1,"name":"  ","overview":null,"first_air_date":"2020-02-30","genres":[],"credits":{"cast":[{"name":"甲","character":"乙"}]}}`)
	projection, err := ProjectTMDB(document)
	if err != nil {
		t.Fatal(err)
	}
	if len(projection.Item.Fields) != 0 || len(projection.Item.Ratings) != 0 || len(projection.Item.Credits) != 1 || projection.Item.Credits[0].Fields["role"][0] != "乙" {
		t.Fatalf("missing values = %#v", projection.Item)
	}
}
