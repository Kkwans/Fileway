package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCatalogCollectionModelsTest {
    @Test
    fun collectionParserRetainsNameAndNumber() {
        val collection = parseMediaCollection(JSONObject().put("name", "系列甲").put("number", "2"))
        assertEquals("系列甲", collection.name)
        assertEquals("2", collection.number)
        assertNull(parseMediaCollection(JSONObject().put("name", "系列甲")).number)
    }

    @Test
    fun collectionParserRejectsBlankNameControlCharactersAndPrivateFields() {
        assertThrows(IllegalArgumentException::class.java) {
            parseMediaCollection(JSONObject().put("name", " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseMediaCollection(JSONObject().put("name", "系列\n甲"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseMediaCollection(JSONObject().put("name", "系列甲").put("inputId", "private"))
        }
    }
}
