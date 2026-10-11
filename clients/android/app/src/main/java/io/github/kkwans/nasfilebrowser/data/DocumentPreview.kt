package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.sqrt

const val DOCUMENT_TEXT_LIMIT = 10L * 1024 * 1024
const val DOCUMENT_PDF_LIMIT = 64L * 1024 * 1024
enum class DocumentPreviewKind { TEXT, PDF, OTHER }
data class DocumentTextBlock(val start: Int, val text: String)
data class DocumentText(val text: String, val encoding: String, val blocks: List<DocumentTextBlock>, val bom: Boolean = false,
    val byteSize: Long = text.length.toLong())
data class DocumentTextMatch(val start: Int, val end: Int)
data class DocumentTextSearch(val matches: List<DocumentTextMatch>, val truncated: Boolean)

fun documentPreviewKind(file: ResourceRef): DocumentPreviewKind {
    if (file.directory) return DocumentPreviewKind.OTHER
    val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        file.type == "pdf" || extension == "pdf" -> DocumentPreviewKind.PDF
        file.type in setOf("text", "textImmutable") -> DocumentPreviewKind.TEXT
        file.type in setOf("video", "audio", "image") -> DocumentPreviewKind.OTHER
        extension in setOf("txt", "log", "csv", "tsv") || FileCategory.MARKDOWN.matches(file.name, false) ||
            FileCategory.CONFIG.matches(file.name, false) || FileCategory.CODE.matches(file.name, false) -> DocumentPreviewKind.TEXT
        else -> DocumentPreviewKind.OTHER
    }
}

fun documentWirePath(file: ResourceRef): String {
    require(!file.directory && file.path.startsWith('/') && file.path != "/" && file.downloadId.isEmpty()) { "请从服务器选择文件" }
    require(file.wirePath.isNotEmpty() || !file.path.contains('\uFFFD')) { "原始路径不可用，请刷新后重新选择" }
    val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
    resourceWireBytes(wire)
    require(wire.removePrefix("/").split('/').none { segment ->
        val bytes = resourceWireBytes("/$segment").drop(1).toByteArray()
        bytes.isEmpty() || bytes.any { it == 0.toByte() || it == 47.toByte() } ||
            bytes.contentEquals(byteArrayOf(46)) || bytes.contentEquals(byteArrayOf(46, 46))
    }) { "原始路径无效，请刷新后重试" }
    return wire
}

/** BOMs are authoritative; other bytes must be strict UTF-8, never repaired or guessed. */
fun decodeDocumentText(bytes: ByteArray): DocumentText {
    require(bytes.size <= DOCUMENT_TEXT_LIMIT) { "文本超过 10 MiB 阅读上限，请下载后打开" }
    fun begins(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
    require(!begins(0xFF, 0xFE, 0, 0) && !begins(0, 0, 0xFE, 0xFF)) { "暂不支持 UTF-32 文本，请下载后打开" }
    val (encoding, skip) = when {
        begins(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
        begins(0xFF, 0xFE) -> "UTF-16LE" to 2
        begins(0xFE, 0xFF) -> "UTF-16BE" to 2
        else -> "UTF-8" to 0
    }
    val text = try {
        charset(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        error("无法按 $encoding 完整读取文字，原文件未改变；请下载后选择正确编码打开")
    }
    require(text.none { it < ' ' && it !in setOf('\n', '\r', '\t', '\u000C') }) { "文件包含二进制控制字符，请下载后使用合适的应用打开" }
    val blocks = mutableListOf<DocumentTextBlock>()
    var start = 0
    while (start < text.length) {
        var end = (start + 4096).coerceAtMost(text.length)
        var newline = end - 1
        while (newline >= start && text[newline] != '\n') newline--
        if (newline >= start) end = newline + 1
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        blocks.add(DocumentTextBlock(start, text.substring(start, end)))
        start = end
    }
    return DocumentText(text, encoding, blocks, bom = skip > 0, byteSize = bytes.size.toLong())
}

fun searchDocumentText(text: String, query: String): DocumentTextSearch {
    require(query.length <= 1024) { "查找文字过长，请缩短至 1024 个字符以内" }
    if (query.isEmpty()) return DocumentTextSearch(emptyList(), false)
    val found = mutableListOf<DocumentTextMatch>()
    var start = 0
    while (start <= text.length) {
        val index = text.indexOf(query, start, ignoreCase = true)
        if (index < 0) break
        if (found.size == 2000) return DocumentTextSearch(found, true)
        found.add(DocumentTextMatch(index, index + query.length))
        start = index + query.length
    }
    return DocumentTextSearch(found, false)
}

fun documentBitmapDimensions(width: Int, height: Int, targetWidth: Int, memoryClassMB: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && targetWidth > 0)
    val budget = (memoryClassMB.toLong() * CACHE_MB / 16).coerceIn(4 * CACHE_MB, 16 * CACHE_MB)
    val proposedWidth = targetWidth.toDouble()
    val proposedHeight = proposedWidth * height / width
    val scale = sqrt(budget / (proposedWidth * proposedHeight * 4)).coerceAtMost(1.0)
    val w = (proposedWidth * scale).toInt().coerceAtLeast(1)
    val h = (proposedHeight * scale).toInt().coerceIn(1, (budget / (w.toLong() * 4)).toInt().coerceAtLeast(1))
    return w to h
}
