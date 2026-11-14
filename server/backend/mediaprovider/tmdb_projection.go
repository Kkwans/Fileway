package mediaprovider

import (
	"encoding/json"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

// TMDBProjection is descriptive provider evidence. Image paths are not URLs
// or Fileway assets; the artwork importer must still authorize and fetch them.
type TMDBProjection struct {
	Item         mediametadata.Item
	PosterPath   string
	BackdropPath string
}

type tmdbProjectionPerson struct {
	ID         *int64 `json:"id"`
	Name       string `json:"name"`
	Character  string `json:"character"`
	Order      *int   `json:"order"`
	Department string `json:"department"`
	Job        string `json:"job"`
	Roles      []struct {
		Character string `json:"character"`
	} `json:"roles"`
	Jobs []struct {
		Job string `json:"job"`
	} `json:"jobs"`
}

type tmdbProjectionCredits struct {
	Cast []tmdbProjectionPerson `json:"cast"`
	Crew []tmdbProjectionPerson `json:"crew"`
}

type tmdbProjectionFields struct {
	ID            *int64 `json:"id"`
	Title         string `json:"title"`
	OriginalTitle string `json:"original_title"`
	Name          string `json:"name"`
	OriginalName  string `json:"original_name"`
	Overview      string `json:"overview"`
	ReleaseDate   string `json:"release_date"`
	FirstAirDate  string `json:"first_air_date"`
	Genres        []struct {
		Name string `json:"name"`
	} `json:"genres"`
	IMDbID      string `json:"imdb_id"`
	ExternalIDs *struct {
		IMDbID string `json:"imdb_id"`
	} `json:"external_ids"`
	VoteAverage      *float64               `json:"vote_average"`
	VoteCount        *int64                 `json:"vote_count"`
	PosterPath       string                 `json:"poster_path"`
	BackdropPath     string                 `json:"backdrop_path"`
	Credits          *tmdbProjectionCredits `json:"credits"`
	AggregateCredits *tmdbProjectionCredits `json:"aggregate_credits"`
}

// ProjectTMDB converts a fetched movie or series document into the existing
// metadata evidence shape. It neither selects a match nor applies field
// priority, user state, or an artwork import. Empty fields are not clears.
func ProjectTMDB(document Document) (TMDBProjection, error) {
	var result TMDBProjection
	if document.Entity.Provider != "tmdb" || document.Entity.ID <= 0 || (document.Entity.Kind != Movie && document.Entity.Kind != Series) {
		return result, &Error{Code: InvalidRequest}
	}
	encoded, err := json.Marshal(document.Fields)
	if err != nil {
		return result, &Error{Code: InvalidResponse}
	}
	var source tmdbProjectionFields
	if err := json.Unmarshal(encoded, &source); err != nil || source.ID == nil || *source.ID != document.Entity.ID {
		return result, &Error{Code: InvalidResponse}
	}
	result.Item = mediametadata.Item{Kind: mediametadata.Movie, Fields: make(map[string][]string)}
	title, original, date := source.Title, source.OriginalTitle, source.ReleaseDate
	credits := source.Credits
	if document.Entity.Kind == Series {
		result.Item.Kind = mediametadata.TVShow
		title, original, date = source.Name, source.OriginalName, source.FirstAirDate
		if source.AggregateCredits != nil {
			credits = source.AggregateCredits
		}
	}
	addTMDBField(result.Item.Fields, "title", title)
	addTMDBField(result.Item.Fields, "originaltitle", original)
	addTMDBField(result.Item.Fields, "plot", source.Overview)
	if parsed, err := time.Parse(time.DateOnly, strings.TrimSpace(date)); err == nil && parsed.Year() > 0 {
		addTMDBField(result.Item.Fields, "year", strconv.Itoa(parsed.Year()))
	}
	for _, genre := range source.Genres {
		addTMDBField(result.Item.Fields, "genre", genre.Name)
	}
	result.Item.ExternalIDs = append(result.Item.ExternalIDs, mediametadata.ExternalID{Provider: "tmdb", Value: strconv.FormatInt(document.Entity.ID, 10), Tag: "tmdbid"})
	imdb := strings.TrimSpace(source.IMDbID)
	if source.ExternalIDs != nil {
		other := strings.TrimSpace(source.ExternalIDs.IMDbID)
		if imdb != "" && other != "" && imdb != other {
			return TMDBProjection{}, &Error{Code: InvalidResponse}
		}
		if imdb == "" {
			imdb = other
		}
	}
	if imdb != "" {
		if !validTMDBIMDbWorkID(imdb) {
			return TMDBProjection{}, &Error{Code: InvalidResponse}
		}
		result.Item.ExternalIDs = append(result.Item.ExternalIDs, mediametadata.ExternalID{Provider: "imdb", Value: imdb, Tag: "imdb_id"})
	}
	if source.VoteAverage != nil || source.VoteCount != nil {
		if source.VoteAverage != nil && (*source.VoteAverage < 0 || *source.VoteAverage > 10) || source.VoteCount != nil && *source.VoteCount < 0 {
			return TMDBProjection{}, &Error{Code: InvalidResponse}
		}
		scale := float64(10)
		rating := mediametadata.Rating{RawSource: "tmdb", Provider: "tmdb", Metric: mediametadata.UserAverage, Scale: &scale, CountKind: "user_votes", Value: source.VoteAverage, Count: source.VoteCount, Fields: map[string][]string{}}
		if source.VoteAverage != nil {
			rating.Fields["value"] = []string{string(document.Fields["vote_average"])}
		}
		if source.VoteCount != nil {
			rating.Fields["votes"] = []string{string(document.Fields["vote_count"])}
			rating.CountPrecision = "exact"
		}
		result.Item.Ratings = []mediametadata.Rating{rating}
	}
	if credits != nil {
		result.Item.Credits = projectTMDBCredits(*credits)
	}
	// Reuse the importer's existing path validation, without retaining its URL.
	images := ImageConfiguration{base: "https://image.tmdb.org/t/p/", sizes: map[ArtworkKind][]string{Poster: {"original"}, Backdrop: {"original"}}}
	for _, image := range []struct {
		kind ArtworkKind
		path string
		out  *string
	}{{Poster, source.PosterPath, &result.PosterPath}, {Backdrop, source.BackdropPath, &result.BackdropPath}} {
		if image.path != "" {
			if _, err := images.ImageURL(image.kind, "original", image.path); err != nil {
				return TMDBProjection{}, &Error{Code: InvalidResponse}
			}
			*image.out = image.path
		}
	}
	return result, nil
}

func addTMDBField(fields map[string][]string, name, value string) {
	if value = strings.TrimSpace(value); value != "" {
		fields[name] = append(fields[name], value)
	}
}

func validTMDBIMDbWorkID(value string) bool {
	if len(value) < 3 || len(value) > 128 || !strings.HasPrefix(value, "tt") {
		return false
	}
	return strings.IndexFunc(value[2:], func(r rune) bool { return r < '0' || r > '9' }) == -1
}

func projectTMDBCredits(credits tmdbProjectionCredits) []mediametadata.Credit {
	var result []mediametadata.Credit
	for _, cast := range credits.Cast {
		credit, ok := tmdbPersonCredit(cast, "actor")
		if !ok {
			continue
		}
		addTMDBField(credit.Fields, "role", cast.Character)
		for _, role := range cast.Roles {
			addTMDBField(credit.Fields, "role", role.Character)
		}
		if cast.Order != nil && *cast.Order >= 0 {
			addTMDBField(credit.Fields, "order", strconv.Itoa(*cast.Order))
		}
		result = append(result, credit)
	}
	for _, crew := range credits.Crew {
		jobs := []string{}
		if strings.TrimSpace(crew.Job) != "" {
			jobs = append(jobs, crew.Job)
		}
		for _, job := range crew.Jobs {
			if strings.TrimSpace(job.Job) != "" {
				jobs = append(jobs, job.Job)
			}
		}
		if len(jobs) == 0 {
			jobs = append(jobs, "")
		}
		byKind := map[string]int{}
		for _, job := range jobs {
			kind := tmdbCrewKind(crew.Department, job)
			index, exists := byKind[kind]
			if !exists {
				credit, ok := tmdbPersonCredit(crew, kind)
				if !ok {
					break
				}
				addTMDBField(credit.Fields, "department", crew.Department)
				index = len(result)
				result = append(result, credit)
				byKind[kind] = index
			}
			addTMDBField(result[index].Fields, "role", job)
		}
	}
	return result
}

func tmdbPersonCredit(person tmdbProjectionPerson, kind string) (mediametadata.Credit, bool) {
	name := strings.TrimSpace(person.Name)
	if name == "" {
		return mediametadata.Credit{}, false
	}
	credit := mediametadata.Credit{Kind: kind, Tag: kind, Fields: map[string][]string{"name": {name}}}
	if person.ID != nil && *person.ID > 0 {
		credit.ExternalIDs = []mediametadata.ExternalID{{Provider: "tmdb", Value: strconv.FormatInt(*person.ID, 10), Tag: "tmdbid"}}
	}
	return credit, true
}

func tmdbCrewKind(department, job string) string {
	switch strings.TrimSpace(job) {
	case "Director", "Co-Director", "Series Director":
		return "director"
	case "Writer", "Screenplay", "Story", "Teleplay", "Novel", "Original Story", "Screenstory":
		return "writer"
	}
	if strings.EqualFold(strings.TrimSpace(department), "Writing") {
		return "writer"
	}
	return "crew"
}
