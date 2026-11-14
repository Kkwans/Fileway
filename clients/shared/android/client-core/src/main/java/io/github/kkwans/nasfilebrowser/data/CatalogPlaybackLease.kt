package io.github.kkwans.nasfilebrowser.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/** Holds one authenticated HTTP Range resource on a single server timeline. */
class CatalogPlaybackLease internal constructor(
    private val api: NasSession,
    private val asset: PreviewLease,
    private val admission: MediaCatalogPlaybackResolution,
) {
    val url: String get() = asset.url
    val startPositionMs: Long = admission.watchProgress?.positionMs ?: 0

    private data class Sample(val positionMs: Long, val durationMs: Long?, val ended: Boolean)
    private val samples = Channel<Sample>(Channel.CONFLATED)
    private val failed = MutableStateFlow(false)
    val progressSyncFailed = failed.asStateFlow()
    private val worker = if (admission.watchProgress == null) null else CoroutineScope(Dispatchers.IO).launch {
        val endpoint = "/api/media-library/v1/playback-sessions/${requireNotNull(admission.sessionId)}/progress"
        var sequence = 0L
        var lastAcknowledged: Sample? = null
        for (sample in samples) {
            if (sample == lastAcknowledged) continue
            val expected = ++sequence
            val body = JSONObject().put("sequence", expected).put("positionMs", sample.positionMs)
                .put("durationMs", sample.durationMs ?: JSONObject.NULL).put("ended", sample.ended)
            for (attempt in 0..1) {
                try {
                    val receipt = withTimeoutOrNull(3_000) { api.request("POST", endpoint, body) }
                    check(receipt != null && parseCatalogWatchProgress(receipt).sequence == expected)
                    lastAcknowledged = sample
                    failed.value = false
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is ServiceException && error.status in listOf(401, 403, 404, 409, 410)) {
                        failed.value = true
                        samples.close()
                        return@launch
                    }
                    if (attempt == 0) delay(500)
                    else failed.value = true
                }
            }
        }
    }

    /** Accept only observations confirmed on this lease's original timeline. */
    fun recordProgress(positionMs: Long, durationMs: Long, ended: Boolean) {
        if (worker == null || positionMs !in 0..MAX_WATCH_MEDIA_MS) return
        val duration = durationMs.takeIf { it in 1..MAX_WATCH_MEDIA_MS }
        val position = if (duration == null) positionMs else positionMs.coerceAtMost(duration)
        if (position == 0L && duration == null) return
        samples.trySend(Sample(position, duration, ended && duration != null && duration - position <= 2_000))
    }

    /** Renew the same endpoint. After an unknown result retry the same operation id once. */
    suspend fun maintain() {
        val sessionId = admission.sessionId ?: return
        val leaseId = admission.leaseId ?: return
        var generation = admission.leaseGeneration ?: return
        var renewAfter = admission.renewAfter ?: return
        var expiresAt = admission.expiresAt
        while (true) {
            delay((renewAfter - System.currentTimeMillis()).coerceAtLeast(1_000L))
            val operation = UUID.randomUUID().toString()
            val endpoint = "/api/media-library/v1/playback-sessions/$sessionId/renew"
            val body = JSONObject().put("operationId", operation).put("expectedLeaseGeneration", generation)
            val renewed = try {
                api.request("POST", endpoint, body)
            } catch (error: Exception) {
                if (error is CancellationException || error is ServiceException && error.status < 500) throw error
                if (System.currentTimeMillis() + 2_000 >= expiresAt) throw error
                delay(2_000)
                api.request("POST", endpoint, body)
            }
            val nextGeneration = renewed.getLong("leaseGeneration")
            require(renewed.getString("sessionId") == sessionId && renewed.getString("leaseId") == leaseId &&
                nextGeneration == generation + 1) { "播放来源已变化" }
            generation = nextGeneration
            renewAfter = renewed.getLong("renewAfter")
            expiresAt = renewed.getLong("expiresAt")
        }
    }

    suspend fun release() = withContext(NonCancellable) {
        try {
            // Send the final confirmed sample while the authenticated lease is still open.
            samples.close()
            worker?.let { if (withTimeoutOrNull(2_000) { it.join(); true } != true) it.cancelAndJoin() }
            asset.release()
        }
        finally { admission.sessionId?.let { closeServerLease(api, it) } }
    }
}

internal suspend fun closeServerLease(api: NasSession, id: String) {
    withTimeoutOrNull(2_000) {
        try { api.closeCatalogPlayback(id) }
        catch (error: Exception) { if (error is CancellationException) throw error }
    }
}
