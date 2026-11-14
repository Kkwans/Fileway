package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject

data class MediaProviderStatus(
    val credentialStoreAvailable: Boolean, val candidateSearchAvailable: Boolean,
    val matchConfirmationAvailable: Boolean, val networkJobsAvailable: Boolean,
)

enum class MediaProviderOwner { USER, INSTANCE, UNKNOWN }
enum class MediaProviderConnectionState { CONFIGURED, MISSING, UNKNOWN }

/** A configured credential is stored by the server; it has not necessarily been accepted by TMDB. */
class MediaProviderConnection internal constructor(
    val id: String, val provider: String, val owner: MediaProviderOwner, val ownerUserId: Long?,
    val state: MediaProviderConnectionState, val revision: Long, internal val scope: MediaSessionScope,
) {
    override fun toString(): String = "MediaProviderConnection(redacted)"
}

/** A continuation is valid only for the account, session and page size which produced it. */
class MediaProviderConnectionCursor internal constructor(
    private val value: String, private val scope: MediaSessionScope, private val deviceSession: String, private val limit: Int,
) {
    internal fun use(scope: MediaSessionScope, deviceSession: String, limit: Int): String {
        require(this.scope == scope && this.deviceSession == deviceSession && this.limit == limit) { "资料连接分页不属于当前会话" }
        return value
    }
    override fun toString(): String = "MediaProviderConnectionCursor(redacted)"
}
data class MediaProviderConnectionPage(val items: List<MediaProviderConnection>, val nextCursor: MediaProviderConnectionCursor?)
data class MediaProviderCheck(val connectionId: String, val revision: Long, val reachable: Boolean)

enum class MediaProviderPolicyMode(internal val wire: String) {
    LOCAL_ONLY("local_only"), LOCAL_FIRST_ONLINE("local_first_online"), ONLINE_FIRST("online_first"), UNKNOWN("")
}
data class MediaProviderPolicy(
    val libraryId: String, val mode: MediaProviderPolicyMode, val connectionId: String?,
    val language: String, val region: String, val allowEditorJobs: Boolean, val revision: Long,
)

/** Credential writes deliberately have no durable journal entry; refresh after an unknown outcome. */
sealed interface MediaProviderCredentialResult {
    val scope: MediaSessionScope
    val operation: MediaOperation
    data class Success(
        override val scope: MediaSessionScope, override val operation: MediaOperation,
        val connection: MediaProviderConnection, val sessionHealth: SessionHealth?, val sessionSyncIncomplete: Boolean = false,
    ) : MediaProviderCredentialResult
    data class Failure(
        override val scope: MediaSessionScope, override val operation: MediaOperation, val error: MediaFailure,
    ) : MediaProviderCredentialResult
}

enum class MediaProviderMatchKind(internal val wire: String) { MOVIE("movie"), SERIES("series") }
data class MediaProviderDeclaredId(val provider: String, val mediaType: String?, val id: String)
data class MediaProviderIdentificationFile(
    val filename: String, val parsedTitle: String, val year: Int?, val typeHint: String,
    val providerIds: List<MediaProviderDeclaredId>, val state: String, val issues: List<String>, val revision: Long,
) {
    override fun toString(): String = "MediaProviderIdentificationFile(redacted)"
}
class MediaProviderIdentification internal constructor(
    val ref: MediaCatalogRef, val title: String, val structureKind: MediaItemKind, val matchState: MediaCatalogMatchState,
    val itemRevision: Long, val structureRevision: Long, val externalIds: List<MediaExternalId>,
    val externalIdsNextCursor: String?, val file: MediaProviderIdentificationFile?,
) {
    override fun toString(): String = "MediaProviderIdentification(redacted)"
}

/** Candidates retain the exact item revisions and connection selected for this search. */
class MediaProviderCandidate internal constructor(
    val ref: MediaCatalogRef, val kind: MediaProviderMatchKind, val externalId: String,
    val title: String, val originalTitle: String?, val year: Int?, val evidence: List<String>,
    val itemRevision: Long, val structureRevision: Long, val canConfirm: Boolean, internal val connectionId: String?,
) {
    override fun toString(): String = "MediaProviderCandidate(redacted)"
}
data class MediaProviderCandidatePage(val items: List<MediaProviderCandidate>, val page: Int, val hasMore: Boolean)
data class MediaProviderMatchReceipt(
    val libraryId: String, val itemId: String, val kind: MediaProviderMatchKind, val externalId: String, val itemRevision: Long,
)
data class MediaOnlineMatch(val provider: String, val kind: MediaProviderMatchKind, val externalId: String, val fetchedAt: Long)

internal fun requireProviderConnectionId(value: String) {
    require(value.matches(Regex("pc_[0-9a-f]{32}"))) { "资料连接标识无效" }
}

internal fun requireProviderExternalId(value: String) {
    val number = value.toLongOrNull()
    require(number != null && number > 0 && number.toString() == value) { "资料条目标识无效" }
}

internal fun requireProviderPolicy(mode: MediaProviderPolicyMode, connectionId: String?, language: String, region: String) {
    require(mode != MediaProviderPolicyMode.UNKNOWN)
    connectionId?.let(::requireProviderConnectionId)
    require(mode == MediaProviderPolicyMode.LOCAL_ONLY || connectionId != null)
    require(language.isEmpty() || language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")))
    require(region.isEmpty() || region.matches(Regex("[A-Z]{2}")))
}

private fun JSONObject.providerText(key: String, maxBytes: Int = 4096): String =
    (get(key) as? String ?: error("在线资料响应无效")).also { require(it.toByteArray(Charsets.UTF_8).size <= maxBytes) }
private fun JSONObject.providerOptionalText(key: String, maxBytes: Int = 4096): String? =
    if (!has(key) || isNull(key)) null else providerText(key, maxBytes)
private fun JSONObject.providerLong(key: String, minimum: Long = 0): Long {
    val number = (get(key) as? Number)?.toString()?.toLongOrNull()
    require(number != null && number >= minimum) { "在线资料响应无效" }
    return number
}
private fun JSONObject.providerBoolean(key: String): Boolean = get(key) as? Boolean ?: error("在线资料响应无效")
private fun JSONObject.providerOptionalObject(key: String): JSONObject? =
    if (!has(key) || isNull(key)) null else getJSONObject(key)
private fun JSONObject.providerStrings(key: String, maxItems: Int, maxBytes: Int = 512): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val values = getJSONArray(key)
    require(values.length() <= maxItems)
    return (0 until values.length()).map { index ->
        (values.get(index) as? String ?: error("在线资料响应无效")).also { require(it.toByteArray(Charsets.UTF_8).size <= maxBytes) }
    }
}

internal fun parseProviderStatus(row: JSONObject): MediaProviderStatus = MediaProviderStatus(
    row.providerBoolean("credentialStoreAvailable"), row.providerBoolean("candidateSearchAvailable"),
    row.providerBoolean("matchConfirmationAvailable"), row.providerBoolean("networkJobsAvailable"),
)

internal fun parseProviderConnection(row: JSONObject, scope: MediaSessionScope): MediaProviderConnection {
    val id = row.providerText("id", 64).also(::requireProviderConnectionId)
    val owner = when (row.providerText("ownerKind", 32)) {
        "user" -> MediaProviderOwner.USER; "instance" -> MediaProviderOwner.INSTANCE; else -> MediaProviderOwner.UNKNOWN
    }
    val userId = if (row.has("ownerUserId") && !row.isNull("ownerUserId")) row.providerLong("ownerUserId", 1) else null
    if (owner == MediaProviderOwner.USER) require(userId != null)
    val state = when (row.providerText("status", 32)) {
        "configured" -> MediaProviderConnectionState.CONFIGURED; "missing" -> MediaProviderConnectionState.MISSING
        else -> MediaProviderConnectionState.UNKNOWN
    }
    return MediaProviderConnection(id, row.providerText("provider", 64), owner, userId, state, row.providerLong("revision", 1), scope)
}

internal fun parseProviderCheck(row: JSONObject, connectionId: String): MediaProviderCheck {
    val id = row.providerText("connectionId", 64).also(::requireProviderConnectionId)
    require(id == connectionId && row.providerBoolean("reachable"))
    return MediaProviderCheck(id, row.providerLong("revision", 1), true)
}

internal fun parseProviderPolicy(row: JSONObject): MediaProviderPolicy {
    val library = row.providerText("libraryId", 200).also(::requireMediaRouteId)
    val mode = MediaProviderPolicyMode.entries.firstOrNull { it != MediaProviderPolicyMode.UNKNOWN && it.wire == row.providerText("mode", 32) }
        ?: MediaProviderPolicyMode.UNKNOWN
    val connection = row.providerOptionalText("connectionId", 64)?.also(::requireProviderConnectionId)
    val language = row.providerText("language", 64)
    val region = row.providerText("region", 2)
    if (mode != MediaProviderPolicyMode.UNKNOWN) requireProviderPolicy(mode, connection, language, region)
    return MediaProviderPolicy(library, mode, connection, language, region, row.providerBoolean("allowEditorJobs"), row.providerLong("revision"))
}

private fun providerKind(value: String): MediaProviderMatchKind = when (value) {
    "movie" -> MediaProviderMatchKind.MOVIE; "series" -> MediaProviderMatchKind.SERIES
    else -> error("在线资料类型无效")
}

private fun JSONObject.requireProviderRef(ref: MediaCatalogRef) {
    val actual = getJSONObject("ref")
    require(actual.providerText("libraryId", 200) == ref.libraryId && actual.providerText("itemId", 200) == ref.itemId)
}

internal fun parseProviderIdentification(row: JSONObject, ref: MediaCatalogRef): MediaProviderIdentification {
    row.requireProviderRef(ref)
    val kind = when (row.providerText("structureKind", 32)) {
        "movie" -> MediaItemKind.MOVIE; "series" -> MediaItemKind.SERIES; "season" -> MediaItemKind.SEASON
        "episode" -> MediaItemKind.EPISODE; "video" -> MediaItemKind.VIDEO; else -> MediaItemKind.UNKNOWN
    }
    val state = when (row.providerText("matchState", 64)) {
        "unmatched" -> MediaCatalogMatchState.UNMATCHED; "local_confirmed" -> MediaCatalogMatchState.LOCAL_CONFIRMED
        "provider_confirmed" -> MediaCatalogMatchState.PROVIDER_CONFIRMED; "needs_review" -> MediaCatalogMatchState.NEEDS_REVIEW
        else -> MediaCatalogMatchState.UNKNOWN
    }
    val ids = row.getJSONArray("externalIds")
    require(ids.length() <= 20)
    val file = row.providerOptionalObject("file")?.let { file ->
        val year = if (file.has("year") && !file.isNull("year")) file.providerLong("year", 1).also { require(it <= 9999) }.toInt() else null
        val providerIds = if (file.has("providerIds") && !file.isNull("providerIds")) file.getJSONArray("providerIds") else null
        require(providerIds == null || providerIds.length() <= 128)
        MediaProviderIdentificationFile(file.providerText("filename", 8192), file.providerText("parsedTitle", 4096), year,
            file.providerText("typeHint", 64), (0 until (providerIds?.length() ?: 0)).map { i ->
                val id = requireNotNull(providerIds).getJSONObject(i)
                MediaProviderDeclaredId(id.providerText("provider", 64), id.providerOptionalText("mediaType", 64), id.providerText("id", 256))
            }, file.providerText("state", 64), file.providerStrings("issues", 128), file.providerLong("revision", 1))
    }
    return MediaProviderIdentification(ref, row.providerText("title", 4096), kind, state, row.providerLong("itemRevision", 1),
        row.providerLong("structureRevision", 1), (0 until ids.length()).map { index ->
            parseMediaExternalId(ids.getJSONObject(index)).also {
                require(it.provider.toByteArray(Charsets.UTF_8).size <= 64 && it.entityKind.toByteArray(Charsets.UTF_8).size <= 64 && it.value.toByteArray(Charsets.UTF_8).size <= 256)
            }
        },
        row.providerOptionalText("externalIdsNextCursor", 256)?.also(::mediaCursor), file)
}

internal fun parseProviderCandidates(
    row: JSONObject, identification: MediaProviderIdentification, kind: MediaProviderMatchKind, page: Int, connectionId: String?,
): MediaProviderCandidatePage {
    row.requireProviderRef(identification.ref)
    require(row.providerLong("itemRevision", 1) == identification.itemRevision &&
        row.providerLong("structureRevision", 1) == identification.structureRevision && row.providerLong("page", 1) == page.toLong())
    val more = row.providerBoolean("hasMore")
    require(!more || page < 500)
    val rows = row.getJSONArray("items")
    require(rows.length() <= 100)
    val items = (0 until rows.length()).map { index ->
        val candidate = rows.getJSONObject(index)
        require(candidate.providerText("provider", 64) == "tmdb" && providerKind(candidate.providerText("kind", 32)) == kind)
        val id = candidate.providerText("externalId", 32).also(::requireProviderExternalId)
        val year = if (candidate.has("year") && !candidate.isNull("year")) candidate.providerLong("year", 1).also { require(it <= 9999) }.toInt() else null
        val canConfirm = when (kind) {
            MediaProviderMatchKind.MOVIE -> identification.structureKind in setOf(MediaItemKind.MOVIE, MediaItemKind.VIDEO)
            MediaProviderMatchKind.SERIES -> identification.structureKind == MediaItemKind.SERIES
        }
        MediaProviderCandidate(identification.ref, kind, id, candidate.providerText("title", 4096), candidate.providerOptionalText("originalTitle", 4096),
            year, candidate.providerStrings("evidence", 32, 128), identification.itemRevision, identification.structureRevision, canConfirm, connectionId)
    }
    return MediaProviderCandidatePage(items, page, more)
}

internal fun parseProviderMatchReceipt(row: JSONObject): MediaProviderMatchReceipt = MediaProviderMatchReceipt(
    row.providerText("libraryId", 200).also(::requireMediaRouteId), row.providerText("itemId", 200).also(::requireMediaRouteId),
    providerKind(row.providerText("kind", 32)), row.providerText("externalId", 32).also(::requireProviderExternalId), row.providerLong("itemRevision", 1),
)

internal fun parseMediaOnlineMatch(row: JSONObject): MediaOnlineMatch {
    val provider = row.providerText("provider", 64)
    require(provider == "tmdb")
    return MediaOnlineMatch(provider, providerKind(row.providerText("kind", 32)),
        row.providerText("externalId", 32).also(::requireProviderExternalId), row.providerLong("fetchedAt"))
}
