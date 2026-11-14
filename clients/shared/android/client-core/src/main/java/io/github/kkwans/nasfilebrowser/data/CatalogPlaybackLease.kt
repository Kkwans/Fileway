package io.github.kkwans.nasfilebrowser.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
        try { asset.release() }
        finally { admission.sessionId?.let { closeServerLease(api, it) } }
    }
}

internal suspend fun closeServerLease(api: NasSession, id: String) {
    withTimeoutOrNull(2_000) {
        try { api.closeCatalogPlayback(id) }
        catch (error: Exception) { if (error is CancellationException) throw error }
    }
}
