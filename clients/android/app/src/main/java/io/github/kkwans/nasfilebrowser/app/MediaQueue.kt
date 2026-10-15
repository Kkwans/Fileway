package io.github.kkwans.nasfilebrowser.app

import java.util.Locale

enum class MediaKind { IMAGE, VIDEO, AUDIO }
enum class MediaQueueSource(val label: String) { DIRECTORY("当前目录"), SEARCH("当前搜索结果"), SINGLE("单个文件"), TAGGED("当前标签结果"), DOWNLOADED("本机下载") }

fun ResourceRef.mediaKind(): MediaKind? = mediaKind(name, directory, type)

/** Classification also serves display-only records that carry no resource identity. */
fun mediaKind(name: String, directory: Boolean = false, type: String = ""): MediaKind? {
    if (directory) return null
    return when {
        type == "video" || name.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "m2ts", "ts") -> MediaKind.VIDEO
        type == "image" || name.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif") -> MediaKind.IMAGE
        type == "audio" || name.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "aif", "wma", "ape", "alac") -> MediaKind.AUDIO
        else -> null
    }
}

val ResourceRef.mediaKey: String get() = if (downloadId.isNotEmpty()) "download/$downloadId" else wirePath.ifEmpty { path }

/** Immutable list at open time. No recursion, live search append, wraparound or auto-next. */
data class MediaQueue(
    val snapshotId: Long, val owner: String, val kind: MediaKind,
    val source: MediaQueueSource, val items: List<ResourceRef>, val index: Int,
) {
    init { require(items.isNotEmpty() && index in items.indices) }
    val current: ResourceRef get() = items[index]
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < items.lastIndex
    fun select(target: Int): MediaQueue? = if (target in items.indices) copy(index = target) else null

    companion object {
        fun snapshot(id: Long, owner: String, selected: ResourceRef, candidates: List<ResourceRef>, source: MediaQueueSource): MediaQueue? {
            val kind = selected.mediaKind() ?: return null
            val sameKind = candidates.filter { it.mediaKind() == kind }.distinctBy { it.mediaKey }
            val index = sameKind.indexOfFirst { it.mediaKey == selected.mediaKey }
            val items = if (index < 0) listOf(selected) else sameKind.mapIndexed { i, item -> if (i == index) selected else item }
            return MediaQueue(id, owner, kind, if (index < 0) MediaQueueSource.SINGLE else source, items, index.coerceAtLeast(0))
        }
    }
}
