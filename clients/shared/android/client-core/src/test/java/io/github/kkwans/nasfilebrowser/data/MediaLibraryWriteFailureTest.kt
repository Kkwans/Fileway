package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun scanStartsNeverBlockLibrarySetupBehindConfirmation() {
        assertFalse(MediaMutationKind.START_SCAN in mediaLibrarySetupConfirmationKinds)
        assertFalse(MediaMutationKind.START_SCAN_BATCH in mediaLibrarySetupConfirmationKinds)
        assertTrue(MediaMutationKind.CREATE_LIBRARY in mediaLibrarySetupConfirmationKinds)
        assertTrue(MediaMutationKind.CREATE_SOURCE in mediaLibrarySetupConfirmationKinds)
    }

    @Test fun metadataRefreshUsesProviderFailureSemantics() {
        assertTrue(MediaMutationKind.REFRESH_METADATA in providerMutationKinds)
        val invalid = providerFailure(ServiceException(422, "library offline"), true)
        assertEquals(MediaFailureKind.INVALID, invalid.kind)
        assertFalse(invalid.mutationMayHaveCommitted)
        val limited = providerFailure(ServiceException(429, "rate limited"), true)
        assertEquals(MediaFailureKind.RATE_LIMITED, limited.kind)
        assertFalse(limited.mutationMayHaveCommitted)
    }
}
