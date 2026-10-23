package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleDelayInputTest {
    @Test fun acceptsImeSignedDecimalsAndSupportedLimits() {
        mapOf(
            "-0.5" to -500L, "－0。5" to -500L, "−０．５" to -500L,
            " ＋１．２５ " to 1250L, "-600" to -600000L, "600" to 600000L,
        ).forEach { (input, expected) -> assertEquals(input, expected, parseSubtitleDelayMillis(input)!!) }
    }

    @Test fun rejectsInvalidNonFiniteAmbiguousAndOutOfRangeInput() {
        listOf("", "--1", "0。5。1", "NaN", "Infinity", "1e999", "600.001", "-600.001", "1,5")
            .forEach { assertNull(it, parseSubtitleDelayMillis(it)) }
    }
}
