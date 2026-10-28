package io.github.kkwans.nasfilebrowser.player

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.TextSubtitleArea
import io.github.kkwans.nasfilebrowser.data.SubtitleFontCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt
import kotlin.math.max

/** Stable native video Surface plus independent text/ASS layers; HUD changes cannot resize them. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewport(context: Context) : FrameLayout(context) {
    var onPictureBoundsChanged: ((Rect) -> Unit)? = null
    private var lastPictureBounds = Rect()
    private val content = AspectRatioFrameLayout(context).apply { setAspectRatio(16f / 9f) }
    val video = SurfaceView(context)
    val text = SubtitleView(context)
    val stageText = SubtitleView(context)
    private var subtitles: MediaSubtitleLayer? = null
    private var cues: List<Cue> = emptyList()
    private var appearance = TextSubtitleAppearance()
    private var fontUpdates: Job? = null
    fun textAppearance(value: TextSubtitleAppearance) {
        appearance = value
        if (value.fontKey != "subtitle-default" && value.fontKey != "system-default") SubtitleFontCatalog.request()
        val style = CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT,
            Color.argb((value.backgroundOpacity * 255).roundToInt(), 0, 0, 0),
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, SubtitleFontCatalog.state.value.resolve(value.fontKey).typeface)
        for (view in listOf(text, stageText)) {
            view.setApplyEmbeddedFontSizes(false)
            view.setStyle(style)
        }
        updateTextSize()
        showCues(cues)
    }
    fun showCues(value: List<Cue>) {
        cues = value
        val words = value.filter { it.bitmap == null }.map { cue ->
            // Override only font family/background; preserve TTML/SAMI colours,
            // bold/italic and other spans. Bitmaps never enter this path.
            val original = checkNotNull(cue.text) // A non-bitmap Cue contains text.
            val words = if (original !is Spanned) original else SpannableString(original).apply {
                getSpans(0, length, BackgroundColorSpan::class.java).forEach(::removeSpan)
                getSpans(0, length, TypefaceSpan::class.java).forEach(::removeSpan)
            }
            cue.buildUpon().setText(words).clearWindowColor()
                .setLine(1f - appearance.bottomPadding, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        }
        val bitmaps = value.filter { it.bitmap != null }
        text.setCues(if (appearance.area == TextSubtitleArea.PICTURE) bitmaps + words else bitmaps)
        stageText.setCues(if (appearance.area == TextSubtitleArea.STAGE) words else emptyList())
    }
    init {
        addView(content, LayoutParams(-1, -1, Gravity.CENTER))
        content.addView(video, LayoutParams(-1, -1)); content.addView(text, LayoutParams(-1, -1))
        addView(stageText, LayoutParams(-1, -1))
        textAppearance(appearance)
    }
    private fun updateTextSize() {
        // The same glyph size is used inside the picture and over its black bars.
        val pixels = content.height * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * appearance.scale
        if (pixels > 0f) for (view in listOf(text, stageText)) view.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val picture = Rect(content.left, content.top, content.right, content.bottom)
        if (picture != lastPictureBounds) {
            lastPictureBounds = picture
            onPictureBoundsChanged?.invoke(Rect(picture))
        }
        updateStageSafeArea(rootWindowInsets)
        updateTextSize()
    }
    private fun updateStageSafeArea(insets: WindowInsets?) {
        val cutout = insets?.displayCutout
        val origin = IntArray(2).also(rootView::getLocationOnScreen)
        val position = IntArray(2).also(::getLocationOnScreen)
        val left = max(0, origin[0] + (cutout?.safeInsetLeft ?: 0) - position[0])
        val top = max(0, origin[1] + (cutout?.safeInsetTop ?: 0) - position[1])
        val right = max(0, position[0] + width - (origin[0] + rootView.width - (cutout?.safeInsetRight ?: 0)))
        val bottom = max(0, position[1] + height - (origin[1] + rootView.height - (cutout?.safeInsetBottom ?: 0)))
        if (stageText.paddingLeft != left || stageText.paddingTop != top || stageText.paddingRight != right || stageText.paddingBottom != bottom)
            stageText.setPadding(left, top, right, bottom)
    }
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateStageSafeArea(insets)
        return super.onApplyWindowInsets(insets)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fontUpdates = CoroutineScope(Dispatchers.Main.immediate).launch {
            SubtitleFontCatalog.state.collect { textAppearance(appearance) }
        }
    }
    override fun onDetachedFromWindow() {
        fontUpdates?.cancel(); fontUpdates = null
        super.onDetachedFromWindow()
    }
    fun subtitles(layer: MediaSubtitleLayer?) {
        subtitles?.let { content.removeView(it) }
        subtitles = layer
        layer?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            content.addView(it, LayoutParams(-1, -1))
            it.showText = ::showCues
        }
        showCues(emptyList())
    }
    fun resizeMode(mode: Int) {
        require(mode in listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM))
        content.resizeMode = mode
    }
    fun videoSize(width: Int, height: Int, pixelRatio: Float) {
        if (width > 0 && height > 0) content.setAspectRatio(width * pixelRatio / height)
        subtitles?.let { it.storageWidth = width; it.storageHeight = height }
    }
}
