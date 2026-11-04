package io.github.kkwans.fileway.tv

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util

/** Correlation within one bound source identity, never a global resource ID. */
internal data class TvSubtitleKey(
    val codec: String, val language: String, val label: String,
    val default: Boolean, val forced: Boolean,
)

internal data class TvSubtitleMatch(val sourceIndex: Int, val nativeId: Int)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun tvSubtitleKey(codec: String, language: String?, label: String?, default: Boolean, forced: Boolean): TvSubtitleKey {
    val normalizedCodec = when (codec.lowercase(java.util.Locale.ROOT)) {
        "ssa", "ass", MimeTypes.TEXT_SSA -> "ass"
        "subrip", MimeTypes.APPLICATION_SUBRIP -> "subrip"
        "webvtt", MimeTypes.TEXT_VTT -> "webvtt"
        "hdmv_pgs_subtitle", MimeTypes.APPLICATION_PGS -> "pgs"
        "mov_text", MimeTypes.APPLICATION_TX3G -> "tx3g"
        "ttml", MimeTypes.APPLICATION_TTML -> "ttml"
        else -> codec.lowercase(java.util.Locale.ROOT)
    }
    val normalizedLanguage = Util.normalizeLanguageCode(language?.trim()?.replace('_', '-').orEmpty())
        .orEmpty().takeUnless { it == "und" }.orEmpty()
    return TvSubtitleKey(normalizedCodec, normalizedLanguage, label?.trim().orEmpty(), default, forced)
}

/** Require uniqueness on BOTH sides; list order and equal track counts prove nothing. */
internal fun matchUniqueTvSubtitle(key: TvSubtitleKey, source: Map<Int, TvSubtitleKey>, native: Map<Int, TvSubtitleKey>): TvSubtitleMatch? {
    val from = source.entries.singleOrNull { it.value == key } ?: return null
    val to = native.entries.singleOrNull { it.value == key } ?: return null
    return TvSubtitleMatch(from.key, to.key)
}
