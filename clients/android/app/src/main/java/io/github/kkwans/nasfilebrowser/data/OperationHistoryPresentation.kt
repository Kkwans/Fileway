package io.github.kkwans.nasfilebrowser.data

/** Display-only split. Neither value is a ResourceRef or an actionable wire path. */
internal data class HistoryTargetDisplay(val name: String, val parent: String?)
private val WindowsHistoryTarget = Regex("^[A-Za-z]:[\\\\/]")

internal fun operationHistoryTarget(value: String): HistoryTargetDisplay {
    if ('\n' in value || '\r' in value) return HistoryTargetDisplay(value, null)
    val windows = WindowsHistoryTarget.containsMatchIn(value) || value.startsWith("\\\\")
    if (!windows && !value.startsWith('/')) return HistoryTargetDisplay(value, null)
    val trimmed = if (windows) value.trimEnd('/', '\\') else value.trimEnd('/')
    if (trimmed.isEmpty() || windows && trimmed.length == 2 && trimmed[1] == ':') return HistoryTargetDisplay(value, null)
    val split = if (windows) maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\')) else trimmed.lastIndexOf('/')
    if (split < 0) return HistoryTargetDisplay(value, null)
    val parent = when {
        split == 0 -> "/"
        windows && split == 2 && trimmed[1] == ':' -> trimmed.substring(0, 3)
        else -> trimmed.substring(0, split)
    }
    return HistoryTargetDisplay(trimmed.substring(split + 1), parent)
}
