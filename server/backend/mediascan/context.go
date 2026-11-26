package mediascan

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

// ContextSink extends the existing sink without changing standalone scanner
// consumers. A persistent implementation commits context and checkpoint under
// one lease; a returned ref is an optimistic revision fence, not a read grant.
type ContextSink interface {
	ObserveDirectory(context.Context, medialibrary.DirectoryContextObservation) (medialibrary.DirectoryContextRef, error)
}

const maxContextSidecars = 32

// readDirectoryContext performs a bounded first pass before any child or video
// is observed. This makes enumeration order irrelevant, including when a
// filesystem permits multiple case variants of the same special NFO name.
func (w *walker) readDirectoryContext(ctx context.Context, locator string, parent medialibrary.DirectoryContextRef) (observation medialibrary.DirectoryContextObservation, resultErr error) {
	observation = medialibrary.DirectoryContextObservation{
		Locator: absoluteLocator(w.binding.RootLocator, locator),
		Parent:  parent,
		TVShow:  medialibrary.DirectorySidecar{Status: "missing"},
		Season:  medialibrary.DirectorySidecar{Status: "missing"},
	}
	dir, err := w.source.Open(ctx, locator, true)
	if err != nil {
		return observation, err
	}
	defer func() {
		if err := dir.Close(); resultErr == nil && err != nil {
			resultErr = err
		}
	}()
	info, stamp, err := dir.State()
	if err != nil {
		return observation, err
	}
	var tvNames, seasonNames, episodeNames []string
	var tvOverflow, seasonOverflow bool
	for {
		if err := w.checkDirectory(ctx, locator, dir, info, stamp); err != nil {
			return observation, err
		}
		entries, readErr := dir.ReadDir(w.options.ReadBatch)
		if len(entries) == 0 && readErr == nil {
			return observation, io.ErrNoProgress
		}
		for _, entry := range entries {
			name := entry.Name()
			if invalidEntryName(name) {
				return observation, fs.ErrInvalid
			}
			switch {
			case strings.EqualFold(name, "tvshow.nfo"):
				if len(tvNames) < maxContextSidecars {
					tvNames = append(tvNames, name)
				} else {
					tvOverflow = true
				}
			case strings.EqualFold(name, "season.nfo"):
				if len(seasonNames) < maxContextSidecars {
					seasonNames = append(seasonNames, name)
				} else {
					seasonOverflow = true
				}
			case videoName(name):
				if len(episodeNames) < mediametadata.MaxEpisodeHints {
					episodeNames = append(episodeNames, name)
				}
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return observation, readErr
			}
			break
		}
	}
	if err := w.checkDirectory(ctx, locator, dir, info, stamp); err != nil {
		return observation, err
	}
	observation.TVShow = w.readDirectorySidecars(ctx, locator, tvNames, tvOverflow, mediametadata.TVShow)
	observation.Season = w.readDirectorySidecars(ctx, locator, seasonNames, seasonOverflow, mediametadata.Season)
	observation.Episodes = directoryEpisodeEvidence(path.Base(locator), episodeNames)
	if err := ctx.Err(); err != nil {
		return observation, err
	}
	if err := w.checkDirectory(ctx, locator, dir, info, stamp); err != nil {
		return observation, err
	}
	return observation, nil
}

var localSeriesSeasonPattern = regexp.MustCompile(`(?i)(?:^|[^a-z0-9])s([0-9]{1,3})(?:$|[^a-z0-9])`)
var localSeriesChineseSeasonPattern = regexp.MustCompile(`第([零〇一二两三四五六七八九十百千0-9]+)季`)
var localSeriesSeasonSuffixPattern = regexp.MustCompile(`(?i)(?:[ ._\-]*(?:s[0-9]{1,3}|第[零〇一二两三四五六七八九十百千0-9]+季))$`)

func directoryEpisodeEvidence(directory string, names []string) *medialibrary.DirectoryEpisodeEvidence {
	title := localSeriesDirectoryTitle(directory)
	if title == "" || len(names) < 2 {
		return nil
	}
	type episodeRecord struct {
		title           string
		year            *int
		season, episode int
	}
	records := make([]episodeRecord, 0, len(names))
	for _, name := range names {
		parsed := mediametadata.ParseNameInDirectory(name, directory)
		var season, episode int
		if len(parsed.SeasonEpisodes) == 1 {
			season, episode = parsed.SeasonEpisodes[0].Season, parsed.SeasonEpisodes[0].Episode
		} else if len(parsed.AbsoluteEpisodes) == 1 {
			episode = parsed.AbsoluteEpisodes[0]
		} else {
			continue
		}
		if episode < 1 || episode > 9999 {
			continue
		}
		records = append(records, episodeRecord{title: parsed.Title, year: parsed.Year, season: season, episode: episode})
	}
	if len(records) < 2 {
		return nil
	}
	counts := make(map[string]int, len(records))
	for _, record := range records {
		counts[record.title]++
	}
	baseline := ""
	best := 0
	for _, record := range records {
		if counts[record.title] > best {
			baseline, best = record.title, counts[record.title]
		}
	}
	directoryMatches := make([]episodeRecord, 0, len(records))
	for _, record := range records {
		if localSeriesTitleMatches(directory, title, record.title) {
			directoryMatches = append(directoryMatches, record)
		}
	}
	selected := directoryMatches
	if len(selected) < 2 {
		if best*2 <= len(records) {
			return nil
		}
		selected = selected[:0]
		for _, record := range records {
			if localSeriesFileTitlesMatch(baseline, record.title) {
				selected = append(selected, record)
			}
		}
	}
	if len(selected) < 2 {
		return nil
	}
	parsedDirectory := mediametadata.ParseDirectoryName(directory)
	year := parsedDirectory.Year
	season := localSeriesDirectorySeason(directory)
	numbers := make(map[int]struct{})
	for _, record := range selected {
		if season == 0 {
			season = record.season
		} else if record.season != 0 && season != record.season {
			return nil
		}
		if year == nil && record.year != nil {
			year = record.year
		} else if year != nil && record.year != nil && *year != *record.year {
			return nil
		}
		numbers[record.episode] = struct{}{}
	}
	if len(numbers) < 2 || len(numbers) > mediametadata.MaxEpisodeHints {
		return nil
	}
	if season == 0 {
		season = 1
	}
	ordered := make([]int, 0, len(numbers))
	for number := range numbers {
		ordered = append(ordered, number)
	}
	sort.Ints(ordered)
	return &medialibrary.DirectoryEpisodeEvidence{Title: title, Year: year, Season: season, Numbers: ordered}
}

func localSeriesDirectoryTitle(directory string) string {
	parsed := mediametadata.ParseDirectoryName(directory)
	if parsed.Title != "" && !strings.ContainsAny(parsed.Title, "[]【】") && !strings.Contains(strings.ToLower(parsed.Title), "www") {
		return strings.TrimSpace(parsed.Title)
	}
	cleaned := strings.TrimSpace(directory)
	for {
		var content string
		switch {
		case strings.HasPrefix(cleaned, "【"):
			end := strings.Index(cleaned, "】")
			if end < 0 {
				return ""
			}
			content = cleaned[len("【"):end]
			cleaned = strings.TrimSpace(cleaned[end+len("】"):])
		case strings.HasPrefix(cleaned, "["):
			end := strings.Index(cleaned, "]")
			if end < 0 {
				return ""
			}
			content = cleaned[1:end]
			cleaned = strings.TrimSpace(cleaned[end+len("]"):])
		default:
			content = ""
		}
		if content == "" {
			break
		}
		lower := strings.ToLower(content)
		if strings.Contains(lower, "www") || strings.Contains(lower, "http") || strings.Contains(content, "发布") ||
			strings.Contains(content, "首发") || strings.Contains(content, "高清") || strings.Contains(content, "影视") ||
			strings.Contains(lower, "bde4") || strings.Contains(lower, "bphd") || strings.Contains(lower, "ddhd") || strings.Contains(lower, "pthd") {
			continue
		}
		cleaned = content
		break
	}
	if index := strings.IndexAny(cleaned, ".[（("); index >= 0 {
		cleaned = cleaned[:index]
	}
	cleaned = strings.TrimSpace(strings.TrimPrefix(cleaned, "韩剧"))
	cleaned = strings.TrimSpace(localSeriesSeasonSuffixPattern.ReplaceAllString(cleaned, ""))
	return strings.Trim(cleaned, " -_")
}

func localSeriesFileTitlesMatch(left, right string) bool {
	if strings.TrimSpace(left) == "" || strings.TrimSpace(right) == "" {
		return false
	}
	if strings.Contains(left, right) || strings.Contains(right, left) {
		return true
	}
	leftAliases := mediametadata.TitleAliases(left)
	rightAliases := mediametadata.TitleAliases(right)
	for _, a := range leftAliases {
		for _, b := range rightAliases {
			if a == b {
				return true
			}
		}
	}
	return false
}

func localSeriesTitleMatches(directory, title, fileTitle string) bool {
	if strings.TrimSpace(fileTitle) == "" {
		return false
	}
	needles := []string{title}
	for _, alias := range mediametadata.TitleAliases(directory) {
		if len([]rune(strings.TrimSpace(alias))) >= 2 {
			needles = append(needles, strings.TrimSpace(alias))
		}
	}
	for _, needle := range needles {
		if needle != "" && strings.Contains(fileTitle, needle) {
			return true
		}
	}
	return false
}

func localSeriesDirectorySeason(directory string) int {
	if match := localSeriesSeasonPattern.FindStringSubmatch(directory); len(match) == 2 {
		if season, err := strconv.Atoi(match[1]); err == nil && season >= 0 && season <= 999 {
			return season
		}
	}
	if match := localSeriesChineseSeasonPattern.FindStringSubmatch(directory); len(match) == 2 {
		return localSeriesChineseSeason(match[1])
	}
	return 0
}

func localSeriesChineseSeason(value string) int {
	if season, err := strconv.Atoi(value); err == nil && season >= 0 && season <= 999 {
		return season
	}
	switch value {
	case "零", "〇":
		return 0
	case "一":
		return 1
	case "二", "两":
		return 2
	case "三":
		return 3
	case "四":
		return 4
	case "五":
		return 5
	case "六":
		return 6
	case "七":
		return 7
	case "八":
		return 8
	case "九":
		return 9
	case "十":
		return 10
	default:
		return 0
	}
}

func (w *walker) readDirectorySidecars(ctx context.Context, directory string, names []string, overflow bool, expected mediametadata.Kind) medialibrary.DirectorySidecar {
	if overflow {
		return medialibrary.DirectorySidecar{Status: "conflict"}
	}
	if len(names) == 0 {
		return medialibrary.DirectorySidecar{Status: "missing"}
	}
	sort.Strings(names)
	var chosen *medialibrary.MetadataObservation
	for _, name := range names {
		current, status := w.readContextNFO(ctx, appendLocator(directory, name))
		if status != "parsed" {
			return medialibrary.DirectorySidecar{Status: status}
		}
		if len(current.Document.Items) != 1 || current.Document.Items[0].Kind != expected {
			return medialibrary.DirectorySidecar{Status: "invalid"}
		}
		if chosen != nil && !bytes.Equal(chosen.Digest, current.Digest) {
			return medialibrary.DirectorySidecar{Status: "conflict"}
		}
		if chosen == nil {
			chosen = current
		}
	}
	return medialibrary.DirectorySidecar{Status: "parsed", NFO: chosen}
}

// readContextNFO uses the same safe opener, limits, digest and before/after
// identity checks as a file sidecar. No caller may persist a partial read.
func (w *walker) readContextNFO(ctx context.Context, locator string) (*medialibrary.MetadataObservation, string) {
	resource, err := w.source.Open(ctx, locator, false)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, "changed"
		}
		return nil, "unavailable"
	}
	info, stamp, err := resource.State()
	if err != nil || info == nil || resource.File == nil || !info.Mode().IsRegular() || files.IsPrivateMediaFile(info) {
		_ = resource.Close()
		return nil, "unavailable"
	}
	digest := sha256.New()
	document, parseErr := mediametadata.ParseNFO(contextReader{ctx: ctx, reader: io.TeeReader(resource.File, digest)}, mediametadata.Limits{})
	held, heldStamp, stateErr := resource.State()
	current, openErr := w.source.Open(ctx, locator, false)
	if stateErr != nil || openErr != nil {
		_ = resource.Close()
		if current != nil {
			_ = current.Close()
		}
		if errors.Is(openErr, fs.ErrNotExist) {
			return nil, "changed"
		}
		return nil, "unavailable"
	}
	now, nowStamp, stateErr := current.State()
	closeErr := errors.Join(current.Close(), resource.Close())
	if stateErr != nil || closeErr != nil || now == nil || held == nil || files.IsPrivateMediaFile(now) || files.IsPrivateMediaFile(held) {
		return nil, "unavailable"
	}
	if !os.SameFile(info, now) || !os.SameFile(info, held) || stamp != heldStamp || stamp != nowStamp {
		return nil, "changed"
	}
	if parseErr != nil {
		return nil, "invalid"
	}
	return &medialibrary.MetadataObservation{SourceKey: absoluteLocator(w.binding.RootLocator, locator), Digest: digest.Sum(nil), ParserVersion: 1, Document: *document}, "parsed"
}
