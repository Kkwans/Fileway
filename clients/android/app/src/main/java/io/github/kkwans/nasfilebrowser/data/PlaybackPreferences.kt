package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TextSubtitleArea { PICTURE, STAGE }

data class TextSubtitleAppearance(val scale: Float = 1f, val bottomPadding: Float = .08f,
    val area: TextSubtitleArea = TextSubtitleArea.PICTURE, val backgroundOpacity: Float = 0f,
    val fontKey: String = "subtitle-default") {
    init {
        require(scale.isFinite() && scale in .5f..2f && bottomPadding.isFinite() && bottomPadding in 0f.. .4f)
        require(backgroundOpacity.isFinite() && backgroundOpacity in 0f..1f && fontKey.isNotBlank())
    }
}

enum class VideoDecodePolicy(val label: String) {
    AUTO("自动：硬件优先"), HARDWARE("仅硬件"), SOFTWARE("软件")
}

/** Device preference; no account, token or playback snapshot is stored here. */
class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.getFloat("hold_rate", 3f).takeIf { it.isFinite() && it in .1f..5f } ?: 3f)
    val holdRate = mutable.asStateFlow()
    private val presetWrites = Mutex()
    private val presetMutable = MutableStateFlow(readPlaybackRatePresets(prefs.getString("rate_presets", null)))
    val ratePresets = presetMutable.asStateFlow()
    suspend fun saveRatePresets(values: List<Float>) = presetWrites.withLock {
        val normalized = normalizePlaybackRatePresets(values)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            check(prefs.edit().putString("rate_presets", normalized.joinToString(",")).commit()) { "无法保存倍速预设" }
            presetMutable.value = normalized
        }
    }
    private val decoderWrites = Mutex()
    private val decoderMutable = MutableStateFlow(VideoDecodePolicy.entries.firstOrNull { it.name == prefs.getString("video_decoder", "AUTO") } ?: VideoDecodePolicy.AUTO)
    val videoDecodePolicy = decoderMutable.asStateFlow()
    suspend fun saveVideoDecodePolicy(value: VideoDecodePolicy) = decoderWrites.withLock {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            check(prefs.edit().putString("video_decoder", value.name).commit()) { "无法保存解码策略" }
            decoderMutable.value = value
        }
    }
    private val subtitleWrites = Mutex()
    private val subtitleMutable = MutableStateFlow(TextSubtitleAppearance(
        prefs.getFloat("subtitle_scale", 1f).takeIf { it.isFinite() && it in .5f..2f } ?: 1f,
        prefs.getFloat("subtitle_bottom", .08f).takeIf { it.isFinite() && it in 0f.. .4f } ?: .08f,
        TextSubtitleArea.entries.firstOrNull { it.name == prefs.getString("subtitle_area", "PICTURE") } ?: TextSubtitleArea.PICTURE,
        prefs.getFloat("subtitle_background", 0f).takeIf { it.isFinite() && it in 0f..1f } ?: 0f,
        prefs.getString("subtitle_font", "subtitle-default")?.takeIf { it.isNotBlank() } ?: "subtitle-default",
    ))
    val textSubtitleAppearance = subtitleMutable.asStateFlow()
    suspend fun saveTextSubtitleAppearance(value: TextSubtitleAppearance) = subtitleWrites.withLock {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            check(prefs.edit().putFloat("subtitle_scale", value.scale).putFloat("subtitle_bottom", value.bottomPadding)
                .putString("subtitle_area", value.area.name).putFloat("subtitle_background", value.backgroundOpacity)
                .putString("subtitle_font", value.fontKey).commit()) { "无法保存字幕外观" }
            // Publish the acknowledged value even if the UI owner leaves during commit.
            subtitleMutable.value = value
        }
    }
    private val holdWrites = Mutex()
    suspend fun saveHoldRate(value: Float) = holdWrites.withLock {
        require(value.isFinite() && value in .1f..5f)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            check(prefs.edit().putFloat("hold_rate", value).commit()) { "无法保存长按倍速" }
            mutable.value = value
        }
    }
}

/** Restore the rate that was active before holding, including a custom rate. */
class HeldPlaybackRate(private val read: () -> Float, private val apply: (Float) -> Unit) {
    private var previous: Float? = null
    fun start(rate: Float) {
        if (previous != null || !rate.isFinite() || rate !in .1f..5f) return
        previous = read()
        apply(rate)
    }
    fun release() { val value = previous ?: return; previous = null; apply(value) }
}

fun parsePlaybackRate(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in .1f..5f }

val DefaultPlaybackRatePresets: List<Float> = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)

internal fun normalizePlaybackRatePresets(values: List<Float>): List<Float> {
    require(values.isNotEmpty() && values.all { it.isFinite() && it in .1f..5f })
    return values.distinct().sorted()
}

internal fun readPlaybackRatePresets(saved: String?): List<Float> {
    if (saved == null) return DefaultPlaybackRatePresets
    val values = saved.split(',').map { it.toFloatOrNull() ?: return DefaultPlaybackRatePresets }
    return if (values.isNotEmpty() && values.all { it.isFinite() && it in .1f..5f })
        normalizePlaybackRatePresets(values) else DefaultPlaybackRatePresets
}

/** Accept signed seconds from IMEs that emit full-width punctuation. */
fun parseSubtitleDelayMillis(text: String): Long? {
    val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        .replace('\u2212', '-').replace('\u3002', '.').trim()
    val seconds = normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it in -600.0..600.0 } ?: return null
    return (seconds * 1000).toLong()
}
