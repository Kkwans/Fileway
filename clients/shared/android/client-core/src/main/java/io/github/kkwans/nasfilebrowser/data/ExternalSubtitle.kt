package io.github.kkwans.nasfilebrowser.data

/** Single-file subtitles can travel through one authenticated media lease. */
fun isExternalSubtitle(name: String): Boolean = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in
    setOf("srt", "ass", "ssa", "vtt", "sup", "ttml", "dfxp", "smi", "sami")
