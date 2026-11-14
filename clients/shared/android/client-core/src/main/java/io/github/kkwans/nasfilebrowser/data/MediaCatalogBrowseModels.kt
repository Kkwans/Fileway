package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** A reference belongs to one live account/session. A browse group is optional view context, not item identity. */
class MediaCatalogRef internal constructor(
    val scope: MediaSessionScope,
    val libraryId: String,
    val itemId: String,
    internal val deviceSession: String,
    val browseGroupId: String? = null,
) {
    init { requireMediaRouteId(libraryId); requireMediaRouteId(itemId); browseGroupId?.let(::requireMediaRouteId) }
    internal fun requireBound(scope: MediaSessionScope, deviceSession: String) {
        require(this.scope == scope && this.deviceSession == deviceSession) { "影视条目不属于当前会话" }
    }
    override fun equals(other: Any?): Boolean = other is MediaCatalogRef && scope == other.scope &&
        deviceSession == other.deviceSession && libraryId == other.libraryId && itemId == other.itemId
    override fun hashCode(): Int = listOf(scope, deviceSession, libraryId, itemId).hashCode()
    override fun toString(): String = "MediaCatalogRef(redacted)"
}

/** An opaque, non-persistent continuation; never log or reuse under another query or login. */
class MediaCatalogCursor internal constructor(
    private val value: String,
    private val scope: MediaSessionScope,
    private val deviceSession: String,
    private val queryKey: String,
) {
    internal fun use(scope: MediaSessionScope, deviceSession: String, queryKey: String): String {
        require(this.scope == scope && this.deviceSession == deviceSession && this.queryKey == queryKey) { "影视分页不属于当前查询或会话" }
        return value
    }
    internal fun matches(value: String): Boolean = this.value == value
    override fun toString(): String = "MediaCatalogCursor(redacted)"
}

enum class MediaCatalogPageState { READY, PARTIAL, PREPARING, DEFERRED, UNSUPPORTED, ERROR }
enum class MediaCatalogHomeState { NO_LIBRARIES, EMPTY_LIBRARY, SCANNING, READY, PARTIAL, SOURCE_OFFLINE, RESTRICTED, SERVICE_UNAVAILABLE }
enum class MediaCatalogDetailState { READY, PARTIAL_METADATA, OFFLINE, SOURCE_CHANGED, LEGACY }
enum class MediaCatalogMatchState { UNMATCHED, LOCAL_CONFIRMED, PROVIDER_CONFIRMED, NEEDS_REVIEW, UNKNOWN }
enum class MediaCatalogAvailability { AVAILABLE, OFFLINE, MISSING, CHECKING, UNKNOWN }
enum class MediaCatalogMetadataState { AVAILABLE, PARTIAL, RESTRICTED, OFFLINE, UNMATCHED, UNKNOWN }
enum class MediaCatalogDisplayMode { POSTER, LANDSCAPE, UNMATCHED, UNKNOWN }
enum class MediaCatalogCountState { READY, DEFERRED, COMPUTING, ERROR, UNKNOWN }
enum class MediaCatalogPlaybackMode { SINGLE, COMBINED_FILE, CHECKING, UNKNOWN }
enum class MediaCatalogCoverageState { READY, PARTIAL, DEFERRED, UNKNOWN }

enum class MediaCatalogRowId(internal val wire: String) {
    HERO("hero"), RECENTLY_ADDED("recently-added"), RECENTLY_UPDATED_SERIES("recently-updated-series"),
    UNMATCHED("unmatched"), CONTINUE("continue"), FAVORITES("favorites"), UNKNOWN("");
    internal companion object { fun from(value: String) = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN }
}

enum class MediaCatalogFilterKind(internal val wire: String) { MOVIE("movie"), SERIES("series"), VIDEO("video") }
enum class MediaCatalogMatchFilter(internal val wire: String) {
    UNMATCHED("unmatched"), LOCAL_CONFIRMED("local_confirmed"), PROVIDER_CONFIRMED("provider_confirmed"), NEEDS_REVIEW("needs_review")
}
enum class MediaCatalogSort(internal val wire: String) { ID("id"), NEWEST("newest"), UPDATED("updated") }
data class MediaCatalogQuery(
    val libraryId: String? = null,
    val structureKind: MediaCatalogFilterKind? = null,
    val matchState: MediaCatalogMatchFilter? = null,
    val sort: MediaCatalogSort = MediaCatalogSort.ID,
    val limit: Int = 20,
) {
    init {
        libraryId?.let(::requireMediaRouteId)
        require(limit in 1..100 && (sort != MediaCatalogSort.UPDATED || structureKind == null || structureKind == MediaCatalogFilterKind.SERIES))
    }
}

enum class MediaCatalogAction(internal val wire: String) {
    PLAY("play"), RESUME("resume"), START_OVER("startOver"), OPEN_DETAILS("openDetails"), EDIT_METADATA("editMetadata"),
    SEARCH_MATCH("searchMatch"), CONFIRM_MATCH("confirmMatch"), CHANGE_ORDER("changeOrder"), REFRESH_METADATA("refreshMetadata"),
    RETRY_ARTWORK("retryArtwork"), MANAGE_SOURCES("manageSources"), MANAGE_MEMBERS("manageMembers"), FAVORITE("favorite"),
    MARK_WATCHED("markWatched"), REMOVE_FROM_CONTINUE("removeFromContinue"), CREATE_LIBRARY("createLibrary")
}
class MediaCatalogActions internal constructor(
    private val allowed: Map<MediaCatalogAction, Boolean>,
    private val reasons: Map<MediaCatalogAction, String>,
) {
    fun can(action: MediaCatalogAction): Boolean = allowed[action] == true
    fun reason(action: MediaCatalogAction): String? = reasons[action]
    override fun toString(): String = "MediaCatalogActions($allowed)"
}

class MediaCatalogArtwork internal constructor(
    val ref: MediaCatalogRef, val assetId: String, val revision: Long, val role: String,
    private val endpoint: String,
) {
    internal fun serviceRelativePath(): String = endpoint
    override fun toString(): String = "MediaCatalogArtwork(redacted)"
}
data class MediaCatalogRating(
    val provider: String, val metric: MediaRatingMetric, val value: Double, val scale: Double,
    val audience: String?, val count: Long?, val countKind: String?, val countPrecision: String?,
)
data class MediaCatalogDuration(val seconds: Double, val basis: String, val editionId: String?)
data class MediaCatalogEpisodeCounts(
    val collected: Long?, val available: Long?, val specials: Long?, val unmappedFileCount: Long?,
    val total: Long?, val totalBasis: String?, val orderId: String?, val state: MediaCatalogCountState,
    val totalSource: String?, val totalAsOf: Long?,
    /** The server supplied a verified route; use repository.episodeCounts to request it. */
    val hasEndpoint: Boolean,
)
data class MediaCatalogCard(
    val ref: MediaCatalogRef, val revision: Long, val structureKind: MediaItemKind,
    val contentCategories: List<String>, val matchState: MediaCatalogMatchState, val title: String,
    val originalTitle: String?, val year: Long?, val displayMode: MediaCatalogDisplayMode,
    val artwork: MediaCatalogArtwork?, val primaryRating: MediaCatalogRating?, val duration: MediaCatalogDuration?,
    val episodes: MediaCatalogEpisodeCounts?, val availability: MediaCatalogAvailability,
    val metadataState: MediaCatalogMetadataState, val firstAddedAt: Long, val seriesUpdatedAt: Long?,
    val size: Long?, val extension: String?, val actions: MediaCatalogActions,
    val backdrop: MediaCatalogArtwork? = null,
) { override fun toString(): String = "MediaCatalogCard(redacted)" }
data class MediaCatalogPage<T>(
    val items: List<T>, val nextCursor: MediaCatalogCursor?, val hasMore: Boolean,
    val state: MediaCatalogPageState, val revision: String, val orderId: String? = null,
)
data class MediaCatalogHomeRow(
    val id: MediaCatalogRowId, val kind: String, val state: MediaCatalogPageState,
    val items: List<MediaCatalogCard>, val nextCursor: MediaCatalogCursor?, val hasMore: Boolean, val revision: String,
)
data class MediaCatalogHome(
    val schemaVersion: Long, val state: MediaCatalogHomeState, val libraries: List<MediaLibrarySummary>,
    val hero: MediaCatalogHomeRow?, val rows: List<MediaCatalogHomeRow>,
    val capabilities: Map<String, Boolean>, val actions: MediaCatalogActions,
) { override fun toString(): String = "MediaCatalogHome(redacted)" }

sealed interface MediaCatalogDetail {
    data class Complete(val detail: MediaItemDetail, val card: MediaCatalogCard?, val state: MediaCatalogDetailState) : MediaCatalogDetail {
        override fun toString(): String = "MediaCatalogDetail.Complete(redacted)"
    }
    data class Partial(val nextCursor: MediaCatalogCursor) : MediaCatalogDetail
}
data class MediaCatalogSeason(val series: MediaCatalogRef, val ref: MediaCatalogRef, val number: Long, val label: String, val orderId: String, val card: MediaCatalogCard) {
    override fun toString(): String = "MediaCatalogSeason(redacted)"
}
data class MediaCatalogEpisodePlayback(
    val mode: MediaCatalogPlaybackMode, val independentStart: Boolean, val coveredEpisodes: List<MediaCatalogRef>,
    val coverageState: MediaCatalogCoverageState, val label: String?,
)
data class MediaCatalogEpisode(
    val ref: MediaCatalogRef, val seasonId: String, val seasonNumber: Long, val episodeNumber: Long,
    val orderId: String, val card: MediaCatalogCard, val playback: MediaCatalogEpisodePlayback?,
)

/** Never persist or log this lease-bearing value. It is not an origin URL. */
class MediaPlaybackEndpoint internal constructor(private val value: String) {
    internal fun serviceRelativePath(): String = value
    override fun toString(): String = "MediaPlaybackEndpoint(redacted)"
}
class MediaCatalogVariant internal constructor(
    val ref: MediaCatalogRef, val editionId: String, val variantId: String, val bindingId: String, val sourceFileId: String,
    val editionName: String, val isDefault: Boolean, val sourceLabel: String, val displayName: String,
    val contentType: String, val size: Long, val fileRevision: Long, val identityEpoch: Long,
    val weakIdentity: Boolean, val durationSeconds: Double?, val resolution: String?,
) { override fun toString(): String = "MediaCatalogVariant(redacted)" }
data class MediaCatalogVariantPage(val items: List<MediaCatalogVariant>, val nextCursor: MediaCatalogCursor?)

/** The legacy `url` is deliberately not retained. A missing endpoint requires a newer server. */
class MediaCatalogPlaybackResolution internal constructor(
    val ref: MediaCatalogRef, val editionId: String, val variantId: String, val sourceFileId: String,
    val fileRevision: Long, val identityEpoch: Long, val weakIdentity: Boolean,
    val canResumeAcrossOpen: Boolean, val resumeRequiresSameRevision: Boolean,
    val displayName: String, val contentType: String, val size: Long, val durationSeconds: Double?,
    val resolution: String?, val rangeSupported: Boolean, val endpoint: MediaPlaybackEndpoint?, val expiresAt: Long,
    val sessionId: String?, val leaseId: String?, val leaseGeneration: Long?, val renewAfter: Long?,
    val resumeMode: String?, val timelineToken: String?,
) {
    override fun toString(): String = "MediaCatalogPlaybackResolution(redacted)"
}

private fun JSONObject.textValue(key: String): String = get(key) as? String ?: error("影视目录响应格式无效")
private fun JSONObject.optionalTextValue(key: String): String? = if (!has(key) || isNull(key)) null else textValue(key)
private fun JSONObject.booleanValue(key: String): Boolean = get(key) as? Boolean ?: error("影视目录响应格式无效")
private fun JSONObject.integerValue(key: String, min: Long = 0): Long {
    val value = (get(key) as? Number)?.toString()?.toLongOrNull()
    require(value != null && value >= min) { "影视目录响应格式无效" }
    return value
}
private fun JSONObject.optionalIntegerValue(key: String): Long? = if (!has(key) || isNull(key)) null else integerValue(key)
private fun JSONObject.decimalValue(key: String): Double {
    val value = (get(key) as? Number)?.toDouble()
    require(value != null && value.isFinite()) { "影视目录响应格式无效" }
    return value
}
private fun JSONObject.optionalDecimalValue(key: String): Double? = if (!has(key) || isNull(key)) null else decimalValue(key)
private fun JSONObject.routeId(key: String) = textValue(key).also(::requireMediaRouteId)
private fun JSONArray.catalogStrings(max: Int): List<String> {
    require(length() <= max)
    return (0 until length()).map { get(it) as? String ?: error("影视目录响应格式无效") }
}
private fun <T> JSONArray.catalogRows(max: Int, parse: (JSONObject) -> T): List<T> {
    require(length() <= max)
    return (0 until length()).map { parse(getJSONObject(it)) }
}
private fun safeShort(value: String): String = value.also { require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 4096) }
private fun optionalSafeText(row: JSONObject, key: String): String? = row.optionalTextValue(key)?.let(::safeShort)
internal fun catalogCursorValue(value: String, maxBytes: Int = 4096): String = value.also { require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= maxBytes) }

private fun pageState(value: String) = when (value) {
    "ready" -> MediaCatalogPageState.READY; "partial" -> MediaCatalogPageState.PARTIAL
    "preparing" -> MediaCatalogPageState.PREPARING; "deferred" -> MediaCatalogPageState.DEFERRED
    "unsupported" -> MediaCatalogPageState.UNSUPPORTED; "error" -> MediaCatalogPageState.ERROR
    else -> error("未知影视分页状态")
}
private fun itemKind(value: String) = when (value) {
    "movie" -> MediaItemKind.MOVIE; "series" -> MediaItemKind.SERIES; "season" -> MediaItemKind.SEASON
    "episode" -> MediaItemKind.EPISODE; "video" -> MediaItemKind.VIDEO; else -> MediaItemKind.UNKNOWN
}
private fun ratingMetric(value: String) = when (value) {
    "user_average" -> MediaRatingMetric.USER_AVERAGE; "critic_approval" -> MediaRatingMetric.CRITIC_APPROVAL
    "audience_approval" -> MediaRatingMetric.AUDIENCE_APPROVAL; "critic_average" -> MediaRatingMetric.CRITIC_AVERAGE
    else -> MediaRatingMetric.UNKNOWN
}
private fun actionMap(row: JSONObject, key: String, deviceSession: String, kind: MediaItemKind?, availability: MediaCatalogAvailability?, knownState: Boolean = true): MediaCatalogActions {
    val flags = row.getJSONObject(key)
    val rawReasons = row.getJSONObject("actionReasons")
    val allowed = MediaCatalogAction.entries.associateWith { action ->
        val value = if (flags.has(action.wire)) flags.booleanValue(action.wire) else false
        when {
            !knownState -> false
            deviceSession.isNotEmpty() && action in setOf(MediaCatalogAction.EDIT_METADATA, MediaCatalogAction.MANAGE_SOURCES, MediaCatalogAction.MANAGE_MEMBERS, MediaCatalogAction.CREATE_LIBRARY) -> false
            action == MediaCatalogAction.PLAY && (availability != MediaCatalogAvailability.AVAILABLE || kind !in setOf(MediaItemKind.MOVIE, MediaItemKind.EPISODE, MediaItemKind.VIDEO)) -> false
            kind == MediaItemKind.UNKNOWN -> false
            else -> value
        }
    }
    val reasons = MediaCatalogAction.entries.mapNotNull { action ->
        val value = rawReasons.optionalTextValue(action.wire)
        if (value != null) require(value.matches(Regex("[a-z0-9_]{1,80}")))
        val masked = !knownState || deviceSession.isNotEmpty() && action in setOf(MediaCatalogAction.EDIT_METADATA,
            MediaCatalogAction.MANAGE_SOURCES, MediaCatalogAction.MANAGE_MEMBERS, MediaCatalogAction.CREATE_LIBRARY)
        when {
            masked -> action to (if (knownState) "not_granted" else "unknown_state")
            value != null -> action to value
            else -> null
        }
    }.toMap()
    return MediaCatalogActions(allowed, reasons)
}

private fun parseRef(row: JSONObject, scope: MediaSessionScope, deviceSession: String) =
    MediaCatalogRef(scope, row.routeId("libraryId"), row.routeId("itemId"), deviceSession, row.optionalTextValue("browseGroupId"))

private fun parseArtwork(row: JSONObject, ref: MediaCatalogRef): MediaCatalogArtwork {
    val asset = row.routeId("assetId")
    val expected = "/api/media-library/v1/libraries/${ref.libraryId}/items/${ref.itemId}/artwork/$asset"
    require(row.textValue("endpoint") == expected) { "影视图片地址无效" }
    return MediaCatalogArtwork(ref, asset, row.integerValue("revision", 1), row.textValue("role"), expected)
}

private fun parseCounts(row: JSONObject, libraryId: String, seriesId: String?, seasonId: String?): MediaCatalogEpisodeCounts {
    val state = when (row.textValue("countState")) {
        "ready" -> MediaCatalogCountState.READY; "deferred" -> MediaCatalogCountState.DEFERRED
        "computing" -> MediaCatalogCountState.COMPUTING; "error" -> MediaCatalogCountState.ERROR
        else -> MediaCatalogCountState.UNKNOWN
    }
    val collected = row.optionalIntegerValue("collected")
    val available = row.optionalIntegerValue("available")
    val specials = row.optionalIntegerValue("specials")
    val total = row.optionalIntegerValue("total")
    val totalBasis = row.optionalTextValue("totalBasis")
    val orderId = row.optionalTextValue("orderId")?.also(::requireMediaRouteId)
    if (state != MediaCatalogCountState.READY) require(collected == null && available == null && specials == null)
    if (state == MediaCatalogCountState.READY) require(collected != null && available != null && specials != null && available <= collected)
    val totalSource = row.optionalTextValue("source")
    val totalAsOf = row.optionalIntegerValue("asOf")
    if (total != null) require(!totalBasis.isNullOrEmpty() && orderId != null && totalSource != null && totalAsOf != null)
    val endpoint = row.optionalTextValue("endpoint")
    if (endpoint != null) {
        require(seriesId != null)
        val expected = "/api/media-library/v1/libraries/$libraryId/items/$seriesId/episode-counts" + (seasonId?.let { "?seasonId=$it" } ?: "")
        require(endpoint == expected) { "影视计数地址无效" }
    }
    return MediaCatalogEpisodeCounts(collected, available, specials, row.optionalIntegerValue("unmappedFileCount"), total, totalBasis, orderId, state, totalSource, totalAsOf, endpoint != null)
}

internal fun parseCatalogCard(row: JSONObject, scope: MediaSessionScope, deviceSession: String, parentSeriesId: String? = null): MediaCatalogCard {
    val ref = parseRef(row.getJSONObject("ref"), scope, deviceSession)
    val kind = itemKind(row.textValue("structureKind"))
    val availability = when (row.textValue("availability")) {
        "available" -> MediaCatalogAvailability.AVAILABLE; "offline" -> MediaCatalogAvailability.OFFLINE
        "missing" -> MediaCatalogAvailability.MISSING; "checking" -> MediaCatalogAvailability.CHECKING; else -> MediaCatalogAvailability.UNKNOWN
    }
    val state = when (row.textValue("matchState")) {
        "unmatched" -> MediaCatalogMatchState.UNMATCHED; "local_confirmed" -> MediaCatalogMatchState.LOCAL_CONFIRMED
        "provider_confirmed" -> MediaCatalogMatchState.PROVIDER_CONFIRMED; "needs_review" -> MediaCatalogMatchState.NEEDS_REVIEW
        else -> MediaCatalogMatchState.UNKNOWN
    }
    val metadata = when (row.textValue("metadataState")) {
        "available" -> MediaCatalogMetadataState.AVAILABLE; "partial" -> MediaCatalogMetadataState.PARTIAL
        "restricted" -> MediaCatalogMetadataState.RESTRICTED; "offline" -> MediaCatalogMetadataState.OFFLINE
        "unmatched" -> MediaCatalogMetadataState.UNMATCHED; else -> MediaCatalogMetadataState.UNKNOWN
    }
    val mode = when (row.textValue("displayMode")) {
        "poster" -> MediaCatalogDisplayMode.POSTER; "landscape" -> MediaCatalogDisplayMode.LANDSCAPE
        "unmatched" -> MediaCatalogDisplayMode.UNMATCHED; else -> MediaCatalogDisplayMode.UNKNOWN
    }
    val artwork = if (!row.has("artwork") || row.isNull("artwork")) null else parseArtwork(row.getJSONObject("artwork"), ref)
    val backdrop = if (!row.has("backdrop") || row.isNull("backdrop")) null else
        parseArtwork(row.getJSONObject("backdrop"), ref).also { require(it.role == "backdrop") }
    val rating = if (!row.has("primaryRating") || row.isNull("primaryRating")) null else row.getJSONObject("primaryRating").let {
        val scale = it.decimalValue("scale"); require(scale > 0)
        MediaCatalogRating(it.textValue("provider"), ratingMetric(it.textValue("metric")), it.decimalValue("value"), scale,
            it.optionalTextValue("audience"), it.optionalIntegerValue("count"), it.optionalTextValue("countKind"), it.optionalTextValue("countPrecision"))
    }
    val duration = if (!row.has("duration") || row.isNull("duration")) null else row.getJSONObject("duration").let {
        val seconds = it.decimalValue("seconds"); require(seconds >= 0)
        MediaCatalogDuration(seconds, it.textValue("basis"), it.optionalTextValue("editionId")?.also(::requireMediaRouteId))
    }
    val seriesId = if (kind == MediaItemKind.SERIES) ref.itemId else parentSeriesId
    val seasonId = if (kind == MediaItemKind.SEASON) ref.itemId else null
    val counts = if (!row.has("episodes") || row.isNull("episodes")) null else parseCounts(row.getJSONObject("episodes"), ref.libraryId, seriesId, seasonId)
    val actions = actionMap(row, "canActions", deviceSession, kind, availability,
        kind != MediaItemKind.UNKNOWN && availability != MediaCatalogAvailability.UNKNOWN && state != MediaCatalogMatchState.UNKNOWN &&
            metadata != MediaCatalogMetadataState.UNKNOWN && mode != MediaCatalogDisplayMode.UNKNOWN)
    return MediaCatalogCard(ref, row.integerValue("revision", 1), kind, row.getJSONArray("contentCategories").catalogStrings(128), state,
        row.textValue("title"), row.optionalTextValue("originalTitle"), row.optionalIntegerValue("year"), mode, artwork, rating, duration,
        counts, availability, metadata, row.integerValue("firstAddedAt"), row.optionalIntegerValue("seriesUpdatedAt"),
        row.optionalIntegerValue("size"), row.optionalTextValue("extension"), actions, backdrop)
}

private fun rowQueryKey(id: MediaCatalogRowId): String = "/api/media-library/v1/home/rows/${id.wire}"
internal fun catalogRowEndpoint(id: MediaCatalogRowId): String {
    require(id != MediaCatalogRowId.UNKNOWN) { "未知影视首页栏目" }
    return rowQueryKey(id)
}

private fun cursorFrom(
    row: JSONObject, key: String, scope: MediaSessionScope, deviceSession: String, request: MediaCatalogCursor?, maxBytes: Int = 4096,
): MediaCatalogCursor? {
    val value = row.optionalTextValue("nextCursor") ?: return null
    catalogCursorValue(value, maxBytes)
    require(request == null || !request.matches(value)) { "影视分页没有前进" }
    return MediaCatalogCursor(value, scope, deviceSession, key)
}

private fun parseRow(row: JSONObject, scope: MediaSessionScope, deviceSession: String, request: MediaCatalogCursor? = null): MediaCatalogHomeRow {
    val id = MediaCatalogRowId.from(row.textValue("id"))
    val state = pageState(row.textValue("state"))
    val revision = safeShort(row.textValue("revision"))
    val kind = row.textValue("kind")
    if (id == MediaCatalogRowId.UNKNOWN) {
        // A new server row cannot introduce an arbitrary fetch endpoint.
        return MediaCatalogHomeRow(id, kind, MediaCatalogPageState.UNSUPPORTED, emptyList(), null, false, revision)
    }
    require(row.textValue("endpoint") == rowQueryKey(id)) { "影视栏目地址无效" }
    val next = cursorFrom(row, rowQueryKey(id), scope, deviceSession, request)
    val hasMore = row.booleanValue("hasMore")
    require(hasMore == (next != null) && (state != MediaCatalogPageState.PARTIAL || hasMore))
    val max = if (id == MediaCatalogRowId.HERO) 5 else 12
    val items = if (row.has("items")) row.getJSONArray("items").catalogRows(max) { parseCatalogCard(it, scope, deviceSession) } else emptyList()
    return MediaCatalogHomeRow(id, kind, state, items, next, hasMore, revision)
}

internal fun parseCatalogHome(row: JSONObject, scope: MediaSessionScope, deviceSession: String): MediaCatalogHome {
    val state = when (row.textValue("state")) {
        "no_libraries" -> MediaCatalogHomeState.NO_LIBRARIES; "empty_library" -> MediaCatalogHomeState.EMPTY_LIBRARY
        "scanning" -> MediaCatalogHomeState.SCANNING; "ready" -> MediaCatalogHomeState.READY
        "partial" -> MediaCatalogHomeState.PARTIAL; "source_offline" -> MediaCatalogHomeState.SOURCE_OFFLINE
        "restricted" -> MediaCatalogHomeState.RESTRICTED; "service_unavailable" -> MediaCatalogHomeState.SERVICE_UNAVAILABLE
        else -> error("未知影视首页状态")
    }
    val libraries = row.getJSONArray("libraries").catalogRows(10_000, ::parseMediaLibrary)
    val hero = if (!row.has("hero") || row.isNull("hero")) null else parseRow(row.getJSONObject("hero"), scope, deviceSession).also {
        require(it.id == MediaCatalogRowId.HERO)
    }
    val rows = row.getJSONArray("rows").catalogRows(8) { parseRow(it, scope, deviceSession) }
    val capabilitiesObject = row.getJSONObject("capabilities")
    val capabilities = capabilitiesObject.keys().asSequence().associateWith { capabilitiesObject.booleanValue(it) }
    return MediaCatalogHome(row.integerValue("schemaVersion", 1), state, libraries, hero, rows, capabilities,
        actionMap(row, "canActions", deviceSession, null, null))
}

internal fun parseCatalogHomeRow(row: JSONObject, scope: MediaSessionScope, deviceSession: String, requested: MediaCatalogRowId, cursor: MediaCatalogCursor?): MediaCatalogHomeRow =
    parseRow(row, scope, deviceSession, cursor).also { require(it.id == requested) }

internal fun <T> parseCatalogPage(
    row: JSONObject, scope: MediaSessionScope, deviceSession: String, queryKey: String, limit: Int,
    requestedCursor: MediaCatalogCursor?, parse: (JSONObject) -> T,
): MediaCatalogPage<T> {
    val state = pageState(row.textValue("pageState"))
    val next = cursorFrom(row, queryKey, scope, deviceSession, requestedCursor)
    val hasMore = row.booleanValue("hasMore")
    require(hasMore == (next != null) && (state != MediaCatalogPageState.PARTIAL || hasMore))
    val revision = safeShort(row.textValue("revision"))
    val orderId = row.optionalTextValue("orderId")?.also(::requireMediaRouteId)
    return MediaCatalogPage(row.getJSONArray("items").catalogRows(limit, parse), next, hasMore, state, revision, orderId)
}

internal fun parseCatalogDetail(row: JSONObject, ref: MediaCatalogRef, deviceSession: String, queryKey: String, requestedCursor: MediaCatalogCursor?): MediaCatalogDetail {
    val rawState = row.optionalTextValue("pageState")
    if (rawState == "partial") {
        require(row.booleanValue("hasMore"))
        return MediaCatalogDetail.Partial(requireNotNull(cursorFrom(row, queryKey, ref.scope, deviceSession, requestedCursor)))
    }
    val state = when (rawState) {
        null -> MediaCatalogDetailState.LEGACY; "ready" -> MediaCatalogDetailState.READY
        "partial_metadata" -> MediaCatalogDetailState.PARTIAL_METADATA; "offline" -> MediaCatalogDetailState.OFFLINE
        "source_changed" -> MediaCatalogDetailState.SOURCE_CHANGED; else -> error("未知影视详情状态")
    }
    val detail = parseMediaItemDetail(row)
    require(detail.item.libraryId == ref.libraryId && detail.item.id == ref.itemId)
    val card = if (!row.has("card") || row.isNull("card")) null else parseCatalogCard(row.getJSONObject("card"), ref.scope, deviceSession,
        if (detail.item.kind == MediaItemKind.SEASON) detail.item.parentId else null).also {
        require(it.ref == ref)
    }
    return MediaCatalogDetail.Complete(detail, card, state)
}

internal fun parseCatalogSeason(row: JSONObject, series: MediaCatalogRef, deviceSession: String): MediaCatalogSeason {
    val id = row.routeId("seasonId")
    val card = parseCatalogCard(row.getJSONObject("card"), series.scope, deviceSession, series.itemId)
    require(card.ref.libraryId == series.libraryId && card.ref.itemId == id && card.structureKind == MediaItemKind.SEASON)
    return MediaCatalogSeason(series, card.ref, row.integerValue("number"), row.textValue("label"), row.routeId("orderId"), card)
}

internal fun parseCatalogEpisode(row: JSONObject, series: MediaCatalogRef, seasonId: String, deviceSession: String): MediaCatalogEpisode {
    val id = row.routeId("episodeId")
    require(row.routeId("seasonId") == seasonId)
    val card = parseCatalogCard(row.getJSONObject("card"), series.scope, deviceSession, series.itemId)
    require(card.ref.libraryId == series.libraryId && card.ref.itemId == id && card.structureKind == MediaItemKind.EPISODE)
    val playback = if (!row.has("playback") || row.isNull("playback")) null else row.getJSONObject("playback").let { value ->
        val mode = when (value.textValue("playMode")) {
            "single" -> MediaCatalogPlaybackMode.SINGLE; "combined_file" -> MediaCatalogPlaybackMode.COMBINED_FILE
            "checking" -> MediaCatalogPlaybackMode.CHECKING; else -> MediaCatalogPlaybackMode.UNKNOWN
        }
        val coverage = when (value.textValue("coverageState")) {
            "ready" -> MediaCatalogCoverageState.READY; "partial" -> MediaCatalogCoverageState.PARTIAL
            "deferred" -> MediaCatalogCoverageState.DEFERRED; else -> MediaCatalogCoverageState.UNKNOWN
        }
        val ids = value.getJSONArray("coveredEpisodeIds").catalogStrings(128).map {
            requireMediaRouteId(it); MediaCatalogRef(series.scope, series.libraryId, it, deviceSession)
        }
        MediaCatalogEpisodePlayback(mode, value.booleanValue("independentStart") && mode == MediaCatalogPlaybackMode.SINGLE && coverage == MediaCatalogCoverageState.READY,
            ids, coverage, value.optionalTextValue("label"))
    }
    return MediaCatalogEpisode(card.ref, seasonId, row.integerValue("seasonNumber"), row.integerValue("episodeNumber"), row.routeId("orderId"), card, playback)
}

internal fun parseCatalogCounts(row: JSONObject, series: MediaCatalogRef, seasonId: String?): MediaCatalogEpisodeCounts =
    parseCounts(row, series.libraryId, series.itemId, seasonId)

internal fun parseCatalogVariant(row: JSONObject, ref: MediaCatalogRef): MediaCatalogVariant {
    require(row.routeId("itemId") == ref.itemId)
    val duration = row.optionalDecimalValue("durationSeconds")?.also { require(it >= 0) }
    return MediaCatalogVariant(ref, row.routeId("editionId"), row.routeId("variantId"), row.routeId("bindingId"), row.routeId("sourceFileId"),
        row.textValue("editionName"), row.booleanValue("isDefault"), row.textValue("sourceLabel"), row.textValue("displayName"),
        row.textValue("contentType"), row.integerValue("size"), row.integerValue("fileRevision", 1), row.integerValue("identityEpoch", 1),
        row.booleanValue("weakIdentity"), duration, row.optionalTextValue("resolution"))
}

internal fun parseCatalogVariants(row: JSONObject, ref: MediaCatalogRef, deviceSession: String, queryKey: String, limit: Int, requestedCursor: MediaCatalogCursor?): MediaCatalogVariantPage =
    MediaCatalogVariantPage(row.getJSONArray("items").catalogRows(limit) { parseCatalogVariant(it, ref) },
        cursorFrom(row, queryKey, ref.scope, deviceSession, requestedCursor, 512))

internal fun parseCatalogPlayback(row: JSONObject, variant: MediaCatalogVariant): MediaCatalogPlaybackResolution {
    require(row.routeId("itemId") == variant.ref.itemId && row.routeId("editionId") == variant.editionId &&
        row.routeId("variantId") == variant.variantId && row.routeId("sourceFileId") == variant.sourceFileId)
    val endpointText = row.optionalTextValue("endpoint")
    val endpoint = endpointText?.let {
        val uri = URI(it)
        val parts = uri.rawPath?.split('/') ?: emptyList()
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null &&
            parts.size == 7 && parts[0].isEmpty() && parts[1] == "api" && parts[2] == "media-library" && parts[3] == "v1" &&
            parts[4] == "streams" && parts[6] == "original" && parts[5].matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "影视播放地址无效" }
        MediaPlaybackEndpoint(it)
    }
    val duration = row.optionalDecimalValue("durationSeconds")?.also { require(it >= 0) }
    val leaseGeneration = row.optionalIntegerValue("leaseGeneration")?.also { require(it > 0) }
    val sessionId = optionalSafeText(row, "sessionId")?.also(::requireMediaRouteId)
    val leaseId = optionalSafeText(row, "leaseId")?.also(::requireMediaRouteId)
    if (leaseId != null && endpointText != null) require(endpointText == "/api/media-library/v1/streams/$leaseId/original")
    val expiresAt = row.integerValue("expiresAt", 1)
    val renewAfter = row.optionalIntegerValue("renewAfter")?.also { require(it <= expiresAt) }
    return MediaCatalogPlaybackResolution(variant.ref, variant.editionId, variant.variantId, variant.sourceFileId,
        row.integerValue("fileRevision", 1), row.integerValue("identityEpoch", 1), row.booleanValue("weakIdentity"),
        row.booleanValue("canResumeAcrossOpen"), row.booleanValue("resumeRequiresSameRevision"), row.textValue("displayName"),
        row.textValue("contentType"), row.integerValue("size"), duration, row.optionalTextValue("resolution"),
        row.booleanValue("rangeSupported"), endpoint, expiresAt, sessionId,
        leaseId, leaseGeneration, renewAfter,
        optionalSafeText(row, "resumeMode"), optionalSafeText(row, "timelineToken"))
}
