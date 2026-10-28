package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/** In-memory only. A format identity is never a UI title, list index or engine track ID. */
data class DecoderTrackIdentity(
    val type: Int, val id: String?, val label: String?, val language: String?,
    val mime: String?, val codecs: String?, val roleFlags: Int, val selectionFlags: Int,
    val channels: Int, val sampleRate: Int, val accessibilityChannel: Int,
) {
    companion object {
        @androidx.annotation.OptIn(UnstableApi::class)
        fun from(type: Int, format: Format) = DecoderTrackIdentity(type, format.id, format.label,
            format.language, format.sampleMimeType, format.codecs, format.roleFlags, format.selectionFlags,
            format.channelCount, format.sampleRate, format.accessibilityChannel)
    }
}

enum class DecoderSubtitleMode { AUTO, OFF, INTERNAL, EXTERNAL }
data class DecoderExternalSelection(val sourceKey: String, val trackKey: String)
data class DecoderRestore(
    val positionMs: Long, val wantsPlay: Boolean, val baseRate: Float, val volume: Int,
    val audio: DecoderTrackIdentity?, val audioOff: Boolean,
    val subtitleMode: DecoderSubtitleMode, val subtitle: DecoderTrackIdentity?,
    val externalSubtitle: DecoderExternalSelection?, val externalSources: Map<String, String>,
    val subtitleDelayMs: Long,
)
