package io.github.kkwans.nasfilebrowser.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.json.JSONObject

internal data class SourceStreamReceipt(val bytes: Long, val sha256: String)

/** Shared ASS/PGS framing and actual HTTP-call cancellation. Consumers own
 * storage/parsing, and only a verified complete frame commits their asset. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SourceSubtitleStream {
    suspend fun read(url: String, identity: String, format: String, limit: Long,
        data: suspend (ByteArray) -> Unit, pending: suspend () -> Unit = {}): SourceStreamReceipt = withContext(Dispatchers.IO) {
        withTimeout(150_000) { coroutineScope {
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(1, 15, TimeUnit.SECONDS)).build()
            val network = CancellableHttpSources(client)
            val guard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { network.cancel() }
            }
            try {
                val source = network.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri(url).build())
                    val contentType = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value?.firstOrNull()?.substringBefore(';')?.trim()
                    require(contentType.equals("application/x-fileway-media-asset+ndjson", true)) { "源字幕流格式不受支持" }
                    val lines = BoundedLines(source); val digest = MessageDigest.getInstance("SHA-256")
                    var started = false; var total = 0L
                    while (true) {
                        ensureActive()
                        val frame = JSONObject(lines.next() ?: throw IOException("源字幕流未完整结束"))
                        when (frame.getString("type")) {
                            "start" -> {
                                require(!started && total == 0L && frame.getString("identity") == identity && frame.getString("format") == format) { "源字幕身份不匹配" }
                                started = true
                            }
                            "pending" -> { require(started); pending() }
                            "data" -> {
                                require(started)
                                val encoded = frame.getString("data"); require(encoded.length <= 43692)
                                val bytes = Base64.getDecoder().decode(encoded)
                                require(bytes.size in 1..32768 && total <= limit - bytes.size) { "源字幕超过大小限制" }
                                data(bytes); digest.update(bytes); total += bytes.size
                            }
                            "complete" -> {
                                require(started && total > 0 && frame.getLong("bytes") == total) { "源字幕长度不匹配" }
                                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                require(frame.getString("sha256") == actual) { "源字幕校验失败" }
                                return@coroutineScope SourceStreamReceipt(total, actual)
                            }
                            "error" -> throw IOException(if (frame.optString("code") == "SOURCE_CHANGED") "媒体版本或读取权限已变化" else "源字幕提取失败，可重试")
                            else -> throw IOException("未知源字幕流消息")
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                } finally { source.close() }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive(); throw failure
            } finally {
                network.cancel()
                withContext(NonCancellable) {
                    guard.cancelAndJoin(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                }
            }
        } }
    }

    private class BoundedLines(private val source: DataSource) {
        private val buffer = ByteArray(8192); private var offset = 0; private var length = 0
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
