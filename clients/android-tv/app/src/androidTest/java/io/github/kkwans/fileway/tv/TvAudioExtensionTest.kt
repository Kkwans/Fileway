package io.github.kkwans.fileway.tv

import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned bundled tones through the actual TV player, renderer policy and JNI.
 * Rendered buffer counters prove delivery to the sink, not audible speakers,
 * multichannel passthrough, Q1 behavior or HDR. No account/data reset. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvAudioExtensionTest {
    @Test fun bundledCodecsLoadAndRenderAcrossTracksAndPausedSeek(): Unit = runBlocking {
        assertTrue("This gate requires an audio-complete TV APK", BuildConfig.HAS_FFMPEG_AUDIO)
        val library = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
        assertEquals("API28 JNI must actually load", true, library.getMethod("isAvailable").invoke(null))
        assertEquals(true, library.getMethod("supportsFormat", String::class.java).invoke(null, MimeTypes.AUDIO_TRUEHD))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("media/subtitle-fixture.mkv").use { it.readBytes() }
        assertEquals("Only the authored multi-codec fixture is allowed",
            "d582828ab83e23f5d6f784babe30eb6ffb274c8e108258e0fb54207b4c06da64",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val file = File.createTempFile("tv-owned-audio-", ".mkv", context.cacheDir).apply { writeBytes(bytes) }
        val records = JSONArray()
        var scenario: ActivityScenario<TvActivity>? = null
        var player: NativePlayer? = null
        var model: TvModel? = null
        var viewport: PlayerViewport? = null
        var container: FrameLayout? = null
        suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
        suspend fun await(stage: String, predicate: () -> Boolean) = withTimeout(20_000) {
            while (!main {
                check(player?.state?.value?.error == null) { "$stage: ${player?.state?.value?.error}" }
                predicate()
            }) delay(60)
        }
        // Read-only instrumentation access follows the existing native phone
        // diagnostics; it does not add a test transport/factory to production.
        fun engine() = NativePlayer::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(player) as ExoPlayer
        try {
            val active = ActivityScenario.launch(TvActivity::class.java)
            scenario = active
            active.onActivity { model = ViewModelProvider(it)[TvModel::class.java] }
            await("startup account restore completes") { model?.state?.value?.restoring == false }
            active.onActivity { host ->
                check(model!!.media.state.value.file == null) { "Refuse interrupting an existing TV media session" }
                player = model!!.media.player
                viewport = PlayerViewport(host)
                container = host.findViewById(android.R.id.content)
                container!!.addView(viewport, FrameLayout.LayoutParams(-1, -1))
                player!!.attach(viewport!!)
                player!!.open(android.net.Uri.fromFile(file).toString(), videoDecodePolicy = VideoDecodePolicy.SOFTWARE)
                player!!.subtitle(-1)
            }
            await("actual fixture tracks") { player!!.state.value.audio.count { it.id >= 0 } == 4 }
            for ((index, mime) in listOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AAC,
                MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_TRUEHD).withIndex()) {
                val id = main { player!!.state.value.audio.single { it.id >= 0 && it.codec == mime }.id }
                val position = if (index % 2 == 0) 2000L else 6000L
                val renderedBefore = main {
                    player!!.pause()
                    player!!.audio(id)
                    player!!.seek(position)
                    player!!.rate(if (index % 2 == 0) 1f else 1.5f)
                    val counters = engine().audioDecoderCounters
                    counters?.ensureUpdated()
                    counters to (counters?.renderedOutputBufferCount ?: 0)
                }
                await("paused selection $mime") {
                    val state = player!!.state.value
                    state.selectedAudio == id && state.pendingAudio == null && state.canSavePosition
                }
                main {
                    assertFalse("Track/seek/rate retains pause intent", player!!.wantsPlay)
                    player!!.toggle()
                }
                await("rendered output $mime") {
                    val state = player!!.state.value
                    val counters = engine().audioDecoderCounters
                    counters?.ensureUpdated()
                    val buffers = counters?.renderedOutputBufferCount ?: 0
                    state.playing && state.positionMs >= position + 250 && state.audioDecoder != "未知" &&
                        buffers > (if (counters === renderedBefore.first) renderedBefore.second else 0) &&
                        (mime != MimeTypes.AUDIO_TRUEHD || state.audioDecoder.startsWith("ffmpeg"))
                }
                main {
                    records.put(JSONObject().put("mime", mime).put("decoder", player!!.state.value.audioDecoder)
                        .put("renderedBuffers", engine().audioDecoderCounters?.renderedOutputBufferCount)
                        .put("positionMs", player!!.state.value.positionMs).put("rate", player!!.state.value.rate))
                }
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    main {
                        player?.stop()
                        viewport?.let { player?.detach(it); container?.removeView(it) }
                    }
                    // ActivityScenario.close waits for Main; never call it on Main.
                    scenario?.close()
                }
            } finally {
                file.delete()
                File(context.getExternalFilesDir(null), "tv-audio-acceptance.json").writeText(
                    JSONObject().put("ffmpeg", library.getMethod("getVersion").invoke(null)).put("records", records)
                        .put("scope", "Actual TV NativePlayer and output-buffer delivery; physical audibility/HDR not verified").toString(2))
            }
        }
    }
}
