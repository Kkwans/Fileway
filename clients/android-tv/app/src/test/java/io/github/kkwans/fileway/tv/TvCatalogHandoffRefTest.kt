package io.github.kkwans.fileway.tv

import io.github.kkwans.fileway.remote.HandoffMediaRef
import org.junit.Assert.assertEquals
import org.junit.Test

class TvCatalogHandoffRefTest {
    private fun ref() = HandoffMediaRef("instance_1", "lib_1", "item_1", 7,
        "edition_1", "variant_1", "binding_1", "file_1", 9, 2, 4)

    @Test
    fun matchingCatalogReferenceIsAccepted() {
        assertEquals(ref(), validateCatalogHandoffRef(ref(), itemRevision = 7, editionId = "edition_1",
            variantId = "variant_1", bindingId = "binding_1", sourceFileId = "file_1",
            fileRevision = 9, identityEpoch = 2, mappingRevision = 4, available = true))
    }

    @Test
    fun staleOrUnavailableCatalogReferenceIsRejected() {
        val invalid = listOf(
            ref().copy(itemRevision = 8),
            ref().copy(fileRevision = 10),
            ref().copy(identityEpoch = 3),
            ref().copy(mappingRevision = 5),
            ref().copy(bindingId = "binding_2"),
        )
        invalid.forEach { value ->
            try {
                validateCatalogHandoffRef(value, itemRevision = 7, editionId = "edition_1",
                    variantId = "variant_1", bindingId = "binding_1", sourceFileId = "file_1",
                    fileRevision = 9, identityEpoch = 2, mappingRevision = 4, available = true)
                throw AssertionError("Stale catalog reference accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            validateCatalogHandoffRef(ref(), itemRevision = 7, editionId = "edition_1",
                variantId = "variant_1", bindingId = "binding_1", sourceFileId = "file_1",
                fileRevision = 9, identityEpoch = 2, mappingRevision = 4, available = false)
            throw AssertionError("Unavailable source accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
