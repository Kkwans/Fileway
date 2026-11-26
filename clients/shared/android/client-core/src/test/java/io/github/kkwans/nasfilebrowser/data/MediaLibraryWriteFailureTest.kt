package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryWriteFailureTest {
    @Test fun storageFailureBeforeDispatchDoesNotClaimMutation() {
        val failure = mediaStorageFailureBeforeDispatch()
        assertEquals(MediaFailureKind.STORAGE, failure.kind)
        assertEquals(false, failure.mutationMayHaveCommitted)
    }
}
