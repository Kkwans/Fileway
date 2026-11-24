package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogVariantStateTest {
    private val ref = MediaCatalogRef(MediaSessionScope(MediaAccountScope("profile", 1, "account", 7), "owner", 1), "lib_1", "item_1", "device")

    @Test
    fun legacyVariantWithoutStateRemainsAvailable() {
        val variant = parseCatalogVariant(variantRow(), ref)
        assertEquals(MediaCatalogSourceKind.SERVER, variant.sourceKind)
        assertEquals(MediaCatalogSourceAvailability.AVAILABLE, variant.availability)
        assertTrue(variant.available)
        assertNull(variant.reasonCode)
    }

    @Test
    fun unavailableVariantRetainsReasonAndRecovery() {
        val variant = parseCatalogVariant(variantRow()
            .put("sourceKind", "server").put("availability", "missing")
            .put("reasonCode", "file_missing").put("recoveryAction", "rescan"), ref)
        assertEquals(MediaCatalogSourceAvailability.MISSING, variant.availability)
        assertEquals("file_missing", variant.reasonCode)
        assertEquals("rescan", variant.recoveryAction)
        assertFalse(variant.available)
    }

    @Test
    fun unknownAvailabilityIsNotPlayable() {
        val variant = parseCatalogVariant(variantRow().put("availability", "future_state"), ref)
        assertEquals(MediaCatalogSourceAvailability.UNKNOWN, variant.availability)
        assertFalse(variant.available)
    }

    private fun variantRow() = JSONObject()
        .put("itemId", "item_1").put("editionId", "edition_1").put("variantId", "variant_1")
        .put("bindingId", "binding_1").put("sourceFileId", "file_1").put("editionName", "原始版本")
        .put("isDefault", true).put("sourceLabel", "本地来源").put("displayName", "电影.mkv")
        .put("contentType", "video/x-matroska").put("size", 10).put("fileRevision", 1)
        .put("identityEpoch", 1).put("weakIdentity", false)
}
