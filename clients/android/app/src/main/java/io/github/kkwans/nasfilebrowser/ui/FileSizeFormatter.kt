package io.github.kkwans.nasfilebrowser.ui

import java.util.Locale

/** Preserve existing large-unit presentation while keeping non-empty small files distinguishable. */
internal fun readableSize(size: Long): String = when {
    size < 0 -> "大小未知"
    size < 1024 -> "$size B"
    size >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", size.toDouble() / (1L shl 30))
    size >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", size.toDouble() / (1L shl 20))
    else -> String.format(Locale.ROOT, "%.0f KB", size.toDouble() / 1024)
}
