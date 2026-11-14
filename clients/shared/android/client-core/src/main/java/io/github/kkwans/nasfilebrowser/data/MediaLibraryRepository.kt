package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.core.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** One instance/account/session. A UI must also compare result.scope before publishing it. */
class MediaLibraryRepository(
    private val context: SessionContext,
    private val isCurrent: (SessionContext) -> Boolean,
    private val journal: MediaWriteJournal,
) {
    val scope: MediaSessionScope = mediaSessionScope(context)
    private val catalogDeviceSession: String = context.api.identity.deviceSessionId

    fun newOperation(): MediaOperation = MediaOperation.new(scope.account)

    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(context) || context.api.identity.id != scope.account.userId ||
            context.api.sessionHealth.value?.code == SessionHealthCode.ACCOUNT_CHANGED) {
            throw CancellationException("媒体库会话已切换")
        }
    }

    private suspend fun <T> read(block: suspend () -> T): MediaReadResult<T> {
        checkCurrent()
        return try {
            val value = withTimeout(30_000) { block() }
            checkCurrent()
            MediaReadResult.Success(scope, value, context.api.sessionHealth.value)
        } catch (error: TimeoutCancellationException) {
            checkCurrent()
            MediaReadResult.Failure(scope, MediaFailure(MediaFailureKind.NETWORK))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            checkCurrent()
            MediaReadResult.Failure(scope, mediaFailure(error, false))
        }
    }

    private fun checkCatalogCredential() {
        if (context.api.identity.deviceSessionId != catalogDeviceSession) throw CancellationException("影视设备会话已切换")
    }

    private suspend fun <T> catalogRead(cursor: MediaCatalogCursor?, block: suspend () -> T): MediaReadResult<T> {
        val result = read {
            checkCatalogCredential()
            val value = block()
            checkCatalogCredential()
            value
        }
        checkCatalogCredential()
        return when (result) {
            is MediaReadResult.Success -> result
            is MediaReadResult.Failure -> {
                val kind = when (result.error.status) {
                    410 -> if (cursor != null) MediaFailureKind.CURSOR_EXPIRED else MediaFailureKind.UNAVAILABLE
                    409 -> if (cursor != null) MediaFailureKind.CURSOR_STALE else MediaFailureKind.CONFLICT
                    422 -> MediaFailureKind.INVALID
                    429 -> MediaFailureKind.RATE_LIMITED
                    else -> result.error.kind
                }
                result.copy(error = result.error.copy(kind = kind))
            }
        }
    }

    /** A stable item reference bound to this repository's live account/session. */
    fun catalogRef(libraryId: String, itemId: String): MediaCatalogRef =
        MediaCatalogRef(scope, libraryId, itemId, catalogDeviceSession)

    suspend fun home(): MediaReadResult<MediaCatalogHome> = catalogRead(null) {
        parseCatalogHome(context.api.request("GET", "/api/media-library/v1/home"), scope, catalogDeviceSession)
    }

    suspend fun homeRow(id: MediaCatalogRowId, cursor: MediaCatalogCursor? = null): MediaReadResult<MediaCatalogHomeRow> {
        val base = catalogRowEndpoint(id)
        val endpoint = catalogContinuation(base, cursor)
        return catalogRead(cursor) { parseCatalogHomeRow(context.api.request("GET", endpoint), scope, catalogDeviceSession, id, cursor) }
    }

    suspend fun catalog(query: MediaCatalogQuery = MediaCatalogQuery(), cursor: MediaCatalogCursor? = null): MediaReadResult<MediaCatalogPage<MediaCatalogCard>> {
        val base = catalogQueryEndpoint(query)
        val endpoint = catalogContinuation(base, cursor)
        return catalogRead(cursor) {
            parseCatalogPage(context.api.request("GET", endpoint), scope, catalogDeviceSession, base, query.limit, cursor) { row ->
                parseCatalogCard(row, scope, catalogDeviceSession).also { if (query.libraryId != null) require(it.ref.libraryId == query.libraryId) }
            }
        }
    }

    /** A partial detail contains only its scoped continuation, not an empty item. */
    suspend fun catalogDetail(ref: MediaCatalogRef, cursor: MediaCatalogCursor? = null): MediaReadResult<MediaCatalogDetail> {
        ref.requireBound(scope, catalogDeviceSession)
        val base = itemEndpoint(ref.libraryId, ref.itemId)
        val endpoint = catalogContinuation(base, cursor)
        return catalogRead(cursor) { parseCatalogDetail(context.api.request("GET", endpoint), ref, catalogDeviceSession, base, cursor) }
    }

    suspend fun seasons(series: MediaCatalogRef, limit: Int = 20, cursor: MediaCatalogCursor? = null): MediaReadResult<MediaCatalogPage<MediaCatalogSeason>> {
        series.requireBound(scope, catalogDeviceSession); require(limit in 1..100)
        val base = "${itemEndpoint(series.libraryId, series.itemId)}/seasons?limit=$limit"
        val endpoint = catalogContinuation(base, cursor)
        return catalogRead(cursor) {
            parseCatalogPage(context.api.request("GET", endpoint), scope, catalogDeviceSession, base, limit, cursor) {
                parseCatalogSeason(it, series, catalogDeviceSession)
            }.also { page ->
                if (page.items.isNotEmpty()) require(page.orderId != null && page.items.all { it.orderId == page.orderId })
            }
        }
    }

    suspend fun episodes(series: MediaCatalogRef, season: MediaCatalogSeason, limit: Int = 20, cursor: MediaCatalogCursor? = null): MediaReadResult<MediaCatalogPage<MediaCatalogEpisode>> {
        require(season.series == series) { "所选季不属于当前剧集" }
        season.ref.requireBound(scope, catalogDeviceSession)
        return episodesById(series, season.ref.itemId, season.orderId, limit, cursor)
    }

    /** Deep links may supply IDs; the server still verifies season/order membership and permissions. */
    suspend fun episodesById(series: MediaCatalogRef, seasonId: String, orderId: String? = null, limit: Int = 20, cursor: MediaCatalogCursor? = null): MediaReadResult<MediaCatalogPage<MediaCatalogEpisode>> {
        series.requireBound(scope, catalogDeviceSession); requireMediaRouteId(seasonId); orderId?.let(::requireMediaRouteId); require(limit in 1..100)
        val base = "${itemEndpoint(series.libraryId, series.itemId)}/episodes?seasonId=$seasonId&limit=$limit" + (orderId?.let { "&orderId=$it" } ?: "")
        val endpoint = catalogContinuation(base, cursor)
        return catalogRead(cursor) {
            parseCatalogPage(context.api.request("GET", endpoint), scope, catalogDeviceSession, base, limit, cursor) {
                parseCatalogEpisode(it, series, seasonId, catalogDeviceSession)
            }.also { page ->
                if (page.items.isNotEmpty()) require(page.orderId != null && page.items.all { it.orderId == page.orderId })
                if (orderId != null && page.orderId != null) require(page.orderId == orderId)
            }
        }
    }

    suspend fun episodeCounts(series: MediaCatalogRef, season: MediaCatalogSeason? = null): MediaReadResult<MediaCatalogEpisodeCounts> {
        season?.let { require(it.series == series); it.ref.requireBound(scope, catalogDeviceSession) }
        return episodeCountsById(series, season?.ref?.itemId)
    }

    suspend fun episodeCountsById(series: MediaCatalogRef, seasonId: String? = null): MediaReadResult<MediaCatalogEpisodeCounts> {
        series.requireBound(scope, catalogDeviceSession)
        seasonId?.let(::requireMediaRouteId)
        val endpoint = "${itemEndpoint(series.libraryId, series.itemId)}/episode-counts" + (seasonId?.let { "?seasonId=$it" } ?: "")
        return catalogRead(null) { parseCatalogCounts(context.api.request("GET", endpoint), series, seasonId) }
    }

    /** Variants are current source choices, not a download permission or a promise of timeline continuity. */
    suspend fun playbackVariants(ref: MediaCatalogRef, limit: Int = 20, cursor: MediaCatalogCursor? = null): MediaReadResult<MediaCatalogVariantPage> {
        ref.requireBound(scope, catalogDeviceSession); require(limit in 1..100)
        val base = "${itemEndpoint(ref.libraryId, ref.itemId)}/variants?limit=$limit"
        val endpoint = catalogContinuation(base, cursor, 512)
        return catalogRead(cursor) { parseCatalogVariants(context.api.request("GET", endpoint), ref, catalogDeviceSession, base, limit, cursor) }
    }

    /** Explicit selection creates a short-lived server lease; never auto-retry an unknown outcome. */
    suspend fun resolvePlayback(variant: MediaCatalogVariant): MediaReadResult<MediaCatalogPlaybackResolution> {
        variant.ref.requireBound(scope, catalogDeviceSession)
        val endpoint = "${itemEndpoint(variant.ref.libraryId, variant.ref.itemId)}/playback-resolutions"
        val body = JSONObject().put("editionId", variant.editionId).put("variantId", variant.variantId).put("bindingId", variant.bindingId)
        return when (val result = catalogRead(null) { parseCatalogPlayback(context.api.request("POST", endpoint, body), variant) }) {
            is MediaReadResult.Success -> result
            is MediaReadResult.Failure -> {
                // The response may have been lost after the server created a short lease.
                val status = result.error.status
                val unknown = status == null || status >= 500
                result.copy(error = result.error.copy(mutationMayHaveCommitted = unknown))
            }
        }
    }

    private fun checkCatalogCurrentNow() {
        if (!isCurrent(context) || context.api.identity.id != scope.account.userId ||
            context.api.sessionHealth.value?.code == SessionHealthCode.ACCOUNT_CHANGED) throw CancellationException("媒体库会话已切换")
        checkCatalogCredential()
    }

    /** Resolve an artwork path only for the session that received its card. */
    fun artworkEndpoint(artwork: MediaCatalogArtwork): String {
        checkCatalogCurrentNow()
        artwork.ref.requireBound(scope, catalogDeviceSession)
        return artwork.serviceRelativePath()
    }

    /** Returns the validated service-relative lease path only while this repository is current. */
    fun playbackEndpoint(resolution: MediaCatalogPlaybackResolution): String? {
        checkCatalogCurrentNow()
        resolution.ref.requireBound(scope, catalogDeviceSession)
        return resolution.endpoint?.serviceRelativePath()
    }

    suspend fun openPlaybackLease(resolution: MediaCatalogPlaybackResolution): CatalogPlaybackLease {
        val endpoint = playbackEndpoint(resolution) ?: error("服务器尚未提供影视原画播放")
        try {
            return CatalogPlaybackLease(context.api, context.api.assetLease(endpoint), resolution)
        } catch (error: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                resolution.sessionId?.let { closeServerLease(context.api, it) }
            }
            throw error
        }
    }

    private fun catalogContinuation(base: String, cursor: MediaCatalogCursor?, maxBytes: Int = 4096): String {
        val value = cursor?.use(scope, catalogDeviceSession, base)?.let { catalogCursorValue(it, maxBytes) } ?: return base
        return base + (if ('?' in base) "&" else "?") + "cursor=" + URLEncoder.encode(value, "UTF-8")
    }

    suspend fun listLibraries(limit: Int = 50, cursor: String? = null, archived: Boolean = false): MediaReadResult<MediaPage<MediaLibrarySummary>> {
        val query = pageQuery(limit, cursor, 128) + "&archived=$archived"
        return read { parsePage(context.api.request("GET", "/api/media-library/v1/libraries?$query"), limit, cursor, 128, ::parseMediaLibrary) }
    }

    suspend fun getLibrary(libraryId: String): MediaReadResult<MediaLibrarySummary> {
        requireMediaRouteId(libraryId)
        return read { parseMediaLibrary(context.api.request("GET", libraryEndpoint(libraryId))).also { require(it.id == libraryId) } }
    }

    suspend fun listSources(libraryId: String, limit: Int = 50, cursor: String? = null): MediaReadResult<MediaPage<MediaLibrarySource>> {
        requireMediaRouteId(libraryId)
        val query = pageQuery(limit, cursor, 200)
        return read {
            parsePage(context.api.request("GET", "${libraryEndpoint(libraryId)}/sources?$query"), limit, cursor, 200) {
                parseMediaSource(it).also { source -> require(source.libraryId == libraryId) }
            }
        }
    }

    suspend fun listItems(libraryId: String, limit: Int = 50, cursor: String? = null): MediaReadResult<MediaPage<MediaItemSummary>> {
        requireMediaRouteId(libraryId)
        val query = pageQuery(limit, cursor, 200)
        return read { parsePage(context.api.request("GET", "${libraryEndpoint(libraryId)}/items?$query"), limit, cursor, 200) {
            parseMediaItem(it).also { item -> require(item.libraryId == libraryId) }
        } }
    }

    suspend fun getItem(libraryId: String, itemId: String): MediaReadResult<MediaItemDetail> {
        requireMediaRouteId(libraryId); requireMediaRouteId(itemId)
        return read { parseMediaItemDetail(context.api.request("GET", itemEndpoint(libraryId, itemId))).also {
            require(it.item.id == itemId && it.item.libraryId == libraryId)
        } }
    }

    suspend fun providerStatus(): MediaReadResult<MediaProviderStatus> = catalogRead(null) {
        parseProviderStatus(context.api.request("GET", "/api/media-library/v1/provider-status"))
    }

    suspend fun providerConnections(limit: Int = 50, cursor: MediaProviderConnectionCursor? = null): MediaReadResult<MediaProviderConnectionPage> {
        require(limit in 1..100)
        val previous = cursor?.use(scope, catalogDeviceSession, limit)
        val query = pageQuery(limit, previous, 64)
        return catalogRead(null) {
            val row = context.api.request("GET", "/api/media-library/v1/provider-connections?$query")
            val items = row.getJSONArray("items")
            require(items.length() <= limit)
            val next = if (row.has("nextCursor")) (row.get("nextCursor") as? String ?: error("资料连接分页无效")) else null
            next?.let { requireProviderConnectionId(it); require(it != previous) }
            MediaProviderConnectionPage((0 until items.length()).map { parseProviderConnection(items.getJSONObject(it), scope) },
                next?.let { MediaProviderConnectionCursor(it, scope, catalogDeviceSession, limit) })
        }
    }

    /** HTTP 422 means the provider rejected the stored credential; 429 means busy or rate limited. */
    suspend fun checkProviderConnection(connectionId: String): MediaReadResult<MediaProviderCheck> {
        requireProviderConnectionId(connectionId)
        return catalogRead(null) {
            parseProviderCheck(context.api.request("POST", "/api/media-library/v1/provider-connections/$connectionId/check", JSONObject()), connectionId)
        }
    }

    /** The token is sent only in this call and is never added to the persistent write journal. */
    suspend fun createPersonalProvider(operation: MediaOperation, token: String): MediaProviderCredentialResult {
        requireProviderToken(token)
        return providerCredentialWrite(operation, "POST", "/api/media-library/v1/provider-connections",
            operationBody(operation).put("ownerKind", "user").put("token", token), null)
    }

    /** Rotate only a personal connection received in this session. Refresh after an unknown result. */
    suspend fun rotatePersonalProvider(operation: MediaOperation, connection: MediaProviderConnection, token: String): MediaProviderCredentialResult {
        requireProviderToken(token)
        require(connection.scope == scope && connection.owner == MediaProviderOwner.USER && connection.ownerUserId == scope.account.userId)
        requireProviderConnectionId(connection.id)
        return providerCredentialWrite(operation, "PUT", "/api/media-library/v1/provider-connections/${connection.id}",
            revisionBody(operation, connection.revision).put("token", token), connection.id)
    }

    private suspend fun providerCredentialWrite(operation: MediaOperation, method: String, endpoint: String, body: JSONObject,
        expectedId: String?): MediaProviderCredentialResult {
        checkCurrent(); checkCatalogCredential()
        require(operation.account == scope.account) { "操作不属于当前账号或服务器" }
        var acknowledged: MediaProviderCredentialResult? = null
        try {
            withTimeout(30_000) {
                context.api.actionWithAcknowledgement(method, endpoint, body) { status, text ->
                    acknowledged = try {
                        require(!text.isNullOrBlank())
                        val connection = parseProviderConnection(JSONObject(text), scope)
                        require(connection.provider == "tmdb" && connection.owner == MediaProviderOwner.USER &&
                            connection.ownerUserId == scope.account.userId && (expectedId == null || connection.id == expectedId))
                        MediaProviderCredentialResult.Success(scope, operation, connection, context.api.sessionHealth.value)
                    } catch (_: Exception) {
                        MediaProviderCredentialResult.Failure(scope, operation,
                            MediaFailure(MediaFailureKind.INVALID_RESPONSE, status, mutationMayHaveCommitted = true))
                    }
                }
            }
            checkCurrent(); checkCatalogCredential()
            return when (val result = checkNotNull(acknowledged)) {
                is MediaProviderCredentialResult.Success -> result.copy(sessionHealth = context.api.sessionHealth.value)
                is MediaProviderCredentialResult.Failure -> result
            }
        } catch (_: TimeoutCancellationException) {
            checkCurrent(); checkCatalogCredential()
            return providerCredentialIncomplete(acknowledged) ?: MediaProviderCredentialResult.Failure(scope, operation,
                MediaFailure(MediaFailureKind.NETWORK, mutationMayHaveCommitted = true))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            checkCurrent(); checkCatalogCredential()
            return providerCredentialIncomplete(acknowledged) ?: MediaProviderCredentialResult.Failure(scope, operation, providerFailure(error, true))
        }
    }

    suspend fun metadataPolicy(libraryId: String): MediaReadResult<MediaProviderPolicy> {
        requireMediaRouteId(libraryId)
        return catalogRead(null) {
            parseProviderPolicy(context.api.request("GET", "${libraryEndpoint(libraryId)}/metadata-policy")).also { require(it.libraryId == libraryId) }
        }
    }

    suspend fun updateMetadataPolicy(operation: MediaOperation, libraryId: String, expectedRevision: Long,
        mode: MediaProviderPolicyMode, connectionId: String? = null, language: String = "", region: String = "",
        allowEditorJobs: Boolean = false): MediaWriteResult<MediaProviderPolicy> {
        requireProviderPolicy(mode, connectionId, language, region)
        return providerWrite(mutation(operation, MediaMutationKind.UPDATE_METADATA_POLICY, libraryId,
            body = revisionBody(operation, expectedRevision).put("mode", mode.wire).put("connectionId", connectionId ?: "")
                .put("language", language).put("region", region).put("allowEditorJobs", allowEditorJobs)))
    }

    suspend fun identification(ref: MediaCatalogRef): MediaReadResult<MediaProviderIdentification> {
        ref.requireBound(scope, catalogDeviceSession)
        return catalogRead(null) { parseProviderIdentification(context.api.request("GET", "${itemEndpoint(ref.libraryId, ref.itemId)}/identification"), ref) }
    }

    /** Page is an explicit bounded provider query; searching does not change the match. */
    suspend fun searchMatchCandidates(identification: MediaProviderIdentification, kind: MediaProviderMatchKind, page: Int = 1,
        title: String? = null, year: Int? = null, connectionId: String? = null): MediaReadResult<MediaProviderCandidatePage> {
        identification.ref.requireBound(scope, catalogDeviceSession)
        require(page in 1..500 && (year == null || year in 1000..9999))
        connectionId?.let(::requireProviderConnectionId)
        title?.let { require(it.toByteArray(Charsets.UTF_8).size <= 512 && it.none { c -> c.isISOControl() || c == '/' || c == '\\' }) }
        val body = JSONObject().put("itemRevision", identification.itemRevision).put("structureRevision", identification.structureRevision)
            .put("kind", kind.wire).put("page", page).put("title", title ?: "").put("year", year ?: 0).put("connectionId", connectionId ?: "")
        return catalogRead(null) {
            val row = context.api.request("POST", "${itemEndpoint(identification.ref.libraryId, identification.ref.itemId)}/match-candidates", body)
            parseProviderCandidates(row, identification, kind, page, connectionId)
        }
    }

    /** Confirm only an explicitly chosen candidate; the exact request is recoverable in the journal. */
    suspend fun confirmMatch(operation: MediaOperation, candidate: MediaProviderCandidate): MediaWriteResult<MediaProviderMatchReceipt> {
        candidate.ref.requireBound(scope, catalogDeviceSession)
        require(candidate.canConfirm) { "当前条目不支持此类型的在线匹配" }
        return providerWrite(mutation(operation, MediaMutationKind.CONFIRM_PROVIDER_MATCH, candidate.ref.libraryId, itemId = candidate.ref.itemId,
            body = operationBody(operation).put("kind", candidate.kind.wire).put("externalId", candidate.externalId)
                .put("itemRevision", candidate.itemRevision).put("structureRevision", candidate.structureRevision)
                .put("connectionId", candidate.connectionId ?: "")))
    }

    suspend fun pendingMetadataPolicyOperation(libraryId: String): MediaOperation? {
        requireMediaRouteId(libraryId); checkCurrent(); checkCatalogCredential()
        return journal.firstUnresolved(scope.account, setOf(MediaMutationKind.UPDATE_METADATA_POLICY), libraryId)
    }

    suspend fun pendingMatchConfirmation(ref: MediaCatalogRef): MediaOperation? {
        ref.requireBound(scope, catalogDeviceSession); checkCurrent(); checkCatalogCredential()
        return journal.firstUnresolved(scope.account, setOf(MediaMutationKind.CONFIRM_PROVIDER_MATCH, MediaMutationKind.UNDO_PROVIDER_MATCH), ref.libraryId, ref.itemId)
    }

    suspend fun undoMatch(operation: MediaOperation, item: MediaProviderIdentification): MediaWriteResult<MediaProviderUndoReceipt> {
        item.ref.requireBound(scope, catalogDeviceSession)
        val matchRevision = requireNotNull(item.undoMatchRevision)
        return providerWrite(mutation(operation, MediaMutationKind.UNDO_PROVIDER_MATCH, item.ref.libraryId, itemId = item.ref.itemId,
            body = operationBody(operation).put("itemRevision", item.itemRevision)
                .put("structureRevision", item.structureRevision).put("matchRevision", matchRevision)))
    }

    private suspend fun <T> providerWrite(request: MediaMutationRequest): MediaWriteResult<T> {
        checkCatalogCredential()
        val result = typedWrite<T>(request)
        checkCurrent(); checkCatalogCredential()
        return result
    }

    suspend fun listItemCredits(libraryId: String, itemId: String, revision: Long, limit: Int = 20, cursor: String? = null) =
        itemPage(libraryId, itemId, "credits", revision, limit, cursor, ::parseMediaCredit)
    suspend fun listItemRatings(libraryId: String, itemId: String, revision: Long, limit: Int = 20, cursor: String? = null) =
        itemPage(libraryId, itemId, "ratings", revision, limit, cursor, ::parseMediaRating)
    suspend fun listItemArtwork(libraryId: String, itemId: String, revision: Long, limit: Int = 20, cursor: String? = null) =
        itemPage(libraryId, itemId, "artwork", revision, limit, cursor, ::parseMediaArtwork)
    suspend fun listItemExternalIds(libraryId: String, itemId: String, revision: Long, limit: Int = 20, cursor: String? = null) =
        itemPage(libraryId, itemId, "external-ids", revision, limit, cursor, ::parseMediaExternalId)
    suspend fun listItemCandidates(libraryId: String, itemId: String, revision: Long, limit: Int = 20, cursor: String? = null) =
        itemPage(libraryId, itemId, "issues", revision, limit, cursor, ::parseMediaCandidate)

    private suspend fun <T> itemPage(libraryId: String, itemId: String, kind: String, revision: Long,
        limit: Int, cursor: String?, parse: (JSONObject) -> T): MediaReadResult<MediaRevisionPage<T>> {
        requireMediaRouteId(libraryId); requireMediaRouteId(itemId); require(revision > 0)
        val query = pageQuery(limit, cursor, 256) + "&revision=$revision"
        return read { parseMediaRevisionPage(context.api.request("GET", "${itemEndpoint(libraryId, itemId)}/$kind?$query"),
            limit, cursor, revision, parse) }
    }

    /** This reports the scanner's current rule revision, not full catalog/playback capability. */
    suspend fun scanSupport(): MediaReadResult<MediaScanSupport> = read {
        parseMediaScanSupport(context.api.request("GET", "/api/media-library/v1/scan-support"))
    }

    suspend fun getScan(libraryId: String, scanId: String): MediaReadResult<MediaScan> {
        requireMediaRouteId(libraryId); requireMediaRouteId(scanId)
        return read { parseMediaScan(context.api.request("GET", "${libraryEndpoint(libraryId)}/scans/$scanId")).also {
            require(it.libraryId == libraryId && it.id == scanId)
        } }
    }

    suspend fun listScans(libraryId: String, limit: Int = 20, cursor: String? = null): MediaReadResult<MediaPage<MediaScan>> {
        requireMediaRouteId(libraryId)
        val query = pageQuery(limit, cursor, 128)
        return catalogRead(null) {
            parsePage(context.api.request("GET", "${libraryEndpoint(libraryId)}/scans?$query"), limit, cursor, 128) {
                parseMediaScan(it).also { scan -> require(scan.libraryId == libraryId) }
            }
        }
    }

    suspend fun metadataJob(libraryId: String, scanId: String): MediaReadResult<MediaMetadataJob> {
        val endpoint = scanMetadataEndpoint(libraryId, scanId)
        return catalogRead(null) { parseMetadataJob(context.api.request("GET", endpoint)) }
    }

    suspend fun metadataReview(libraryId: String, scanId: String, limit: Int = 20, cursor: String? = null): MediaReadResult<MediaPage<MediaMetadataReviewItem>> {
        val endpoint = scanMetadataEndpoint(libraryId, scanId)
        val query = pageQuery(limit, cursor, 128)
        return catalogRead(null) {
            parsePage(context.api.request("GET", "$endpoint/review?$query"), limit, cursor, 128) {
                parseMetadataReview(it) { id -> catalogRef(libraryId, id) }
            }
        }
    }

    /** Generation checks prevent a repeated action from changing a newer job. */
    suspend fun changeMetadataJob(libraryId: String, scanId: String, generation: Long, cancel: Boolean): MediaReadResult<Unit> {
        val endpoint = scanMetadataEndpoint(libraryId, scanId)
        require(generation > 0)
        return catalogRead(null) {
            context.api.action(if (cancel) "DELETE" else "POST", "$endpoint?generation=$generation")
            Unit
        }
    }

    suspend fun createLibrary(operation: MediaOperation, name: String): MediaWriteResult<MediaLibrarySummary> =
        typedWrite(mutation(operation, MediaMutationKind.CREATE_LIBRARY, body = operationBody(operation).put("name", name)))

    suspend fun updateLibrary(operation: MediaOperation, libraryId: String, expectedRevision: Long, name: String): MediaWriteResult<MediaLibrarySummary> =
        typedWrite(mutation(operation, MediaMutationKind.UPDATE_LIBRARY, libraryId, body = revisionBody(operation, expectedRevision).put("name", name)))

    suspend fun archiveLibrary(operation: MediaOperation, libraryId: String, expectedRevision: Long): MediaWriteResult<MediaLibrarySummary> =
        typedWrite(mutation(operation, MediaMutationKind.ARCHIVE_LIBRARY, libraryId, body = revisionBody(operation, expectedRevision)))

    suspend fun restoreLibrary(operation: MediaOperation, libraryId: String, expectedRevision: Long): MediaWriteResult<MediaLibrarySummary> =
        typedWrite(mutation(operation, MediaMutationKind.RESTORE_LIBRARY, libraryId, body = revisionBody(operation, expectedRevision)))

    suspend fun grantMember(operation: MediaOperation, libraryId: String, expectedRevision: Long, userId: Long, role: MediaMemberRole): MediaWriteResult<MediaMemberChange> =
        typedWrite(mutation(operation, MediaMutationKind.GRANT_MEMBER, libraryId, userId = userId,
            body = revisionBody(operation, expectedRevision).put("role", role.name.lowercase())))

    suspend fun revokeMember(operation: MediaOperation, libraryId: String, expectedRevision: Long, userId: Long): MediaWriteResult<MediaMemberChange> =
        typedWrite(mutation(operation, MediaMutationKind.REVOKE_MEMBER, libraryId, userId = userId, body = revisionBody(operation, expectedRevision)))

    suspend fun createLocalSource(operation: MediaOperation, libraryId: String, expectedRevision: Long, root: MediaSourceRoot): MediaWriteResult<MediaLibrarySource> =
        typedWrite(mutation(operation, MediaMutationKind.CREATE_SOURCE, libraryId, body = revisionBody(operation, expectedRevision).put("wireRoot", root.wire)))

    /** Only a user-selected root may confirm an existing binding. */
    suspend fun attestSource(operation: MediaOperation, libraryId: String, bindingId: String, expectedRevision: Long, root: MediaSourceRoot): MediaWriteResult<MediaLibrarySource> =
        typedWrite(mutation(operation, MediaMutationKind.ATTEST_SOURCE, libraryId, bindingId = bindingId,
            body = revisionBody(operation, expectedRevision).put("wireRoot", root.wire)))

    suspend fun patchMetadata(operation: MediaOperation, libraryId: String, itemId: String, expectedRevision: Long,
        fields: Map<String, MediaMetadataFieldPatch>): MediaWriteResult<MediaMetadataReceipt> {
        val body = JSONObject()
        fields.toSortedMap().forEach { (name, patch) ->
            val value = JSONObject()
            patch.values?.let { value.put("values", JSONArray(it)) }
            if (patch.clear) value.put("clear", true)
            if (patch.reset) value.put("reset", true)
            patch.locked?.let { value.put("locked", it) }
            body.put(name, value)
        }
        return typedWrite(mutation(operation, MediaMutationKind.PATCH_METADATA, libraryId, itemId = itemId,
            body = revisionBody(operation, expectedRevision).put("fields", body)))
    }

    suspend fun undoMetadata(operation: MediaOperation, libraryId: String, itemId: String, expectedRevision: Long,
        targetRevision: Long): MediaWriteResult<MediaMetadataReceipt> =
        typedWrite(mutation(operation, MediaMutationKind.UNDO_METADATA, libraryId, itemId = itemId,
            body = revisionBody(operation, expectedRevision).put("targetRevision", targetRevision)))

    suspend fun startScan(operation: MediaOperation, libraryId: String, bindingId: String, expectedRevision: Long, rulesRevision: Long): MediaWriteResult<MediaScan> =
        typedWrite(mutation(operation, MediaMutationKind.START_SCAN, libraryId,
            body = revisionBody(operation, expectedRevision).put("bindingId", bindingId).put("rulesRevision", rulesRevision)))

    suspend fun startScanBatch(operation: MediaOperation, libraryId: String, sources: List<MediaLibrarySource>, rulesRevision: Long): MediaWriteResult<MediaScanBatch> {
        require(sources.all { it.libraryId == libraryId })
        val entries = JSONArray(sources.sortedBy { it.id }.map { JSONObject().put("bindingId", it.id).put("expectedRevision", it.revision) })
        return typedWrite(mutation(operation, MediaMutationKind.START_SCAN_BATCH, libraryId,
            body = operationBody(operation).put("rulesRevision", rulesRevision).put("sources", entries)))
    }

    suspend fun cancelScan(operation: MediaOperation, libraryId: String, scanId: String, expectedGeneration: Long): MediaWriteResult<MediaScan> =
        typedWrite(mutation(operation, MediaMutationKind.CANCEL_SCAN, libraryId, scanId, body = generationBody(operation, expectedGeneration)))

    suspend fun retryScan(operation: MediaOperation, libraryId: String, scanId: String, expectedGeneration: Long): MediaWriteResult<MediaScan> =
        typedWrite(mutation(operation, MediaMutationKind.RETRY_SCAN, libraryId, scanId, body = generationBody(operation, expectedGeneration)))

    /** Explicit user action only. The saved operation, endpoint and parameters are reused verbatim. */
    suspend fun retryOperation(operation: MediaOperation): MediaWriteResult<Any> {
        require(operation.account == scope.account) { "操作不属于当前账号或服务器" }
        val request = journal.replay(operation)
        val catalogBound = request.kind == MediaMutationKind.PATCH_USER_STATE || request.kind in providerMutationKinds
        if (catalogBound) checkCatalogCredential()
        val result = write(request)
        if (catalogBound) { checkCurrent(); checkCatalogCredential() }
        return result
    }

    suspend fun getUserState(ref: MediaCatalogRef): MediaReadResult<MediaCatalogUserState> {
        ref.requireBound(scope, catalogDeviceSession)
        return catalogRead(null) {
            parseMediaCatalogUserState(context.api.request("GET", "${itemEndpoint(ref.libraryId, ref.itemId)}/user-state"))
        }
    }

    suspend fun updateUserState(operation: MediaOperation, ref: MediaCatalogRef, expectedRevision: Long,
        favorite: Boolean? = null, manualWatched: Boolean? = null,
    ): MediaWriteResult<MediaCatalogUserState> {
        ref.requireBound(scope, catalogDeviceSession)
        checkCatalogCredential()
        return typedWrite(mutation(operation, MediaMutationKind.PATCH_USER_STATE, ref.libraryId, itemId = ref.itemId,
            body = revisionBody(operation, expectedRevision).apply {
                favorite?.let { put("favorite", it) }
                manualWatched?.let { put("manualWatched", it) }
            }))
    }

    suspend fun pendingUserStateOperation(ref: MediaCatalogRef): MediaOperation? {
        ref.requireBound(scope, catalogDeviceSession)
        checkCurrent()
        checkCatalogCredential()
        return journal.firstUnresolved(scope.account, setOf(MediaMutationKind.PATCH_USER_STATE), ref.libraryId, ref.itemId)
    }

    /** Library setup resumes an unresolved write only after explicit user confirmation. */
    suspend fun pendingSetupOperation(): MediaOperation? {
        checkCurrent()
        return journal.firstUnresolved(scope.account, setOf(
            MediaMutationKind.CREATE_LIBRARY, MediaMutationKind.CREATE_SOURCE,
            MediaMutationKind.START_SCAN, MediaMutationKind.START_SCAN_BATCH, MediaMutationKind.CANCEL_SCAN, MediaMutationKind.RETRY_SCAN,
        ))
    }

    suspend fun forgetResolvedOperation(operation: MediaOperation) {
        checkCurrent()
        require(operation.account == scope.account)
        journal.forget(operation)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> typedWrite(request: MediaMutationRequest): MediaWriteResult<T> = write(request) as MediaWriteResult<T>

    private suspend fun write(request: MediaMutationRequest): MediaWriteResult<Any> {
        checkCurrent()
        require(request.operation.account == scope.account) { "操作不属于当前账号或服务器" }
        val providerRequest = request.kind in providerMutationKinds
        val previouslyUnknown = providerRequest && journal.records(scope.account).any {
            it.operation == request.operation && (it.state == MediaWriteState.UNKNOWN || it.state == MediaWriteState.ACKNOWLEDGED_INVALID_RESPONSE)
        }
        val started = try { withTimeout(2_000) { journal.begin(scope, request) } }
        catch (_: TimeoutCancellationException) { false }
        if (!started) return MediaWriteResult.Failure(scope, request.operation, MediaFailure(MediaFailureKind.STORAGE), true)
        var acknowledgement: MediaWriteResult<Any>? = null
        try {
            // The account can change while the write-ahead record is being saved.
            checkCurrent()
            if (providerRequest) checkCatalogCredential()
            withTimeout(30_000) {
                context.api.actionWithAcknowledgement(request.method, request.endpoint, request.body()) { status, text ->
                    val parsed = try {
                        require(!text.isNullOrBlank())
                        val value = request.parse(JSONObject(text))
                        val receipt = receipt(request, value)
                        journal.accepted(request.operation, receipt, null)
                        MediaWriteResult.Success(scope, request.operation, value, context.api.sessionHealth.value)
                    } catch (_: Exception) {
                        val error = MediaFailure(MediaFailureKind.INVALID_RESPONSE, status, mutationMayHaveCommitted = true)
                        journal.accepted(request.operation, null, error)
                        MediaWriteResult.Failure(scope, request.operation, error)
                    }
                    // The in-memory acknowledgement already exists before this bounded local I/O.
                    acknowledgement = parsed
                    val saved = journal.saveOutcome(request.operation)
                    acknowledgement = when (parsed) {
                        is MediaWriteResult.Success -> parsed.copy(journalStorageFailed = !saved)
                        is MediaWriteResult.Failure -> parsed.copy(journalStorageFailed = !saved)
                    }
                }
            }
            val result = checkNotNull(acknowledgement)
            return when (result) {
                is MediaWriteResult.Success -> result.copy(sessionHealth = context.api.sessionHealth.value)
                is MediaWriteResult.Failure -> result
            }
        } catch (error: TimeoutCancellationException) {
            acknowledgement?.let { return syncIncomplete(it) }
            return failedWrite(request, MediaFailure(MediaFailureKind.NETWORK, mutationMayHaveCommitted = true))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            acknowledgement?.let { return syncIncomplete(it) }
            val failure = if (providerRequest) providerFailure(error, true) else mediaFailure(error, true)
            // These endpoints recheck source authority after committing. A
            // 4xx from that second check is not proof that no write occurred.
            val postCommitCheck = request.kind in setOf(MediaMutationKind.CREATE_SOURCE, MediaMutationKind.ATTEST_SOURCE,
                MediaMutationKind.PATCH_METADATA, MediaMutationKind.UNDO_METADATA)
            return failedWrite(request, if ((previouslyUnknown || postCommitCheck && failure.status != null && failure.status in setOf(400, 403, 404, 409)))
                failure.copy(mutationMayHaveCommitted = true) else failure)
        } finally { journal.finish(request.operation) }
    }

    private suspend fun failedWrite(request: MediaMutationRequest, failure: MediaFailure): MediaWriteResult.Failure {
        journal.failed(request.operation, failure)
        // UNKNOWN was already persisted before dispatch. Persist definitive failures too.
        val saved = if (failure.mutationMayHaveCommitted) true else journal.saveOutcome(request.operation)
        return MediaWriteResult.Failure(scope, request.operation, failure, !saved)
    }
}

private fun syncIncomplete(result: MediaWriteResult<Any>): MediaWriteResult<Any> = when (result) {
    is MediaWriteResult.Success -> result.copy(sessionSyncIncomplete = true)
    is MediaWriteResult.Failure -> result
}

private fun providerCredentialIncomplete(result: MediaProviderCredentialResult?): MediaProviderCredentialResult? = when (result) {
    is MediaProviderCredentialResult.Success -> result.copy(sessionSyncIncomplete = true)
    is MediaProviderCredentialResult.Failure, null -> result
}

private fun requireProviderToken(token: String) {
    require(token.isNotEmpty() && token.toByteArray(Charsets.UTF_8).size <= 4096 &&
        token.none { it.isWhitespace() || Character.isSpaceChar(it) || it.isISOControl() }) { "TMDB 凭据格式无效" }
    require(runCatching { Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(token)) }.isSuccess) { "TMDB 凭据格式无效" }
}

private fun providerFailure(error: Exception, write: Boolean): MediaFailure {
    val failure = mediaFailure(error, write)
    return when (failure.status) {
        422 -> failure.copy(kind = MediaFailureKind.INVALID, mutationMayHaveCommitted = false)
        429 -> failure.copy(kind = MediaFailureKind.RATE_LIMITED, mutationMayHaveCommitted = false)
        else -> failure
    }
}

private fun mutation(operation: MediaOperation, kind: MediaMutationKind, libraryId: String? = null, scanId: String? = null,
    userId: Long? = null, bindingId: String? = null, itemId: String? = null, body: JSONObject) =
    MediaMutationRequest(operation, kind, libraryId, scanId, userId, bindingId, itemId, body)
private fun operationBody(operation: MediaOperation) = JSONObject().put("operationId", operation.id)
private fun revisionBody(operation: MediaOperation, revision: Long) = operationBody(operation).put("expectedRevision", revision)
private fun generationBody(operation: MediaOperation, generation: Long) = operationBody(operation).put("expectedGeneration", generation)
private fun libraryEndpoint(id: String) = "/api/media-library/v1/libraries/$id"
private fun itemEndpoint(libraryId: String, itemId: String) = "${libraryEndpoint(libraryId)}/items/$itemId"
private fun scanMetadataEndpoint(libraryId: String, scanId: String): String {
    requireMediaRouteId(libraryId)
    requireMediaRouteId(scanId)
    return "${libraryEndpoint(libraryId)}/scans/$scanId/metadata"
}

private fun catalogQueryEndpoint(query: MediaCatalogQuery): String = buildString {
    append("/api/media-library/v1/catalog/items?limit=").append(query.limit).append("&sort=").append(query.sort.wire)
    query.libraryId?.let { append("&libraryId=").append(it) }
    query.structureKind?.let { append("&structureKind=").append(it.wire) }
    query.matchState?.let { append("&matchState=").append(it.wire) }
}

private fun pageQuery(limit: Int, cursor: String?, maxCursorBytes: Int): String {
    require(limit in 1..100)
    cursor?.let { require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= maxCursorBytes) }
    return "limit=$limit" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
}

private fun <T> parsePage(row: JSONObject, limit: Int, cursor: String?, maxCursorBytes: Int, parse: (JSONObject) -> T): MediaPage<T> {
    val items = row.getJSONArray("items")
    require(items.length() <= limit)
    val next = if (row.has("nextCursor")) row.get("nextCursor") as? String ?: error("媒体库分页响应无效") else null
    next?.let { require(it.isNotEmpty() && it != cursor && it.toByteArray(Charsets.UTF_8).size <= maxCursorBytes) }
    return MediaPage((0 until items.length()).map { parse(items.getJSONObject(it)) }, next)
}

private fun mediaFailure(error: Exception, write: Boolean): MediaFailure {
    val status = (error as? ServiceException)?.status
    val kind = when (status) {
        400 -> MediaFailureKind.INVALID
        401 -> MediaFailureKind.AUTHENTICATION
        403 -> MediaFailureKind.FORBIDDEN
        404 -> MediaFailureKind.NOT_FOUND
        409 -> MediaFailureKind.CONFLICT
        503 -> MediaFailureKind.UNAVAILABLE
        null -> if (error is TransportException) MediaFailureKind.NETWORK else MediaFailureKind.INVALID_RESPONSE
        else -> MediaFailureKind.SERVER
    }
    val mayHaveCommitted = write && (status == null || status !in setOf(400, 401, 403, 404, 409))
    return MediaFailure(kind, status, mayHaveCommitted)
}

private fun receipt(request: MediaMutationRequest, value: Any): MediaWriteReceipt = when (value) {
    is MediaLibrarySummary -> MediaWriteReceipt(value.id.also(::requireMediaRouteId), revision = value.revision)
    is MediaMemberChange -> MediaWriteReceipt(requireNotNull(request.libraryId), memberId = value.userId,
        revision = value.revision, libraryRevision = value.libraryRevision)
    is MediaLibrarySource -> MediaWriteReceipt(value.libraryId, sourceBindingId = value.id.also(::requireMediaRouteId),
        revision = value.revision, libraryRevision = value.libraryRevision)
    is MediaScan -> MediaWriteReceipt(value.libraryId, scanId = value.id.also(::requireMediaRouteId), scanGeneration = value.generation)
    is MediaScanBatch -> MediaWriteReceipt(value.libraryId)
    is MediaMetadataReceipt -> MediaWriteReceipt(requireNotNull(request.libraryId), revision = value.revision,
        itemId = value.itemId, metadataRevision = value.metadataRevision)
    is MediaCatalogUserState -> MediaWriteReceipt(requireNotNull(request.libraryId),
        revision = value.revision.takeIf { it > 0 }, itemId = request.itemId)
    is MediaProviderPolicy -> MediaWriteReceipt(value.libraryId, revision = value.revision.takeIf { it > 0 })
    is MediaProviderMatchReceipt -> MediaWriteReceipt(value.libraryId, revision = value.itemRevision, itemId = value.itemId)
    is MediaProviderUndoReceipt -> MediaWriteReceipt(value.libraryId, revision = value.itemRevision, itemId = value.itemId)
    else -> error("媒体库回执类型无效")
}

private val providerMutationKinds = setOf(MediaMutationKind.UPDATE_METADATA_POLICY,
    MediaMutationKind.CONFIRM_PROVIDER_MATCH, MediaMutationKind.UNDO_PROVIDER_MATCH)
