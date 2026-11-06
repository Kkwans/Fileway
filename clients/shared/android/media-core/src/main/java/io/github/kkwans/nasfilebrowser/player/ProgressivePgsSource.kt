package io.github.kkwans.nasfilebrowser.player

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PgsStreamReceipt(val bytes: Long, val sha256: String)

/** One append-only spool; snapshots keep it alive after the producer exits. */
private class PgsSpool(directory: File) : Closeable {
    val file = File.createTempFile("external-pgs-stream-", ".sup", directory)
    private var references = 1
    private val ownerClosed = AtomicBoolean()
    suspend fun snapshot(builder: ExternalPgs.IndexBuilder): ExternalPgs.Timeline {
        synchronized(this) { check(references > 0); references++ }
        return try { builder.snapshot(onClosed = ::release) }
        catch (failure: Throwable) { release(); throw failure }
    }
    private fun release() = synchronized(this) {
        check(references > 0)
        references--
        if (references == 0 && file.exists() && !file.delete()) throw IOException("SUP spool cleanup failed")
    }
    override fun close() { if (ownerClosed.compareAndSet(false, true)) release() }
}

/** Protocol, bounded disk spool and one-pass SUP index only. The caller owns
 * every delivered timeline, including when its callback rejects an update.
 * No player, account, URL persistence or UI is owned by this loader. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProgressivePgsSource(private val directory: File, private val metadataLimit: Long,
    private val positionUs: () -> Long) {
    suspend fun load(url: String, identity: String,
        onTimeline: suspend (ExternalPgs.Timeline) -> Unit): PgsStreamReceipt = withContext(Dispatchers.IO) {
        // Server work is bounded to two minutes, including lane admission.
        withTimeout(150_000) { readStream(url, identity, onTimeline) }
    }
    private suspend fun readStream(url: String, identity: String,
        onTimeline: suspend (ExternalPgs.Timeline) -> Unit): PgsStreamReceipt = coroutineScope {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(1, 15, TimeUnit.SECONDS)).build()
        val network = CancellableHttpSources(client)
        // Cancellation runs independently from a blocked body read; a one-shot
        // dispatcher.cancelAll cannot cancel bodies already handed to Media3.
        val guard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { network.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                val spool = PgsSpool(directory)
                try {
                    FileOutputStream(spool.file).use { output ->
                        ExternalPgs.IndexBuilder(spool.file, metadataLimit).use { builder ->
                            val source = network.createDataSource()
                            try {
                                source.open(DataSpec.Builder().setUri(url).build())
                                val contentType = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
                                    ?.value?.firstOrNull()?.substringBefore(';')?.trim()
                                require(contentType.equals("application/x-fileway-media-asset+ndjson", ignoreCase = true)) { "源字幕流格式不受支持" }
                                val lines = BoundedLines(source)
                                val digest = MessageDigest.getInstance("SHA-256")
                                var started = false
                                var total = 0L
                                var published = -1L
                                var publishedAt = 0L
                                suspend fun publish(force: Boolean = false) {
                                    if (builder.eventCount == 0 || (!force && builder.revision == published)) return
                                    if (!force && published < 0 && positionUs() >= builder.coveredUntilUs) return
                                    val now = SystemClock.elapsedRealtime()
                                    if (!force && published >= 0 && now - publishedAt < 500) return
                                    currentCoroutineContext().ensureActive()
                                    val timeline = spool.snapshot(builder)
                                    try { onTimeline(timeline) }
                                    catch (failure: Throwable) { timeline.close(); throw failure }
                                    published = builder.revision; publishedAt = now
                                }
                                while (true) {
                                    ensureActive()
                                    val line = lines.next() ?: throw IOException("源字幕流未完整结束")
                                    val frame = JSONObject(line)
                                    when (frame.getString("type")) {
                                        "start" -> {
                                            require(!started && total == 0L && frame.getString("identity") == identity && frame.getString("format") == "sup") { "源字幕身份不匹配" }
                                            started = true
                                        }
                                        "pending" -> { require(started); publish() }
                                        "data" -> {
                                            require(started)
                                            val encoded = frame.getString("data")
                                            require(encoded.length <= 43692)
                                            val bytes = Base64.getDecoder().decode(encoded)
                                            require(bytes.size in 1..32768 && total <= EXTERNAL_SUP_BYTES_LIMIT - bytes.size) { "源字幕超过大小限制" }
                                            if (directory.usableSpace < 16L * 1024 * 1024 + bytes.size) throw ExternalSubtitleStorageException()
                                            output.write(bytes); digest.update(bytes); total += bytes.size
                                            builder.advance(total)
                                            publish()
                                        }
                                        "complete" -> {
                                            require(started && total > 0 && frame.getLong("bytes") == total) { "源字幕长度不匹配" }
                                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                            require(frame.getString("sha256") == actual) { "源字幕校验失败" }
                                            builder.advance(total, finished = true)
                                            output.fd.sync()
                                            publish(force = true)
                                            return@withContext PgsStreamReceipt(total, actual)
                                        }
                                        "error" -> throw IOException(if (frame.optString("code") == "SOURCE_CHANGED") "媒体版本或读取权限已变化" else "源字幕提取失败，可重试")
                                        else -> throw IOException("未知源字幕流消息")
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE")
                                error("Unreachable")
                            } finally { source.close() }
                        }
                    }
                } finally { spool.close() }
            }
        } catch (failure: Exception) {
            // A canceled HTTP body can throw IOException while the coroutine
            // is unwinding. Preserve cancellation instead of failing siblings.
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

    /** Limit bytes before creating a String/JSON object; readLine alone is
     * unbounded. Small input fragments and heartbeats do not imply progress. */
    private class BoundedLines(private val source: DataSource) {
        private val buffer = ByteArray(8192)
        private var offset = 0
        private var length = 0
        fun next(): String? {
            val output = ByteArrayOutputStream()
            while (true) {
                if (offset == length) {
                    length = source.read(buffer, 0, buffer.size); offset = 0
                    if (length < 0) {
                        if (output.size() != 0) throw IOException("源字幕消息被截断")
                        return null
                    }
                    require(length > 0)
                }
                val start = offset
                while (offset < length && buffer[offset] != 10.toByte()) offset++
                val count = offset - start
                require(output.size() + count <= 48 * 1024) { "源字幕消息过大" }
                output.write(buffer, start, count)
                if (offset < length) { offset++; return output.toString(Charsets.UTF_8.name()) }
            }
        }
    }
}
