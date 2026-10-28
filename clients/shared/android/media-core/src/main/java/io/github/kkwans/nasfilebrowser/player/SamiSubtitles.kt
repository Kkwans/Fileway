package io.github.kkwans.nasfilebrowser.player

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class SamiCaption(val startMs: Long, val durationMs: Long?, val html: String)
data class SamiTrack(val key: String, val title: String, val language: String, val captions: List<SamiCaption>)

/** SAMI 1.0 timing/language structure; Android Html and Media3 render the text.
 * https://learn.microsoft.com/en-us/previous-versions/windows/desktop/dnacc/understanding-sami-1.0
 * No WebView, script, image fetch, or media reopen is involved.
 */
object SamiSubtitles {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val sync = Regex("<sync\\b(?:[^'\">]|\"[^\"]*\"|'[^']*')*>", options)
    private val paragraph = Regex("<p\\b((?:[^'\">]|\"[^\"]*\"|'[^']*')*)>", options)
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", options)
    private val styles = Regex("<style\\b[^>]*>(.*?)</style\\s*>", options)
    private val cssClass = Regex("\\.([\\w-]+)\\s*\\{([^}]*)\\}", options)
    private val cssProperty = Regex("([\\w-]+)\\s*:\\s*([^;}]*)", options)
    private val comments = Regex("<!--.*?-->", options)
    private val scripts = Regex("<script\\b[^>]*>.*?</script\\s*>", options)
    private val tags = Regex("</?(?:p|sync|body|sami)\\b[^>]*>", options)
    private fun attributes(value: String) = attribute.findAll(value).associate {
        it.groupValues[1].lowercase(Locale.ROOT) to (it.groups[2]?.value ?: it.groups[3]?.value ?: it.groupValues[4])
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size in 1..16 * 1024 * 1024) { "字幕文件为空或超过读取限制" }
        val b = bytes.take(3).map { it.toInt() and 255 }
        val (encoding, offset) = when {
            b.size >= 3 && b[0] == 0xef && b[1] == 0xbb && b[2] == 0xbf -> "UTF-8" to 3
            b.size >= 2 && b[0] == 0xff && b[1] == 0xfe -> "UTF-16LE" to 2
            b.size >= 2 && b[0] == 0xfe && b[1] == 0xff -> "UTF-16BE" to 2
            else -> {
                val header = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
                val declaration = Regex("charset\\s*=\\s*[\"']?([a-z0-9._-]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
                (declaration ?: "UTF-8") to 0
            }
        }
        return try {
            Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (failure: Exception) { throw IllegalArgumentException("字幕编码无法识别，请将文件另存为 UTF-8 后重试", failure) }
    }

    fun parse(bytes: ByteArray): List<SamiTrack> = parse(decode(bytes))
    fun parse(document: String): List<SamiTrack> {
        require(document.length <= 16 * 1024 * 1024)
        val languages = linkedMapOf<String, Pair<String, String>>()
        for (style in styles.findAll(document)) for (match in cssClass.findAll(style.groupValues[1])) {
            val properties = cssProperty.findAll(match.groupValues[2]).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim().trim('"', '\'') }
            if (properties["lang"] != null || properties["sami_type"].equals("cc", true)) {
                val key = match.groupValues[1].lowercase(Locale.ROOT)
                languages[key] = (properties["name"] ?: match.groupValues[1]) to properties["lang"].orEmpty()
            }
        }
        val body = document.substringAfter(Regex("<body\\b[^>]*>", options).find(document)?.value ?: "\u0000", document)
        val clean = scripts.replace(comments.replace(body, ""), "")
        val points = sync.findAll(clean).toList()
        require(points.isNotEmpty() && points.size <= 20_000) { "SAMI 时间轴为空或超过读取限制" }
        data class Part(val key: String, val source: Boolean, val html: String)
        data class Block(val time: Long, val parts: List<Part>)
        var previous = -1L
        val blocks = points.mapIndexed { index, point ->
            val time = attributes(point.value)["start"]?.toLongOrNull() ?: error("SAMI 缺少有效时间")
            require(time in 0..(Long.MAX_VALUE / 1000) && time >= previous) { "SAMI 时间轴无效或倒退" }
            previous = time
            val content = clean.substring(point.range.last + 1, points.getOrNull(index + 1)?.range?.first ?: clean.length)
            val paragraphs = paragraph.findAll(content).toList()
            val parts = paragraphs.mapIndexed { at, p ->
                val attrs = attributes(p.groupValues[1]); val key = attrs["class"].orEmpty().lowercase(Locale.ROOT)
                if (key.isNotEmpty()) languages.putIfAbsent(key, key to "")
                val html = tags.replace(content.substring(p.range.last + 1, paragraphs.getOrNull(at + 1)?.range?.first ?: content.length), "").trim()
                Part(key, attrs["id"].equals("source", true), html)
            }
            Block(time, if (parts.isEmpty() && content.isNotBlank()) listOf(Part("", false, tags.replace(content, "").trim())) else parts)
        }
        if (languages.isEmpty()) languages[""] = "SAMI" to ""
        require(languages.size <= 32) { "SAMI 语言轨道超过读取限制" }
        return languages.map { (key, info) ->
            var source = ""; var caption = ""
            val values = linkedMapOf<Long, String>()
            for (block in blocks) {
                val parts = block.parts.filter { it.key.isEmpty() || it.key == key }
                if (parts.isEmpty()) continue
                val sources = parts.filter { it.source }; val normal = parts.filterNot { it.source }
                if (sources.isNotEmpty()) source = sources.joinToString("<br>") { it.html }
                if (normal.isNotEmpty()) caption = normal.joinToString("<br>") { it.html }
                values[block.time] = listOf(source, caption).filter { it.isNotEmpty() }.joinToString("<br>")
            }
            val entries = values.entries.toList()
            SamiTrack(key, info.first, info.second, entries.mapIndexed { index, value ->
                SamiCaption(value.key, entries.getOrNull(index + 1)?.key?.minus(value.key), value.value)
            })
        }.filter { it.captions.isNotEmpty() }.also { require(it.isNotEmpty()) { "SAMI 没有可用语言轨道" } }
    }
}
