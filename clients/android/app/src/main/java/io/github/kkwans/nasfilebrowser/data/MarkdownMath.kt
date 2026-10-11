package io.github.kkwans.nasfilebrowser.data

import kotlin.math.max

/** Source is the exact selectable/copyable Markdown, including its delimiters. */
internal data class MarkdownMathFormula(
    val key: String,
    val tex: String,
    val source: String,
    val display: Boolean,
    val sourceOrder: Int,
)

internal data class MarkdownMathMetrics(
    val emPx: Float,
    val exPx: Float,
    val containerWidthPx: Float,
    val foregroundRgb: String,
) {
    fun isValid(): Boolean = emPx.isFinite() && emPx in 1f..256f && exPx.isFinite() &&
        exPx in 0.5f..128f && containerWidthPx.isFinite() && containerWidthPx in 1f..16384f &&
        foregroundRgb.matches(Regex("#[0-9a-fA-F]{6}"))
}

internal sealed interface MarkdownMathResult {
    data class Rendered(
        val svg: String,
        val widthPx: Float,
        val heightPx: Float,
        val ascentPx: Float,
        val depthPx: Float,
    ) : MarkdownMathResult
    data class Failed(val reason: String) : MarkdownMathResult
}

/** These bound the adapter in addition to the engine's expansion guards. They
 * do not assert a hard total heap cap for a browser process. */
internal object MarkdownMathBudget {
    const val FORMULAS = 256
    const val TEX_CHARS = 16 * 1024
    const val DOCUMENT_CHARS = 256 * 1024
    const val SVG_BYTES = 512 * 1024
    const val DOCUMENT_SVG_BYTES = 8 * 1024 * 1024
    const val DIMENSION_PX = 8192f
    const val PIXELS = 4_194_304f
    const val TOO_LARGE = "公式超过安全排版限制"
    const val INVALID = "公式无法排版，请查看原文"
    const val UNAVAILABLE = "数学排版组件不可用，请查看原文"

    fun ordered(formulas: List<MarkdownMathFormula>): List<MarkdownMathFormula> {
        require(formulas.size <= FORMULAS) { TOO_LARGE }
        require(formulas.map { it.key }.toSet().size == formulas.size) { INVALID }
        var total = 0L
        formulas.forEach {
            require(it.key.length in 1..160 && it.sourceOrder >= 0 && it.source.isNotEmpty()) { INVALID }
            require(it.tex.length <= TEX_CHARS && it.source.length <= TEX_CHARS + 8) { TOO_LARGE }
            total += it.tex.length
            require(total <= DOCUMENT_CHARS) { TOO_LARGE }
        }
        return formulas.sortedWith(compareBy<MarkdownMathFormula> { it.sourceOrder }.thenBy { it.key })
    }

    fun geometryValid(width: Float, height: Float, ascent: Float, depth: Float): Boolean =
        listOf(width, height, ascent, depth).all { it.isFinite() } &&
            width in 0.01f..DIMENSION_PX && height in 0.01f..DIMENSION_PX &&
            ascent >= 0f && depth >= 0f && ascent <= height + 0.5f && depth <= height + 0.5f &&
            kotlin.math.abs(ascent + depth - height) <= 0.5f && width * height <= PIXELS

    fun outputValid(result: MarkdownMathResult.Rendered): Boolean =
        result.svg.toByteArray(Charsets.UTF_8).size <= SVG_BYTES &&
            geometryValid(result.widthPx, result.heightPx, result.ascentPx, result.depthPx)
}

/** TextCenter's reference baseline stays fixed when its placeholder grows.
 * Reserve just enough extra height on both sides, then draw at the actual
 * measured baseline. No TeX glyph metrics are guessed from a font size. */
internal fun markdownMathRequiredHeight(height: Float, measuredBaseline: Float, ascent: Float, depth: Float): Float =
    height + 2f * max(0f, max(ascent - measuredBaseline, depth - (height - measuredBaseline)))
