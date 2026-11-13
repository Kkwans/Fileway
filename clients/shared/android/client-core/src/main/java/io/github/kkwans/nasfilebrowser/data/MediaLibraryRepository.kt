package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.core.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URLEncoder

/** One instance/account/session. A UI must also compare result.scope before publishing it. */
class MediaLibraryRepository(
    private val context: SessionContext,
    private val isCurrent: (SessionContext) -> Boolean,
    private val journal: MediaWriteJournal,
) {
    val scope: MediaSessionScope = mediaSessionScope(context)

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

    suspend fun startScan(operation: MediaOperation, libraryId: String, bindingId: String, expectedRevision: Long, rulesRevision: Long): MediaWriteResult<MediaScan> =
        typedWrite(mutation(operation, MediaMutationKind.START_SCAN, libraryId,
            body = revisionBody(operation, expectedRevision).put("bindingId", bindingId).put("rulesRevision", rulesRevision)))

    suspend fun cancelScan(operation: MediaOperation, libraryId: String, scanId: String, expectedGeneration: Long): MediaWriteResult<MediaScan> =
        typedWrite(mutation(operation, MediaMutationKind.CANCEL_SCAN, libraryId, scanId, body = generationBody(operation, expectedGeneration)))

    suspend fun retryScan(operation: MediaOperation, libraryId: String, scanId: String, expectedGeneration: Long): MediaWriteResult<MediaScan> =
        typedWrite(mutation(operation, MediaMutationKind.RETRY_SCAN, libraryId, scanId, body = generationBody(operation, expectedGeneration)))

    /** Explicit user action only. The saved operation, endpoint and parameters are reused verbatim. */
    suspend fun retryOperation(operation: MediaOperation): MediaWriteResult<Any> {
        require(operation.account == scope.account) { "操作不属于当前账号或服务器" }
        return write(journal.replay(operation))
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> typedWrite(request: MediaMutationRequest): MediaWriteResult<T> = write(request) as MediaWriteResult<T>

    private suspend fun write(request: MediaMutationRequest): MediaWriteResult<Any> {
        checkCurrent()
        require(request.operation.account == scope.account) { "操作不属于当前账号或服务器" }
        val started = try { withTimeout(2_000) { journal.begin(scope, request) } }
        catch (_: TimeoutCancellationException) { false }
        if (!started) return MediaWriteResult.Failure(scope, request.operation, MediaFailure(MediaFailureKind.STORAGE), true)
        var acknowledgement: MediaWriteResult<Any>? = null
        try {
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
            return failedWrite(request, mediaFailure(error, true))
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

private fun mutation(operation: MediaOperation, kind: MediaMutationKind, libraryId: String? = null, scanId: String? = null,
    userId: Long? = null, body: JSONObject) = MediaMutationRequest(operation, kind, libraryId, scanId, userId, body)
private fun operationBody(operation: MediaOperation) = JSONObject().put("operationId", operation.id)
private fun revisionBody(operation: MediaOperation, revision: Long) = operationBody(operation).put("expectedRevision", revision)
private fun generationBody(operation: MediaOperation, generation: Long) = operationBody(operation).put("expectedGeneration", generation)
private fun libraryEndpoint(id: String) = "/api/media-library/v1/libraries/$id"

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
    else -> error("媒体库回执类型无效")
}
