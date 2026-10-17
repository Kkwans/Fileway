package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.*
import org.junit.Test

class SeekDragSessionTest {
    @Test fun longMovieIsControllableAndReversingDoesNotAccumulateClockDrift() {
        val drag = SeekDragSession(7, 60_000, 9_813_824)
        val coarse = drag.preview(.25f) - drag.startMs
        val fine = drag.preview(.1f) - drag.startMs
        assertTrue("Small movements remain fine-grained", fine in 1..30_000)
        assertTrue("Longer movements accelerate", coarse > fine * 2.5)
        assertEquals(drag.startMs - fine, drag.preview(-.1f))
        assertEquals(60_000L, drag.preview(0f))
        assertNull(drag.finish(7, released = true, seekable = true))
    }

    @Test fun shortClipsBoundariesAndVeryLargeDurationsKeepTheirRealBounds() {
        val short = SeekDragSession(1, 6_000, 12_000)
        assertTrue(short.preview(.1f) in 6_001..6_500)
        assertEquals(12_000L, short.preview(1f))
        assertEquals(0L, short.preview(-1f))
        val huge = SeekDragSession(2, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, huge.preview(1f))
        assertTrue(huge.preview(-1f) in 0 until Long.MAX_VALUE - 1)
    }

    @Test fun cancelChangedMediaOrUnavailableSeekNeverCommits() {
        for (case in listOf(Triple(7L, false, true), Triple(8L, true, true), Triple(7L, true, false))) {
            val drag = SeekDragSession(7, 60_000, 600_000)
            drag.preview(.1f)
            assertNull(drag.finish(case.first, case.second, case.third))
            assertNull("A cancelled drag cannot be resurrected", drag.finish(7, true, true))
        }
    }

    @Test fun onlyTheFinalPreviewCommitsOnceAndInvalidPointerValuesAreIgnored() {
        val drag = SeekDragSession(7, 60_000, 600_000)
        val first = drag.preview(.25f)
        assertEquals(first, drag.preview(Float.NaN))
        assertEquals(first, drag.preview(Float.POSITIVE_INFINITY))
        val last = drag.preview(.1f)
        assertTrue(last < first)
        assertEquals(last, drag.finish(7, true, true))
        assertNull(drag.finish(7, true, true))
        assertEquals(last, drag.preview(-1f))
    }
}
