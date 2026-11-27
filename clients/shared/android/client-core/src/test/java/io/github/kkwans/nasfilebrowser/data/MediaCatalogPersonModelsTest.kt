package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCatalogPersonModelsTest {
    private val scope = MediaSessionScope(MediaAccountScope("profile", 1, "account", 7), "owner", 1)

    @Test
    fun personParserRetainsRolesAndAuthorizedPreview() {
        val person = parseCatalogPerson(JSONObject()
            .put("ref", JSONObject().put("libraryId", "lib_1").put("personId", "person_1"))
            .put("name", "张三")
            .put("roles", JSONArray(listOf("演员", "导演")))
            .put("portrait", JSONObject().put("endpoint", "/api/media-library/v1/libraries/lib_1/items/item_1/artwork/tmdb_profile_3_0"))
            .put("preview", JSONArray().put(card())), scope, "device")
        assertEquals("person_1", person.ref.personId)
        assertEquals("/api/media-library/v1/libraries/lib_1/items/item_1/artwork/tmdb_profile_3_0", person.portraitEndpoint)
        assertEquals(listOf("演员", "导演"), person.roles)
        assertEquals("Fixture", person.preview.single().title)
    }

    @Test
    fun personParserRejectsMissingPreviewOrForeignLibrary() {
        val base = JSONObject().put("ref", JSONObject().put("libraryId", "lib_1").put("personId", "person_1"))
            .put("name", "张三").put("roles", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { parseCatalogPerson(base.put("preview", JSONArray()), scope, "device") }
        assertThrows(IllegalArgumentException::class.java) { parseCatalogPerson(base.put("preview", JSONArray().put(card("lib_2"))), scope, "device") }
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
