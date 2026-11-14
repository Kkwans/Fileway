package mediaprovider

import (
	"context"
	"net/url"
	"slices"
	"strings"
)

type ArtworkKind string

const (
	Poster   ArtworkKind = "poster"
	Backdrop ArtworkKind = "backdrop"
	Profile  ArtworkKind = "profile"
	Still    ArtworkKind = "still"
)

const tmdbImageBase = "https://image.tmdb.org/t/p/"

// CatalogArtworkURL uses standard TMDB sizes for the two catalog placements.
// The path always comes from a confirmed provider document.
func CatalogArtworkURL(kind ArtworkKind, filePath string) (string, error) {
	size := "w500"
	if kind == Backdrop {
		size = "w780"
	} else if kind != Poster {
		return "", &Error{Code: InvalidRequest}
	}
	return tmdbImageURL(tmdbImageBase, size, filePath)
}

// ImageConfiguration is validated provider configuration, not a caller-defined
// proxy destination. API credentials must not be attached to its public URLs.
type ImageConfiguration struct {
	base  string
	sizes map[ArtworkKind][]string
}

func (client *TMDBClient) Configuration(ctx context.Context) (ImageConfiguration, error) {
	var response struct {
		Images struct {
			Base     string   `json:"secure_base_url"`
			Poster   []string `json:"poster_sizes"`
			Backdrop []string `json:"backdrop_sizes"`
			Profile  []string `json:"profile_sizes"`
			Still    []string `json:"still_sizes"`
		} `json:"images"`
	}
	if err := client.get(ctx, "/configuration", nil, &response); err != nil {
		return ImageConfiguration{}, err
	}
	if response.Images.Base != tmdbImageBase {
		return ImageConfiguration{}, &Error{Code: InvalidResponse}
	}
	sizes := map[ArtworkKind][]string{Poster: response.Images.Poster, Backdrop: response.Images.Backdrop, Profile: response.Images.Profile, Still: response.Images.Still}
	for _, values := range sizes {
		if len(values) == 0 || len(values) > 32 {
			return ImageConfiguration{}, &Error{Code: InvalidResponse}
		}
		for _, value := range values {
			if !validImageSize(value) {
				return ImageConfiguration{}, &Error{Code: InvalidResponse}
			}
		}
	}
	return ImageConfiguration{base: response.Images.Base, sizes: sizes}, nil
}

func validImageSize(value string) bool {
	if value == "original" {
		return true
	}
	return len(value) >= 2 && len(value) <= 6 && (value[0] == 'w' || value[0] == 'h') && strings.IndexFunc(value[1:], func(r rune) bool { return r < '0' || r > '9' }) < 0
}

func (configuration ImageConfiguration) Sizes(kind ArtworkKind) []string {
	return slices.Clone(configuration.sizes[kind])
}

// ImageURL constructs a provider-only URL for the server artwork importer. A
// library client receives a Fileway asset reference after normal asset checks,
// never this URL or an arbitrary remote URL taken from a local NFO.
func (configuration ImageConfiguration) ImageURL(kind ArtworkKind, size, filePath string) (string, error) {
	if configuration.base == "" || !slices.Contains(configuration.sizes[kind], size) {
		return "", &Error{Code: InvalidRequest}
	}
	return tmdbImageURL(configuration.base, size, filePath)
}

func tmdbImageURL(base, size, filePath string) (string, error) {
	if len(filePath) < 2 || len(filePath) > 256 || filePath[0] != '/' {
		return "", &Error{Code: InvalidRequest}
	}
	name := filePath[1:]
	if strings.IndexFunc(name, func(r rune) bool {
		allowed := r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_' || r == '-' || r == '.'
		return !allowed
	}) >= 0 {
		return "", &Error{Code: InvalidRequest}
	}
	dot := strings.LastIndexByte(name, '.')
	if dot < 1 {
		return "", &Error{Code: InvalidRequest}
	}
	switch name[dot:] {
	case ".jpg", ".jpeg", ".png", ".webp":
	default:
		return "", &Error{Code: InvalidRequest}
	}
	return base + size + "/" + url.PathEscape(name), nil
}
