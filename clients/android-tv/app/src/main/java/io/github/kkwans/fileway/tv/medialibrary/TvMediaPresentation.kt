package io.github.kkwans.fileway.tv.medialibrary

import coil3.request.ImageRequest

/** Presentation only. Never deserialize the service response directly into these types. */
data class TvMediaArtwork(
    /** An authorized, account/instance/revision-scoped request supplied by the repository.
     * The UI never resolves provider URLs or attaches credentials. */
    val request: ImageRequest,
)

enum class TvMediaRatingSource(val label: String) {
    DOUBAN("豆瓣"), IMDB("IMDb"), ROTTEN_TOMATOES_CRITICS("烂番茄 · 影评人"),
    ROTTEN_TOMATOES_AUDIENCE("烂番茄 · 观众"), TMDB("TMDB"),
}

data class TvMediaRating(
    val source: TvMediaRatingSource,
    /** Already formatted in the source's own scale, e.g. a percentage for Rotten Tomatoes. */
    val valueLabel: String,
    val countLabel: String? = null,
)

data class TvMediaCard(
    val id: String,
    val title: String,
    val poster: TvMediaArtwork? = null,
    val backdrop: TvMediaArtwork? = null,
    val originalTitle: String? = null,
    val yearLabel: String? = null,
    val genreLabel: String? = null,
    /** Actual movie runtime, or unique collected-episode count; never a file count. */
    val extentLabel: String? = null,
    val overview: String? = null,
    val ratings: List<TvMediaRating> = emptyList(),
    val availabilityLabel: String? = null,
    val canPlay: Boolean = false,
) {
    val primaryRating: TvMediaRating?
        get() = ratings.filter { it.valueLabel.isNotBlank() && it.source != TvMediaRatingSource.TMDB }
            .minByOrNull { it.source.ordinal }
    val metadataLabel: String
        get() = listOfNotNull(yearLabel, genreLabel, extentLabel).filter(String::isNotBlank).joinToString(" · ")
}

data class TvMediaContinueItem(
    /** Stable watch-entry identity, not the containing work ID. */
    val id: String,
    val item: TvMediaCard,
    val episodeLabel: String? = null,
    val remainingLabel: String? = null,
    /** Null when the source timeline/duration is unknown. */
    val progress: Float? = null,
)

data class TvMediaShelf(
    val id: String,
    val title: String,
    val items: List<TvMediaCard> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class TvMediaHomeState(
    /** Change on instance/account change; clears only this screen's local UI state. */
    val scopeKey: String,
    /** Latest authorized candidates. IDs must remain stable for the same work.
     * Remove revoked items immediately; preserve the last authorized list while a
     * normal refresh is merely loading. A removed active item requires reselection. */
    val featured: List<TvMediaCard> = emptyList(),
    val continuing: List<TvMediaContinueItem> = emptyList(),
    val shelves: List<TvMediaShelf> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val emptyTitle: String = "片库还没有内容",
    val emptyMessage: String = "添加媒体来源并完成扫描后，影片会出现在这里。",
)

data class TvMediaPerson(
    val id: String,
    val name: String,
    val roleLabel: String,
    val portrait: TvMediaArtwork? = null,
)

data class TvMediaSeason(val id: String, val label: String)

data class TvMediaEpisode(
    val id: String,
    val title: String,
    val numberLabel: String,
    val still: TvMediaArtwork? = null,
    val extentLabel: String? = null,
    val availabilityLabel: String? = null,
    val progress: Float? = null,
    val canPlay: Boolean = false,
)

data class TvMediaDetailState(
    val scopeKey: String,
    val item: TvMediaCard,
    val playLabel: String = "播放",
    val favorite: Boolean = false,
    val watched: Boolean = false,
    val sourceCount: Int = 0,
    val sourceSummary: String? = null,
    val people: List<TvMediaPerson> = emptyList(),
    val seasons: List<TvMediaSeason> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<TvMediaEpisode> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: String? = null,
    val related: List<TvMediaCard> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

enum class TvMediaSourceKind { SERVER, CLOUD, NETWORK }

data class TvMediaSource(
    val id: String,
    val locationLabel: String,
    val qualityLabel: String?,
    val detailLabel: String?,
    val fileName: String?,
    val kind: TvMediaSourceKind = TvMediaSourceKind.SERVER,
    val available: Boolean,
    val unavailableReason: String? = null,
    /** Present only when the server grants a supported recovery action. */
    val recoveryLabel: String? = null,
)

data class TvMediaEdition(
    val id: String,
    val title: String,
    val runtimeLabel: String? = null,
    val sources: List<TvMediaSource> = emptyList(),
)

data class TvMediaSourcePickerState(
    val scopeKey: String,
    val itemId: String,
    val title: String,
    val editions: List<TvMediaEdition>,
    val currentSourceId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val applying: Boolean = false,
)

data class TvMediaNavigationItem(val id: String, val label: String)
