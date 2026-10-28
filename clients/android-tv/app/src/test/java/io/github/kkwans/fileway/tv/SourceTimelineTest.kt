package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.player.SourceTimeline
import org.junit.Assert.*
import org.junit.Test

class SourceTimelineTest {
    @Test fun compatibleWindowUsesOriginalPositionAndDuration() {
        val source = SourceTimeline(7_200_000, 3_600_000, 120_000)
        assertEquals(3_605_000L, source.toSource(5_000))
        assertEquals(5_000L, source.toLocal(3_605_000))
        assertNull(source.toLocal(3_599_999))
        assertNull(source.toLocal(3_720_000))
    }
    @Test fun finalWindowClampsToRealSourceEnd() {
        val source = SourceTimeline(121_500, 120_000, 120_000)
        assertEquals(121_500L, source.toSource(120_000))
        assertEquals(1_500L, source.toLocal(121_500))
        assertNull(source.toLocal(121_501))
    }
    @Test fun nativePresentationAndCompatiblePresentationReferToSameCheckpoint() {
        val native = SourceTimeline(120_000)
        val compatible = SourceTimeline(120_000, 60_000, 60_000)
        assertEquals(native.toSource(65_000), compatible.toSource(5_000))
        assertEquals(65_000L, native.toLocal(compatible.toSource(5_000)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOffsetBeyondOriginalMedia() {
        SourceTimeline(1_000, 1_001)
    }
}
