package io.github.kkwans.nasfilebrowser.player

import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Observes the stock adapter and renderer. It never changes codec configuration or video pixels. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoOutputProbe(private val changed: (RenderedVideoOutput) -> Unit) : VideoFrameMetadataListener {
    private data class Codec(val sequence: Long, val name: String, val mime: String, val configuredMime: String)
    private val sequence = AtomicLong()
    private val codec = AtomicReference<Codec?>()
    // Renderer callbacks are serialized on ExoPlayer's playback thread.
    private var lastOutput: RenderedVideoOutput? = null

    fun observe(factory: MediaCodecAdapter.Factory): MediaCodecAdapter.Factory = MediaCodecAdapter.Factory { configuration ->
        val adapter = factory.createAdapter(configuration)
        if (MimeTypes.isVideo(configuration.codecInfo.mimeType)) {
            codec.set(Codec(sequence.incrementAndGet(), configuration.codecInfo.name, configuration.codecInfo.mimeType,
                configuration.mediaFormat.getString(MediaFormat.KEY_MIME) ?: configuration.codecInfo.codecMimeType))
        }
        adapter
    }

    fun isCurrent(value: RenderedVideoOutput) = codec.get()?.sequence == value.codecSession

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long,
        format: Format, mediaFormat: MediaFormat?) {
        val selected = codec.get() ?: return
        fun integer(key: String) = mediaFormat?.takeIf { it.containsKey(key) }?.getInteger(key)?.takeIf { it != Format.NO_VALUE }
        val output = RenderedVideoOutput(selected.sequence, selected.name, selected.mime, selected.configuredMime,
            mediaFormat?.getString(MediaFormat.KEY_MIME), integer(MediaFormat.KEY_COLOR_STANDARD),
            integer(MediaFormat.KEY_COLOR_TRANSFER), integer(MediaFormat.KEY_COLOR_RANGE),
            (mediaFormat?.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.remaining() ?: 0) > 0 ||
                format.colorInfo?.hdrStaticInfo?.isNotEmpty() == true)
        if (output != lastOutput) {
            lastOutput = output
            changed(output)
        }
    }
}
