package fbhttp

import (
	"strings"
	"testing"
)

func TestDoubanRatingsPathUsesContainerDefault(t *testing.T) {
	t.Setenv("FILEWAY_DOUBAN_RATINGS_FILE", "")
	if got := doubanRatingsPath(); got != "/config/douban-ratings.jsonl" {
		t.Fatalf("default path = %q", got)
	}
	t.Setenv("FILEWAY_DOUBAN_RATINGS_FILE", "/data/ratings.jsonl")
	if got := doubanRatingsPath(); got != "/data/ratings.jsonl" {
		t.Fatalf("configured path = %q", got)
	}
}

func TestDoubanRatingIndexMatchesByIMDbThenTitleYear(t *testing.T) {
	index, err := parseDoubanRatingIndex(strings.NewReader(`{"id":"1292052","title":"肖申克的救赎 The Shawshank Redemption","year":1994,"imdb":"tt0111161","rating":9.7,"votes":3000000}
{"id":"1291546","title":"阿甘正传 Forrest Gump","year":1994,"imdb":"tt0109830","rating":9.5,"votes":2000000}
`))
	if err != nil {
		t.Fatal(err)
	}
	if got := index.lookup("tt0111161", "无关标题", 2020); got == nil || got.Rating != 9.7 {
		t.Fatalf("IMDb match failed: %#v", got)
	}
	if got := index.lookup("", "阿甘正传", 1994); got == nil || got.Rating != 9.5 {
		t.Fatalf("title/year match failed: %#v", got)
	}
	if got := index.lookup("", "阿甘正传", 1995); got != nil {
		t.Fatalf("year mismatch must not match: %#v", got)
	}
}

func TestDoubanRatingIndexRejectsInvalidScores(t *testing.T) {
	if _, err := parseDoubanRatingIndex(strings.NewReader(`{"id":"1","title":"坏数据","year":2020,"rating":11,"votes":1}`)); err == nil {
		t.Fatal("expected invalid score error")
	}
}
