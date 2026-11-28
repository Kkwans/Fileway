package io.github.kkwans.fileway.tv

internal object TvUpdateInstallDirectory {
    const val DEFAULT = "Documents/Fileway"
}

internal fun normalizeTvUpdateDirectory(value: String): String? {
    val trimmed = value.trim().removePrefix("/").removeSuffix("/")
    if (trimmed.isEmpty() || trimmed.length > 120 || trimmed.contains('\\') || trimmed.any(Char::isISOControl)) return null
    val segments = trimmed.split('/')
    if (segments.size !in 1..4 || segments.any { it.isBlank() || it == "." || it == ".." }) return null
    if (segments.first().lowercase() !in setOf("documents", "download")) return null
    return segments.joinToString("/") { it.trim() }
}
