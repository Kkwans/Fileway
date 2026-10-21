package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.svg.SvgDecoder
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions
import com.mikepenz.markdown.model.MarkdownExtendedSpans
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import io.github.kkwans.nasfilebrowser.data.MarkdownMathBudget
import io.github.kkwans.nasfilebrowser.data.MarkdownMathFormula
import io.github.kkwans.nasfilebrowser.data.MarkdownMathResult
import io.github.kkwans.nasfilebrowser.data.markdownMathRequiredHeight
import kotlin.math.ceil
import kotlin.math.roundToInt

// Fixed Compose public appendInlineContent uses this annotation tag. Keep its
// ordering, including image placeholders, when reading TextLayoutResult.rects.
private const val INLINE_TAG = "androidx.compose.foundation.text.inlineContent"

internal data class MarkdownMathInlineBinding(
    val inlineContent: Map<String, InlineTextContent>,
    val extendedSpans: MarkdownExtendedSpans,
)

/** A single public renderer binding covers headings, tight lists, quotes and
 * footnotes without replacing their upstream components. Relative em sizes
 * follow each Text's actual style. Current Reader has no other span painters;
 * additional painters can be supplied without discarding them. */
@Composable
internal fun rememberMarkdownMathBinding(
    results: Map<String, MarkdownMathResult>,
    baseEmPx: Float,
    otherInlineKeys: Set<String> = emptySet(),
    additionalPainters: List<ExtendedSpanPainter> = emptyList(),
): MarkdownMathInlineBinding {
    require(baseEmPx.isFinite() && baseEmPx > 0f)
    val density = LocalDensity.current
    val rendered = remember(results) { results.mapNotNull { (key, value) ->
        (value as? MarkdownMathResult.Rendered)?.let { key to it }
    }.toMap() }
    val heights = remember(rendered, baseEmPx) { mutableStateMapOf<String, Float>() }
    val baselines = remember(rendered, baseEmPx) { mutableStateMapOf<String, Float>() }
    val inline = rendered.mapValues { (key, result) ->
        val heightEm = heights[key] ?: result.heightPx / baseEmPx
        InlineTextContent(Placeholder((result.widthPx / baseEmPx).em, heightEm.em, PlaceholderVerticalAlign.TextCenter)) { source ->
            BoxWithConstraints {
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val scale = widthPx / result.widthPx
                val scaled = result.copy(widthPx = widthPx, heightPx = result.heightPx * scale,
                    ascentPx = result.ascentPx * scale, depthPx = result.depthPx * scale)
                val baseline = baselines[key]
                if (baseline != null) {
                    val top = baseline - scaled.ascentPx
                    if (top >= -0.5f && top + scaled.heightPx <= heightPx + 0.5f)
                        MarkdownMathImage(scaled, source, Modifier.offset { IntOffset(0, top.roundToInt()) })
                }
            }
        }
    }
    val painter = remember(rendered, baseEmPx, otherInlineKeys) {
        object : ExtendedSpanPainter() {
            override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = span
            override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString,
                builder: AnnotatedString.Builder) = linkAnnotation
            override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
                val annotations = layoutResult.layoutInput.text.getStringAnnotations(INLINE_TAG, 0, layoutResult.layoutInput.text.length)
                    .filter { it.item in rendered || it.item in otherInlineKeys || it.item == MARKDOWN_TAG_IMAGE_URL }
                annotations.forEachIndexed { index, annotation ->
                    val result = rendered[annotation.item]
                    val rect = layoutResult.placeholderRects.getOrNull(index)
                    if (result != null && rect != null && rect.width > 0f && rect.height > 0f) {
                        val scale = rect.width / result.widthPx
                        val baseline = layoutResult.getLineBaseline(layoutResult.getLineForOffset(annotation.start)) - rect.top
                        val required = markdownMathRequiredHeight(rect.height, baseline, result.ascentPx * scale, result.depthPx * scale)
                        if (required > rect.height + 0.5f) {
                            val oldEm = heights[annotation.item] ?: result.heightPx / baseEmPx
                            heights[annotation.item] = oldEm * ceil(required) / rect.height
                        }
                        if (baselines[annotation.item]?.let { kotlin.math.abs(it - baseline) > 0.1f } != false)
                            baselines[annotation.item] = baseline
                    }
                }
                return SpanDrawInstructions { }
            }
        }
    }
    val spans = markdownExtendedSpans {
        remember(painter, additionalPainters) { ExtendedSpans(*(additionalPainters + painter).toTypedArray()) }
    }
    return MarkdownMathInlineBinding(inline, spans)
}

internal fun AnnotatedString.Builder.appendMarkdownMath(formula: MarkdownMathFormula, result: MarkdownMathResult?) {
    when (result) {
        is MarkdownMathResult.Rendered -> appendInlineContent(formula.key, formula.source)
        is MarkdownMathResult.Failed -> { append(formula.source); append("（${result.reason}）") }
        null -> append(formula.source)
    }
}

/** Native text and links stay in the upstream MarkdownText. The upstream also
 * adds its own image inline content using the existing transformer/lease. */
@Composable
internal fun MarkdownMathText(
    content: AnnotatedString,
    results: Map<String, MarkdownMathResult>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val density = LocalDensity.current
    val inherited = LocalMarkdownInlineContent.current.inlineContent
    val annotations = remember(content) { content.getStringAnnotations(INLINE_TAG, 0, content.length) }
    val rendered = remember(content, results) {
        annotations.mapNotNull { annotation ->
            (results[annotation.item] as? MarkdownMathResult.Rendered)?.let { annotation.item to it }
        }.toMap()
    }
    if (rendered.isEmpty()) {
        MarkdownText(content, modifier, style)
        return
    }
    BoxWithConstraints(modifier) {
        val availablePx = with(density) { maxWidth.toPx() }
        // A very wide inline formula scales as one unit. Display blocks use the
        // separate horizontally scrollable component and retain their size.
        val scaled = remember(rendered, availablePx) { rendered.mapValues { (_, result) ->
            val scale = if (availablePx.isFinite() && availablePx > 0f) minOf(1f, availablePx / result.widthPx) else 1f
            result.copy(widthPx = result.widthPx * scale, heightPx = result.heightPx * scale,
                ascentPx = result.ascentPx * scale, depthPx = result.depthPx * scale)
        } }
        val heights = remember(content, scaled, style, density.density, density.fontScale) { mutableStateMapOf<String, Float>() }
        val baselines = remember(content, scaled, style, density.density, density.fontScale) { mutableStateMapOf<String, Float>() }
        val inline = scaled.mapValues { (key, result) ->
            val height = heights[key] ?: ceil(result.heightPx)
            InlineTextContent(Placeholder(
                width = with(density) { result.widthPx.toSp() },
                height = with(density) { height.toSp() },
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )) { alternateText ->
                val baseline = baselines[key]
                if (baseline != null) {
                    val top = baseline - result.ascentPx
                    if (top >= -0.5f && top + result.heightPx <= height + 0.5f) {
                        Box(Modifier.fillMaxSize()) {
                            MarkdownMathImage(result, alternateText, Modifier.offset { IntOffset(0, top.roundToInt()) })
                        }
                    }
                }
            }
        }
        val activeAnnotations = annotations.filter { it.item in inline || it.item in inherited || it.item == MARKDOWN_TAG_IMAGE_URL }
        val onLayout: (TextLayoutResult, Color?) -> Unit = { layout, _ ->
            activeAnnotations.forEachIndexed { index, annotation ->
                val result = scaled[annotation.item]
                val rect = layout.placeholderRects.getOrNull(index)
                if (result != null && rect != null) {
                    val baseline = layout.getLineBaseline(layout.getLineForOffset(annotation.start)) - rect.top
                    val height = heights[annotation.item] ?: ceil(result.heightPx)
                    val required = ceil(markdownMathRequiredHeight(height, baseline, result.ascentPx, result.depthPx))
                    if (required > height + 0.5f) heights[annotation.item] = required
                    if (baselines[annotation.item]?.let { kotlin.math.abs(it - baseline) > 0.1f } != false)
                        baselines[annotation.item] = baseline
                }
            }
        }
        CompositionLocalProvider(LocalMarkdownInlineContent provides markdownInlineContent(inherited + inline)) {
            MarkdownText(content, style = style, onTextLayout = onLayout)
        }
    }
}

@Composable
private fun MarkdownMathImage(result: MarkdownMathResult.Rendered, source: String, modifier: Modifier = Modifier) {
    if (!MarkdownMathBudget.geometryValid(result.widthPx, result.heightPx, result.ascentPx, result.depthPx)) {
        Text("$source（公式超过显示尺寸限制）", modifier, color = MaterialTheme.colorScheme.error)
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(result.svg, result.widthPx, result.heightPx, context) {
        ImageRequest.Builder(context)
            .data(result.svg.toByteArray(Charsets.UTF_8))
            .diskCachePolicy(CachePolicy.DISABLED)
            .decoderFactory(SvgDecoder.Factory(useViewBoundsAsIntrinsicSize = false, renderToBitmap = false))
            .size(ceil(result.widthPx).toInt().coerceAtLeast(1), ceil(result.heightPx).toInt().coerceAtLeast(1))
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    if (state is coil3.compose.AsyncImagePainter.State.Error) {
        Text("$source（公式图形不可用）", modifier, color = MaterialTheme.colorScheme.error)
    } else Image(painter, null,
            modifier.requiredSize(with(density) { result.widthPx.toDp() }, with(density) { result.heightPx.toDp() }),
            contentScale = ContentScale.FillBounds)
}

/** Display math is its own image, not a placeholder spanning source newlines.
 * Exact TeX remains selectable/copyable in the on-demand source panel and in
 * the document source. Inline paragraph selection keeps its original binding.
 */
@Composable
internal fun MarkdownMathBlock(formula: MarkdownMathFormula, result: MarkdownMathResult?, modifier: Modifier = Modifier) {
    when (result) {
        is MarkdownMathResult.Rendered -> {
            var sourceOpen by remember(formula.key, formula.source) { mutableStateOf(false) }
            var copyFailed by remember(formula.source) { mutableStateOf(false) }
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            DisableSelection {
                Box(modifier.horizontalScroll(rememberScrollState()).heightIn(min = 48.dp).combinedClickable(
                    role = Role.Button, onClickLabel = "查看公式源码", onClick = { sourceOpen = true },
                    onLongClickLabel = "查看公式源码", onLongClick = { sourceOpen = true },
                ).semantics { contentDescription = formula.source }, contentAlignment = Alignment.CenterStart) {
                    MarkdownMathImage(result, formula.source)
                }
            }
            if (sourceOpen) FileOperationSheet("公式源码", { sourceOpen = false }, footer = {
                FilewayTextButton({ sourceOpen = false }) { Text("关闭") }
                FilewayButton({
                    try {
                        clipboard.setText(AnnotatedString(formula.source))
                        copyFailed = false
                        android.widget.Toast.makeText(context, "已复制公式源码", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { copyFailed = true }
                }) { Text("复制源码") }
            }) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(formula.source, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    if (copyFailed) Text("无法复制，请长按选择源码。", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is MarkdownMathResult.Failed -> Text("${formula.source}（${result.reason}）", modifier, color = MaterialTheme.colorScheme.error)
        null -> Text(formula.source, modifier)
    }
}
