package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject

internal enum class MediaMutationKind {
    CREATE_LIBRARY, UPDATE_LIBRARY, ARCHIVE_LIBRARY, RESTORE_LIBRARY, GRANT_MEMBER, REVOKE_MEMBER,
    CREATE_SOURCE, START_SCAN, CANCEL_SCAN, RETRY_SCAN, ATTEST_SOURCE, PATCH_METADATA, UNDO_METADATA, PATCH_USER_STATE,
    UPDATE_METADATA_POLICY, CONFIRM_PROVIDER_MATCH, START_SCAN_BATCH,
}

internal fun requireMediaRouteId(value: String) {
    requireMediaId(value)
    // The implemented server generates opaque identifiers from these URL-safe
    // characters. Never let a restored record introduce another route.
    require(value.matches(Regex("[A-Za-z0-9_-]+"))) { "媒体库标识无效" }
}

private fun JSONObject.exactKeys(expected: Set<String>) {
    require(keys().asSequence().toSet() == expected) { "媒体库操作记录无效" }
}
private fun JSONObject.strictString(key: String): String = get(key) as? String ?: error("媒体库操作记录无效")
private fun JSONObject.strictLong(key: String, min: Long = 1): Long {
    val value = (get(key) as? Number)?.toString()?.toLongOrNull()
    require(value != null && value >= min) { "媒体库操作记录无效" }
    return value
}

/** The only serializable mutation forms are the known server management APIs. */
internal class MediaMutationRequest(
    val operation: MediaOperation, val kind: MediaMutationKind, val libraryId: String? = null,
    val scanId: String? = null, val userId: Long? = null, val bindingId: String? = null, val itemId: String? = null, body: JSONObject,
) {
    private val json = body.toString()
    init {
        val input = JSONObject(json)
        val keys = when (kind) {
            MediaMutationKind.CREATE_LIBRARY -> setOf("operationId", "name")
            MediaMutationKind.UPDATE_LIBRARY -> setOf("operationId", "expectedRevision", "name")
            MediaMutationKind.ARCHIVE_LIBRARY, MediaMutationKind.RESTORE_LIBRARY, MediaMutationKind.REVOKE_MEMBER -> setOf("operationId", "expectedRevision")
            MediaMutationKind.GRANT_MEMBER -> setOf("operationId", "expectedRevision", "role")
            MediaMutationKind.CREATE_SOURCE, MediaMutationKind.ATTEST_SOURCE -> setOf("operationId", "expectedRevision", "wireRoot")
            MediaMutationKind.START_SCAN -> setOf("operationId", "expectedRevision", "bindingId", "rulesRevision")
            MediaMutationKind.START_SCAN_BATCH -> setOf("operationId", "rulesRevision", "sources")
            MediaMutationKind.CANCEL_SCAN, MediaMutationKind.RETRY_SCAN -> setOf("operationId", "expectedGeneration")
            MediaMutationKind.PATCH_METADATA -> setOf("operationId", "expectedRevision", "fields")
            MediaMutationKind.UNDO_METADATA -> setOf("operationId", "expectedRevision", "targetRevision")
            MediaMutationKind.PATCH_USER_STATE -> buildSet {
                add("operationId")
                add("expectedRevision")
                listOf("favorite", "manualWatched").filter(input::has).forEach {
                    require(input.get(it) is Boolean)
                    add(it)
                }
                require(size > 2)
            }
            MediaMutationKind.UPDATE_METADATA_POLICY -> setOf("operationId", "expectedRevision", "mode", "connectionId", "language", "region", "allowEditorJobs")
            MediaMutationKind.CONFIRM_PROVIDER_MATCH -> setOf("operationId", "itemRevision", "structureRevision", "kind", "externalId", "connectionId")
        }
        input.exactKeys(keys)
        // Normal JNI commands are capped at 1 MiB; leave headroom for the envelope.
        val maxBody = if (kind == MediaMutationKind.PATCH_METADATA) 768 * 1024 else 8192
        require(input.strictString("operationId") == operation.id && json.toByteArray(Charsets.UTF_8).size <= maxBody)
        if ("expectedRevision" in keys) input.strictLong("expectedRevision", if (kind in setOf(MediaMutationKind.PATCH_USER_STATE, MediaMutationKind.UPDATE_METADATA_POLICY)) 0 else 1)
        if ("expectedGeneration" in keys) input.strictLong("expectedGeneration")
        if ("targetRevision" in keys) input.strictLong("targetRevision")
        if ("rulesRevision" in keys) input.strictLong("rulesRevision")
        if (kind == MediaMutationKind.START_SCAN_BATCH) scanBatchBindings(input)
        if ("bindingId" in keys) input.strictString("bindingId").also(::requireMediaId)
        if ("wireRoot" in keys) MediaSourceRoot.fromWire(input.strictString("wireRoot"))
        if ("role" in keys) require(input.strictString("role") in setOf("viewer", "editor"))
        if ("fields" in keys) validateMediaMetadataPatch(input.getJSONObject("fields"))
        if (kind == MediaMutationKind.UPDATE_METADATA_POLICY) {
            val mode = MediaProviderPolicyMode.entries.firstOrNull { it != MediaProviderPolicyMode.UNKNOWN && it.wire == input.strictString("mode") }
            requireProviderPolicy(requireNotNull(mode), input.strictString("connectionId").ifEmpty { null }, input.strictString("language"), input.strictString("region"))
            require(input.get("allowEditorJobs") is Boolean)
        }
        if (kind == MediaMutationKind.CONFIRM_PROVIDER_MATCH) {
            input.strictLong("itemRevision"); input.strictLong("structureRevision")
            require(input.strictString("kind") in setOf("movie", "series"))
            requireProviderExternalId(input.strictString("externalId"))
            input.strictString("connectionId").takeIf(String::isNotEmpty)?.let(::requireProviderConnectionId)
        }
        if ("name" in keys) {
            val name = input.strictString("name").trim()
            require(name.isNotEmpty() && name.codePointCount(0, name.length) <= 200 && name.none(Char::isISOControl))
        }
        if (kind == MediaMutationKind.CREATE_LIBRARY) require(libraryId == null) else requireMediaRouteId(requireNotNull(libraryId))
        if (kind in setOf(MediaMutationKind.CANCEL_SCAN, MediaMutationKind.RETRY_SCAN)) requireMediaRouteId(requireNotNull(scanId)) else require(scanId == null)
        if (kind in setOf(MediaMutationKind.GRANT_MEMBER, MediaMutationKind.REVOKE_MEMBER)) require(userId != null && userId in 1..0xffff_ffffL) else require(userId == null)
        if (kind == MediaMutationKind.ATTEST_SOURCE) requireMediaRouteId(requireNotNull(bindingId)) else require(bindingId == null)
        if (kind in setOf(MediaMutationKind.PATCH_METADATA, MediaMutationKind.UNDO_METADATA, MediaMutationKind.PATCH_USER_STATE, MediaMutationKind.CONFIRM_PROVIDER_MATCH)) requireMediaRouteId(requireNotNull(itemId)) else require(itemId == null)
    }

    fun body(): JSONObject = JSONObject(json)
    fun sameRequest(other: MediaMutationRequest): Boolean = operation == other.operation && kind == other.kind &&
        libraryId == other.libraryId && scanId == other.scanId && userId == other.userId && bindingId == other.bindingId && itemId == other.itemId && json == other.json

    val method: String get() = when (kind) {
        MediaMutationKind.UPDATE_LIBRARY, MediaMutationKind.PATCH_METADATA, MediaMutationKind.PATCH_USER_STATE -> "PATCH"
        MediaMutationKind.GRANT_MEMBER, MediaMutationKind.UPDATE_METADATA_POLICY -> "PUT"
        MediaMutationKind.REVOKE_MEMBER -> "DELETE"
        else -> "POST"
    }
    val endpoint: String get() {
        val root = "/api/media-library/v1/libraries"
        if (kind == MediaMutationKind.CREATE_LIBRARY) return root
        val library = "$root/$libraryId"
        return when (kind) {
            MediaMutationKind.UPDATE_LIBRARY -> library
            MediaMutationKind.ARCHIVE_LIBRARY -> "$library/archive"
            MediaMutationKind.RESTORE_LIBRARY -> "$library/restore"
            MediaMutationKind.GRANT_MEMBER, MediaMutationKind.REVOKE_MEMBER -> "$library/members/$userId"
            MediaMutationKind.CREATE_SOURCE -> "$library/sources/local"
            MediaMutationKind.ATTEST_SOURCE -> "$library/sources/$bindingId/attest"
            MediaMutationKind.PATCH_METADATA -> "$library/items/$itemId/metadata"
            MediaMutationKind.UNDO_METADATA -> "$library/items/$itemId/metadata/undo"
            MediaMutationKind.PATCH_USER_STATE -> "$library/items/$itemId/user-state"
            MediaMutationKind.UPDATE_METADATA_POLICY -> "$library/metadata-policy"
            MediaMutationKind.CONFIRM_PROVIDER_MATCH -> "$library/items/$itemId/match-confirmation"
            MediaMutationKind.START_SCAN -> "$library/scans"
            MediaMutationKind.START_SCAN_BATCH -> "$library/scan-batches"
            MediaMutationKind.CANCEL_SCAN -> "$library/scans/$scanId/cancel"
            MediaMutationKind.RETRY_SCAN -> "$library/scans/$scanId/retry"
            MediaMutationKind.CREATE_LIBRARY -> error("invalid operation")
        }
    }

    fun parse(row: JSONObject): Any = when (kind) {
        MediaMutationKind.CREATE_LIBRARY, MediaMutationKind.UPDATE_LIBRARY, MediaMutationKind.ARCHIVE_LIBRARY, MediaMutationKind.RESTORE_LIBRARY ->
            parseMediaLibrary(row).also { if (libraryId != null) require(it.id == libraryId) }
        MediaMutationKind.GRANT_MEMBER, MediaMutationKind.REVOKE_MEMBER -> parseMediaMember(row).also { require(it.userId == userId) }
        MediaMutationKind.CREATE_SOURCE, MediaMutationKind.ATTEST_SOURCE -> parseMediaSource(row).also {
            require(it.libraryId == libraryId && (bindingId == null || it.id == bindingId))
        }
        MediaMutationKind.PATCH_METADATA, MediaMutationKind.UNDO_METADATA -> parseMediaMetadataReceipt(row).also { require(it.itemId == itemId) }
        MediaMutationKind.PATCH_USER_STATE -> parseMediaCatalogUserState(row)
        MediaMutationKind.START_SCAN_BATCH -> {
            val expected = scanBatchBindings(body())
            val items = row.getJSONArray("items")
            require(items.length() == expected.size)
            val jobs = (0 until items.length()).map { parseMediaScan(items.getJSONObject(it)) }
            require(jobs.all { it.libraryId == libraryId } && jobs.mapNotNull { it.bindingId }.toSet() == expected)
            MediaScanBatch(requireNotNull(libraryId), jobs)
        }
        MediaMutationKind.UPDATE_METADATA_POLICY -> parseProviderPolicy(row).also { require(it.libraryId == libraryId) }
        MediaMutationKind.CONFIRM_PROVIDER_MATCH -> parseProviderMatchReceipt(row).also {
            val input = body()
            require(it.libraryId == libraryId && it.itemId == itemId && it.kind.wire == input.strictString("kind") && it.externalId == input.strictString("externalId"))
        }
        MediaMutationKind.START_SCAN, MediaMutationKind.CANCEL_SCAN, MediaMutationKind.RETRY_SCAN -> parseMediaScan(row).also {
            require(it.libraryId == libraryId)
            if (kind == MediaMutationKind.CANCEL_SCAN) require(it.id == scanId)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("body", body()).apply {
        libraryId?.let { put("libraryId", it) }
        scanId?.let { put("scanId", it) }
        userId?.let { put("userId", it) }
        bindingId?.let { put("bindingId", it) }
        itemId?.let { put("itemId", it) }
    }

    companion object {
        fun fromJson(operation: MediaOperation, value: JSONObject): MediaMutationRequest {
            val kind = MediaMutationKind.valueOf(value.strictString("kind"))
            val library = if (value.has("libraryId")) value.strictString("libraryId") else null
            val scan = if (value.has("scanId")) value.strictString("scanId") else null
            val user = if (value.has("userId")) value.strictLong("userId") else null
            val binding = if (value.has("bindingId")) value.strictString("bindingId") else null
            val item = if (value.has("itemId")) value.strictString("itemId") else null
            val expected = mutableSetOf("kind", "body").apply {
                if (library != null) add("libraryId")
                if (scan != null) add("scanId")
                if (user != null) add("userId")
                if (binding != null) add("bindingId")
                if (item != null) add("itemId")
            }
            value.exactKeys(expected)
            return MediaMutationRequest(operation, kind, library, scan, user, binding, item, value.getJSONObject("body"))
        }
    }
}

private fun scanBatchBindings(input: JSONObject): Set<String> {
    val sources = input.getJSONArray("sources")
    require(sources.length() in 1..60)
    return (0 until sources.length()).map { index ->
        val source = sources.getJSONObject(index)
        source.exactKeys(setOf("bindingId", "expectedRevision"))
        source.strictLong("expectedRevision")
        source.strictString("bindingId").also(::requireMediaRouteId)
    }.toSet().also { require(it.size == sources.length()) }
}

private val metadataFieldLimits = mapOf(
    "title" to 1024, "originaltitle" to 1024, "sorttitle" to 1024, "showtitle" to 1024,
    "plot" to 65536, "outline" to 65536, "tagline" to 4096,
    *listOf("year", "premiered", "aired", "releasedate", "season", "episode", "seasonnumber", "displayseason",
        "displayepisode", "runtime", "mpaa", "country", "genre", "tag", "studio", "director", "credits", "writer", "status", "collectionnumber")
        .map { it to 1024 }.toTypedArray(),
)

/** Validate both newly made and restored operations against the server's patch semantics. */
private fun validateMediaMetadataPatch(fields: JSONObject) {
    val names = fields.keys().asSequence().toList()
    require(names.isNotEmpty() && names.size <= 64)
    for (name in names) {
        val maxLength = requireNotNull(metadataFieldLimits[name]) { "影视资料字段不受支持" }
        val field = fields.getJSONObject(name)
        val keys = field.keys().asSequence().toSet()
        require(keys.all { it in setOf("values", "clear", "reset", "locked") })
        val clear = if (field.has("clear")) field.get("clear") as? Boolean ?: error("影视资料格式无效") else false
        val reset = if (field.has("reset")) field.get("reset") as? Boolean ?: error("影视资料格式无效") else false
        val locked = if (field.has("locked")) field.get("locked") as? Boolean ?: error("影视资料格式无效") else null
        val hasValues = field.has("values")
        require(!(clear && (hasValues || reset)) && !(reset && (hasValues || locked == true)) && (reset || clear || hasValues || locked != null))
        if (hasValues) {
            val values = field.getJSONArray("values")
            require(values.length() in 1..128 && (name != "title" || values.length() == 1))
            for (index in 0 until values.length()) {
                val value = values.get(index) as? String ?: error("影视资料格式无效")
                require(value.isNotBlank() && value.codePointCount(0, value.length) <= maxLength &&
                    value.none { it.isISOControl() && it !in "\n\r\t" })
                require(runCatching { Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value)) }.isSuccess)
            }
        }
    }
}
