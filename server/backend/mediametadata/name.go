package mediametadata

import (
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const (
	MaxNameBytes    = 4096
	MaxEpisodeHints = 128
)

// ParsedName contains filename and optional parent-directory evidence. No field
// proves a work identity, file content, timeline, HDR output, or a provider's
// actual record. Ambiguities require resolution before automatic matching.
// OriginalName is never changed.
type ParsedName struct {
	OriginalName     string                 `json:"originalName"`
	Title            string                 `json:"title"`
	Year             *int                   `json:"year,omitempty"`
	Directory        *DirectoryNameEvidence `json:"directory,omitempty"`
	TypeHint         string                 `json:"typeHint"`
	ProviderIDs      []NameProviderID       `json:"providerIds,omitempty"`
	SeasonEpisodes   []SeasonEpisode        `json:"seasonEpisodes,omitempty"`
	AbsoluteEpisodes []int                  `json:"absoluteEpisodes,omitempty"`
	DateHint         string                 `json:"dateHint,omitempty"`
	Parts            []int                  `json:"parts,omitempty"`
	PartLabels       []string               `json:"partLabels,omitempty"`
	Editions         []string               `json:"editions,omitempty"`
	TechnicalHints   map[string][]string    `json:"technicalHints,omitempty"`
	Evidence         []NameEvidence         `json:"evidence,omitempty"`
	Ambiguities      []string               `json:"ambiguities,omitempty"`
}

type DirectoryNameEvidence struct {
	OriginalName         string   `json:"originalName"`
	Title                string   `json:"title"`
	Year                 *int     `json:"year,omitempty"`
	CompleteEpisodeCount *int     `json:"completeEpisodeCount,omitempty"`
	Ambiguities          []string `json:"ambiguities,omitempty"`
}

type NameProviderID struct {
	Provider  string `json:"provider"`
	MediaType string `json:"mediaType,omitempty"`
	ID        string `json:"id"`
}

type SeasonEpisode struct {
	Season  int `json:"season"`
	Episode int `json:"episode"`
}

// Start and End are UTF-8 byte offsets in OriginalName (End exclusive). Raw
// always equals OriginalName[Start:End]. Value is a normalized hint, not proof.
type NameEvidence struct {
	Kind  string `json:"kind"`
	Raw   string `json:"raw"`
	Value string `json:"value"`
	Start int    `json:"start"`
	End   int    `json:"end"`
}

type nameParser struct {
	result      ParsedName
	stem        string
	removed     []bool
	movieHint   bool
	episodeHint bool
}

var (
	providerNamePattern    = regexp.MustCompile(`(?i)(tmdb|imdb)(?:id)?[ ._:=\-]+(?:(movie|tv)[ ._:=\-]+)?(tt[0-9]+|[0-9]+)`)
	seasonEpisodePattern   = regexp.MustCompile(`(?i)s([0-9]{1,3})[ ._]*e([0-9]{1,4})`)
	episodeContinuation    = regexp.MustCompile(`(?i)^[ ._]*e([0-9]{1,4})`)
	episodeRange           = regexp.MustCompile(`(?i)^[ \t]*[-–][ \t]*e?([0-9]{1,4})`)
	fullSeasonRange        = regexp.MustCompile(`(?i)^[ \t]*[-–][ \t]*s([0-9]{1,3})[ ._]*e([0-9]{1,4})`)
	fullXRange             = regexp.MustCompile(`(?i)^[ \t]*[-–][ \t]*([0-9]{1,3})x([0-9]{1,4})`)
	xEpisodePattern        = regexp.MustCompile(`(?i)([0-9]{1,3})x([0-9]{1,4})`)
	chineseEpisodePattern  = regexp.MustCompile(`第([零〇一二两三四五六七八九十百千0-9]+)季[ ._\-]*第?([零〇一二两三四五六七八九十百千0-9]+)(?:[至到\-–]第?([零〇一二两三四五六七八九十百千0-9]+))?[集话話]`)
	chineseAbsolutePattern = regexp.MustCompile(`第([零〇一二两三四五六七八九十百千0-9]+)(?:[至到\-–]第?([零〇一二两三四五六七八九十百千0-9]+))?[集话話]`)
	absolutePattern        = regexp.MustCompile(`(?i)(?:ep?([0-9]{1,4})|[ \t]+-[ \t]+([0-9]{1,4}))(?:v[0-9]+)?`)
	absoluteRangePattern   = regexp.MustCompile(`(?i)\bep?([0-9]{1,4})[ ._]*[-–][ ._]*e?p?([0-9]{1,4})\b`)
	datePattern            = regexp.MustCompile(`((?:19|20|21)[0-9]{2})[.\-_]([0-9]{2})[.\-_]([0-9]{2})`)
	compactDatePattern     = regexp.MustCompile(`((?:19|20|21)[0-9]{2})([0-9]{2})([0-9]{2})`)
	bracketYearPattern     = regexp.MustCompile(`[\[(（【]((?:18|19|20|21)[0-9]{2})[\])）】]`)
	yearPattern            = regexp.MustCompile(`(?:18|19|20|21)[0-9]{2}`)
	directoryYearRange     = regexp.MustCompile(`(?:18|19|20|21)[0-9]{2}[ ._]*[-–][ ._]*(?:18|19|20|21)[0-9]{2}`)
	completeSeasonPattern  = regexp.MustCompile(`(?i)([0-9]{1,3})[ ._]*集全`)
	seriesDirectoryPattern = regexp.MustCompile(`^(?:韩剧|剧集|电视剧)[【\[]([^】\]]+)[】\]]`)
	animeTitlePattern      = regexp.MustCompile(`(?i)^\[CoolComic404\]\[([^\]]+)\]`)
	partPattern            = regexp.MustCompile(`(?i)(?:cd|disc|part)[ ._\-]*([0-9]{1,2}|VIII|VII|VI|IV|III|II|IX|X|V|I)`)
	chinesePartPattern     = regexp.MustCompile(`[上中下][部集篇]`)
	editionPattern         = regexp.MustCompile(`(?i)director'?s[ ._\-]+cut|extended(?:[ ._\-]+cut)?|theatrical[ ._\-]+cut|final[ ._\-]+cut|unrated|remastered|导演剪辑(?:加长版|版)?|導演剪輯版|加长版|加長版|院线版|院線版|未分级(?:加长版|版)?|未分級版|修复版|修復版|无删减版|無刪減版|完整版|[0-9]{1,3}分钟|终极剪辑版|終極剪輯版`)
	specialPattern         = regexp.MustCompile(`(?i)SP|OVA|OAD`)
	emptyNameBrackets      = regexp.MustCompile(`\([ \t,，.\-–_]*\)|\[[ \t,，.\-–_]*\]|\{[ \t,，.\-–_]*\}|【[ \t,，.\-–_]*】|（[ \t,，.\-–_]*）`)
)

type technicalRule struct {
	kind    string
	pattern *regexp.Regexp
}

var technicalNameRules = []technicalRule{
	{"release_tag", regexp.MustCompile(`(?i)(?:\[[^\]\r\n]*(?:满屏版|杜比视界|帧率版本|简繁英字幕|国语音轨|多音轨|合集)[^\]\r\n]*\]|【[^】\r\n]*(?:满屏版|杜比视界|帧率版本|简繁英字幕|国语音轨|多音轨|合集)[^】\r\n]*】)`)},
	{"collection", regexp.MustCompile(`(?i)\b[0-9]+in1\b`)},
	{"container_tag", regexp.MustCompile(`(?i)\[(?:MKV|MP4|M4V|AVI|MOV|WEBM|TS|M2TS|MPG|MPEG|WMV|FLV|VOB|ISO)\]`)},
	{"resolution", regexp.MustCompile(`(?i)BD(?:1080[pi]|2160p|720p)|HD1080[pi]|4320p|2160p|1440p|1080[pi]|720p|576p|480p|8k|4k`)},
	{"video_codec", regexp.MustCompile(`(?i)x26[45]|h[ .]?26[45]|hevc|avc|av1|xvid`)},
	{"dynamic_range", regexp.MustCompile(`(?i)dolby[ ._\-]+vision|hdr10\+|hdr10|hdr|hlg|DoVi|dv|\bSDR\b`)},
	{"source", regexp.MustCompile(`(?i)web[ ._\-]?dl|webrip|blu[ ._\-]?ray|bdrip|brrip|remux|hdtv|dvdrip|\bDVD\b|DSNP|iTunes|\bIQ\b|\b(?:FRA|TC|EC|DC)\b|\bBD(?:[ ._-]*[1-9])?\b|蓝光(?:版)?`)},
	{"audio_channels", regexp.MustCompile(`(?i)\b(?:ac3|dts|aac|ddp|e[ ._\-]?ac3)[ ._\-]*[0-9]+(?:[ ._][0-9]+)?[ ._\-]*ch\b`)},
	{"audio_layout", regexp.MustCompile(`(?i)[0-9][ ._.][01]`)},
	{"audio_codec", regexp.MustCompile(`(?i)dts[ ._\-]?hd[ ._\-]?ma|truehd|ddp|dd|e[ ._\-]?ac3|ac3|aac|dts|atmos|flac|opus|pcm`)},
	{"bit_depth", regexp.MustCompile(`(?i)(?:8|10|12)[ ._\-]?bit`)},
	{"edition", regexp.MustCompile(`(?i)\b(?:IMAX|UHD|CC)\b`)},
	{"audio_variant", regexp.MustCompile(`(?i)\b(?:AAC[0-9]|[1-9]Audios?)\b`)},
	{"frame_rate", regexp.MustCompile(`(?i)\b[0-9]{2,3}fps\b`)},
	{"version", regexp.MustCompile(`(?i)\bV[0-9]+\b`)},
	{"language", regexp.MustCompile(`(?i)Mandarin&English|English&Mandarin|CHS[ ._-]?ENG|\b(?:English|Mandarin|CHS|JP|CN|USA|JPN|GER|CEE|Japanese)\b`)},
	{"subtitle", regexp.MustCompile(`国英双语|中英双字|中英字幕|中英特效|特效字幕|国语中字|粤语中字|日语中字|韩语中字|韓語中字|法语中字|英语中字|国日粤三语|泰国粤三语|中日双字|简繁双语|简繁字幕|双语中字|中文字幕|内嵌特效中简|特效|中字`)},
	{"release_noise", regexp.MustCompile(`(?i)\b[0-9]{1,3}(?:st|nd|rd|th)[ ._\-]+anniversary\b|无水印`)},
	{"release_group", regexp.MustCompile(`(?i)(?:\b(?:MNHD-FRDS|RARBG|HDBWEB|DreamHD|ParkHD|BBQDDQ|CTRLTV|FRDS|MTeam|WiKi|PTer|HDC|HDS|D-Z0N3|DiaosMan|SSDSSE|NukeHD|SONYHD|882FX|taobaobt|Mp4Ba(?:Fans)?|FFans|KOOK|UUMp4|D2Mp4|99Mp4|4KHDR|52movieba|TAG|CHD|MiniFHD|CoolComic404|Waf)\b|人人影视|BT世界网|抠脚)`)},
	{"release_group", regexp.MustCompile(`(?i)\bXiaomi(?:\([0-9]+\))?|\bLelveTV\b|\bXLYS\b`)},
	{"market_tag", regexp.MustCompile(`(?i)\[(?:泰国|港版|日本|美国|韩国|剧情|喜剧|动作|爱情|科幻|悬疑|犯罪)(?:[ ._-]+(?:泰国|港版|日本|美国|韩国|剧情|喜剧|动作|爱情|科幻|悬疑|犯罪))*\]`)},
	{"promotional_prefix", regexp.MustCompile(`(?i)^(?:(?:福利电影|魅力社)[0-9A-Za-z ._\-]*com[ ._\-]+|(?:【[^】]*(?:无水印|请访问|www[ ._]|https?://)[^】]*】|\[[^\]]*(?:www[ ._]|https?://)[^\]]*\])+)`)},
	{"site_prefix", regexp.MustCompile(`(?i)^(?:【[^】]*(?:www[ ._]|https?://)[^】]*】|\[[^\]]*(?:www[ ._]|https?://)[^\]]*\])`)},
	{"site_tag", regexp.MustCompile(`(?i)(?:【[^】]*(?:www[ ._]|https?://|微信公众号)[^】]*】|\[[^\]]*(?:www[ ._]|https?://)[^\]]*\])`)},
	{"promo_suffix", regexp.MustCompile(`(?i)可以加(?:115|qq|微信)[ ._]*交流群`)},
}

// ParseName parses a single filename, not a filesystem path or URL. It uses
// deterministic, bounded rules, no filesystem, network, clock or catalog.
// Untyped TMDB IDs do not become movie IDs; absolute/date numbering does not
// create a season. TechnicalHints must never be used as probed capabilities.
func ParseName(filename string) ParsedName {
	p := nameParser{result: ParsedName{OriginalName: filename, TypeHint: "unknown"}}
	if len(filename) > MaxNameBytes {
		p.ambiguous("name_too_long")
		return p.result
	}
	if !utf8.ValidString(filename) || strings.ContainsRune(filename, 0) {
		p.ambiguous("invalid_filename")
		return p.result
	}
	if strings.ContainsAny(filename, `/\`) {
		p.ambiguous("path_not_filename")
		return p.result
	}
	p.stem = filename
	for {
		dot := strings.LastIndexByte(p.stem, '.')
		if dot < 0 {
			break
		}
		switch strings.ToLower(p.stem[dot:]) {
		case ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm", ".ts", ".m2ts", ".mpg", ".mpeg", ".wmv", ".flv", ".vob", ".iso", ".strm":
			p.stem = p.stem[:dot]
		default:
			dot = -1
		}
		if dot < 0 {
			break
		}
	}
	p.removed = make([]bool, len(p.stem))
	p.parseProviderIDs()
	p.parseDates()
	p.parseEpisodes(seasonEpisodePattern)
	p.parseEpisodes(xEpisodePattern)
	p.parseChineseEpisodes()
	p.parseAbsoluteEpisodes()
	p.parseTechnicalHints()
	p.parseEditionsAndParts()
	p.parseYears()
	p.result.Title = p.title()
	if p.result.Title == "" {
		if len(p.result.TechnicalHints["promotional_prefix"]) != 0 {
			p.ambiguous("promotional_filename")
		} else if len(p.result.ProviderIDs) == 0 && len(p.result.SeasonEpisodes) == 0 && len(p.result.AbsoluteEpisodes) == 0 && p.result.DateHint == "" && p.result.Year == nil && (len(p.result.TechnicalHints) > 0 || len(p.result.Editions) > 0) {
			p.result.Title = cleanNameTitle(p.stem)
			p.ambiguous("metadata_or_title")
		} else {
			p.ambiguous("missing_title")
		}
	}
	if p.movieHint && p.episodeHint {
		p.ambiguous("type_conflict")
	} else if p.hasAmbiguity("provider_entity_conflict") {
		p.result.TypeHint = "unknown"
	} else if p.episodeHint {
		p.result.TypeHint = "episode"
	} else if p.movieHint {
		p.result.TypeHint = "movie"
	}
	if len(p.result.SeasonEpisodes) > 1 {
		p.ambiguous("combined_episode_file")
	}
	if len(p.result.AbsoluteEpisodes) > 0 && len(p.result.SeasonEpisodes) > 0 {
		p.ambiguous("numbering_scheme_conflict")
	}
	sort.SliceStable(p.result.Evidence, func(i, j int) bool { return p.result.Evidence[i].Start < p.result.Evidence[j].Start })
	sort.Strings(p.result.Ambiguities)
	return p.result
}

// ParseDirectoryName extracts only explicit directory evidence. A declared
// complete episode count is structural context, not proof of a provider work.
func ParseDirectoryName(directory string) DirectoryNameEvidence {
	if directory == "" || directory == "." || directory == ".." || strings.ContainsAny(directory, `/\\`) {
		return DirectoryNameEvidence{OriginalName: directory, Ambiguities: []string{"invalid_directory"}}
	}
	parsed := ParseName(directory)
	title := parsed.Title
	if match := seriesDirectoryPattern.FindStringSubmatch(directory); len(match) == 2 {
		title = cleanNameTitle(match[1])
	}
	year := parsed.Year
	if directoryYearRange.MatchString(directory) {
		year = nil
		parsed.Ambiguities = append(parsed.Ambiguities, "directory_multiple_years")
	} else if year == nil {
		var years []int
		for _, match := range yearPattern.FindAllStringIndex(directory, -1) {
			if !tokenBounds(directory, match[0], match[1]) {
				continue
			}
			value, _ := strconv.Atoi(directory[match[0]:match[1]])
			years = appendUniqueInt(years, value)
		}
		if len(years) > 1 {
			parsed.Ambiguities = append(parsed.Ambiguities, "directory_multiple_years")
		} else if len(years) == 1 {
			match := yearPattern.FindStringIndex(directory)
			if match != nil && cleanNameTitle(directory[:match[0]]) != "" {
				year = &years[0]
			}
		}
	}
	var complete *int
	if !seasonEpisodePattern.MatchString(directory) && !xEpisodePattern.MatchString(directory) && !chineseEpisodePattern.MatchString(directory) {
		for _, match := range completeSeasonPattern.FindAllStringSubmatchIndex(directory, -1) {
			if !leftTokenBound(directory, match[0]) || !rightTokenBound(directory, match[1]) {
				continue
			}
			count, _ := strconv.Atoi(directory[match[2]:match[3]])
			if count >= 1 && count <= MaxEpisodeHints {
				if complete != nil && *complete != count {
					parsed.Ambiguities = append(parsed.Ambiguities, "directory_conflicting_episode_counts")
					complete = nil
					break
				}
				complete = &count
			}
		}
	}
	return DirectoryNameEvidence{OriginalName: directory, Title: title, Year: year, CompleteEpisodeCount: complete,
		Ambiguities: append([]string(nil), parsed.Ambiguities...)}
}

// ParseNameInDirectory keeps the immediate parent name as separate evidence.
// Directory values never overwrite filename values or resolve a conflict.
func ParseNameInDirectory(filename, directory string) ParsedName {
	result := ParseName(filename)
	if directory == "" || directory == "." || directory == ".." || strings.ContainsAny(directory, `/\\`) {
		return result
	}
	parsed := ParseDirectoryName(directory)
	result.Directory = &parsed
	if result.Year != nil && parsed.Year != nil && *result.Year != *parsed.Year {
		result.Ambiguities = append(result.Ambiguities, "directory_year_conflict")
	}
	if result.Title == "" && slices.Contains(result.Ambiguities, "promotional_filename") && parsed.Title != "" && parsed.Year != nil && len(parsed.Ambiguities) == 0 {
		result.Ambiguities = slices.DeleteFunc(result.Ambiguities, func(issue string) bool { return issue == "promotional_filename" })
	}
	if len(parsed.Ambiguities) != 0 && result.Year == nil {
		result.Ambiguities = append(result.Ambiguities, "directory_ambiguous_evidence")
	}
	if parsed.CompleteEpisodeCount != nil && len(result.AbsoluteEpisodes) == 1 && len(result.SeasonEpisodes) == 0 &&
		result.AbsoluteEpisodes[0] >= 1 && result.AbsoluteEpisodes[0] <= *parsed.CompleteEpisodeCount &&
		cleanNameTitle(result.Title) == cleanNameTitle(parsed.Title) && !slices.Contains(result.Ambiguities, "numbering_scheme_conflict") {
		episode := result.AbsoluteEpisodes[0]
		result.SeasonEpisodes = []SeasonEpisode{{Season: 1, Episode: episode}}
		result.AbsoluteEpisodes = nil
		result.TypeHint = "episode"
		result.Ambiguities = slices.DeleteFunc(result.Ambiguities, func(issue string) bool { return issue == "absolute_episode_requires_mapping" })
	}
	sort.Strings(result.Ambiguities)
	return result
}

func (p *nameParser) parseProviderIDs() {
	for _, match := range providerNamePattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) || !tokenBounds(p.stem, match[0], match[1]) {
			continue
		}
		provider := strings.ToLower(p.stem[match[2]:match[3]])
		mediaType := ""
		if match[4] >= 0 {
			mediaType = strings.ToLower(p.stem[match[4]:match[5]])
		}
		id := strings.ToLower(p.stem[match[6]:match[7]])
		if provider == "imdb" && (!strings.HasPrefix(id, "tt") || len(id) < 9 || len(id) > 12) || provider == "tmdb" && (strings.HasPrefix(id, "tt") || strings.TrimLeft(id, "0") == "") {
			p.record("invalid_provider_id", match[0], match[1], provider, false)
			p.ambiguous("invalid_provider_id")
			continue
		}
		for _, prior := range p.result.ProviderIDs {
			if prior.Provider == provider && prior.MediaType != "" && mediaType != "" && prior.MediaType != mediaType {
				p.ambiguous("provider_entity_conflict")
			}
			if prior.Provider == provider && (prior.MediaType == mediaType || prior.MediaType == "" || mediaType == "") && prior.ID != id {
				p.ambiguous("conflicting_provider_ids")
			}
		}
		p.result.ProviderIDs = append(p.result.ProviderIDs, NameProviderID{provider, mediaType, id})
		p.movieHint = p.movieHint || mediaType == "movie"
		p.record("provider_id", match[0], match[1], provider+":"+mediaType+":"+id, true)
	}
}

func (p *nameParser) parseDates() {
	for _, pattern := range []*regexp.Regexp{datePattern, compactDatePattern} {
		for _, match := range pattern.FindAllStringSubmatchIndex(p.stem, -1) {
			if !p.available(match[0], match[1]) || !tokenBounds(p.stem, match[0], match[1]) {
				continue
			}
			end := match[1]
			if strings.HasPrefix(p.stem[end:], "期") {
				end += len("期")
			}
			date := p.stem[match[2]:match[3]] + "-" + p.stem[match[4]:match[5]] + "-" + p.stem[match[6]:match[7]]
			if _, err := time.Parse("2006-01-02", date); err != nil {
				p.record("invalid_date", match[0], end, date, true)
				p.ambiguous("invalid_date")
				continue
			}
			if p.result.DateHint != "" && p.result.DateHint != date {
				p.ambiguous("conflicting_dates")
			}
			p.result.DateHint = date
			p.record("date", match[0], end, date, true)
			p.ambiguous("date_requires_context")
		}
	}
	if p.hasAmbiguity("conflicting_dates") {
		p.result.DateHint = ""
	}
}

func (p *nameParser) parseEpisodes(pattern *regexp.Regexp) {
	for _, match := range pattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) || !leftTokenBound(p.stem, match[0]) {
			continue
		}
		season, _ := strconv.Atoi(p.stem[match[2]:match[3]])
		first, _ := strconv.Atoi(p.stem[match[4]:match[5]])
		end := match[1]
		episodes := []int{first}
		invalid := false
		for {
			rest := p.stem[end:]
			extra := episodeContinuation.FindStringSubmatchIndex(rest)
			isRange := false
			lastGroup := 2
			if extra == nil {
				extra = fullSeasonRange.FindStringSubmatchIndex(rest)
				if extra == nil {
					extra = fullXRange.FindStringSubmatchIndex(rest)
				}
				if extra != nil {
					otherSeason, _ := strconv.Atoi(rest[extra[2]:extra[3]])
					lastGroup = 4
					if otherSeason != season && p.available(end, end+extra[1]) && rightEpisodeBound(p.stem, end+extra[1]) {
						invalid = true
						p.ambiguous("cross_season_range")
					}
				} else {
					extra = episodeRange.FindStringSubmatchIndex(rest)
				}
				isRange = extra != nil
			}
			if extra == nil || !p.available(end, end+extra[1]) {
				break
			}
			candidateEnd := end + extra[1]
			if !rightEpisodeBound(p.stem, candidateEnd) {
				break
			}
			last, _ := strconv.Atoi(rest[extra[lastGroup]:extra[lastGroup+1]])
			if isRange {
				start := episodes[len(episodes)-1]
				if last < start || last-start+len(episodes) > MaxEpisodeHints {
					invalid = true
				} else {
					for number := start + 1; number <= last; number++ {
						episodes = append(episodes, number)
					}
				}
			} else {
				episodes = append(episodes, last)
			}
			end = candidateEnd
			if len(episodes) > MaxEpisodeHints {
				invalid = true
				break
			}
		}
		if !tokenBounds(p.stem, match[0], end) {
			continue
		}
		if invalid {
			p.record("invalid_episode_range", match[0], end, "", true)
			p.ambiguous("invalid_episode_range")
			p.episodeHint = true
			continue
		}
		p.addSeasonEpisodes(season, episodes)
		p.record("season_episode", match[0], end, strconv.Itoa(season)+":"+joinNumbers(episodes), true)
	}
}

func (p *nameParser) parseChineseEpisodes() {
	for _, match := range chineseEpisodePattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) {
			continue
		}
		season, validSeason := chineseNumber(p.stem[match[2]:match[3]])
		first, validFirst := chineseNumber(p.stem[match[4]:match[5]])
		last := first
		validLast := true
		if match[6] >= 0 {
			last, validLast = chineseNumber(p.stem[match[6]:match[7]])
		}
		if !validSeason || !validFirst || !validLast || season > 999 || last < first || last-first+1 > MaxEpisodeHints {
			p.record("invalid_episode_range", match[0], match[1], "", true)
			p.ambiguous("invalid_episode_range")
			continue
		}
		var episodes []int
		for number := first; number <= last; number++ {
			episodes = append(episodes, number)
		}
		p.addSeasonEpisodes(season, episodes)
		p.record("season_episode", match[0], match[1], strconv.Itoa(season)+":"+joinNumbers(episodes), true)
	}
}

func (p *nameParser) addSeasonEpisodes(season int, episodes []int) {
	p.episodeHint = true
	for _, prior := range p.result.SeasonEpisodes {
		if prior.Season != season {
			p.ambiguous("multiple_seasons")
		}
	}
	for _, episode := range episodes {
		pair := SeasonEpisode{season, episode}
		found := false
		for _, prior := range p.result.SeasonEpisodes {
			if prior == pair {
				found = true
				break
			}
		}
		if !found {
			if len(p.result.SeasonEpisodes) >= MaxEpisodeHints {
				p.ambiguous("episode_hint_limit")
				break
			}
			p.result.SeasonEpisodes = append(p.result.SeasonEpisodes, pair)
		}
	}
}

func (p *nameParser) parseAbsoluteEpisodes() {
	for _, match := range chineseAbsolutePattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) {
			continue
		}
		first, ok := chineseNumber(p.stem[match[2]:match[3]])
		last, lastOK := first, true
		if match[4] >= 0 {
			last, lastOK = chineseNumber(p.stem[match[4]:match[5]])
		}
		if !ok || !lastOK || last < first || last-first+1 > MaxEpisodeHints {
			p.ambiguous("invalid_absolute_episode")
			continue
		}
		var numbers []int
		for number := first; number <= last; number++ {
			numbers = append(numbers, number)
		}
		p.addAbsolute(match[0], match[1], numbers)
	}
	for _, match := range absoluteRangePattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) {
			continue
		}
		first, _ := strconv.Atoi(p.stem[match[2]:match[3]])
		last, _ := strconv.Atoi(p.stem[match[4]:match[5]])
		if first < 1 || last < first || last-first+1 > MaxEpisodeHints {
			p.record("invalid_absolute_episode", match[0], match[1], "", true)
			p.ambiguous("invalid_absolute_episode")
			continue
		}
		numbers := make([]int, 0, last-first+1)
		for number := first; number <= last; number++ {
			numbers = append(numbers, number)
		}
		p.addAbsolute(match[0], match[1], numbers)
	}
	for _, match := range absolutePattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) || !rightTokenBound(p.stem, match[1]) || match[4] < 0 && !leftTokenBound(p.stem, match[0]) {
			continue
		}
		start, end := match[2], match[3]
		if start < 0 {
			start, end = match[4], match[5]
		}
		number, _ := strconv.Atoi(p.stem[start:end])
		p.addAbsolute(match[0], match[1], []int{number})
	}
	for _, match := range specialPattern.FindAllStringIndex(p.stem, -1) {
		if p.available(match[0], match[1]) && tokenBounds(p.stem, match[0], match[1]) {
			p.record("special", match[0], match[1], strings.ToUpper(p.stem[match[0]:match[1]]), false)
			if len(p.result.SeasonEpisodes) == 0 {
				p.ambiguous("special_requires_mapping")
			}
		}
	}
}

func (p *nameParser) addAbsolute(start, end int, numbers []int) {
	for _, number := range numbers {
		found := false
		for _, prior := range p.result.AbsoluteEpisodes {
			if prior == number {
				found = true
				break
			}
		}
		if !found {
			if len(p.result.AbsoluteEpisodes) >= MaxEpisodeHints {
				p.ambiguous("episode_hint_limit")
				break
			}
			p.result.AbsoluteEpisodes = append(p.result.AbsoluteEpisodes, number)
		}
	}
	p.record("absolute_episode", start, end, joinNumbers(numbers), true)
	p.ambiguous("absolute_episode_requires_mapping")
}

func (p *nameParser) parseEditionsAndParts() {
	for _, match := range editionPattern.FindAllStringIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) || !tokenBounds(p.stem, match[0], match[1]) {
			continue
		}
		raw := strings.ToLower(p.stem[match[0]:match[1]])
		edition := ""
		switch {
		case strings.Contains(raw, "director") || strings.Contains(raw, "导演") || strings.Contains(raw, "導演"):
			edition = "directors_cut"
		case strings.Contains(raw, "extended") || strings.Contains(raw, "加长") || strings.Contains(raw, "加長"):
			edition = "extended"
		case strings.Contains(raw, "theatrical") || strings.Contains(raw, "院线") || strings.Contains(raw, "院線"):
			edition = "theatrical"
		case strings.Contains(raw, "final"):
			edition = "final_cut"
		case strings.Contains(raw, "unrated") || strings.Contains(raw, "未分级") || strings.Contains(raw, "未分級"):
			edition = "unrated"
		default:
			edition = "remastered"
		}
		p.result.Editions = appendUnique(p.result.Editions, edition)
		p.record("edition", match[0], match[1], edition, true)
	}
	for _, match := range partPattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) || !tokenBounds(p.stem, match[0], match[1]) {
			continue
		}
		raw := strings.ToUpper(p.stem[match[2]:match[3]])
		marker := strings.ToLower(p.stem[match[0]:match[1]])
		number, err := strconv.Atoi(raw)
		if err != nil {
			number = map[string]int{"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5, "VI": 6, "VII": 7, "VIII": 8, "IX": 9, "X": 10}[raw]
		}
		if number == 0 {
			p.ambiguous("invalid_part")
			continue
		}
		p.result.Parts = appendUniqueInt(p.result.Parts, number)
		remove := len(p.result.TechnicalHints) != 0 && (strings.HasPrefix(marker, "cd") || strings.HasPrefix(marker, "disc"))
		p.record("part", match[0], match[1], strconv.Itoa(number), remove)
		p.ambiguous("part_requires_confirmation")
	}
	for _, match := range chinesePartPattern.FindAllStringIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) {
			continue
		}
		raw := p.stem[match[0]:match[1]]
		// 下 does not prove part 2: a work can have two or three parts.
		p.result.PartLabels = appendUnique(p.result.PartLabels, raw)
		p.record("part_label", match[0], match[1], raw, false)
		p.ambiguous("part_requires_confirmation")
	}
}

func (p *nameParser) parseTechnicalHints() {
	for _, match := range animeTitlePattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) {
			continue
		}
		title := strings.TrimSpace(p.stem[match[2]:match[3]])
		p.result.Evidence = append(p.result.Evidence, NameEvidence{"technical_anime_title", p.stem[match[0]:match[1]], title, match[0], match[1]})
		p.removed[match[2]-1] = true
		p.removed[match[3]] = true
	}
	for _, rule := range technicalNameRules {
		for _, match := range rule.pattern.FindAllStringIndex(p.stem, -1) {
			if !p.available(match[0], match[1]) || rule.kind != "release_tag" && rule.kind != "promotional_prefix" && rule.kind != "site_prefix" && rule.kind != "site_tag" && p.insideSiteTag(match[0]) {
				continue
			}
			if rule.kind == "audio_layout" {
				left := match[0]
				for left > 0 && p.removed[left-1] {
					left--
				}
				if left > 0 && asciiAlphanumeric(p.stem[left-1]) && !unicode.IsLetter(rune(p.stem[left-1])) || !p.rightTokenBound(match[1]) {
					continue
				}
			} else if rule.kind != "release_tag" && rule.kind != "promotional_prefix" && rule.kind != "site_prefix" && rule.kind != "site_tag" && (!p.leftTokenBound(match[0]) || !p.rightTokenBound(match[1])) {
				continue
			}
			value := strings.ToLower(p.stem[match[0]:match[1]])
			if p.result.TechnicalHints == nil {
				p.result.TechnicalHints = make(map[string][]string)
			}
			p.result.TechnicalHints[rule.kind] = appendUnique(p.result.TechnicalHints[rule.kind], value)
			p.record("technical_"+rule.kind, match[0], match[1], value, true)
		}
	}
}

func (p *nameParser) parseYears() {
	var years []int
	for _, match := range bracketYearPattern.FindAllStringSubmatchIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) || cleanNameTitle(p.stem[:match[0]]) == "" {
			continue
		}
		raw := p.stem[match[0]:match[1]]
		paired := strings.HasPrefix(raw, "(") && strings.HasSuffix(raw, ")") || strings.HasPrefix(raw, "[") && strings.HasSuffix(raw, "]") || strings.HasPrefix(raw, "（") && strings.HasSuffix(raw, "）") || strings.HasPrefix(raw, "【") && strings.HasSuffix(raw, "】")
		if !paired {
			continue
		}
		year, _ := strconv.Atoi(p.stem[match[2]:match[3]])
		years = appendUniqueInt(years, year)
		p.record("year", match[0], match[1], strconv.Itoa(year), true)
	}
	if len(years) > 1 {
		p.ambiguous("conflicting_years")
		return
	}
	if len(years) == 1 {
		p.result.Year = &years[0]
		return
	}
	if p.episodeHint || len(p.result.SeasonEpisodes) != 0 || len(p.result.AbsoluteEpisodes) != 0 {
		type yearCandidate struct {
			start, end, value int
		}
		var candidates []yearCandidate
		for _, match := range yearPattern.FindAllStringIndex(p.stem, -1) {
			if !p.available(match[0], match[1]) || !tokenBounds(p.stem, match[0], match[1]) || cleanNameTitle(p.unremoved(0, match[0])) == "" {
				continue
			}
			year, _ := strconv.Atoi(p.stem[match[0]:match[1]])
			candidates = append(candidates, yearCandidate{start: match[0], end: match[1], value: year})
		}
		if len(candidates) > 1 {
			p.ambiguous("conflicting_years")
			return
		}
		if len(candidates) == 1 {
			candidate := candidates[0]
			p.result.Year = &candidate.value
			p.record("year", candidate.start, candidate.end, strconv.Itoa(candidate.value), true)
			return
		}
	}
	// A trailing unbracketed year is explicitly ambiguous: it can be part of
	// a title (e.g. Blade Runner 2049). Never take a leading title number.
	for _, match := range yearPattern.FindAllStringIndex(p.stem, -1) {
		if !p.available(match[0], match[1]) || !tokenBounds(p.stem, match[0], match[1]) || cleanNameTitle(p.unremoved(0, match[0])) == "" || cleanNameTitle(p.unremoved(match[1], len(p.stem))) != "" {
			continue
		}
		year, _ := strconv.Atoi(p.stem[match[0]:match[1]])
		p.result.Year = &year
		p.record("year_candidate", match[0], match[1], strconv.Itoa(year), true)
		if len(p.result.TechnicalHints) == 0 && len(p.result.Editions) == 0 {
			p.ambiguous("unbracketed_year")
		}
		break
	}
}

func (p *nameParser) insideSiteTag(offset int) bool {
	start := strings.LastIndexAny(p.stem[:offset+1], "[【（(")
	if start < 0 {
		return false
	}
	end := strings.IndexAny(p.stem[offset:], "]】）)")
	if end < 0 {
		return false
	}
	tag := strings.ToLower(p.stem[start : offset+end+1])
	return strings.Contains(tag, "www.") || strings.Contains(tag, "www ") || strings.Contains(tag, "www_") ||
		strings.Contains(tag, "http://") || strings.Contains(tag, "https://")
}

func (p *nameParser) leftTokenBound(start int) bool {
	for start > 0 && p.removed[start-1] {
		start--
	}
	return start == 0 || !asciiAlphanumeric(p.stem[start-1])
}
func (p *nameParser) rightTokenBound(end int) bool {
	for end < len(p.stem) && p.removed[end] {
		end++
	}
	return end == len(p.stem) || !asciiAlphanumeric(p.stem[end])
}
func (p *nameParser) available(start, end int) bool {
	for index := start; index < end; index++ {
		if p.removed[index] {
			return false
		}
	}
	return true
}

func (p *nameParser) record(kind string, start, end int, value string, remove bool) {
	p.result.Evidence = append(p.result.Evidence, NameEvidence{kind, p.stem[start:end], value, start, end})
	if remove {
		for index := start; index < end; index++ {
			p.removed[index] = true
		}
	}
}

func (p *nameParser) ambiguous(code string) {
	p.result.Ambiguities = appendUnique(p.result.Ambiguities, code)
}
func (p *nameParser) hasAmbiguity(code string) bool {
	for _, item := range p.result.Ambiguities {
		if item == code {
			return true
		}
	}
	return false
}
func (p *nameParser) unremoved(start, end int) string {
	text := []byte(p.stem[start:end])
	for index := range text {
		if p.removed[start+index] {
			text[index] = ' '
		}
	}
	return string(text)
}
func (p *nameParser) title() string { return cleanNameTitle(p.unremoved(0, len(p.stem))) }

func TitleAliases(value string) []string {
	var aliases []string
	var current []rune
	script := 0
	flush := func() {
		if len(current) == 0 {
			return
		}
		title := ParseName(string(current) + ".mkv").Title
		if title == "" {
			title = cleanNameTitle(string(current))
		}
		if utf8.RuneCountInString(title) >= 2 {
			aliases = appendUnique(aliases, title)
		}
		current = nil
	}
	for _, r := range value {
		next := 0
		if unicode.In(r, unicode.Han, unicode.Hiragana, unicode.Katakana, unicode.Hangul) {
			next = 1
		} else if unicode.IsLetter(r) {
			next = 2
		}
		if next != 0 && script != 0 && next != script {
			flush()
		}
		if next != 0 {
			script = next
		}
		current = append(current, r)
	}
	flush()
	if len(aliases) < 2 {
		return nil
	}
	return aliases
}

func cleanNameTitle(value string) string {
	value = strings.NewReplacer(".", " ", "_", " ").Replace(value)
	for {
		next := emptyNameBrackets.ReplaceAllString(value, " ")
		if next == value {
			break
		}
		value = next
	}
	return strings.Join(strings.Fields(strings.Trim(value, " \t\r\n.-_–&@")), " ")
}

func tokenBounds(value string, start, end int) bool {
	return leftTokenBound(value, start) && rightTokenBound(value, end)
}
func leftTokenBound(value string, start int) bool {
	return start == 0 || !asciiAlphanumeric(value[start-1])
}
func rightTokenBound(value string, end int) bool {
	return end == len(value) || !asciiAlphanumeric(value[end])
}
func rightEpisodeBound(value string, end int) bool {
	return rightTokenBound(value, end) || value[end] == 'e' || value[end] == 'E'
}
func asciiAlphanumeric(value byte) bool {
	return value >= '0' && value <= '9' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
}
func appendUnique(values []string, value string) []string {
	for _, prior := range values {
		if prior == value {
			return values
		}
	}
	return append(values, value)
}
func appendUniqueInt(values []int, value int) []int {
	for _, prior := range values {
		if prior == value {
			return values
		}
	}
	return append(values, value)
}
func joinNumbers(values []int) string {
	text := make([]string, len(values))
	for index, value := range values {
		text[index] = strconv.Itoa(value)
	}
	return strings.Join(text, ",")
}

func chineseNumber(raw string) (int, bool) {
	if value, err := strconv.Atoi(raw); err == nil {
		return value, value >= 0 && value <= 9999
	}
	digits := map[rune]int{'零': 0, '〇': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9}
	units := map[rune]int{'十': 10, '百': 100, '千': 1000}
	if !strings.ContainsAny(raw, "十百千") {
		value := 0
		for _, symbol := range raw {
			digit, ok := digits[symbol]
			if !ok {
				return 0, false
			}
			value = value*10 + digit
			if value > 9999 {
				return 0, false
			}
		}
		return value, raw != ""
	}
	total, number, lastUnit := 0, 0, 10000
	haveNumber := false
	zeroAfterUnit := false
	for _, symbol := range raw {
		if digit, ok := digits[symbol]; ok {
			if haveNumber && number != 0 {
				return 0, false
			}
			number, haveNumber = digit, true
			if digit == 0 && lastUnit < 10000 {
				zeroAfterUnit = true
			}
			continue
		}
		unit, ok := units[symbol]
		if !ok || unit >= lastUnit {
			return 0, false
		}
		if haveNumber && number == 0 {
			return 0, false
		}
		if !haveNumber {
			if unit != 10 || total != 0 {
				return 0, false
			}
			number = 1
		}
		total += number * unit
		number, haveNumber, lastUnit = 0, false, unit
		zeroAfterUnit = false
	}
	if lastUnit >= 100 && number > 0 && !zeroAfterUnit {
		return 0, false
	}
	return total + number, total+number <= 9999
}
