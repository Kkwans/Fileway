package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject

enum class MediaItemKind { MOVIE, SERIES, SEASON, EPISODE, VIDEO, UNKNOWN }
enum class MediaItemSourceState { AVAILABLE, OFFLINE, CHANGED, UNVERIFIED, UNKNOWN }
enum class MediaItemMetadataState { NONE, AVAILABLE, OFFLINE, RESTRICTED, UNKNOWN }
enum class MediaMetadataSource { FILENAME, NFO, MANUAL, ONLINE, UNKNOWN }
enum class MediaRatingMetric { USER_AVERAGE, CRITIC_APPROVAL, AUDIENCE_APPROVAL, CRITIC_AVERAGE, UNKNOWN }
enum class MediaArtworkStatus { DECLARED, REMOTE_UNAVAILABLE, INVALID, UNKNOWN }

data class MediaItemSummary(
    val id: String, val libraryId: String, val kind: MediaItemKind, val parentId: String?,
    val title: String, val revision: Long, val createdAt: Long,
    val sourceState: MediaItemSourceState, val metadataState: MediaItemMetadataState,
)
data class MediaMetadataField(val values: List<String>, val source: MediaMetadataSource, val locked: Boolean, val explicitClear: Boolean, val revision: Long)
data class MediaCredit(val id: String, val personId: String, val name: String, val kind: String, val role: String, val roles: List<String>, val position: Long)

/** A value remains in its reported scale and metric; null means not supplied or not valid. */
data class MediaRatingValue(
    val rawSource: String, val rawScale: String?, val fields: Map<String, List<String>>, val provider: String?,
    val metric: MediaRatingMetric, val audience: String?, val value: Double?, val scale: Double?, val count: Long?,
    val countKind: String?, val countPrecision: String?, val default: Boolean?, val issues: List<String>,
)
data class MediaRating(val id: String, val scope: String, val origin: String, val importedAt: Long, val rating: MediaRatingValue)
/** An artwork ID is a declaration only; the API does not provide an image URL. */
data class MediaArtwork(val id: String, val role: String, val status: MediaArtworkStatus)
data class MediaExternalId(val provider: String, val entityKind: String, val value: String)
data class MediaMetadataCandidate(val code: String, val sourceKinds: List<String>)
data class MediaRevisionPage<T>(val items: List<T>, val nextCursor: String?, val revision: Long)

data class MediaItemDetail(
    val item: MediaItemSummary, val metadataRevision: Long, val fields: Map<String, MediaMetadataField>,
    val credits: MediaRevisionPage<MediaCredit>, val ratings: MediaRevisionPage<MediaRating>,
    val artwork: MediaRevisionPage<MediaArtwork>, val externalIds: MediaRevisionPage<MediaExternalId>,
    val issues: List<String>, val candidates: MediaRevisionPage<MediaMetadataCandidate>, val onlineMatch: MediaOnlineMatch? = null,
)
data class MediaMetadataReceipt(val itemId: String, val revision: Long, val metadataRevision: Long)

/** Null means omitted; an empty values list is not a clear. Use clear or reset explicitly. */
data class MediaMetadataFieldPatch(val values: List<String>? = null, val clear: Boolean = false, val reset: Boolean = false, val locked: Boolean? = null)

private fun JSONObject.text(key: String): String = get(key) as? String ?: error("影视资料响应格式无效")
private fun JSONObject.optionalText(key: String): String? = if (!has(key) || isNull(key)) null else text(key)
private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: error("影视资料响应格式无效")
private fun JSONObject.integer(key: String, minimum: Long = 0): Long {
    val value = (get(key) as? Number)?.toString()?.toLongOrNull()
    require(value != null && value >= minimum) { "影视资料响应格式无效" }
    return value
}
private fun JSONObject.nullableInteger(key: String): Long? = if (isNull(key)) null else integer(key)
private fun JSONObject.nullableDecimal(key: String): Double? {
    if (isNull(key)) return null
    val value = (get(key) as? Number)?.toDouble()
    require(value != null && value.isFinite()) { "影视评分格式无效" }
    return value
}
private fun JSONObject.id(key: String): String = text(key).also(::requireMediaRouteId)
private fun JSONArray.strings(limit: Int): List<String> {
    require(length() <= limit)
    return (0 until length()).map { get(it) as? String ?: error("影视资料响应格式无效") }
}
private fun JSONObject.stringMap(key: String, maxFields: Int, maxValues: Int): Map<String, List<String>> {
    val row = getJSONObject(key)
    val keys = row.keys().asSequence().toList()
    require(keys.size <= maxFields)
    return keys.associateWith { row.getJSONArray(it).strings(maxValues) }
}
internal fun mediaCursor(value: String?): String? = value?.also {
    require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 256) { "影视资料分页参数无效" }
}
private fun JSONObject.cursor(key: String): String? = mediaCursor(optionalText(key))
private fun <T> JSONArray.rows(max: Int, parse: (JSONObject) -> T): List<T> {
    require(length() <= max)
    return (0 until length()).map { parse(getJSONObject(it)) }
}

internal fun parseMediaItem(row: JSONObject): MediaItemSummary {
    val kind = when (row.text("kind")) {
        "movie" -> MediaItemKind.MOVIE; "series" -> MediaItemKind.SERIES; "season" -> MediaItemKind.SEASON
        "episode" -> MediaItemKind.EPISODE; "video" -> MediaItemKind.VIDEO; else -> MediaItemKind.UNKNOWN
    }
    val state = when (row.text("sourceState")) {
        "available" -> MediaItemSourceState.AVAILABLE; "offline" -> MediaItemSourceState.OFFLINE
        "changed" -> MediaItemSourceState.CHANGED; "unverified" -> MediaItemSourceState.UNVERIFIED; else -> MediaItemSourceState.UNKNOWN
    }
    val metadata = when (row.text("metadataState")) {
        "none" -> MediaItemMetadataState.NONE; "available" -> MediaItemMetadataState.AVAILABLE
        "offline" -> MediaItemMetadataState.OFFLINE; "restricted" -> MediaItemMetadataState.RESTRICTED; else -> MediaItemMetadataState.UNKNOWN
    }
    return MediaItemSummary(row.id("id"), row.id("libraryId"), kind, row.optionalText("parentId")?.also(::requireMediaRouteId),
        row.text("title"), row.integer("revision", 1), row.integer("createdAt", 1), state, metadata)
}

internal fun parseMediaCredit(row: JSONObject): MediaCredit = MediaCredit(row.id("id"), row.id("personId"),
    row.text("name"), row.text("kind"), row.text("role"), row.getJSONArray("roles").strings(128), row.integer("position"))

internal fun parseMediaRating(row: JSONObject): MediaRating {
    val value = row.getJSONObject("rating")
    val metric = when (value.text("metric")) {
        "user_average" -> MediaRatingMetric.USER_AVERAGE; "critic_approval" -> MediaRatingMetric.CRITIC_APPROVAL
        "audience_approval" -> MediaRatingMetric.AUDIENCE_APPROVAL; "critic_average" -> MediaRatingMetric.CRITIC_AVERAGE
        else -> MediaRatingMetric.UNKNOWN
    }
    val default = if (!value.has("default") || value.isNull("default")) null else value.boolean("default")
    val issues = if (value.has("issues") && !value.isNull("issues")) value.getJSONArray("issues").strings(128) else emptyList()
    return MediaRating(row.id("id"), row.text("scope"), row.text("origin"), row.integer("importedAt"), MediaRatingValue(
        value.text("rawSource"), value.optionalText("rawScale"), value.stringMap("fields", 64, 128), value.optionalText("provider"),
        metric, value.optionalText("audience"), value.nullableDecimal("value"), value.nullableDecimal("scale"),
        value.nullableInteger("count"), value.optionalText("countKind"), value.optionalText("countPrecision"), default, issues))
}

internal fun parseMediaArtwork(row: JSONObject): MediaArtwork = MediaArtwork(row.id("id"), row.text("role"), when (row.text("status")) {
    "declared" -> MediaArtworkStatus.DECLARED; "remote_unavailable" -> MediaArtworkStatus.REMOTE_UNAVAILABLE
    "invalid" -> MediaArtworkStatus.INVALID; else -> MediaArtworkStatus.UNKNOWN
})
internal fun parseMediaExternalId(row: JSONObject): MediaExternalId = MediaExternalId(row.text("provider"), row.text("entityKind"), row.text("value"))
internal fun parseMediaCandidate(row: JSONObject): MediaMetadataCandidate = MediaMetadataCandidate(row.text("code"), row.getJSONArray("sourceKinds").strings(128))
internal fun parseMediaMetadataReceipt(row: JSONObject): MediaMetadataReceipt = MediaMetadataReceipt(row.id("itemId"), row.integer("revision", 1), row.integer("metadataRevision"))

internal fun parseMediaItemDetail(row: JSONObject): MediaItemDetail {
    val item = parseMediaItem(row.getJSONObject("item"))
    val fields = row.getJSONObject("fields")
    val keys = fields.keys().asSequence().toList()
    require(keys.size <= 64)
    val parsedFields = keys.associateWith { name ->
        val field = fields.getJSONObject(name)
        val source = when (field.text("source")) {
            "manual" -> MediaMetadataSource.MANUAL; "nfo" -> MediaMetadataSource.NFO
            "filename" -> MediaMetadataSource.FILENAME; "online" -> MediaMetadataSource.ONLINE; else -> MediaMetadataSource.UNKNOWN
        }
        MediaMetadataField(field.getJSONArray("values").strings(128), source, field.boolean("locked"),
            field.boolean("explicitClear"), field.integer("revision", 1))
    }
    return MediaItemDetail(item, row.integer("metadataRevision"), parsedFields,
        MediaRevisionPage(row.getJSONArray("credits").rows(20, ::parseMediaCredit), row.cursor("creditsNextCursor"), item.revision),
        MediaRevisionPage(row.getJSONArray("ratings").rows(20, ::parseMediaRating), row.cursor("ratingsNextCursor"), item.revision),
        MediaRevisionPage(row.getJSONArray("artwork").rows(20, ::parseMediaArtwork), row.cursor("artworkNextCursor"), item.revision),
        MediaRevisionPage(row.getJSONArray("externalIds").rows(20, ::parseMediaExternalId), row.cursor("externalIdsNextCursor"), item.revision),
        row.getJSONArray("issues").strings(20),
        MediaRevisionPage(row.getJSONArray("candidates").rows(20, ::parseMediaCandidate), row.cursor("issuesNextCursor"), item.revision),
        if (!row.has("onlineMatch") || row.isNull("onlineMatch")) null else parseMediaOnlineMatch(row.getJSONObject("onlineMatch")))
}

internal fun <T> parseMediaRevisionPage(row: JSONObject, limit: Int, cursor: String?, revision: Long, parse: (JSONObject) -> T): MediaRevisionPage<T> {
    val actual = row.integer("revision", 1)
    require(actual == revision) { "影视资料修订已变化" }
    val next = row.cursor("nextCursor")
    require(next == null || next != cursor)
    return MediaRevisionPage(row.getJSONArray("items").rows(limit, parse), next, actual)
}
