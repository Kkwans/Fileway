package io.github.kkwans.fileway.tv

import org.junit.Assert.*
import org.junit.Test

class MediaHandoffStateTest {
    @Test fun shortOrNearEndPlaybackCanBeAcknowledgedAfterNaturalEof() {
        assertTrue(handoffPlaybackMatches(true,false,true,true))
        assertTrue(handoffPlaybackMatches(true,true,false,true))
    }
    @Test fun bufferOrUserPauseCannotClaimAnAutoplayHandoff() {
        assertFalse(handoffPlaybackMatches(true,false,false,true))
        assertFalse(handoffPlaybackMatches(true,false,true,false))
    }
    @Test fun pausedHandoffDoesNotNeedAnArtificialPlayPauseCycle() {
        assertTrue(handoffPlaybackMatches(false,false,false,false))
        assertFalse(handoffPlaybackMatches(false,true,false,true))
    }
}
