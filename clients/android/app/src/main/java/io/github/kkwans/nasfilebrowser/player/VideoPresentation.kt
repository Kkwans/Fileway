package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import java.util.Locale

/** Source metadata only: this does not assert the display or decoder is outputting HDR. */
internal fun sourceDynamicRange(mime: String?, codecs: String?, transfer: Int?): String {
    val dolby = codecs.orEmpty().split(',').any {
        it.trim().substringBefore('.').lowercase(Locale.ROOT) in setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")
    }
    return when {
        mime == "video/dolby-vision" || dolby -> "杜比视界"
        transfer == C.COLOR_TRANSFER_ST2084 -> "HDR（PQ）"
        transfer == C.COLOR_TRANSFER_HLG -> "HDR（HLG）"
        transfer == C.COLOR_TRANSFER_SDR -> "SDR"
        else -> "未知"
    }
}

internal fun videoPresentation(name: String, dynamicRange: String): String {
    val extension = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
        .takeIf { it.length in 1..8 && it.all { character -> character.isLetterOrDigit() } }
    val special = dynamicRange.takeIf { it == "杜比视界" || it.startsWith("HDR（") }
    return listOfNotNull(extension, special).joinToString(" · ").ifEmpty { "—" }
}
