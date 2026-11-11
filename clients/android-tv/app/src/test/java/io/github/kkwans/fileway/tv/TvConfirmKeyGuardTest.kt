package io.github.kkwans.fileway.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvConfirmKeyGuardTest {
    @Test fun suppressedPressConsumesRepeatAndReleaseOnlyOnce() {
        val guard = TvConfirmKeyGuard()
        val press = TvConfirmPress(23, 100, 1)
        guard.begin(press)
        assertFalse(guard.consume(press, release = true, repeat = false))
        guard.suppress(press)
        assertTrue(guard.consume(press, release = false, repeat = true))
        assertTrue(guard.consume(press, release = true, repeat = false))
        assertFalse(guard.consume(press, release = true, repeat = false))
    }

    @Test fun lateOldReleaseDoesNotConsumeANewPressOrAnotherDevice() {
        val guard = TvConfirmKeyGuard()
        val old = TvConfirmPress(23, 100, 1)
        val next = TvConfirmPress(23, 200, 1)
        val other = TvConfirmPress(23, 100, 2)
        guard.begin(old)
        guard.suppress(old)
        guard.begin(next)
        guard.begin(other)
        assertTrue(guard.consume(old, release = true, repeat = false))
        assertFalse(guard.consume(next, release = true, repeat = false))
        assertFalse(guard.consume(other, release = true, repeat = false))
        guard.finish(next)
        guard.finish(other)
    }
}
