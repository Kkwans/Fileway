package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCatalogCollectionSearchModelsTest {
    private val scope = MediaSessionScope(MediaAccountScope("profile", 1, "account", 7), "owner", 1)

    @Test
    fun collectionParserRetainsAuthorizedPreview() {
        val collection = parseCatalogCollection(JSONObject()
            .put("ref", JSONObject().put("libraryId", "lib_1").put("collectionId", "collection_1"))
            .put("name", "系列甲").put("preview", JSONArray().put(card())), scope, "device")
        assertEquals("collection_1", collection.ref.collectionId)
        assertEquals("系列甲", collection.name)
        assertEquals("Fixture", collection.preview.single().title)
    }

    @Test
    fun collectionParserRejectsMissingPreviewOrForeignLibrary() {
        val base = JSONObject().put("ref", JSONObject().put("libraryId", "lib_1").put("collectionId", "collection_1"))
            .put("name", "系列甲").put("preview", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { parseCatalogCollection(base, scope, "device") }
        assertThrows(IllegalArgumentException::class.java) {
            parseCatalogCollection(base.put("preview", JSONArray().put(card("lib_2"))), scope, "device")
        }
    }

    private fun card(libraryId: String = "lib_1") = JSONObject()
        .put("ref", JSONObject().put("libraryId", libraryId).put("itemId", "item_1"))
        .put("revision", 3).put("structureKind", "movie").put("contentCategories", JSONArray())
        .put("matchState", "provider_confirmed").put("title", "Fixture").put("originalTitle", JSONObject.NULL)
        .put("year", JSONObject.NULL).put("displayMode", "poster").put("artwork", JSONObject.NULL)
        .put("primaryRating", JSONObject.NULL).put("duration", JSONObject.NULL).put("episodes", JSONObject.NULL)
        .put("availability", "available").put("metadataState", "available").put("firstAddedAt", 1)
        .put("seriesUpdatedAt", JSONObject.NULL).put("size", JSONObject.NULL)
        .put("canActions", JSONObject()).put("actionReasons", JSONObject())
}
