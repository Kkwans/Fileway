package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MediaMutationContentKindTest {
    private val account = MediaAccountScope("profile_fixture", 1, "account_fixture", 7)
    private val operation = MediaOperation(account, "operation_fixture")

    @Test fun createLibraryAcceptsContentKind() {
        val request = MediaMutationRequest(operation, MediaMutationKind.CREATE_LIBRARY,
            body = JSONObject().put("operationId", operation.id).put("name", "Films").put("contentKind", "movie"))
        assertEquals("movie", request.body().getString("contentKind"))
    }

    @Test fun updateLibraryAcceptsContentKind() {
        val request = MediaMutationRequest(operation, MediaMutationKind.UPDATE_LIBRARY, libraryId = "lib_fixture",
            body = JSONObject().put("operationId", operation.id).put("expectedRevision", 3).put("name", "TV").put("contentKind", "series"))
        assertEquals("series", request.body().getString("contentKind"))
    }

    @Test fun legacyLibraryMutationWithoutContentKindRemainsValid() {
        MediaMutationRequest(operation, MediaMutationKind.CREATE_LIBRARY,
            body = JSONObject().put("operationId", operation.id).put("name", "Mixed"))
    }

    @Test fun unknownContentKindIsRejectedBeforeDispatch() {
        try {
            MediaMutationRequest(operation, MediaMutationKind.CREATE_LIBRARY,
                body = JSONObject().put("operationId", operation.id).put("name", "Films").put("contentKind", "episode"))
            fail("unknown content kind accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
