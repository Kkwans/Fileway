package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject

/** A preview is bound to the item, account and device session that requested it. */
class MediaSeriesMappingPreview internal constructor(
    val ref: MediaCatalogRef, val id: String, val title: String,
    val season: Int, val episodes: List<Int>, val combined: Boolean,
    val preservedFields: List<String>, val expiresAt: Long,
) {
    override fun toString(): String = "MediaSeriesMappingPreview(redacted)"
}

data class MediaSeriesMappingResult(
    val libraryId: String, val itemId: String, val seriesId: String, val seasonId: String,
    val episodeIds: List<String>, val mappingRevision: String, val itemRevision: Long,
    val structureRevision: Long, val playMode: String, val independentStart: Boolean,
) {
    override fun toString(): String = "MediaSeriesMappingResult(redacted)"
}

internal fun parseSeriesMappingResult(row: JSONObject): MediaSeriesMappingResult {
    fun id(key: String) = (row.get(key) as? String ?: error("影视关联响应无效")).also(::requireMediaRouteId)
    fun revision(key: String): Long = ((row.get(key) as? Number)?.toString()?.toLongOrNull()
        ?: error("影视关联响应无效")).also { require(it > 0) }
    val episodes = row.getJSONArray("episodeIds")
    require(episodes.length() in 1..100)
    val ids = (0 until episodes.length()).map { (episodes.get(it) as? String ?: error("影视关联响应无效")).also(::requireMediaRouteId) }
    require(ids.distinct().size == ids.size)
    val mode = row.getString("playMode").also { require(it in setOf("single", "combined_file")) }
    val independent = row.get("independentStart") as? Boolean ?: error("影视关联响应无效")
    require(mode != "combined_file" || !independent)
    return MediaSeriesMappingResult(id("libraryId"), id("itemId"), id("seriesId"), id("seasonId"), ids,
        id("mappingRevision"), revision("itemRevision"), revision("structureRevision"), mode, independent)
}
