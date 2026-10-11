package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.svg.SvgDecoder
import coil3.toBitmap
import io.github.kkwans.nasfilebrowser.data.MarkdownMathFormula
import io.github.kkwans.nasfilebrowser.data.MarkdownMathMetrics
import io.github.kkwans.nasfilebrowser.data.MarkdownMathResult
import io.github.kkwans.nasfilebrowser.ui.MarkdownMathSession
import io.github.kkwans.nasfilebrowser.ui.appendMarkdownMath
import io.github.kkwans.nasfilebrowser.ui.rememberMarkdownMathBinding
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned TeX only, no account/URI/file fixture mutation. Engine cases need no
 * Activity; the last case uses a native host for real Compose layout. Exercises
 * the packaged browser engine and native SVG decoder, not a mock lexer. */
@RunWith(AndroidJUnit4::class)
class MarkdownMathEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val light = MarkdownMathMetrics(32f, 16f, 720f, "#202124")
    private fun formula(index: Int, tex: String, display: Boolean = false) =
        MarkdownMathFormula("math/owned/$index", tex, if (display) "\$\$$tex\$\$" else "\$$tex\$", display, index)
    private suspend fun render(vararg formulas: MarkdownMathFormula, metrics: MarkdownMathMetrics = light) =
        MarkdownMathSession(context).render(formulas.toList(), metrics)
    private fun rendered(result: MarkdownMathResult?): MarkdownMathResult.Rendered {
        assertTrue("Fixed offline TeX must produce a real SVG, actual=$result", result is MarkdownMathResult.Rendered)
        return result as MarkdownMathResult.Rendered
    }

    @Test fun offlineCommonAmsChemistryAndUnicodeProduceSelfContainedNativeSvgInk() = runBlocking {
        val formulas = listOf(
            formula(0, "\\frac{a}{g_j}+\\sqrt{x^2} + \\int_0^1 \\alpha\\,dx"),
            formula(1, "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}", true),
            formula(2, "f(x)=\\begin{cases}x^2&x>0\\\\0&x\\le0\\end{cases}", true),
            formula(3, "\\begin{aligned}a&=b+c\\\\d&=e\\end{aligned}", true),
            formula(4, "\\ce{2H2 + O2 -> 2H2O}", true),
            formula(5, "\\text{中文}+\\color{red}{x}"),
        )
        val results = MarkdownMathSession(context).render(formulas, light)
        val loader = ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
        try {
            formulas.forEach { formula ->
                val result = rendered(results[formula.key])
                assertTrue(result.svg.contains("<svg"))
                assertFalse(result.svg.contains("https://"))
                assertFalse(result.svg.contains("<foreignObject"))
                val decoded = loader.execute(ImageRequest.Builder(context).data(result.svg.toByteArray())
                    .size(kotlin.math.ceil(result.widthPx).toInt(), kotlin.math.ceil(result.heightPx).toInt()).build())
                assertTrue("Native SVG parser must accept the real output", decoded is SuccessResult)
                val bitmap = (decoded as SuccessResult).image.toBitmap()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Decoded formula must contain actual ink", pixels.count { android.graphics.Color.alpha(it) > 32 } > 20)
            }
            val fraction = rendered(results[formulas.first().key])
            assertTrue("Fraction/subscript needs real depth below baseline", fraction.depthPx > 0f)
        } finally { loader.shutdown() }
    }

    @Test fun macrosFollowSourceOrderButCannotLeakIntoAnotherDocumentAndRecursionFailsVisibly() = runBlocking {
        val definition = formula(0, "\\gdef\\owned{x^2}\\owned")
        val use = formula(1, "\\owned+1")
        val ordered = render(use, definition)
        rendered(ordered[definition.key]); rendered(ordered[use.key])
        assertTrue(render(use)[use.key] is MarkdownMathResult.Failed)
        val recursive = formula(2, "\\def\\ownedloop{\\ownedloop}\\ownedloop")
        val valid = formula(3, "x+1")
        val guarded = render(recursive, valid)
        assertTrue(guarded[recursive.key] is MarkdownMathResult.Failed)
        rendered(guarded[valid.key])
        Unit
    }

    @Test fun themeAndFontScaleChangeRealSvgGeometryAndDefaultInkWithoutChangingSource() = runBlocking {
        val formula = formula(0, "\\frac{x}{g_j}")
        val lightSvg = rendered(render(formula)[formula.key])
        val darkSvg = rendered(render(formula, metrics = light.copy(emPx = 48f, exPx = 24f, foregroundRgb = "#e8e8e8"))[formula.key])
        assertTrue(lightSvg.svg.contains("#202124"))
        assertTrue(darkSvg.svg.contains("#e8e8e8"))
        assertEquals(lightSvg.widthPx * 1.5f, darkSvg.widthPx, 1f)
        assertEquals(lightSvg.heightPx * 1.5f, darkSvg.heightPx, 1f)
        assertEquals("\$\\frac{x}{g_j}\$", formula.source)
    }

    @Test fun cancellationClosesOnlyItsOwnSessionAndFreshDocumentStillRenders() = runBlocking {
        val session = MarkdownMathSession(context)
        val job = launch(Dispatchers.Main) { session.render((0..127).map { formula(it, "\\frac{x^2}{g_j}") }, light) }
        // The main-thread fence runs after creation/first suspend, without sleeps
        // or a second rendering attempt in the cancelled document.
        withContext(Dispatchers.Main) { job.cancel() }
        job.join()
        assertTrue(job.isCancelled)
        val fresh = formula(0, "x+1")
        rendered(render(fresh)[fresh.key])
        Unit
    }

    @Test fun nativeHeadingAndParagraphUseTheirActualBaselinesAndRetainSelectableTexAndLinks() = runBlocking {
        val tex = "\\frac{x}{g_j}"
        val source = "# Heading \$$tex\$\n\nBefore \$$tex\$ after `ownedCode` and [ownedLink](https://example.invalid)"
        val starts = listOf(source.indexOf('$'), source.indexOf('$', source.indexOf('$', source.indexOf('$') + 1) + 1))
        val formulas = starts.map { offset -> MarkdownMathFormula("math/owned/$offset", tex, "\$$tex\$", false, offset) }
        val results = MarkdownMathSession(context).render(formulas, light)
        formulas.forEach { rendered(results[it.key]) }
        data class Sample(val layout: TextLayoutResult, val index: Int, val offset: Int)
        val samples = ConcurrentHashMap<String, Sample>()
        val observer = object : ExtendedSpanPainter() {
            override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = span
            override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = linkAnnotation
            override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
                layoutResult.layoutInput.text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0,
                    layoutResult.layoutInput.text.length).forEachIndexed { index, annotation ->
                    if (annotation.item in results) samples[annotation.item] = Sample(layoutResult, index, annotation.start)
                }
                return SpanDrawInstructions { }
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val binding = rememberMarkdownMathBinding(results, light.emPx, additionalPainters = listOf(observer))
                    Surface(Modifier.fillMaxSize()) { SelectionContainer {
                        Markdown(content = source, typography = markdownTypography(
                            text = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            paragraph = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            h1 = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp)),
                            annotator = markdownAnnotator { _, node ->
                                if (node.type == GFMElementTypes.INLINE_MATH) {
                                    val formula = formulas.single { it.sourceOrder == node.startOffset }
                                    appendMarkdownMath(formula, results[formula.key]); true
                                } else false
                            }, inlineContent = markdownInlineContent(binding.inlineContent), extendedSpans = binding.extendedSpans)
                    } }
                }
            } }
            withTimeout(5000) {
                while (formulas.any { formula ->
                    val sample = samples[formula.key]
                    val rect = sample?.layout?.placeholderRects?.getOrNull(sample.index)
                    if (sample == null || rect == null) true else {
                        val result = rendered(results[formula.key])
                        val scale = rect.width / result.widthPx
                        val baseline = sample.layout.getLineBaseline(sample.layout.getLineForOffset(sample.offset)) - rect.top
                        baseline + 0.5f < result.ascentPx * scale || rect.height - baseline + 0.5f < result.depthPx * scale
                    }
                }) delay(25)
            }
            val heading = requireNotNull(samples[formulas[0].key])
            val paragraph = requireNotNull(samples[formulas[1].key])
            val headingRect = requireNotNull(heading.layout.placeholderRects[heading.index])
            val paragraphRect = requireNotNull(paragraph.layout.placeholderRects[paragraph.index])
            assertEquals("Heading formula must follow the actual 28sp versus 16sp style", 28f / 16f,
                headingRect.width / paragraphRect.width, 0.05f)
            assertTrue(heading.layout.layoutInput.text.text.contains(formulas[0].source))
            assertTrue(paragraph.layout.layoutInput.text.text.contains(formulas[1].source))
            assertTrue(paragraph.layout.layoutInput.text.text.contains("ownedCode"))
            assertTrue("Math painter must retain upstream link annotations", paragraph.layout.layoutInput.text
                .getLinkAnnotations(0, paragraph.layout.layoutInput.text.length).isNotEmpty())
        } finally { scenario.close() }
    }
}
