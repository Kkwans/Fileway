package io.github.kkwans.nasfilebrowser.ui

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val recentModifiedFormat = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.ROOT)
private val olderModifiedFormat = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ROOT)
private val modifiedFormat = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.ROOT)

/** NAS modified is RFC3339. Display its instant in the device's local zone, as the web list does. */
internal fun displayModified(value: String, zone: ZoneId = ZoneId.systemDefault(), compact: Boolean = false): String? {
    if (value.isBlank() || value.length > 128) return null
    return try {
        val date = OffsetDateTime.parse(value)
        val local = date.toInstant().atZone(zone)
        if (local.year !in 2..9999) null else {
            val format = if (!compact) modifiedFormat else if (local.year == LocalDate.now(zone).year) recentModifiedFormat else olderModifiedFormat
            format.format(local)
        }
    } catch (_: DateTimeException) { null }
}

internal fun fileTypeLabel(file: ResourceRef): String {
    if (file.directory) return "文件夹"
    val kind = when (file.type) { "video" -> "视频"; "image" -> "图片"; "audio" -> "音频"; "text" -> "文本"; else -> "文件" }
    val extension = file.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return if (extension.isEmpty() || extension.length > 12) kind else "$extension $kind"
}
