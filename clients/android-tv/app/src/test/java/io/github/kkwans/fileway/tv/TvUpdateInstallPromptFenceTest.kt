package io.github.kkwans.fileway.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvUpdateInstallPromptFenceTest {
    private data class Source(val account: String)

    @Test fun rebindingTheSameAccountCannotReplayThePriorPrompt() {
        val fence = TvUpdateInstallPromptFence<Source>()
        val oldSession = Source("same account")
        val newSession = oldSession.copy()
        fence.record(oldSession, "original")
        assertEquals(oldSession, newSession)
        assertTrue(fence.allows(oldSession, "original"))
        assertFalse(fence.allows(newSession, "original"))
        assertFalse(fence.allows(null, "original"))
    }

    @Test fun resetAndNewInstallInvalidateEarlierCallbacks() {
        val fence = TvUpdateInstallPromptFence<Source>()
        val source = Source("same account")
        fence.record(source, "first")
        fence.clear()
        assertFalse(fence.allows(source, "first"))
        fence.record(source, "second")
        assertFalse(fence.allows(source, "first"))
        assertTrue(fence.allows(source, "second"))
    }
}
