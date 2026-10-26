package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import java.util.Locale

/** Source metadata only: this does not assert the display or decoder is outputting HDR. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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

/** Renderer-bound frame metadata; activation also requires first-frame confirmation and display evidence. */
data class RenderedVideoOutput(val codecSession: Long, val decoderName: String, val decoderMime: String,
    val configuredMime: String, val outputMime: String?, val colorSpace: Int?, val colorTransfer: Int?,
    val colorRange: Int?, val staticHdrMetadata: Boolean)

internal data class HdrDisplayInfo(val known: Boolean = false, val pq: Boolean = false,
    val hlg: Boolean = false, val dolby: Boolean = false, val ratio: Float? = null)

internal enum class ActiveVideoOutput(val label: String) {
    UNKNOWN("尚未确认"), SDR("SDR"), HDR("HDR"), HDR10("HDR10"), HLG("HDR · HLG"), DOLBY_VISION("HDR · 杜比视界")
}

/** Source Dolby tags, decoder names and display support alone never activate a badge. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun activeVideoOutput(output: RenderedVideoOutput?, display: HdrDisplayInfo, firstFrame: Boolean): ActiveVideoOutput {
    if (output == null || !firstFrame) return ActiveVideoOutput.UNKNOWN
    if (output.colorTransfer == C.COLOR_TRANSFER_SDR) return ActiveVideoOutput.SDR
    val headroom = display.ratio
    if (!display.known || headroom == null || !headroom.isFinite() || headroom <= 1f || output.colorSpace != C.COLOR_SPACE_BT2020)
        return ActiveVideoOutput.UNKNOWN
    val pq = output.colorTransfer == C.COLOR_TRANSFER_ST2084
    val hlg = output.colorTransfer == C.COLOR_TRANSFER_HLG
    val configuredDolby = output.decoderMime == "video/dolby-vision" &&
        output.configuredMime.isNotBlank() && output.configuredMime !in setOf("video/hevc", "video/avc", "video/av01")
    if (display.dolby && configuredDolby && (pq || hlg)) return ActiveVideoOutput.DOLBY_VISION
    return when {
        pq && display.pq -> if (output.staticHdrMetadata) ActiveVideoOutput.HDR10 else ActiveVideoOutput.HDR
        hlg && display.hlg -> ActiveVideoOutput.HLG
        else -> ActiveVideoOutput.UNKNOWN
    }
}

internal fun videoContainer(name: String): String = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    .takeIf { it.length in 1..8 && it.all { character -> character.isLetterOrDigit() } } ?: "—"

internal fun videoPresentation(name: String, output: ActiveVideoOutput): String = when (output) {
    ActiveVideoOutput.UNKNOWN, ActiveVideoOutput.SDR -> videoContainer(name)
    else -> output.label
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoOutputColors(output: RenderedVideoOutput?): String {
    if (output == null) return "尚未报告"
    val space = when (output.colorSpace) { C.COLOR_SPACE_BT2020 -> "BT.2020"; C.COLOR_SPACE_BT709 -> "BT.709"; C.COLOR_SPACE_BT601 -> "BT.601"; else -> "色域未报告" }
    val transfer = when (output.colorTransfer) { C.COLOR_TRANSFER_ST2084 -> "PQ"; C.COLOR_TRANSFER_HLG -> "HLG"; C.COLOR_TRANSFER_SDR -> "SDR"; else -> "传递函数未报告" }
    val range = when (output.colorRange) { C.COLOR_RANGE_FULL -> "全范围"; C.COLOR_RANGE_LIMITED -> "限定范围"; else -> "范围未报告" }
    return "$space · $transfer · $range"
}
