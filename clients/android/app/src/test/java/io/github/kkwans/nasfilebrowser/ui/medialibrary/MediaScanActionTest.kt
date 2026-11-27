package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaScanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaScanActionTest {
    @Test
    fun activeDirectoryScanCanBeCanceled() {
        assertEquals(MediaDirectoryScanAction.CANCEL, mediaDirectoryScanAction(MediaScanStatus.QUEUED))
        assertEquals(MediaDirectoryScanAction.CANCEL, mediaDirectoryScanAction(MediaScanStatus.RUNNING))
    }

    @Test
    fun interruptedOrFailedDirectoryScanCanBeRetried() {
        assertEquals(MediaDirectoryScanAction.RETRY, mediaDirectoryScanAction(MediaScanStatus.FAILED))
        assertEquals(MediaDirectoryScanAction.RETRY, mediaDirectoryScanAction(MediaScanStatus.CANCELED))
        assertEquals(MediaDirectoryScanAction.RETRY, mediaDirectoryScanAction(MediaScanStatus.INTERRUPTED))
    }

    @Test
    fun completedOrUnknownScanHasNoMutatingAction() {
        assertNull(mediaDirectoryScanAction(MediaScanStatus.COMPLETED))
        assertNull(mediaDirectoryScanAction(MediaScanStatus.UNKNOWN))
    }
}
