package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.util.Locale
import org.json.JSONObject

enum class ArchiveEntryKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, DOCUMENT, OTHER }
fun archiveEntryKind(name: String): ArchiveEntryKind = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "svg" -> ArchiveEntryKind.IMAGE
    "mp4", "mkv", "webm", "m4v", "avi", "mov", "m2ts", "ts" -> ArchiveEntryKind.VIDEO
    "mp3", "m4a", "flac", "opus", "aac", "ogg", "wav", "wma", "aif", "aiff" -> ArchiveEntryKind.AUDIO
    "pdf" -> ArchiveEntryKind.PDF
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf" -> ArchiveEntryKind.DOCUMENT
    else -> if (isTextDocumentName(name)) ArchiveEntryKind.TEXT else ArchiveEntryKind.OTHER
}
data class ArchiveEntryOpenStatus(val id: String, val state: String, val stage: String, val size: Long, val processedBytes: Long,
    val asset: String, val error: String?) {
    companion object {
        fun from(row: JSONObject, listing: ArchiveListing, entry: ArchiveEntry): ArchiveEntryOpenStatus {
            fun number(key: String): Long = (row.get(key) as? Number)?.toString()?.toLongOrNull() ?: error("包内文件状态数值无效")
            val id = row.getString("id"); require(id.matches(Regex("[a-f0-9]{64}"))) { "包内文件准备标识无效" }
            val state = row.getString("state")
            require(state in setOf("queued", "checking", "copying", "ready", "failed", "canceling", "canceled")) { "包内文件准备状态无效" }
            require(number("sourceSize") == listing.sourceSize && number("sourceModified") == listing.sourceModified &&
                archiveEntryWireBytes(row.getString("entryWirePath")).contentEquals(archiveEntryWireBytes(entry.wirePath))) { "包内文件来源已变化" }
            val size = number("size"); val processed = number("processedBytes")
            require(size >= 0 && processed in 0..size && (state != "ready" || size == entry.size)) { "包内文件准备进度无效" }
            val asset = row.optString("asset")
            require(state != "ready" || asset == "/api/archives/open/$id/content") { "包内文件地址不属于当前服务" }
            return ArchiveEntryOpenStatus(id, state, row.getString("stage"), size, processed, asset, row.optString("error").takeIf { it.isNotBlank() })
        }
    }
}

/** This relative ResourceRef identifies temporary read-only content, never a user-Fs path. */
fun ArchiveEntry.openResource(): ResourceRef = ResourceRef(path, wirePath, name, false, when (archiveEntryKind(name)) {
    ArchiveEntryKind.IMAGE -> "image"; ArchiveEntryKind.VIDEO -> "video"; ArchiveEntryKind.AUDIO -> "audio"
    ArchiveEntryKind.PDF -> "pdf"; ArchiveEntryKind.TEXT -> "text"; else -> "document"
}, size, modified.toString())
