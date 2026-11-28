package fbhttp

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"unicode"
)

type doubanRatingRecord struct {
	ID     string  `json:"id"`
	Title  string  `json:"title"`
	Year   int     `json:"year"`
	IMDb   string  `json:"imdb"`
	Rating float64 `json:"rating"`
	Votes  int64   `json:"votes"`
}

type doubanRatingIndex struct {
	byIMDb      map[string]doubanRatingRecord
	byTitleYear map[string]doubanRatingRecord
}

func parseDoubanRatingIndex(reader io.Reader) (*doubanRatingIndex, error) {
	index := &doubanRatingIndex{
		byIMDb:      map[string]doubanRatingRecord{},
		byTitleYear: map[string]doubanRatingRecord{},
	}
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64<<10), 4<<20)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var record doubanRatingRecord
		if err := json.Unmarshal([]byte(line), &record); err != nil {
			return nil, fmt.Errorf("parse douban rating: %w", err)
		}
		if record.Title == "" || record.Rating <= 0 || record.Rating > 10 || record.Votes < 0 {
			return nil, fmt.Errorf("invalid douban rating for %q", record.Title)
		}
		if record.IMDb != "" {
			if _, exists := index.byIMDb[record.IMDb]; !exists {
				index.byIMDb[record.IMDb] = record
			}
		}
		if record.Year > 0 {
			for _, title := range doubanRatingTitleKeys(record.Title) {
				key := title + "\x00" + fmt.Sprint(record.Year)
				if _, exists := index.byTitleYear[key]; !exists {
					index.byTitleYear[key] = record
				}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read douban ratings: %w", err)
	}
	return index, nil
}

func doubanRatingTitleKeys(title string) []string {
	full := normalizeDoubanRatingTitle(title)
	if full == "" {
		return nil
	}
	keys := []string{full}
	if prefix := strings.Fields(title); len(prefix) > 1 {
		short := normalizeDoubanRatingTitle(prefix[0])
		if len(short) >= 2 && short != full {
			keys = append(keys, short)
		}
	}
	return keys
}

func normalizeDoubanRatingTitle(title string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return unicode.ToLower(r)
		}
		return -1
	}, title)
}

func (index *doubanRatingIndex) lookup(imdb, title string, year int) *doubanRatingRecord {
	if index == nil {
		return nil
	}
	if imdb != "" {
		if record, ok := index.byIMDb[imdb]; ok {
			return &record
		}
	}
	if year <= 0 {
		return nil
	}
	for _, key := range doubanRatingTitleKeys(title) {
		if record, ok := index.byTitleYear[key+"\x00"+fmt.Sprint(year)]; ok {
			return &record
		}
	}
	return nil
}

var doubanRatingsOnce sync.Once
var doubanRatingsIndex *doubanRatingIndex

func doubanRatingsPath() string {
	if path := os.Getenv("FILEWAY_DOUBAN_RATINGS_FILE"); path != "" {
		return path
	}
	return "/config/douban-ratings.jsonl"
}

func catalogDoubanRatings() *doubanRatingIndex {
	doubanRatingsOnce.Do(func() {
		file, err := os.Open(doubanRatingsPath())
		if err != nil {
			return
		}
		defer file.Close()
		doubanRatingsIndex, _ = parseDoubanRatingIndex(file)
	})
	return doubanRatingsIndex
}

func catalogDoubanRating(title string, year *int) *catalogRating {
	if year == nil {
		return nil
	}
	record := catalogDoubanRatings().lookup("", title, *year)
	if record == nil {
		return nil
	}
	scale := 10.0
	count := record.Votes
	return &catalogRating{Provider: "douban", Metric: "average", Value: &record.Rating,
		Scale: &scale, Count: &count, CountKind: "user_ratings"}
}
