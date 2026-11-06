package io.github.kkwans.nasfilebrowser.player

import androidx.media3.datasource.DataSpec
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

/** A bounded, caller-owned temporary asset. This is not a persisted cache.
 * Cancellation closes the actual HTTP call, including after response headers. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object SourceSubtitleAsset {
    suspend fun download(directory: File, url: String, limit: Long): File {
        require(limit in 1..SUBTITLE_FONT_BYTES_LIMIT.toLong())
        var owned: File? = null
        try {
            withContext(Dispatchers.IO) {
                withTimeout(45_000) {
                    val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
                        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS).build()
                    val network = CancellableHttpSources(client)
                    coroutineScope {
                        val guard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { network.cancel() }
                        }
                        try {
                            val source = network.createDataSource()
                            try {
                                val size = source.open(DataSpec.Builder().setUri(url).build())
                                if (size > limit) throw ExternalSubtitleLimitException(limit)
                                val reserve = 16L * 1024 * 1024
                                if (directory.usableSpace < reserve + size.coerceAtLeast(0)) throw ExternalSubtitleStorageException()
                                val file = File.createTempFile("external-subtitle-asset-", ".bin", directory)
                                owned = file // Assign before the cancellable dispatcher handoff.
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(32 * 1024); var total = 0L
                                    while (true) {
                                        ensureActive()
                                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
                                        if (count < 0) break
                                        check(count > 0) { "源字幕资源没有返回有效数据" }
                                        if (total + count > limit) throw ExternalSubtitleLimitException(limit)
                                        if (directory.usableSpace < reserve + count) throw ExternalSubtitleStorageException()
                                        output.write(buffer, 0, count); total += count
                                    }
                                    check(total > 0 && (size < 0 || total == size)) { "源字幕资源未完整读取" }
                                }
                            } finally { source.close() }
                        } catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            throw failure
                        } finally {
                            network.cancel()
                            withContext(NonCancellable) {
                                guard.cancelAndJoin()
                                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(owned).also { owned = null }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { owned?.delete() }
        }
    }
}
