package io.github.kkwans.fileway.tv.medialibrary

import coil3.request.ImageRequest
import io.github.kkwans.nasfilebrowser.data.MediaCatalogCursor
import io.github.kkwans.nasfilebrowser.data.MediaCatalogCard
import io.github.kkwans.nasfilebrowser.data.MediaCatalogHomeRow
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPageState
import io.github.kkwans.nasfilebrowser.data.MediaCatalogRowId
import io.github.kkwans.nasfilebrowser.data.MediaItemKind
import io.github.kkwans.nasfilebrowser.data.MediaTechnicalObservation

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
    val detailValueLabel: String = valueLabel,
)

internal fun tvMediaRatingCountLabel(source: TvMediaRatingSource, count: Long?, precision: String?): String? {
    count ?: return null
    val unit = when (source) {
        TvMediaRatingSource.DOUBAN -> "人评价"
        TvMediaRatingSource.IMDB, TvMediaRatingSource.TMDB -> "票"
        TvMediaRatingSource.ROTTEN_TOMATOES_CRITICS -> "位影评人"
        TvMediaRatingSource.ROTTEN_TOMATOES_AUDIENCE -> "位观众"
    }
    val lowerBound = if (precision == "lower_bound") "+" else ""
    return "${String.format(java.util.Locale.ROOT, "%,d", count)}$lowerBound $unit"
}

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
    val removing: Boolean = false,
)

data class TvMediaShelf(
    val id: String,
    val title: String,
    val items: List<TvMediaCard> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
)

data class TvMediaCategory(val id: String, val label: String)

data class TvMediaHomeRowState<T>(
    val rowId: MediaCatalogRowId,
    val items: List<T> = emptyList(),
    val cursor: MediaCatalogCursor? = null,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val retryAppend: Boolean = false,
)

internal fun tvMediaShelfVisible(shelf: TvMediaShelf): Boolean =
    shelf.items.isNotEmpty() || shelf.loading || shelf.error != null || shelf.hasMore

internal fun tvMediaShouldLoadMore(lastVisibleIndex: Int, itemCount: Int, hasMore: Boolean,
    loading: Boolean, error: String?): Boolean =
    hasMore && !loading && error == null && itemCount > 0 && lastVisibleIndex >= 0 &&
        lastVisibleIndex >= itemCount - 3

internal fun tvMediaSearchShowsEmpty(searched: Boolean, empty: Boolean, busy: Boolean,
    problem: String?, hasMore: Boolean): Boolean =
    searched && empty && !busy && problem == null && !hasMore

internal fun tvMediaShouldLoadEmptyPage(hasMore: Boolean, loading: Boolean, error: String?): Boolean =
    hasMore && !loading && error == null

internal fun <T, R> tvMediaFirstPreview(preview: List<T>, map: (T) -> R): R? =
    preview.firstOrNull()?.let(map)

internal fun tvMediaPersonPreviewTitle(preview: List<MediaCatalogCard>): String? =
    tvMediaFirstPreview(preview) { it.title.ifBlank { "未命名影视" } }

internal fun tvMediaFeaturedInitialId(entries: List<TvMediaCard>): String? = entries.firstOrNull()?.id

internal fun tvMediaCatalogShelfVisible(shelf: TvMediaShelf, categories: List<TvMediaCategory>): Boolean =
    tvMediaShelfVisible(shelf) || categories.isNotEmpty()

internal fun tvMediaCatalogRows(items: List<TvMediaCard>, columns: Int): List<List<TvMediaCard>> =
    items.chunked(columns.coerceAtLeast(1))

internal fun <T> syncTvMediaHomeRows(
    current: List<TvMediaHomeRowState<T>>,
    incoming: List<TvMediaHomeRowState<T>>,
): List<TvMediaHomeRowState<T>> {
    val previous = current.associateBy { it.rowId }
    return incoming.map { next ->
        val old = previous[next.rowId]
        if (old != null && next.items.isEmpty() && (next.error != null || next.hasMore)) {
            old.copy(cursor = next.cursor, hasMore = next.hasMore, loading = false,
                error = next.error, retryAppend = false)
        } else next
    }
}

internal fun <T> mergeTvMediaHomeRow(
    current: TvMediaHomeRowState<T>,
    incoming: TvMediaHomeRowState<T>,
    append: Boolean,
    key: (T) -> String,
): TvMediaHomeRowState<T> {
    require(current.rowId == incoming.rowId)
    val items = if (append) current.items + incoming.items else incoming.items
    return incoming.copy(items = items.distinctBy(key), loading = false, retryAppend = false)
}

private fun tvMediaHomeRowIdentity(id: MediaCatalogRowId): Pair<String, String>? = when (id) {
    MediaCatalogRowId.RECENTLY_ADDED -> "recently-added" to "最近入库"
    MediaCatalogRowId.RECENTLY_UPDATED_SERIES -> "recently-updated-series" to "最近更新剧集"
    else -> null
}

internal fun tvMediaHomeShelf(row: MediaCatalogHomeRow, items: List<TvMediaCard>): TvMediaShelf? {
    val (id, title) = tvMediaHomeRowIdentity(row.id) ?: return null
    return TvMediaShelf(
        id = id,
        title = title,
        items = items,
        loading = false,
        error = when (row.state) {
            MediaCatalogPageState.READY -> null
            MediaCatalogPageState.PARTIAL -> if (row.hasMore) null else "列表尚未完成，请稍后重试。"
            MediaCatalogPageState.PREPARING, MediaCatalogPageState.DEFERRED -> "列表尚在准备，请稍后重试。"
            MediaCatalogPageState.UNSUPPORTED, MediaCatalogPageState.ERROR -> "列表暂不可用，请稍后重试。"
        },
        hasMore = row.hasMore,
    )
}

internal fun tvMediaHomeRowState(row: MediaCatalogHomeRow): TvMediaHomeRowState<MediaCatalogCard>? {
    val shelf = tvMediaHomeShelf(row, emptyList()) ?: return null
    return TvMediaHomeRowState(row.id, row.items, row.nextCursor, row.hasMore, shelf.loading, shelf.error)
}

internal fun tvMediaHomeRowShelf(state: TvMediaHomeRowState<TvMediaCard>): TvMediaShelf? {
    val (id, title) = tvMediaHomeRowIdentity(state.rowId) ?: return null
    return TvMediaShelf(id, title, state.items, state.loading, state.error, state.hasMore)
}

data class TvMediaHomeState(
    /** Change on instance/account change; clears only this screen's local UI state. */
    val scopeKey: String,
    /** Latest authorized candidates. IDs must remain stable for the same work.
     * Remove revoked items immediately; preserve the last authorized list while a
     * normal refresh is merely loading. A removed active item requires reselection. */
    val featured: List<TvMediaCard> = emptyList(),
    val continuing: List<TvMediaContinueItem> = emptyList(),
    val continueLoading: Boolean = false,
    val continueError: String? = null,
    val continueHasMore: Boolean = false,
    val rows: List<TvMediaHomeRowState<TvMediaCard>> = emptyList(),
    val shelves: List<TvMediaShelf> = emptyList(),
    val categories: List<TvMediaCategory> = emptyList(),
    val selectedCategory: String? = null,
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

private val tvEpisodeFilename = Regex("""(?i)(?:^|[._\s-])(?:S\d{1,2})?EP?(\d{1,3})(?=$|[._\s-])""")

internal fun tvMediaEpisodeTitle(numberLabel: String, title: String): String =
    if (title.isBlank() || tvEpisodeFilename.containsMatchIn(title)) numberLabel else "$numberLabel · $title"

data class TvMediaDetailState(
    val scopeKey: String,
    val item: TvMediaCard,
    val playLabel: String = "播放",
    val favorite: Boolean = false,
    val watched: Boolean = false,
    val userStateBusy: Boolean = false,
    val userStateError: String? = null,
    val sourceCount: Int = 0,
    val sourceSummary: String? = null,
    val technical: MediaTechnicalObservation? = null,
    val people: List<TvMediaPerson> = emptyList(),
    val collections: List<String> = emptyList(),
    val seasons: List<TvMediaSeason> = emptyList(),
    val seasonsLoading: Boolean = false,
    val seasonsError: String? = null,
    val seasonsHasMore: Boolean = false,
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

enum class TvMediaSourceIntent { PLAY, BROWSE }

internal class TvMediaSourceJourney {
    private var itemId: String? = null
    private var intent: TvMediaSourceIntent = TvMediaSourceIntent.PLAY
    private var pendingSourceId: String? = null

    val currentIntent: TvMediaSourceIntent get() = intent

    fun open(value: String, sourceIntent: TvMediaSourceIntent) {
        if (itemId != value || sourceIntent == TvMediaSourceIntent.PLAY) pendingSourceId = null
        itemId = value
        intent = sourceIntent
    }

    /** Returns true only when the confirmation should enter playback. */
    fun confirm(sourceId: String): Boolean {
        pendingSourceId = sourceId
        return intent == TvMediaSourceIntent.PLAY
    }

    fun pendingSourceId(value: String): String? = pendingSourceId.takeIf { itemId == value }
}

internal fun tvCanBrowseSources(kind: MediaItemKind): Boolean = kind in setOf(
    MediaItemKind.MOVIE, MediaItemKind.EPISODE, MediaItemKind.VIDEO,
)

data class TvMediaSourcePickerState(
    val scopeKey: String,
    val itemId: String,
    val title: String,
    val editions: List<TvMediaEdition>,
    val currentSourceId: String? = null,
    val preferredSourceId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val applying: Boolean = false,
    val intent: TvMediaSourceIntent = TvMediaSourceIntent.PLAY,
) {
    val initialSourceId: String? get() = preferredSourceId ?: currentSourceId
    val playsOnConfirm: Boolean get() = intent == TvMediaSourceIntent.PLAY
    val confirmLabel: String get() = if (playsOnConfirm) "使用所选片源" else "设为待播放片源"
    val confirmHint: String get() = if (playsOnConfirm) "不同剪辑分别保留续播位置" else "选择后返回详情，不会立即播放"
}

data class TvMediaNavigationItem(val id: String, val label: String)
