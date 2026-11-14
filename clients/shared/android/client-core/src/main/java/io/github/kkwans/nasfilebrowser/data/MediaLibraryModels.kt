package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.SessionContext
import org.json.JSONObject
import java.util.UUID

/** Durable identity of one account on one configured instance. No address or credential is included. */
data class MediaAccountScope(val profileId: String, val sourceRevision: Long, val accountKey: String, val userId: Long)

/** The owner/generation also fence responses from an earlier login to the same account. */
data class MediaSessionScope(val account: MediaAccountScope, val owner: String, val generation: Int)

internal fun mediaSessionScope(context: SessionContext): MediaSessionScope {
    val profile = context.profile
    val account = context.account
    require(account.profileId == profile.id && account.sourceRevision == profile.sourceRevision)
    require(context.api.profile == profile && context.api.identity.id == account.userId && context.owner.isNotBlank())
    return MediaSessionScope(MediaAccountScope(profile.id, profile.sourceRevision, account.key, account.userId), context.owner, context.generation)
}

/** Keep this value and the *same request parameters* until the operation is resolved.
 * The account scope allows replay after reconnect, but prevents replay on a different instance/account. */
data class MediaOperation(val account: MediaAccountScope, val id: String) {
    init { requireMediaId(id) }
    companion object {
        fun new(account: MediaAccountScope) = MediaOperation(account, UUID.randomUUID().toString())
    }
}

enum class MediaLibraryRole { OWNER, EDITOR, VIEWER }
enum class MediaMemberRole { EDITOR, VIEWER }

data class MediaLibrarySummary(
    val id: String, val name: String, val revision: Long, val role: MediaLibraryRole, val archived: Boolean,
)

data class MediaMemberChange(
    val userId: Long, val role: MediaMemberRole, val revision: Long, val libraryRevision: Long, val revoked: Boolean,
)

/** Opaque source locator. It is never decoded into a display path or printed in logs. */
class MediaSourceRoot private constructor(internal val wire: String) {
    override fun toString(): String = "MediaSourceRoot(redacted)"
    override fun equals(other: Any?): Boolean = other is MediaSourceRoot && wire == other.wire
    override fun hashCode(): Int = wire.hashCode()

    companion object {
        fun fromWire(wire: String): MediaSourceRoot {
            resourceWireBytes(wire) // Validate without replacing or re-encoding the original bytes.
            return MediaSourceRoot(wire)
        }
        fun fromDirectory(directory: ResourceRef): MediaSourceRoot {
            require(directory.directory) { "请选择服务器目录" }
            return fromWire(recentAccessRecordTarget(directory).wirePath)
        }
    }
}

enum class MediaSourceAccess { AVAILABLE, OFFLINE, UNVERIFIED, REBIND_REQUIRED, UNAVAILABLE, UNKNOWN }

/** Owner-only management data. Root fields never belong in catalog, image or playback state. */
data class MediaLibrarySource(
    val id: String, val sourceId: String, val libraryId: String,
    val revision: Long, val libraryRevision: Long, val authorizationRevision: Long,
    val access: MediaSourceAccess, val wireRoot: MediaSourceRoot?, val displayRoot: String?,
)

data class MediaPage<T>(val items: List<T>, val nextCursor: String?)

enum class MediaScanUnavailableReason { NOT_ENABLED, COORDINATOR_UNAVAILABLE, UNKNOWN }
/** Scanner status only: neither full media-library support nor permission for a particular root. */
data class MediaScanSupport(val localRulesRevision: Long, val available: Boolean, val reason: MediaScanUnavailableReason?)

enum class MediaScanStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELED, INTERRUPTED, UNKNOWN }
enum class MediaScanPhase { ENUMERATE, RECONCILE, READY, UNKNOWN }

data class MediaScan(
    val id: String, val libraryId: String, val status: MediaScanStatus, val generation: Long,
    val attempt: Long, val rulesRevision: Long, val phase: MediaScanPhase, val processed: Long,
    val createdAt: Long, val updatedAt: Long, val errorCode: String?, val bindingId: String? = null,
)
data class MediaScanBatch(val libraryId: String, val items: List<MediaScan>)

/** A failure is not a capability declaration. NOT_FOUND can also mean inaccessible. */
enum class MediaFailureKind { AUTHENTICATION, FORBIDDEN, NOT_FOUND, INVALID, CONFLICT, UNAVAILABLE, SERVER, NETWORK, INVALID_RESPONSE, STORAGE, CURSOR_STALE, CURSOR_EXPIRED, RATE_LIMITED }
data class MediaFailure(val kind: MediaFailureKind, val status: Int? = null, val mutationMayHaveCommitted: Boolean = false)

sealed interface MediaReadResult<out T> {
    val scope: MediaSessionScope
    data class Success<T>(override val scope: MediaSessionScope, val value: T, val sessionHealth: SessionHealth?) : MediaReadResult<T>
    data class Failure(override val scope: MediaSessionScope, val error: MediaFailure) : MediaReadResult<Nothing>
}

/** Successful values are receipts, not a fresh read; a replay may return an old revision. */
sealed interface MediaWriteResult<out T> {
    val scope: MediaSessionScope
    val operation: MediaOperation
    data class Success<T>(override val scope: MediaSessionScope, override val operation: MediaOperation,
        val value: T, val sessionHealth: SessionHealth?, val journalStorageFailed: Boolean = false,
        val sessionSyncIncomplete: Boolean = false) : MediaWriteResult<T>
    data class Failure(override val scope: MediaSessionScope, override val operation: MediaOperation,
        val error: MediaFailure, val journalStorageFailed: Boolean = false) : MediaWriteResult<Nothing>
}

internal fun requireMediaId(value: String) {
    require(value.isNotEmpty() && value.trim() == value && value.toByteArray(Charsets.UTF_8).size <= 200) { "媒体库标识无效" }
}

private fun JSONObject.mediaString(name: String): String = get(name) as? String ?: error("媒体库响应格式无效")
private fun JSONObject.mediaBoolean(name: String): Boolean = get(name) as? Boolean ?: error("媒体库响应格式无效")
private fun JSONObject.mediaLong(name: String, minimum: Long): Long {
    val raw = get(name)
    val value = (raw as? Number)?.toString()?.toLongOrNull()
    require(value != null && value >= minimum) { "媒体库响应格式无效" }
    return value
}
private fun JSONObject.mediaId(name: String): String = mediaString(name).also(::requireMediaId)
private fun JSONObject.optionalMediaString(name: String): String? = if (!has(name)) null else mediaString(name)
private fun memberRole(value: String): MediaMemberRole = when (value) {
    "viewer" -> MediaMemberRole.VIEWER
    "editor" -> MediaMemberRole.EDITOR
    else -> error("媒体库成员角色无效")
}

internal fun parseMediaLibrary(row: JSONObject): MediaLibrarySummary {
    val role = when (row.mediaString("role")) {
        "owner" -> MediaLibraryRole.OWNER
        "editor" -> MediaLibraryRole.EDITOR
        "viewer" -> MediaLibraryRole.VIEWER
        else -> error("媒体库角色无效")
    }
    val name = row.mediaString("name")
    require(name.isNotBlank()) { "媒体库名称无效" }
    return MediaLibrarySummary(row.mediaId("id"), name, row.mediaLong("revision", 1), role, row.mediaBoolean("archived"))
}

internal fun parseMediaMember(row: JSONObject): MediaMemberChange = MediaMemberChange(
    row.mediaLong("userId", 1).also { require(it <= 0xffff_ffffL) }, memberRole(row.mediaString("role")),
    row.mediaLong("revision", 1), row.mediaLong("libraryRevision", 1), row.mediaBoolean("revoked"),
)

internal fun parseMediaSource(row: JSONObject): MediaLibrarySource {
    val access = when (row.mediaString("access")) {
        "available" -> MediaSourceAccess.AVAILABLE
        "offline" -> MediaSourceAccess.OFFLINE
        "unverified" -> MediaSourceAccess.UNVERIFIED
        "rebind_required" -> MediaSourceAccess.REBIND_REQUIRED
        "unavailable" -> MediaSourceAccess.UNAVAILABLE
        else -> MediaSourceAccess.UNKNOWN
    }
    val usable = access == MediaSourceAccess.AVAILABLE || access == MediaSourceAccess.OFFLINE
    // Unknown states never expose an unverified root even if a future server sends one.
    val wire = if (usable) row.optionalMediaString("wireRoot")?.let(MediaSourceRoot::fromWire) else null
    val display = if (usable) row.optionalMediaString("displayRoot") else null
    require(if (usable) wire != null && !display.isNullOrEmpty() else access == MediaSourceAccess.UNKNOWN || !row.has("wireRoot") && !row.has("displayRoot")) {
        "媒体库来源响应格式无效"
    }
    return MediaLibrarySource(row.mediaId("id"), row.mediaId("sourceId"), row.mediaId("libraryId"),
        row.mediaLong("revision", 1), row.mediaLong("libraryRevision", 1), row.mediaLong("authorizationRevision", 1), access, wire, display)
}

internal fun parseMediaScan(row: JSONObject): MediaScan {
    val status = when (row.mediaString("status")) {
        "queued" -> MediaScanStatus.QUEUED
        "running" -> MediaScanStatus.RUNNING
        "completed" -> MediaScanStatus.COMPLETED
        "failed" -> MediaScanStatus.FAILED
        "canceled" -> MediaScanStatus.CANCELED
        "interrupted" -> MediaScanStatus.INTERRUPTED
        else -> MediaScanStatus.UNKNOWN
    }
    val phase = when (row.mediaString("phase")) {
        "enumerate" -> MediaScanPhase.ENUMERATE
        "reconcile" -> MediaScanPhase.RECONCILE
        "ready" -> MediaScanPhase.READY
        else -> MediaScanPhase.UNKNOWN
    }
    val code = row.optionalMediaString("errorCode")
    require(code == null || code.matches(Regex("[a-z0-9_]{1,80}"))) { "扫描错误码无效" }
    return MediaScan(row.mediaId("id"), row.mediaId("libraryId"), status, row.mediaLong("generation", 1),
        row.mediaLong("attempt", 0), row.mediaLong("rulesRevision", 1), phase, row.mediaLong("processed", 0),
        row.mediaLong("createdAt", 1), row.mediaLong("updatedAt", 1), code, row.optionalMediaString("bindingId")?.also(::requireMediaRouteId))
}

internal fun parseMediaScanSupport(row: JSONObject): MediaScanSupport {
    val available = row.mediaBoolean("available")
    val reason = when (row.optionalMediaString("reason")) {
        null -> null
        "not_enabled" -> MediaScanUnavailableReason.NOT_ENABLED
        "coordinator_unavailable" -> MediaScanUnavailableReason.COORDINATOR_UNAVAILABLE
        else -> MediaScanUnavailableReason.UNKNOWN
    }
    require(available == (reason == null)) { "扫描服务状态无效" }
    return MediaScanSupport(row.mediaLong("localRulesRevision", 1), available, reason)
}
