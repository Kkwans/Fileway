package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class MarkdownMathTest {
    private fun formula(key: String, order: Int, tex: String = "x") = MarkdownMathFormula(key, tex, "\$$tex\$", false, order)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid input must fail before browser work") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun scopedKeysKeepSourceOrderAndExactUnicodeAlternateText() {
        val body = formula("math/main/100", 100, "\\text{中文🐱 é}")
        val note = formula("math/note-20/0", 20)
        val sorted = MarkdownMathBudget.ordered(listOf(body, note))
        assertEquals(listOf(note, body), sorted)
        assertEquals("\$\\text{中文🐱 é}\$", sorted[1].source)
        rejected { MarkdownMathBudget.ordered(listOf(body, body.copy(sourceOrder = 200))) }
    }

    @Test fun singleAndDocumentBudgetsRejectWithoutTruncatingValidTex() {
        val limit = "x".repeat(MarkdownMathBudget.TEX_CHARS)
        assertEquals(limit, MarkdownMathBudget.ordered(listOf(formula("a", 0, limit))).single().tex)
        rejected { MarkdownMathBudget.ordered(listOf(formula("a", 0, limit + "x"))) }
        rejected { MarkdownMathBudget.ordered((0..MarkdownMathBudget.FORMULAS).map { formula("$it", it) }) }
        rejected { MarkdownMathBudget.ordered((0..16).map { formula("$it", it, limit) }) }
        rejected { MarkdownMathBudget.ordered(listOf(formula("a", -1))) }
    }

    @Test fun nonfiniteHugeOrInconsistentGeometryCannotBecomeAPlaceholderOrBitmap() {
        assertTrue(MarkdownMathBudget.geometryValid(120f, 48f, 32f, 16f))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 8193f))
            assertFalse(MarkdownMathBudget.geometryValid(bad, 48f, 32f, 16f))
        assertFalse(MarkdownMathBudget.geometryValid(4096f, 4096f, 2048f, 2048f))
        assertFalse(MarkdownMathBudget.geometryValid(120f, 48f, 32f, 2f))
        assertFalse(MarkdownMathBudget.geometryValid(120f, 48f, 64f, -16f))
        assertFalse(MarkdownMathBudget.outputValid(MarkdownMathResult.Rendered("🐱".repeat(MarkdownMathBudget.SVG_BYTES / 2), 20f, 20f, 16f, 4f)))
    }

    @Test fun actualBaselineReservesBothTallFractionsAndDeepSubscriptsWithoutClipping() {
        // Simulates a real TextCenter layout whose baseline is not its bottom.
        val initial = 48f
        val baseline = 30f
        val ascent = 36f
        val depth = 12f
        val required = markdownMathRequiredHeight(initial, baseline, ascent, depth)
        assertEquals(60f, required, 0.01f)
        val afterBaseline = baseline + (required - initial) / 2
        assertTrue(afterBaseline >= ascent)
        assertTrue(required - afterBaseline >= depth)
        assertEquals(76f, markdownMathRequiredHeight(48f, 30f, 16f, 32f), 0.01f)
        assertEquals(48f, markdownMathRequiredHeight(48f, 30f, 24f, 12f), 0.01f)
    }

    @Test fun nativeMetricsRequireFiniteDeviceScaledValuesAndExplicitOpaqueRgb() {
        val valid = MarkdownMathMetrics(48f, 24f, 1000f, "#e8e8e8")
        assertTrue(valid.isValid())
        assertFalse(valid.copy(exPx = Float.NaN).isValid())
        assertFalse(valid.copy(emPx = 0f).isValid())
        assertFalse(valid.copy(containerWidthPx = Float.POSITIVE_INFINITY).isValid())
        assertFalse(valid.copy(foregroundRgb = "currentColor").isValid())
        assertFalse(valid.copy(foregroundRgb = "#00ffffff").isValid())
    }
}
