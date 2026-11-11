package io.github.kkwans.nasfilebrowser.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

data class AssStreamUpdate(val data: ByteArray, val coveredUntilMs: Long, val complete: Boolean, val fontsChanged: Boolean = false)

/** A single pass over original script bytes. Network boundaries cannot finish
 * a UTF-8 line/font block. The observed timestamp is provisional, not proof
 * about future events; a decrease disables preview until full verification. */
class IncrementalAss(private val limit: Long = EXTERNAL_TEXT_BYTES_LIMIT) {
    private val line = ByteArrayOutputStream()
    private val fonts = ByteArrayOutputStream()
    private var inFonts = false
    private var inEvents = false
    private var scriptInfo = false
    private var eventHeader = false
    private var fields = emptyList<String>()
    private var latestStart = -1L
    private var ordered = true
    private var total = 0L
    private var finished = false
    private var fontsChanged = false
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    fun append(bytes: ByteArray): AssStreamUpdate {
        check(!finished && bytes.isNotEmpty() && total <= limit - bytes.size) { "ASS 字幕超过资源上限" }
        total += bytes.size
        fontsChanged = false
        val output = ByteArrayOutputStream()
        var start = 0
        for (index in bytes.indices) {
            val byte = bytes[index]
            require(byte != 0.toByte()) { "ASS 字幕包含无效字符" }
            if (byte == 10.toByte() || byte == 13.toByte()) {
                line.write(bytes, start, index - start + 1)
                consumeLine(output); start = index + 1
            }
        }
        if (start < bytes.size) line.write(bytes, start, bytes.size - start)
        return AssStreamUpdate(output.toByteArray(), if (ordered) latestStart else -1, false, fontsChanged)
    }

    /** Only after the transport's complete/length/SHA checks have succeeded. */
    fun finish(): AssStreamUpdate {
        check(!finished); finished = true
        fontsChanged = false
        val output = ByteArrayOutputStream()
        if (line.size() > 0) consumeLine(output)
        flushFonts(output)
        check(scriptInfo && eventHeader) { "ASS 脚本头缺失或格式不受支持" }
        return AssStreamUpdate(output.toByteArray(), Long.MAX_VALUE, true, fontsChanged)
    }

    private fun consumeLine(output: ByteArrayOutputStream) {
        val raw = line.toByteArray(); line.reset()
        val text = decoder.reset().decode(ByteBuffer.wrap(raw)).toString().trim().removePrefix("\uFEFF")
        val section = AssSection.read(raw)
        if (section != null) {
            flushFonts(output)
            inFonts = section == AssSection.FONTS
            inEvents = section == AssSection.EVENTS
            if (section == AssSection.INFO) scriptInfo = true
            if (inEvents) eventHeader = true
        }
        if (inFonts) fonts.write(raw) else output.write(raw)
        if (!inEvents) return
        when {
            text.startsWith("Format:", true) -> fields = text.substringAfter(':').split(',').map { it.trim().lowercase() }
            text.startsWith("Dialogue:", true) -> {
                val values = text.substringAfter(':').split(',', limit = fields.size.coerceAtLeast(1))
                fun at(field: String) = fields.indexOf(field).takeIf { it >= 0 }?.let { values.getOrNull(it)?.trim() }
                val start = at("start")?.let(::time)
                val end = at("end")?.let(::time)
                if (start == null || end == null || end < start || start < latestStart) ordered = false
                if (start != null) latestStart = maxOf(latestStart, start)
            }
        }
    }

    private fun flushFonts(output: ByteArrayOutputStream) {
        if (fonts.size() != 0) { fonts.writeTo(output); fonts.reset(); fontsChanged = true }
    }
    private fun time(value: String): Long? {
        val match = TIME.matchEntire(value) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toInt(); val seconds = match.groupValues[3].toInt()
        if (minutes >= 60 || seconds >= 60 || hours > (Long.MAX_VALUE - 3_599_999) / 3_600_000) return null
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1000 + match.groupValues[4].padEnd(3, '0').toInt()
    }
    private companion object { val TIME = Regex("(\\d+):(\\d{1,2}):(\\d{1,2})[.](\\d{1,3})") }
}

/** The pool owns the growing spool after start; a failed stream never commits.
 * Rendering is a separate reader, so decoder changes cannot own the producer. */
object ProgressiveAssSource {
    suspend fun download(directory: File, url: String, identity: String, writer: SourceSubtitleAssets.Writer): File = withContext(Dispatchers.IO) {
        if (directory.usableSpace < 16L * 1024 * 1024) throw ExternalSubtitleStorageException()
        val file = File.createTempFile("external-ass-stream-", ".ass", directory)
        var transferred = false
        try {
            writer.start(file); transferred = true
            file.outputStream().use { output ->
                var total = 0L
                SourceSubtitleStream.read(url, identity, "ass", EXTERNAL_TEXT_BYTES_LIMIT, data = { bytes ->
                    if (directory.usableSpace < 16L * 1024 * 1024 + bytes.size) throw ExternalSubtitleStorageException()
                    output.write(bytes); total += bytes.size; writer.advance(total)
                })
                output.fd.sync()
            }
            file
        } finally { if (!transferred) file.delete() }
    }
}
