package io.github.kkwans.nasfilebrowser.player

import java.io.ByteArrayOutputStream

internal enum class AssSection(val marker: String) {
    INFO("[Script Info]"), STYLES("[V4 Styles]"), ASS_STYLES("[V4+ Styles]"), EVENTS("[Events]"), FONTS("[Fonts]");

    companion object {
        fun read(data: ByteArray, start: Int = 0, end: Int = data.size): AssSection? {
            val content = data.assLineContent(start, end)
            return entries.firstOrNull { data.startsWithAscii(content, end, it.marker, ignoreCase = true) }
        }
    }
}

internal data class AssEmbeddedFonts(val script: ByteArray, val reduced: Boolean)

/** Extract before libass sees any font blocks. The renderer's single font
 * registry owns the budget for these bytes as well as container attachments. */
internal fun extractAssFonts(data: ByteArray, maxFontBytes: Long, register: (String, ByteArray) -> Boolean): AssEmbeddedFonts {
    val end = data.indexOf(0.toByte()).let { if (it < 0) data.size else it }
    var script: ByteArrayOutputStream? = null
    var keepFrom = 0
    var inFonts = false
    var fontName: String? = null
    var fontStart = 0
    var reduced = false
    fun finishFont(at: Int) {
        val name = fontName ?: return
        fontName = null
        val bytes = if (name.isNotBlank() && name.length <= 256) data.decodeAssFont(fontStart, at, maxFontBytes) else null
        if (bytes == null || !register(name, bytes)) reduced = true
    }
    data.forAssLines(0, end) { start, stop ->
        val section = AssSection.read(data, start, stop)
        if (section != null) {
            if (inFonts) finishFont(start)
            if (section == AssSection.FONTS) {
                if (script == null) script = ByteArrayOutputStream(minOf(end, 4096))
                if (!inFonts) script?.write(data, keepFrom, start - keepFrom)
            } else if (inFonts) keepFrom = start
            inFonts = section == AssSection.FONTS
        } else if (inFonts) {
            val content = data.assLineContent(start, stop)
            if (data.startsWithAscii(content, stop, "fontname:")) {
                finishFont(start)
                // Bound the metadata independently of the encoded payload.
                fontName = if (stop - content <= 1024) data.decodeToString(content + 9, stop).trim() else ""
                fontStart = stop
            }
        }
    }
    if (inFonts) { finishFont(end); keepFrom = end }
    val filtered = script?.let { it.write(data, keepFrom, data.size - keepFrom); it.toByteArray() } ?: data
    return AssEmbeddedFonts(filtered, reduced)
}

private fun ByteArray.decodeAssFont(start: Int, end: Int, limit: Long): ByteArray? {
    var count = 0
    forAssLines(start, end) { from, to ->
        for (index in assLineContent(from, to) until to) {
            val value = this[index].toInt()
            if (value == 32 || value == 9) continue
            if (value !in 33..96) return null
            count++
        }
    }
    val size = count.toLong() * 3 / 4
    if (count % 4 == 1 || size !in 12..limit) return null
    val result = ByteArray(size.toInt())
    var written = 0
    var bits = 0
    var value = 0
    forAssLines(start, end) { from, to ->
        for (index in assLineContent(from, to) until to) {
            val code = this[index].toInt()
            if (code == 32 || code == 9) continue
            value = (value shl 6) or (code - 33)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                result[written++] = (value shr bits).toByte()
                value = value and ((1 shl bits) - 1)
            }
        }
    }
    return result
}

private inline fun ByteArray.forAssLines(from: Int, to: Int, action: (Int, Int) -> Unit) {
    var start = from
    while (start < to) {
        var end = start
        while (end < to && this[end] != 10.toByte() && this[end] != 13.toByte()) end++
        action(start, end)
        start = end + 1
    }
}

private fun ByteArray.assLineContent(start: Int, end: Int): Int {
    var content = start
    while (end - content >= 3 && this[content] == 0xef.toByte() &&
        this[content + 1] == 0xbb.toByte() && this[content + 2] == 0xbf.toByte()) content += 3
    while (content < end && (this[content] == 32.toByte() || this[content] == 9.toByte())) content++
    return content
}

private fun ByteArray.startsWithAscii(start: Int, end: Int, prefix: String, ignoreCase: Boolean = false): Boolean {
    if (end - start < prefix.length) return false
    for (index in prefix.indices) {
        val value = this[start + index].toInt().toChar()
        if (!value.equals(prefix[index], ignoreCase = ignoreCase)) return false
    }
    return true
}
