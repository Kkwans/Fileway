package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.data.FileLayout
import io.github.kkwans.nasfilebrowser.ui.collectionDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Previews stay unobstructed; only the regular list has a per-item action button. */
@RunWith(AndroidJUnit4::class)
internal class FileActionsUiTest : LibraryUiHarness() {
    @Test fun listMenuAndLongPressOpenDetailsWithoutCoveringPreviewsAndSaveServerTags(): Unit = runBlocking {
        val data = LibraryFixtureData(); val path = "/中文 #? %.png"
        data.file(path)
        data.tags.put(JSONObject().put("id", "tag-owned").put("name", "本次测试").put("color", "#3F72D8").put("paths", JSONArray()))
        fixture(data) { source ->
            source.favoriteGroups.put(JSONObject().put("id", "owned-group").put("name", "常用").put("order", 0).put("color", "#3F72D8"))
            for (layout in FileLayout.entries) {
                main { model.fileLayout(layout) }
                withTimeout(5000) { model.state.first { it.fileLayout == layout } }
                assertTrue("The requested layout must be rendered before locating its button",
                    device.wait(Until.hasObject(By.desc(layout.collectionDescription())), 5000))
                if (layout == FileLayout.COMPACT) {
                    var card = text(path.substringAfterLast('/'))
                    while (!card.isLongClickable) card = card.parent ?: error("Missing file card")
                    val preview = action("${path.substringAfterLast('/')} 预览")
                    assertTrue("Compact preview must be centred after removing its action button",
                        kotlin.math.abs(preview.visibleBounds.centerX() - card.visibleBounds.centerX()) <= 2)
                    capture("file-compact-centered")
                }
                val label = "文件操作：${path.substringAfterLast('/')}"
                if (layout == FileLayout.LIST) {
                    var menu = action(label)
                    while (!menu.isClickable) menu = menu.parent ?: error("File action has no clickable owner")
                    assertTrue("The button must retain a 48dp target", menu.visibleBounds.width() >= (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt() - 2)
                    menu.click()
                } else {
                    assertTrue("Preview layouts must not have per-file buttons", device.wait(Until.gone(By.desc(label)), 5000))
                    fileDetails(path.substringAfterLast('/'))
                }
                text("文件详情")
                val toolbar = listOf("加入收藏", "设置文件标签", "下载到本机", "移入回收站").map { action(it).visibleBounds }
                assertTrue("Confirm the object before presenting its actions", toolbar.all { it.top > text("名称").visibleBounds.bottom })
                assertTrue("Action icons must share the same row", toolbar.maxOf { it.centerY() } - toolbar.minOf { it.centerY() } <= 2)
                capture("file-actions-${layout.name.lowercase()}")
                text("关闭").click()
                assertTrue("The previous dialog must be gone before the next layout",
                    device.wait(Until.gone(By.text("文件详情")), 5000))
                withTimeout(5000) {
                    while (true) {
                        var focused = false
                        activity.scenario.onActivity { focused = it.hasWindowFocus() }
                        if (focused) break
                        delay(25)
                    }
                }
            }
            fileDetails(path.substringAfterLast('/'))
            action("加入收藏").click(); text("常用").click()
            assertTrue("The favorite child replaces the details window", device.wait(Until.gone(By.text("文件详情")), 5000))
            capture("file-favorite-picker")
            text("保存收藏").click()
            withTimeout(5000) { model.favorites.state.first { !it.changing && it.items.singleOrNull()?.groupId == "owned-group" } }
            action("取消收藏")
            text("文件详情")
            action("设置文件标签").click(); text("本次测试").click()
            assertTrue("The tag child replaces the details window", device.wait(Until.gone(By.text("文件详情")), 5000))
            capture("file-tag-picker-colors")
            text("保存标记").click()
            withTimeout(5000) { model.tags.state.first { !it.changing && it.items.singleOrNull()?.paths?.contains(path) == true } }
            assertTrue(data.mutations.contains("POST" to "/api/tags/tag-owned/paths"))
            assertFalse(data.mutations.any { it.first == "DELETE" })
            text("文件详情")
            text("关闭").click()
            action("文件标签：本次测试")
            capture("file-tag-visible-on-card")
            for (layout in FileLayout.entries) {
                main { model.fileLayout(layout) }
                assertTrue(device.wait(Until.hasObject(By.desc(layout.collectionDescription())), 5000))
                if (layout == FileLayout.UNBOUNDED) action("文件标签：本次测试")
                else assertTrue("Tags are shown only in unbounded layout", device.wait(Until.gone(By.desc("文件标签：本次测试")), 5000))
            }
        }
    }
}
