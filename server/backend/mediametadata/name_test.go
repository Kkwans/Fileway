package mediametadata

import (
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"slices"
	"strings"
	"testing"
)

func TestAnnotatedNameCorpus(t *testing.T) {
	data, err := os.ReadFile("testdata/names.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Description string `json:"description"`
		Cases       []struct {
			ID        string         `json:"id"`
			Category  string         `json:"category"`
			Rationale string         `json:"rationale"`
			Filename  string         `json:"filename"`
			Expected  map[string]any `json:"expected"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	if len(corpus.Cases) < 300 {
		t.Fatalf("only %d labeled cases", len(corpus.Cases))
	}
	ids, filenames, categories := map[string]bool{}, map[string]bool{}, map[string]int{}
	ambiguous := 0
	for _, fixture := range corpus.Cases {
		if fixture.ID == "" || fixture.Category == "" || fixture.Rationale == "" || ids[fixture.ID] || filenames[fixture.Filename] {
			t.Fatalf("missing/repeated fixture annotation: %#v", fixture)
		}
		ids[fixture.ID], filenames[fixture.Filename] = true, true
		categories[fixture.Category]++
		if _, ok := fixture.Expected["ambiguities"]; ok {
			ambiguous++
		}
		t.Run(fixture.ID, func(t *testing.T) {
			parsed := ParseName(fixture.Filename)
			validateNameEvidence(t, fixture.Filename, parsed)
			encoded, err := json.Marshal(parsed)
			if err != nil {
				t.Fatal(err)
			}
			var actual map[string]any
			if err := json.Unmarshal(encoded, &actual); err != nil {
				t.Fatal(err)
			}
			delete(actual, "originalName")
			delete(actual, "evidence")
			if !reflect.DeepEqual(actual, fixture.Expected) {
				want, _ := json.Marshal(fixture.Expected)
				got, _ := json.Marshal(actual)
				t.Errorf("%s\nfilename: %s\ngot:  %s\nwant: %s", fixture.Rationale, fixture.Filename, got, want)
			}
		})
	}
	if len(categories) < 16 || ambiguous < 60 {
		t.Fatalf("insufficient category/ambiguity distribution: %v, ambiguous=%d", categories, ambiguous)
	}
	t.Logf("synthetic labeled rule coverage: cases=%d, categories=%v, ambiguous=%d; not a recognition-accuracy benchmark", len(corpus.Cases), categories, ambiguous)
}

func validateNameEvidence(t *testing.T, input string, parsed ParsedName) {
	t.Helper()
	if parsed.OriginalName != input {
		t.Fatal("original filename was changed")
	}
	for _, evidence := range parsed.Evidence {
		if evidence.Start < 0 || evidence.End <= evidence.Start || evidence.End > len(input) || input[evidence.Start:evidence.End] != evidence.Raw {
			t.Fatalf("invalid evidence span: %#v", evidence)
		}
	}
	if len(parsed.SeasonEpisodes) > MaxEpisodeHints || len(parsed.AbsoluteEpisodes) > MaxEpisodeHints {
		t.Fatal("episode expansion escaped its bound")
	}
}

func TestRealReleaseNoiseDoesNotPolluteExactTitle(t *testing.T) {
	cases := []struct {
		filename string
		title    string
		year     int
	}{
		{"The.Notebook.恋恋笔记本.2004.中英字幕.BDrip.1080P-人人影视.mp4", "The Notebook 恋恋笔记本", 2004},
		{"[天才枪手][泰国.剧情][港版].Bad.Genius.2017.BD.1080P.x265.10bit.AAC5.1.泰国粤三语.内嵌特效中简-FFans@抠脚.mkv", "[天才枪手] Bad Genius", 2017},
		{"Return.to.Dust.2022.WEB-DL.4K.H.265.DDP.5.1.&.AAC-CTRLTV.mkv", "Return to Dust", 2022},
		{"My.Memories.of.Old.Beijing.1983.V2.1080p.BluRay.x264.FLAC.1.0-SONYHD.mkv", "My Memories of Old Beijing", 1983},
		{"Youth.2015.USA.BluRay.Remux.AVC.1080p.DTS-HDMA7.1-DreamHD.mkv", "Youth", 2015},
		{"Dune.Part.Two.2024.USA.BluRay.2160p.TrueHD7.1.DoVi.HDR.x265.10bit-DreamHD.mkv", "Dune Part Two", 2024},
		{"蜘蛛侠2.加长版.2004.BD1080P.AAC.H264.CHS-ENG.BT世界网.[www.btsj6.com].mp4", "蜘蛛侠2", 2004},
		{"终结者2：审判日.156分钟终极剪辑版.1991.BD1080P.AAC.H264.CHS-ENG.BT世界网.[www.btsj5.com].mp4", "终结者2：审判日", 1991},
	}
	for _, fixture := range cases {
		t.Run(fixture.filename, func(t *testing.T) {
			parsed := ParseName(fixture.filename)
			validateNameEvidence(t, fixture.filename, parsed)
			if parsed.Title != fixture.title || parsed.Year == nil || *parsed.Year != fixture.year {
				t.Fatalf("got title=%q year=%v ambiguities=%v; want title=%q year=%d", parsed.Title, parsed.Year, parsed.Ambiguities, fixture.title, fixture.year)
			}
		})
	}
}

func TestMultipartAudioReleaseNoiseDoesNotPolluteTitle(t *testing.T) {
	parsed := ParseName("Chang 1997 Dvdrip Xvid Ac3.5.1Ch Cd1-Waf.avi")
	validateNameEvidence(t, "Chang 1997 Dvdrip Xvid Ac3.5.1Ch Cd1-Waf.avi", parsed)
	if parsed.Title != "Chang" || parsed.Year == nil || *parsed.Year != 1997 ||
		len(parsed.Parts) != 1 || parsed.Parts[0] != 1 || !slices.Contains(parsed.Ambiguities, "part_requires_confirmation") {
		t.Fatalf("multipart audio release parsed incorrectly: %#v", parsed)
	}
}

func TestReleaseYearSurvivesRealWorldSuffixNoise(t *testing.T) {
	cases := []struct {
		filename string
		year     int
	}{
		{"飞越疯人院(蓝光国英双音轨特效中英双字幕).One.Flew.Over.the.Cuckoo‘s.Nest.1975.35th.Anniversary.CEE.BD-1080p.X265.10bit.AAC.2AUDIOS.CHS.ENG-UUMp4.mp4", 1975},
		{"萝莉视频989pa.com-雷神1.雷神索尔.Thor.2011.BD1080P.X264.AAC.国英双语特效中英双字.mkv", 2011},
	}
	for _, fixture := range cases {
		t.Run(fixture.filename, func(t *testing.T) {
			parsed := ParseName(fixture.filename)
			validateNameEvidence(t, fixture.filename, parsed)
			if parsed.Year == nil || *parsed.Year != fixture.year {
				t.Fatalf("got title=%q year=%v ambiguities=%v; want year=%d", parsed.Title, parsed.Year, parsed.Ambiguities, fixture.year)
			}
		})
	}
}

func TestPromotionalOnlyFilenameUsesUnambiguousDirectoryEvidence(t *testing.T) {
	filename := "【更多无水印蓝光原盘请访问 www.BBSDDS.com】【更多无水印蓝光原盘请访问 www.BBSDDS.com】.MP4.mkv"
	parsed := ParseNameInDirectory(filename, "芳芳.fanfan 1993 1080P H264 AC3-TAG")
	if parsed.Title != "" || len(parsed.Ambiguities) != 0 {
		t.Fatalf("clear directory evidence did not resolve promotional filename: %+v", parsed)
	}
	if parsed.Directory == nil || parsed.Directory.Title != "芳芳 fanfan" || parsed.Directory.Year == nil || *parsed.Directory.Year != 1993 {
		t.Fatalf("independent directory evidence missing: %+v", parsed.Directory)
	}
	ambiguous := ParseNameInDirectory(filename, "芳芳.fanfan 1993-1994 1080P")
	if !slices.Contains(ambiguous.Ambiguities, "promotional_filename") || !slices.Contains(ambiguous.Ambiguities, "directory_ambiguous_evidence") {
		t.Fatalf("ambiguous directory evidence was ignored: %+v", ambiguous)
	}
}

func TestDirectoryBracketedAliasesProvideUsableTitleEvidence(t *testing.T) {
	parsed := ParseNameInDirectory("Chang 1997 Dvdrip Xvid Ac3.5.1Ch Cd1-Waf.avi", "창 [娼, 노는 계집 창, 1997]")
	if parsed.Directory == nil || parsed.Directory.Title != "娼" || parsed.Directory.Year == nil || *parsed.Directory.Year != 1997 {
		t.Fatalf("bracketed directory aliases were not parsed: %+v", parsed.Directory)
	}
	if aliases := TitleAliases(parsed.Directory.Title); len(aliases) != 0 {
		t.Fatalf("single directory alias should be used directly, got %v", aliases)
	}
}

func TestChineseSitePrefixDoesNotPolluteTitle(t *testing.T) {
	parsed := ParseName("【首发于高清影视之家 www.BBQDDQ.com】人间中毒[中文字幕].Obsessed.2014.BluRay.1080p.HEVC-CHD.mkv")
	if parsed.Title != "人间中毒 Obsessed" || parsed.Year == nil || *parsed.Year != 2014 || len(parsed.Ambiguities) != 0 {
		t.Fatalf("site prefix polluted title: %+v", parsed)
	}
}

func TestBracketedReleaseTagsDoNotPolluteTitle(t *testing.T) {
	for _, tc := range []struct{ filename, title string }{
		{"【首发于高清影视之家 www.BBQDDQ.com】银河护卫队2[IMAX满屏版][简繁英字幕].Guardians.of.the.Galaxy.Vol.2.2017.IMAX.2160p.HDR.mkv", "银河护卫队2 Guardians of the Galaxy Vol 2"},
		{"【首发于高清影视之家 www.BBQDDQ.com】雷神3：诸神黄昏[IMAX满屏版][简繁英字幕].Thor.Ragnarok.2017.IMAX.2160p.HDR.mkv", "雷神3：诸神黄昏 Thor Ragnarok"},
		{"【高清影视之家发布 www.HDBTHD.com】火星救援[共2部合集][国英多音轨+中文字幕+特效字幕].2015.2in1.Bluray.2160p.mkv", "火星救援"},
		{"【高清影视之家发布 www.HDBTHD.com】薄荷糖[30帧率版本][简繁字幕].Peppermint.Candy.1999.2160p.WEB-DL.mkv", "薄荷糖 Peppermint Candy"},
	} {
		parsed := ParseName(tc.filename)
		if parsed.Title != tc.title || parsed.Year == nil || len(parsed.Ambiguities) != 0 {
			t.Fatalf("%s: title=%q year=%v ambiguities=%v", tc.filename, parsed.Title, parsed.Year, parsed.Ambiguities)
		}
	}
}

func TestNumericTitleDoesNotBecomeDirectoryYearConflict(t *testing.T) {
	const name = "[www.domp4.cc]2012.2009.BD1080p.国英双语.中英双字.mp4"
	parsed := ParseNameInDirectory(name, name)
	if parsed.Title != "2012" || parsed.Year == nil || *parsed.Year != 2009 || len(parsed.Ambiguities) != 0 {
		t.Fatalf("numeric title was treated as a conflicting year: %+v", parsed)
	}
	if parsed.Directory == nil || parsed.Directory.Title != "2012" || parsed.Directory.Year == nil || *parsed.Directory.Year != 2009 || len(parsed.Directory.Ambiguities) != 0 {
		t.Fatalf("numeric directory title lost its release year: %+v", parsed.Directory)
	}
}

func TestCompleteFilenameDoesNotInheritUnusedDirectoryAmbiguity(t *testing.T) {
	parsed := ParseNameInDirectory("Blade.Runner.2049.2017.BluRay.2160p.x265.mkv",
		"【高清影视之家发布 www.BBQDDQ.com】银翼杀手2049.Blade.Runner.2049.2017.BluRay")
	if parsed.Title != "Blade Runner 2049" || parsed.Year == nil || *parsed.Year != 2017 || len(parsed.Ambiguities) != 0 {
		t.Fatalf("complete filename inherited unused directory ambiguity: %+v", parsed)
	}
	if parsed.Directory == nil || parsed.Directory.Year == nil || *parsed.Directory.Year != 2017 || len(parsed.Directory.Ambiguities) != 0 {
		t.Fatalf("independent directory evidence was not normalized: %+v", parsed.Directory)
	}
	missingYear := ParseNameInDirectory("Blade.Runner.mkv", "Pirates.2003-2017.BluRay")
	if !slices.Contains(missingYear.Ambiguities, "directory_ambiguous_evidence") {
		t.Fatalf("needed directory evidence remained ambiguous: %+v", missingYear)
	}
}

func TestSubtitleOnlySuffixDoesNotPolluteTitle(t *testing.T) {
	parsed := ParseName("隐藏的面孔 中字.mkv")
	if parsed.Title != "隐藏的面孔" || parsed.Year != nil || len(parsed.Ambiguities) != 0 {
		t.Fatalf("subtitle suffix polluted title: %+v", parsed)
	}
}

func TestAnimeReleaseTagsDoNotPolluteTitle(t *testing.T) {
	const filename = "[CoolComic404][Kimi no Na wa ] [ , ][ - ][MKV].mkv"
	const directory = "[CoolComic404][Kimi no Na wa.(2016)][1080P][BDrip][CHS,JP][HEVC-10bit FLAC][MKV]"
	parsed := ParseNameInDirectory(filename, directory)
	if parsed.Title != "Kimi no Na wa" || len(parsed.Ambiguities) != 0 {
		t.Fatalf("anime release tags polluted title: %+v", parsed)
	}
	if parsed.Directory == nil || parsed.Directory.Title != "Kimi no Na wa" || parsed.Directory.Year == nil || *parsed.Directory.Year != 2016 || len(parsed.Directory.Ambiguities) != 0 {
		t.Fatalf("anime directory evidence was not normalized: %+v", parsed.Directory)
	}
}

func TestAudioLayoutCleanupKeepsNumericTitle(t *testing.T) {
	film := ParseName("Film.2018.7.1.1080p.mkv")
	if film.Title != "Film" || strings.Contains(film.Title, "7") {
		t.Fatalf("audio layout polluted title: %+v", film)
	}
	numeric := ParseName("21.2018.1080p.mkv")
	if numeric.Title != "21" {
		t.Fatalf("numeric title was removed: %+v", numeric)
	}
}

func TestParseNameInDirectoryRetainsIndependentYearEvidence(t *testing.T) {
	parsed := ParseNameInDirectory("film.mkv", "Echo (2020) 1080p")
	if parsed.Directory == nil || parsed.Directory.Title != "Echo" || parsed.Directory.Year == nil || *parsed.Directory.Year != 2020 {
		t.Fatalf("directory evidence missing: %+v", parsed)
	}
	if parsed.Year != nil || parsed.Title != "film" {
		t.Fatalf("directory evidence overwrote filename: %+v", parsed)
	}
	conflict := ParseNameInDirectory("film (1998).mkv", "Echo (2020) 1080p")
	if !slices.Contains(conflict.Ambiguities, "directory_year_conflict") {
		t.Fatalf("conflicting years were not retained: %+v", conflict)
	}
	middle := ParseNameInDirectory("film.mkv", "Captain.Marvel.2019.IMAX.2160p.HDR")
	if middle.Directory == nil || middle.Directory.Year == nil || *middle.Directory.Year != 2019 {
		t.Fatalf("single directory year token was lost: %+v", middle)
	}
	leading := ParseNameInDirectory("film.mkv", "2001 A Space Odyssey")
	if leading.Directory != nil && leading.Directory.Year != nil {
		t.Fatalf("title number became a year: %+v", leading)
	}
	multiple := ParseNameInDirectory("film.mkv", "Pirates.2003-2017.Bluray")
	if multiple.Directory != nil && multiple.Directory.Year != nil {
		t.Fatalf("year range became one year: %+v", multiple)
	}
}

func TestParseNameCleansReleaseAndSubtitleNoise(t *testing.T) {
	for _, tc := range []struct {
		filename, title string
		year            int
	}{
		{"The Wolf of Wall Street 2013 MNHD-FRDS.mkv", "The Wolf of Wall Street", 2013},
		{"华尔街之狼 2013 BD1080p 国英双语 中英双字.mkv", "华尔街之狼", 2013},
		{"Spider-Man Far from Home 2019 IMAX UHD 7 1 -RARBG.mkv", "Spider-Man Far from Home", 2019},
		{"[www dbmp4 com]后天 2004 BD1080p 国英双语 中英双字.mkv", "后天", 2004},
		{"[www.domp4.com]华尔街之狼 2013 BD1080p 中英双字.mkv", "华尔街之狼", 2013},
		{"阿黛尔的生活 2013 法语中字.mkv", "阿黛尔的生活", 2013},
		{"Captain Marvel 2019 DSNP.mkv", "Captain Marvel", 2019},
		{"Basic.Instinct.1992.UHD.BluRay.2160p.2Audio.DTS-HD.MA.5.1.x265.10bit.HDR-ALT.mkv", "Basic Instinct", 1992},
		{"Barbie 2023 iTunes.mkv", "Barbie", 2023},
		{"Blade Runner 2049 2017 4Audio -SSDSSE.mkv", "Blade Runner 2049", 2017},
		{"The Martian 2015 TC TrueHD7 1.mkv", "The Martian", 2015},
		{"Love Actually 2003 DTS-HD MA5 1.mkv", "Love Actually", 2003},
		{"Venom 2018 English.mkv", "Venom", 2018},
		{"福利电影989pa com-盲井 无删减版 Blind Shaft 2003 HD1080P 无水印.mkv", "盲井 Blind Shaft", 2003},
		{"魅力社989pa com-盲山 完整版 Blind Mountain 2007 HD1080P.mkv", "盲山 Blind Mountain", 2007},
		{"黄海(蓝光 ) The Yellow Sea 2010.mkv", "黄海 The Yellow Sea", 2010},
		{"老男孩 2003 韩语中字.mkv", "老男孩", 2003},
	} {
		parsed := ParseName(tc.filename)
		if parsed.Title != tc.title || parsed.Year == nil || *parsed.Year != tc.year || len(parsed.Ambiguities) != 0 {
			t.Fatalf("%s: title=%q year=%v ambiguities=%v", tc.filename, parsed.Title, parsed.Year, parsed.Ambiguities)
		}
	}
}

func TestParseNameCleansBracketedChineseReleaseTitle(t *testing.T) {
	parsed := ParseName("【特效双语】《蜘蛛侠：英雄无归》IMAX加长版特效精校双语字幕.mp4")
	if parsed.Title != "蜘蛛侠：英雄无归" || parsed.Year != nil || len(parsed.Ambiguities) != 0 {
		t.Fatalf("bracketed release title parsed incorrectly: %+v", parsed)
	}
	if !slices.Contains(parsed.Editions, "extended") {
		t.Fatalf("extended edition evidence was lost: %+v", parsed)
	}
}

func TestTitleAliasesSeparateChineseAndOriginalTitles(t *testing.T) {
	aliases := TitleAliases("The Notebook 恋恋笔记本")
	if !reflect.DeepEqual(aliases, []string{"The Notebook", "恋恋笔记本"}) {
		t.Fatalf("unexpected aliases: %v", aliases)
	}
	cleaned := TitleAliases("美国队长2：冬日战士 Captain America 2 2014 AAC6 2Audios -DiaosMan")
	if !reflect.DeepEqual(cleaned, []string{"美国队长2：冬日战士", "Captain America 2"}) {
		t.Fatalf("unclean aliases: %v", cleaned)
	}
	mixed := TitleAliases("地心引力(蓝光国英双音轨特效 幕) Gravity 2013 BD")
	if !slices.Contains(mixed, "Gravity") {
		t.Fatalf("mixed alias retained source tags: %v", mixed)
	}
}

func TestNameParserResourceBoundsAndPurity(t *testing.T) {
	for _, input := range []string{strings.Repeat("星", MaxNameBytes), "bad\x00name.mkv", "bad\xffname.mkv", "folder/movie.mkv", `C:\fixture\movie.mkv`} {
		parsed := ParseName(input)
		validateNameEvidence(t, input, parsed)
		if len(parsed.Ambiguities) != 1 || len(parsed.Evidence) != 0 || parsed.Title != "" {
			t.Fatalf("invalid input was parsed: %#v", parsed)
		}
	}
	input := "Long Series S01E0000-E0127 S02E0000-E0127 S03E0000-E0127.mkv"
	parsed := ParseName(input)
	validateNameEvidence(t, input, parsed)
	if len(parsed.SeasonEpisodes) != MaxEpisodeHints {
		t.Fatalf("unexpected episode count: %d", len(parsed.SeasonEpisodes))
	}
	if !reflect.DeepEqual(parsed, ParseName(input)) {
		t.Fatal("parse depends on mutable state")
	}
}

func TestCompleteLocalSeasonDirectoryMapsAbsoluteEpisodeNumbers(t *testing.T) {
	directory := "韩剧【信号】(2016) 16集全 1080P韩语中字 李帝勋 ／ 赵震雄 ／ 金惠秀"
	parsed := ParseNameInDirectory("信号.E01【微信公众号：浪漫的西红柿】可以加115交流群.mkv", directory)
	if parsed.Title != "信号" || parsed.Directory == nil || parsed.Directory.Title != "信号" || parsed.Directory.Year == nil || *parsed.Directory.Year != 2016 {
		t.Fatalf("series directory evidence missing: %#v", parsed)
	}
	if len(parsed.SeasonEpisodes) != 1 || parsed.SeasonEpisodes[0] != (SeasonEpisode{Season: 1, Episode: 1}) || len(parsed.AbsoluteEpisodes) != 0 {
		t.Fatalf("complete local season not mapped: %#v", parsed)
	}
	for _, issue := range parsed.Ambiguities {
		if issue == "absolute_episode_requires_mapping" || issue == "directory_ambiguous_evidence" {
			t.Fatalf("resolved local season retained ambiguity: %v", parsed.Ambiguities)
		}
	}
	for episode := 1; episode <= 16; episode++ {
		parsed := ParseNameInDirectory(fmt.Sprintf("信号.E%02d【微信公众号：浪漫的西红柿】可以加115交流群.mkv", episode), directory)
		if len(parsed.SeasonEpisodes) != 1 || parsed.SeasonEpisodes[0] != (SeasonEpisode{Season: 1, Episode: episode}) || len(parsed.AbsoluteEpisodes) != 0 {
			t.Fatalf("episode %d was not mapped: %#v", episode, parsed)
		}
	}
}

func TestCompleteLocalSeasonDirectoryRejectsOutOfRangeOrUndeclaredEpisodes(t *testing.T) {
	directory := "韩剧【信号】(2016) 16集全 1080P韩语中字"
	for _, tc := range []struct {
		filename, directory string
		absolute            bool
	}{
		{"信号.E17.mkv", directory, true},
		{"信号.E01.mkv", "韩剧【信号】(2016) 1080P韩语中字", true},
		{"信号.E01.mkv", "韩剧【信号】(2016) 16集全 12集全", true},
		{"信号.S02E01.mkv", directory, false},
	} {
		parsed := ParseNameInDirectory(tc.filename, tc.directory)
		if tc.absolute {
			if len(parsed.AbsoluteEpisodes) != 1 || len(parsed.SeasonEpisodes) != 0 {
				t.Fatalf("unsafe absolute conversion: %#v", parsed)
			}
		} else if len(parsed.SeasonEpisodes) != 1 || parsed.SeasonEpisodes[0].Season != 2 {
			t.Fatalf("explicit season overwritten: %#v", parsed)
		}
	}
}

func TestParseNameEpisodeYearAndReleaseTags(t *testing.T) {
	for _, filename := range []string{
		"Meet.Yourself.2023.E01.WEB-DL.2160p.H265.AAC-Xiaomi(1).mp4",
		"Meet.Yourself.S01E02.2023.2160p.IQ.WEB-DL.H265.DDP2.0-LelveTV.mkv",
	} {
		parsed := ParseName(filename)
		if parsed.Title != "Meet Yourself" || parsed.Year == nil || *parsed.Year != 2023 {
			t.Fatalf("episode title/year not cleaned: %s => %+v", filename, parsed)
		}
	}
	parsed := ParseName("热点.2025.EP01-10.HD1080P.X264.AAC.Japanese.CHS.XLYS.mkv")
	if parsed.Title != "热点" || parsed.Year == nil || *parsed.Year != 2025 || len(parsed.AbsoluteEpisodes) != 10 || parsed.AbsoluteEpisodes[0] != 1 || parsed.AbsoluteEpisodes[9] != 10 {
		t.Fatalf("episode range title/year not cleaned: %+v", parsed)
	}
	for filename, want := range map[string]string{
		"Euphoria.US.S01E01.HD1080P.X264.AAC.English.CHS-ENG.BDE4.mkv":               "Euphoria",
		"人生切割术.Severance.S02E01.2025.2160p.ATVP.WEB-DL.DDP5.1.Atmos.H265-MiniTV.mkv": "人生切割术 Severance",
	} {
		if got := ParseName(filename).Title; got != want {
			t.Fatalf("release tag remained in %q: got %q want %q", filename, got, want)
		}
	}
}

func FuzzParseNameEvidence(f *testing.F) {
	for _, seed := range []string{"三体 S00E00.mkv", "[Group] Anime - 01v2 [1080p].mkv", "2001 A Space Odyssey (1968).mkv", "Part I S01E01-E9999.mkv", "剧 第十二季第一百零一集.mkv"} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, input string) { validateNameEvidence(t, input, ParseName(input)) })
}
