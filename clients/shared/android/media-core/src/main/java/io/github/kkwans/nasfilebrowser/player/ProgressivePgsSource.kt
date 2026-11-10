package io.github.kkwans.nasfilebrowser.player

import android.os.SystemClock
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        onTimeline: suspend (ExternalPgs.Timeline) -> Unit): PgsStreamReceipt {
        val spool = PgsSpool(directory)
        try {
            FileOutputStream(spool.file).use { output ->
                ExternalPgs.IndexBuilder(spool.file, metadataLimit).use { builder ->
                    var published = -1L; var publishedAt = 0L; var total = 0L
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
                    val receipt = SourceSubtitleStream.read(url, identity, "sup", EXTERNAL_SUP_BYTES_LIMIT,
                        data = { bytes ->
                            if (directory.usableSpace < 16L * 1024 * 1024 + bytes.size) throw ExternalSubtitleStorageException()
                            output.write(bytes); total += bytes.size
                            builder.advance(total); publish()
                        }, pending = { publish() })
                    builder.advance(total, finished = true); output.fd.sync(); publish(force = true)
                    return PgsStreamReceipt(receipt.bytes, receipt.sha256)
                }
            }
        } finally { spool.close() }
    }
}
