package io.github.kkwans.fileway.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffMediaRefTest {
    private fun ref() = HandoffMediaRef(
        instanceId = "instance_1", libraryId = "lib_1", itemId = "item_1", itemRevision = 7,
        editionId = "edition_1", variantId = "variant_1", bindingId = "binding_1",
        sourceFileId = "file_1", fileRevision = 9, identityEpoch = 2, mappingRevision = 4,
    )

    @Test
    fun mediaRefRoundTripsWithoutCredentialsOrPaths() {
        val value = ref()
        val json = value.json()
        assertEquals(value, HandoffMediaRef.from(json))
        assertEquals(setOf("intentVersion", "instanceId", "libraryId", "itemId", "itemRevision",
            "editionId", "variantId", "bindingId", "sourceFileId", "fileRevision", "identityEpoch", "mappingRevision"),
            json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("intentVersion"))
    }

    @Test
    fun mediaRefRejectsInvalidIdentityRevisionAndPrivateFields() {
        val invalid = listOf<() -> Unit>(
            { ref().copy(instanceId = "https://private.example") },
            { ref().copy(libraryId = "") },
            { ref().copy(itemRevision = 0) },
            { ref().copy(fileRevision = 0) },
            { ref().copy(identityEpoch = 0) },
            { ref().copy(mappingRevision = 0) },
        )
        invalid.forEach { create ->
            try {
                create()
                throw AssertionError("Invalid media reference accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            HandoffMediaRef.from(ref().json().put("token", "secret"))
            throw AssertionError("Private field accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
