package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Always stays in the PCM pipeline so changing gain does not reconfigure playback. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TvPcmGain : BaseAudioProcessor() {
    @Volatile private var gainPercent = 100

    fun setGain(percent: Int) {
        gainPercent = percent.coerceIn(100, 200)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val gain = gainPercent
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (gain == 100) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
                val sample = inputBuffer.short.toInt() * gain / 100
                output.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            inputBuffer.position(inputBuffer.limit())
        }
        output.flip()
    }
}
