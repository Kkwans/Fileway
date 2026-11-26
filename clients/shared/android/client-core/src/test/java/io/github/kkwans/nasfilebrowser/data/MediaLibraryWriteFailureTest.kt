package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryWriteFailureTest {
    @Test fun storageFailureBeforeDispatchDoesNotClaimMutation() {
        val failure = mediaStorageFailureBeforeDispatch()
        assertEquals(MediaFailureKind.STORAGE, failure.kind)
        assertEquals(false, failure.mutationMayHaveCommitted)
    }

    @Test fun journalExceptionBeforeDispatchDoesNotClaimMutation() {
        val failure = mediaFailureBeforeDispatch(IllegalStateException("journal closed"))
        assertEquals(MediaFailureKind.INVALID_RESPONSE, failure.kind)
        assertEquals(false, failure.mutationMayHaveCommitted)
    }
}
