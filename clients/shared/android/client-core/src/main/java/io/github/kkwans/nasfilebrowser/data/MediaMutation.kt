package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject

internal enum class MediaMutationKind {
    CREATE_LIBRARY, UPDATE_LIBRARY, ARCHIVE_LIBRARY, RESTORE_LIBRARY, GRANT_MEMBER, REVOKE_MEMBER,
    CREATE_SOURCE, START_SCAN, CANCEL_SCAN, RETRY_SCAN,
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
    val scanId: String? = null, val userId: Long? = null, body: JSONObject,
) {
    private val json = body.toString()
    init {
        val input = JSONObject(json)
        val keys = when (kind) {
            MediaMutationKind.CREATE_LIBRARY -> setOf("operationId", "name")
            MediaMutationKind.UPDATE_LIBRARY -> setOf("operationId", "expectedRevision", "name")
            MediaMutationKind.ARCHIVE_LIBRARY, MediaMutationKind.RESTORE_LIBRARY, MediaMutationKind.REVOKE_MEMBER -> setOf("operationId", "expectedRevision")
            MediaMutationKind.GRANT_MEMBER -> setOf("operationId", "expectedRevision", "role")
            MediaMutationKind.CREATE_SOURCE -> setOf("operationId", "expectedRevision", "wireRoot")
            MediaMutationKind.START_SCAN -> setOf("operationId", "expectedRevision", "bindingId", "rulesRevision")
            MediaMutationKind.CANCEL_SCAN, MediaMutationKind.RETRY_SCAN -> setOf("operationId", "expectedGeneration")
        }
        input.exactKeys(keys)
        require(input.strictString("operationId") == operation.id && json.toByteArray(Charsets.UTF_8).size <= 8192)
        if ("expectedRevision" in keys) input.strictLong("expectedRevision")
        if ("expectedGeneration" in keys) input.strictLong("expectedGeneration")
        if ("rulesRevision" in keys) input.strictLong("rulesRevision")
        if ("bindingId" in keys) input.strictString("bindingId").also(::requireMediaId)
        if ("wireRoot" in keys) MediaSourceRoot.fromWire(input.strictString("wireRoot"))
        if ("role" in keys) require(input.strictString("role") in setOf("viewer", "editor"))
        if ("name" in keys) {
            val name = input.strictString("name").trim()
            require(name.isNotEmpty() && name.codePointCount(0, name.length) <= 200 && name.none(Char::isISOControl))
        }
        if (kind == MediaMutationKind.CREATE_LIBRARY) require(libraryId == null) else requireMediaRouteId(requireNotNull(libraryId))
        if (kind in setOf(MediaMutationKind.CANCEL_SCAN, MediaMutationKind.RETRY_SCAN)) requireMediaRouteId(requireNotNull(scanId)) else require(scanId == null)
        if (kind in setOf(MediaMutationKind.GRANT_MEMBER, MediaMutationKind.REVOKE_MEMBER)) require(userId != null && userId in 1..0xffff_ffffL) else require(userId == null)
    }

    fun body(): JSONObject = JSONObject(json)
    fun sameRequest(other: MediaMutationRequest): Boolean = operation == other.operation && kind == other.kind &&
        libraryId == other.libraryId && scanId == other.scanId && userId == other.userId && json == other.json

    val method: String get() = when (kind) {
        MediaMutationKind.UPDATE_LIBRARY -> "PATCH"
        MediaMutationKind.GRANT_MEMBER -> "PUT"
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
            MediaMutationKind.START_SCAN -> "$library/scans"
            MediaMutationKind.CANCEL_SCAN -> "$library/scans/$scanId/cancel"
            MediaMutationKind.RETRY_SCAN -> "$library/scans/$scanId/retry"
            MediaMutationKind.CREATE_LIBRARY -> error("invalid operation")
        }
    }

    fun parse(row: JSONObject): Any = when (kind) {
        MediaMutationKind.CREATE_LIBRARY, MediaMutationKind.UPDATE_LIBRARY, MediaMutationKind.ARCHIVE_LIBRARY, MediaMutationKind.RESTORE_LIBRARY ->
            parseMediaLibrary(row).also { if (libraryId != null) require(it.id == libraryId) }
        MediaMutationKind.GRANT_MEMBER, MediaMutationKind.REVOKE_MEMBER -> parseMediaMember(row).also { require(it.userId == userId) }
        MediaMutationKind.CREATE_SOURCE -> parseMediaSource(row).also { require(it.libraryId == libraryId) }
        MediaMutationKind.START_SCAN, MediaMutationKind.CANCEL_SCAN, MediaMutationKind.RETRY_SCAN -> parseMediaScan(row).also {
            require(it.libraryId == libraryId)
            if (kind == MediaMutationKind.CANCEL_SCAN) require(it.id == scanId)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("body", body()).apply {
        libraryId?.let { put("libraryId", it) }
        scanId?.let { put("scanId", it) }
        userId?.let { put("userId", it) }
    }

    companion object {
        fun fromJson(operation: MediaOperation, value: JSONObject): MediaMutationRequest {
            val kind = MediaMutationKind.valueOf(value.strictString("kind"))
            val library = if (value.has("libraryId")) value.strictString("libraryId") else null
            val scan = if (value.has("scanId")) value.strictString("scanId") else null
            val user = if (value.has("userId")) value.strictLong("userId") else null
            val expected = mutableSetOf("kind", "body").apply {
                if (library != null) add("libraryId")
                if (scan != null) add("scanId")
                if (user != null) add("userId")
            }
            value.exactKeys(expected)
            return MediaMutationRequest(operation, kind, library, scan, user, value.getJSONObject("body"))
        }
    }
}
