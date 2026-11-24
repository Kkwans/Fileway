package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCatalogFileModelsTest {
    private val scope = MediaSessionScope(MediaAccountScope("profile", 1, "account", 7), "owner", 1)

    @Test
    fun fileParserRetainsFilenameAndAuthorizedCard() {
        val file = parseCatalogFileMatch(JSONObject()
            .put("fileId", "file_1").put("fileName", "Echo (2020).mkv").put("card", card()), scope, "device")
        assertEquals("file_1", file.fileId)
        assertEquals("Echo (2020).mkv", file.fileName)
        assertEquals("Fixture", file.card.title)
    }

    @Test
    fun fileParserRejectsBlankNameControlCharactersAndUnknownKeys() {
        val base = JSONObject().put("fileId", "file_1").put("fileName", "Echo.mkv").put("card", card())
        assertThrows(IllegalArgumentException::class.java) {
            parseCatalogFileMatch(base.put("fileName", " "), scope, "device")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCatalogFileMatch(base.put("fileName", "Echo\n.mkv"), scope, "device")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCatalogFileMatch(base.put("locator", "/private/path"), scope, "device")
        }
    }

    private fun card() = JSONObject()
        .put("ref", JSONObject().put("libraryId", "lib_1").put("itemId", "item_1"))
        .put("revision", 3).put("structureKind", "video").put("contentCategories", JSONArray())
        .put("matchState", "unmatched").put("title", "Fixture").put("originalTitle", JSONObject.NULL)
        .put("year", JSONObject.NULL).put("displayMode", "unmatched").put("artwork", JSONObject.NULL)
        .put("primaryRating", JSONObject.NULL).put("duration", JSONObject.NULL).put("episodes", JSONObject.NULL)
        .put("availability", "available").put("metadataState", "unmatched").put("firstAddedAt", 1)
        .put("seriesUpdatedAt", JSONObject.NULL).put("size", JSONObject.NULL)
        .put("canActions", JSONObject()).put("actionReasons", JSONObject())
}
