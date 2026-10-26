package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.PlaybackPreferences
import io.github.kkwans.nasfilebrowser.data.SubtitleFontCatalog
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.TextSubtitleArea
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native View pixels and isolated preferences, not compositor/HDR/media output acceptance. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerSubtitleStyleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun viewport(): PlayerViewport = PlayerViewport(instrumentation.targetContext).apply {
        videoSize(640, 360, 1f)
        measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        layout(0, 0, 600, 900)
    }

    private fun pixels(viewport: PlayerViewport): IntArray {
        val bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            fun draw(view: View, x: Int, y: Int) {
                val left = x + view.left; val top = y + view.top
                if (view is SubtitleView) {
                    canvas.save(); canvas.translate(left.toFloat(), top.toFloat())
                    view.draw(canvas); canvas.restore()
                } else if (view is ViewGroup) for (index in 0 until view.childCount) draw(view.getChildAt(index), left, top)
            }
            draw(viewport, 0, 0)
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally { bitmap.recycle() }
    }

    private fun bounds(frame: IntArray, predicate: (Int) -> Boolean = { Color.alpha(it) > 20 }): Rect {
        val points = frame.indices.filter { predicate(frame[it]) }
        assertTrue("Expected actual subtitle pixels", points.isNotEmpty())
        return Rect(points.minOf { it % 600 }, points.minOf { it / 600 }, points.maxOf { it % 600 } + 1, points.maxOf { it / 600 } + 1)
    }

    @Test fun movingTextIntoBlackBarsRetainsGlyphSizeVideoViewAndPictureGeometry() {
        instrumentation.runOnMainSync {
            val viewport = viewport()
            val video = viewport.video
            val text = viewport.text
            val picture = Rect(video.left, (video.parent as View).top, video.right, (video.parent as View).bottom)
            val cue = Cue.Builder().setText("字幕 Native").build()
            viewport.textAppearance(TextSubtitleAppearance(bottomPadding = .04f))
            viewport.showCues(listOf(cue))
            val inside = bounds(pixels(viewport))
            assertTrue(picture.contains(inside))
            viewport.textAppearance(TextSubtitleAppearance(bottomPadding = .04f, area = TextSubtitleArea.STAGE))
            val outside = bounds(pixels(viewport))
            assertTrue("The stage's lower black bar must contain the text", outside.top >= picture.bottom)
            assertTrue(outside.bottom <= viewport.height)
            assertEquals(inside.width(), outside.width())
            assertEquals(inside.height(), outside.height())
            assertSame(video, viewport.video); assertSame(text, viewport.text)
            assertEquals(picture, Rect(video.left, (video.parent as View).top, video.right, (video.parent as View).bottom))
            viewport.textAppearance(TextSubtitleAppearance(bottomPadding = .04f))
            assertEquals(inside, bounds(pixels(viewport)))
        }
    }

    @Test fun transparentAndTranslucentBackgroundsPreserveSourceColoursBoldItalicAndOriginalCue() {
        instrumentation.runOnMainSync {
            val viewport = viewport()
            val words = SpannableString("Styled 字幕").apply {
                setSpan(StyleSpan(Typeface.BOLD_ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(BackgroundColorSpan(Color.RED), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(TypefaceSpan("serif"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val cue = Cue.Builder().setText(words).setWindowColor(Color.RED).build()
            viewport.showCues(listOf(cue))
            val transparent = pixels(viewport)
            assertTrue(transparent.any { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) < 50 })
            assertFalse("Embedded opaque red backgrounds must not defeat no-background mode",
                transparent.any { Color.alpha(it) > 100 && Color.red(it) > 200 && Color.green(it) < 50 && Color.blue(it) < 50 })
            viewport.textAppearance(TextSubtitleAppearance(backgroundOpacity = .5f))
            val half = pixels(viewport)
            assertTrue(transparent.indices.count { Color.alpha(transparent[it]) == 0 && Color.alpha(half[it]) in 126..129 } > 30)
            viewport.textAppearance(TextSubtitleAppearance(backgroundOpacity = .25f))
            val quarter = pixels(viewport)
            assertTrue(transparent.indices.count { Color.alpha(transparent[it]) == 0 && Color.alpha(quarter[it]) in 62..65 } > 30)
            assertEquals(1, words.getSpans(0, words.length, BackgroundColorSpan::class.java).size)
            assertEquals(Typeface.BOLD_ITALIC, words.getSpans(0, words.length, StyleSpan::class.java).single().style)
            val plain = SpannableString(words.toString()).apply {
                setSpan(ForegroundColorSpan(Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            viewport.textAppearance(TextSubtitleAppearance())
            viewport.showCues(listOf(Cue.Builder().setText(plain).build()))
            assertFalse("Bold/italic must affect real native glyph pixels", transparent.contentEquals(pixels(viewport)))
        }
    }

    @Test fun bitmapCuesKeepTheirAuthoredVideoCoordinatesWhenTextStyleAndAreaChange() {
        instrumentation.runOnMainSync {
            val viewport = viewport()
            val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try {
                val cue = Cue.Builder().setBitmap(bitmap).setPosition(.25f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(.3f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).setSize(.25f).build()
                viewport.showCues(listOf(cue))
                val original = pixels(viewport)
                viewport.textAppearance(TextSubtitleAppearance(scale = 2f, bottomPadding = 0f,
                    area = TextSubtitleArea.STAGE, backgroundOpacity = .75f, fontKey = "system-default"))
                assertArrayEquals("Text settings must not change bitmap geometry, alpha or pixels", original, pixels(viewport))
                assertEquals(.3f, cue.line, 0f)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun legacyPreferencesRoundTripNewStyleAndMissingFontsRecoverWithoutChangingUserStores(): Unit = runBlocking {
        val target = instrumentation.targetContext
        val name = "owned-subtitle-style-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(unused: String, mode: Int) = target.getSharedPreferences(name, mode)
        }
        val prefs = isolated.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().putFloat("subtitle_scale", 1.25f).putFloat("subtitle_bottom", .2f).commit())
            val legacy = PlaybackPreferences(isolated).textSubtitleAppearance.value
            assertEquals(TextSubtitleAppearance(1.25f, .2f), legacy)
            val saved = legacy.copy(area = TextSubtitleArea.STAGE, backgroundOpacity = .5f, fontKey = "owned-font-no-longer-installed")
            PlaybackPreferences(isolated).saveTextSubtitleAppearance(saved)
            assertEquals(saved, PlaybackPreferences(isolated).textSubtitleAppearance.value)
            SubtitleFontCatalog.request()
            val fonts = withTimeout(5_000) { SubtitleFontCatalog.state.first { !it.loading } }
            assertTrue(fonts.missing(saved.fontKey))
            assertEquals("subtitle-default", fonts.resolve(saved.fontKey).key)
            assertFalse("System font discovery must succeed on the acceptance target", fonts.failed)
            val real = fonts.options.firstOrNull { it.key.startsWith("system:") } ?: error("No actual system font enumerated")
            assertNotNull(real.typeface)
            val actual = fonts.options.drop(2)
            assertTrue("Font labels must contain real family/style names, not file/index/weight internals", actual.all {
                !Regex("\\.(ttf|otf|ttc)|#\\d| · [4-7]00", RegexOption.IGNORE_CASE).containsMatchIn(it.label) &&
                    it.label.substringAfterLast(" · ") in setOf("常规", "中等", "半粗", "粗体", "常规斜体", "中等斜体", "半粗斜体", "粗体斜体")
            })
            assertTrue("A family should expose at most regular/bold upright/italic choices",
                actual.groupBy { it.label.substringBeforeLast(" · ") }.values.all { it.size <= 4 })
            val system = Paint().apply { typeface = Typeface.DEFAULT }
            val selected = Paint().apply { typeface = real.typeface }
            for (glyph in listOf("中", "A")) if (system.hasGlyph(glyph))
                assertTrue("A discovered face must retain system missing-glyph fallback: $glyph", selected.hasGlyph(glyph))
            instrumentation.runOnMainSync {
                val viewport = viewport()
                viewport.showCues(listOf(Cue.Builder().setText("MiW 1234 字幕").build()))
                val baseline = pixels(viewport)
                viewport.textAppearance(TextSubtitleAppearance(fontKey = saved.fontKey))
                assertArrayEquals("A missing saved font must render using the default", baseline, pixels(viewport))
                val sample = "MiW 1234"
                val reference = Paint().apply { textSize = 18f; typeface = fonts.resolve("subtitle-default").typeface }.measureText(sample)
                val different = fonts.options.drop(2).firstOrNull { option ->
                    kotlin.math.abs(Paint().apply { textSize = 18f; typeface = option.typeface }.measureText(sample) - reference) > 2f
                } ?: error("No measurably distinct actual system font")
                viewport.textAppearance(TextSubtitleAppearance(fontKey = different.key))
                assertFalse("Selecting an actual font must reach the native subtitle painter", baseline.contentEquals(pixels(viewport)))
            }
            assertEquals(saved.fontKey, PlaybackPreferences(isolated).textSubtitleAppearance.value.fontKey)
        } finally { target.deleteSharedPreferences(name) }
    }
    @Test fun defaultFontDoesNotSaveOtherTemporaryStyleEditsAndSurvivesRecreation(): Unit = runBlocking {
        val target = instrumentation.targetContext
        val name = "owned-subtitle-default-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(unused: String, mode: Int) = target.getSharedPreferences(name, mode)
        }
        try {
            val store = PlaybackPreferences(isolated)
            val defaults = TextSubtitleAppearance(scale = 1.25f, bottomPadding = .15f,
                area = TextSubtitleArea.STAGE, backgroundOpacity = .3f)
            store.saveTextSubtitleAppearance(defaults)
            val temporary = defaults.copy(scale = 2f, bottomPadding = .35f, fontKey = "system-default")
            store.saveDefaultSubtitleFont(temporary.fontKey)
            assertEquals(defaults.copy(fontKey = "system-default"), store.textSubtitleAppearance.value)
            assertEquals(defaults.copy(fontKey = "system-default"), PlaybackPreferences(isolated).textSubtitleAppearance.value)
            assertEquals(2f, temporary.scale, 0f)
        } finally { target.deleteSharedPreferences(name) }
    }

}
