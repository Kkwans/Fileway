package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryScanRecoveryTest {
    @Test
    fun scanFailuresReturnToRetryInsteadOfConfirmationDeadEnd() {
        assertTrue(mediaLibraryScanOperation("scan"))
        assertTrue(mediaLibraryScanOperation("setup-scan"))
        assertFalse(mediaLibraryScanOperation("create"))
        assertFalse(mediaLibraryScanOperation(null))
    }
}
