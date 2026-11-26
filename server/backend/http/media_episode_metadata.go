package fbhttp

import (
	"context"
	"errors"
	"sort"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
	"github.com/Kkwans/nas-file-browser/backend/mediaprovider"
)

type catalogEpisodeMetadataKey struct{ LibraryID, EpisodeID string }
type catalogEpisodeMetadata struct {
	Fields      map[string][]string
	OnlineFirst bool
	Revision    int64
}

func (v *catalogView) episodeProviderMetadata(ctx context.Context, library *medialibrary.Library, episodeID string) (*catalogEpisodeMetadata, error) {
	key := catalogEpisodeMetadataKey{library.ID, episodeID}
	if cached, ok := v.episodeMetadata[key]; ok {
		return cached, nil
	}
	structure, err := v.d.store.MediaLibrary.GetItemStructure(ctx, v.d.user.ID, library.ID, episodeID)
	if err != nil {
		return nil, err
	}
	if structure.Kind != "episode" {
		return nil, medialibrary.ErrNotFound
	}
	candidate, err := v.d.store.MediaLibrary.GetSeriesEpisodeCandidate(ctx, v.d.user.ID, library.ID, structure.SeriesID, episodeID)
	if errors.Is(err, medialibrary.ErrNotFound) {
		return &catalogEpisodeMetadata{}, nil
	}
	if err != nil {
		return nil, err
	}
	series, err := v.d.store.MediaLibrary.GetCatalogItem(ctx, v.d.user.ID, library.ID, structure.SeriesID)
	if err != nil {
		return nil, err
	}
	match, err := v.d.store.MediaLibrary.VisibleProviderMatch(ctx, v.d.user.ID, library.ID, series.ID, series.Revision)
	if err != nil {
		return nil, err
	}
	result := &catalogEpisodeMetadata{}
	if match != nil {
		result.OnlineFirst, result.Revision = match.OnlineFirst, match.Revision
		for _, episode := range match.Document.Episodes {
			if episode.SeasonNumber == candidate.SeasonNumber && episode.EpisodeNumber == candidate.EpisodeNumber {
				result.Fields = episode.Item.Fields
				break
			}
		}
	}
	v.episodeMetadata[key] = result
	return result, nil
}

func withProviderEpisodeDocuments(ctx context.Context, store *medialibrary.Store, actor uint,
	libraryID, seriesID string, externalID int64, client *mediaprovider.TMDBClient, language string,
	document medialibrary.ProviderMatchDocument) (medialibrary.ProviderMatchDocument, error) {
	if document.Item.Kind != mediametadata.TVShow {
		return document, nil
	}
	episodes, err := fetchProviderEpisodeDocuments(ctx, store, actor, libraryID, seriesID,
		mediaprovider.EntityRef{Provider: "tmdb", Kind: mediaprovider.Series, ID: externalID}, client, language)
	if err != nil {
		return medialibrary.ProviderMatchDocument{}, err
	}
	document.Episodes = episodes
	return document, nil
}

func fetchProviderEpisodeDocuments(ctx context.Context, store *medialibrary.Store, actor uint,
	libraryID, seriesID string, series mediaprovider.EntityRef, client *mediaprovider.TMDBClient,
	language string) ([]medialibrary.ProviderEpisodeDocument, error) {
	seasons := make(map[int]string, 16)
	var afterSeason *int
	for {
		page, err := store.ListStructureSeasons(ctx, actor, libraryID, seriesID, afterSeason, 100)
		if err != nil {
			return nil, err
		}
		for _, season := range page.Items {
			seasons[season.Number] = season.SeasonID
		}
		if page.NextNumber == nil {
			break
		}
		afterSeason = page.NextNumber
	}
	if len(seasons) == 0 {
		return []medialibrary.ProviderEpisodeDocument{}, nil
	}
	seasonNumbers := make([]int, 0, len(seasons))
	for seasonNumber := range seasons {
		seasonNumbers = append(seasonNumbers, seasonNumber)
	}
	sort.Ints(seasonNumbers)
	result := make([]medialibrary.ProviderEpisodeDocument, 0, len(seasonNumbers)*8)
	for _, seasonNumber := range seasonNumbers {
		seasonID := seasons[seasonNumber]
		localEpisodes := make(map[int]struct{})
		var afterEpisode *int
		for {
			page, err := store.ListStructureEpisodes(ctx, actor, libraryID, seriesID, seasonID, afterEpisode, 100)
			if err != nil {
				return nil, err
			}
			for _, episode := range page.Items {
				localEpisodes[episode.EpisodeNumber] = struct{}{}
			}
			if page.NextNumber == nil {
				break
			}
			afterEpisode = page.NextNumber
		}
		if len(localEpisodes) == 0 {
			continue
		}
		document, err := client.FetchSeason(ctx, series, seasonNumber, mediaprovider.FetchOptions{Language: language})
		if err != nil {
			return nil, err
		}
		projected, err := mediaprovider.ProjectTMDBSeasonEpisodes(document)
		if err != nil {
			return nil, err
		}
		sort.Slice(projected, func(i, j int) bool { return projected[i].Number < projected[j].Number })
		for _, episode := range projected {
			if _, exists := localEpisodes[episode.Number]; !exists {
				continue
			}
			fields := make(map[string][]string, 5)
			if episode.Title != "" {
				fields["title"] = []string{episode.Title}
			}
			if episode.OriginalTitle != "" {
				fields["originaltitle"] = []string{episode.OriginalTitle}
			}
			if episode.Overview != "" {
				fields["plot"] = []string{episode.Overview}
			}
			if episode.AirDate != "" {
				fields["aired"] = []string{episode.AirDate}
			}
			if episode.RuntimeMinutes > 0 {
				fields["runtime"] = []string{strconv.Itoa(episode.RuntimeMinutes)}
			}
			result = append(result, medialibrary.ProviderEpisodeDocument{
				SeasonNumber: episode.SeasonNumber, EpisodeNumber: episode.Number, StillPath: episode.StillPath,
				Item: mediametadata.Item{Kind: mediametadata.Episode, Fields: fields,
					ExternalIDs: []mediametadata.ExternalID{{Provider: "tmdb", Value: strconv.FormatInt(episode.ID, 10), Tag: "tmdbid"}}},
			})
			if len(result) > 2000 {
				return nil, medialibrary.ErrInvalid
			}
		}
	}
	return result, nil
}
