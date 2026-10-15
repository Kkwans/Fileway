package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TagsUiTest : LibraryUiHarness() {
    @Test fun editorsRetainNamesAndChoicesAfterRejectedSaves(): Unit = runBlocking {
        val data = LibraryFixtureData(); data.file("/owned.png")
        fixture(data) { source ->
            source.favoriteRecords.put(JSONObject().put("id", "owned-favorite").put("path", "/owned.png")
                .put("name", "原始收藏").put("groupId", "").put("order", 0))
            // Establish the editor's starting page directly; navigation itself is
            // covered by multiTagWritesUseUnicodePathsAndFilterTheNativeFileList.
            main { model.librarySection(io.github.kkwans.nasfilebrowser.app.LibrarySection.TAGS) }
            withTimeout(5000) { model.tags.state.first { it.loaded && !it.loading } }
            text("新建标签").click()
            val name = device.wait(androidx.test.uiautomator.Until.findObject(By.clazz("android.widget.EditText")), 5000)
            assertNotNull(name); name!!.text = "保留输入的标签"
            action("标签颜色 4").click()
            data.rejectNextTagWrite = true; text("保存").click()
            val failed = withTimeout(5000) { model.tags.state.first { !it.changing && it.error != null } }
            text(requireNotNull(failed.error)); text("保留输入的标签")
            assertEquals(0, data.tags.length())
            text("保存").click()
            withTimeout(5000) { model.tags.state.first { !it.changing && it.items.singleOrNull()?.name == "保留输入的标签" } }
            assertTrue(device.wait(androidx.test.uiautomator.Until.gone(By.clazz("android.widget.EditText")), 5000))
            assertEquals(io.github.kkwans.nasfilebrowser.data.TAG_COLORS[3], data.tags.getJSONObject(0).getString("color"))
            action("收藏资料页").click(); text("原始收藏"); action("收藏更多：原始收藏").click(); text("编辑收藏").click()
            val favoriteName = device.wait(androidx.test.uiautomator.Until.findObject(By.clazz("android.widget.EditText")), 5000)
            assertNotNull(favoriteName); favoriteName!!.text = "保留输入的收藏"
            data.rejectNextFavoriteWrite = true; text("保存").click()
            val rejected = withTimeout(5000) { model.favorites.state.first { !it.changing && it.error != null } }
            text("编辑收藏"); text(requireNotNull(rejected.error)); text("保留输入的收藏")
            assertEquals("原始收藏", source.favoriteRecords.getJSONObject(0).getString("name"))
            text("保存").click()
            withTimeout(5000) { model.favorites.state.first { !it.changing && it.items.singleOrNull()?.name == "保留输入的收藏" } }
            assertTrue(device.wait(androidx.test.uiautomator.Until.gone(By.text("编辑收藏")), 5000))
            assertEquals("保留输入的收藏", source.favoriteRecords.getJSONObject(0).getString("name"))
            capture("library-editor-retry-phone")
        }
    }
    @Test fun multiTagWritesUseUnicodePathsAndFilterTheNativeFileList(): Unit = runBlocking {
        val data = LibraryFixtureData(); val path = "/中文 #? %.png"
        data.file(path); data.file("/未标记.png")
        data.tags.put(JSONObject().put("id", "tag-movie").put("name", "电影").put("color", "#3F72D8").put("paths", JSONArray()))
        data.tags.put(JSONObject().put("id", "tag-photo").put("name", "照片").put("color", "#E5484D").put("paths", JSONArray()))
        fixture(data) {
            fileDetails(path.substringAfterLast('/'))
            action("设置文件标签").click(); text("电影").click(); text("照片").click()
            data.rejectNextTagWrite = true
            text("保存标记").click()
            val failed = withTimeout(5000) { model.tags.state.first { !it.changing && it.error != null } }
            text("文件标签"); text(requireNotNull(failed.error))
            assertTrue("Failed save must not mutate server tags", data.mutations.isEmpty())
            // Retry without selecting the tags again: failed saves retain user intent.
            text("保存标记").click()
            withTimeout(5000) { model.tags.state.first { !it.changing && it.items.count { tag -> path in tag.paths } == 2 } }
            assertTrue(data.mutations.contains("POST" to "/api/tags/tag-movie/paths"))
            assertTrue(data.mutations.contains("POST" to "/api/tags/tag-photo/paths"))
            text("关闭").click(); action("资料库导航").click(); action("标签资料页").click()
            withTimeout(5000) { model.tags.state.first { !it.pathsLoading && it.paths.singleOrNull()?.file?.path == path } }
            text(path.substringAfterLast('/')); capture("tags-phone")
            text("筛选文件页").click()
            withTimeout(5000) { model.state.first { it.tab == "files" } }
            assertEquals(listOf(path), model.directoryItems().map { it.path })
            assertFalse(device.hasObject(By.text("未标记.png")))
            text("清除筛选").click(); text("未标记.png")
        }
    }
}
