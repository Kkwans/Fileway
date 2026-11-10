package io.github.kkwans.fileway.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteKeyLatchTest {
    @Test fun lostReleaseDoesNotConsumeTheNextIndependentPress() {
        val latch = TvRemoteKeyLatch()
        assertTrue(latch.down(20, 100L, 0) { true })
        // A dialog took focus before the first key-up arrived.
        assertFalse(latch.down(20, 200L, 0) { false })
        assertFalse(latch.up(20, 200L))
    }

    @Test fun repeatsAndTheMatchingReleaseRemainConsumed() {
        val latch = TvRemoteKeyLatch()
        assertTrue(latch.down(20, 100L, 0) { true })
        assertTrue(latch.down(20, 100L, 1) { false })
        assertTrue(latch.down(20, 100L, 2) { false })
        assertTrue(latch.up(20, 100L))
        assertFalse(latch.down(20, 200L, 0) { false })
    }

    @Test fun lateReleaseCannotCancelANewerPress() {
        val latch = TvRemoteKeyLatch()
        assertTrue(latch.down(20, 100L, 0) { true })
        assertTrue(latch.down(20, 200L, 0) { true })
        assertFalse(latch.up(20, 100L))
        assertTrue(latch.down(20, 200L, 1) { false })
        assertTrue(latch.up(20, 200L))
    }
}
