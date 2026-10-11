package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import io.github.kkwans.nasfilebrowser.data.BackendKind
import io.github.kkwans.nasfilebrowser.data.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class DirectoryCreationUiTest : LibraryUiHarness() {
    private suspend fun keyboardBounds(): Rect = withTimeout(5000) {
        while (true) {
            val ime = instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val bounds = Rect(); ime?.getBoundsInScreen(bounds)
            if (bounds.height() > 0 && bounds.top < device.displayHeight) return@withTimeout bounds
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("Keyboard window missing")
    }
    private suspend fun clickText(label: String) = clickOwnedText(instrumentation, device, label)
    private fun input() = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing directory input")
    private suspend fun enterName(value: String) = withTimeout(5000) {
        input().text = value
        // A framework setText receipt alone does not prove that the dialog's
        // current draft received the value. Observe the real callback before
        // the one physical submit; never set the controller directly here.
        model.fileOperations.state.first { !it.changing && it.creation?.name == value }
        assertEquals(value, input().text)
    }
    private suspend fun start() {
        action("新建文件或文件夹").click()
        action("新建文件夹").click()
        withTimeout(5000) { model.fileOperations.state.first { it.creation != null } }
        input()
    }
    @Test fun rejectedCreateRetainsInputAndExistingNamesNeverOverwriteThenCreatedDirectoryRefreshes(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { liveDirectoryListing = true; rejectNextMkdirStatus = 503 }
        data.file("/已有文件"); data.file("/已存在", true)
        fixture(data) {
            val scope = model.fileOperations.state.value.scope
            var stage = "rejected-create"
            var intendedName = "未创建 +%"
            try {
                start(); enterName(intendedName); clickText("创建")
                withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.creation?.error != null } }
                assertEquals(intendedName, input().text); assertEquals(1, data.mkdirAttempts.size)
                stage = "existing-file"; intendedName = "已有文件"
                enterName(intendedName); clickText("创建")
                withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.creation?.error?.contains("同名文件") == true } }
                assertFalse(data.files.getValue("/已有文件").getBoolean("isDir")); assertEquals(1, data.mkdirAttempts.size)
                stage = "existing-directory"; intendedName = "已存在"
                enterName(intendedName); clickText("创建")
                withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.creation?.existing != null } }
                capture("directory-existing-conflict"); clickText("打开已有文件夹")
                withTimeout(5000) { model.state.first { !it.busy && it.path == "/已存在" } }
                stage = "create-in-current-directory"; intendedName = "%2F +# 中文"
                start(); enterName(intendedName); clickText("创建")
                withTimeout(5000) { model.state.first { it.files.any { file -> file.name == intendedName && file.directory } } }
                assertNull(model.fileOperations.state.value.creation)
                assertEquals(2, data.mkdirAttempts.size)
                assertEquals("/api/resources" + SearchResult.encodePath("/已存在/$intendedName") + "/", data.mkdirAttempts.last())
                capture("directory-created-in-current-list")
            } catch (failure: Throwable) {
                // Capture before fixture teardown removes the dialog. No raw
                // field values, addresses, error messages or hierarchy dumps.
                val state = model.fileOperations.state.value
                val draft = state.creation
                val sameScope = state.scope == scope
                if (sameScope && device.currentPackageName == instrumentation.targetContext.packageName)
                    runCatching { capture("directory-create-not-ready") }
                val windows = runCatching { ownedWindowFacts(instrumentation) }
                    .getOrElse { "unavailable(${it.javaClass.simpleName})" }
                throw AssertionError("Directory stage=$stage, scopeMatches=$sameScope, changing=${state.changing}, " +
                    "draftPresent=${draft != null}, nameMatches=${draft?.name == intendedName}, " +
                    "errorPresent=${draft?.error != null}, sameFileError=${draft?.error?.contains("同名文件") == true}, " +
                    "existingPresent=${draft?.existing != null}, unknownPresent=${draft?.unknownTarget != null}, " +
                    "mkdirAttempts=${data.mkdirAttempts.size}; $windows", failure)
            }
        }
    }
    @Test fun lostCreateResponseIsConfirmedByReadAndUnknownOutcomeDoesNotRepeatWriteAcrossAccountSwitch(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { liveDirectoryListing = true; mkdirResponseLostOnce = true }
        fixture(data) { source ->
            start(); enterName("已落盘"); clickText("创建")
            withTimeout(5000) { model.state.first { it.files.any { file -> file.path == "/已落盘" } } }
            assertNull(model.fileOperations.state.value.creation); assertEquals(1, data.mkdirAttempts.size)
            data.mkdirResponseLostOnce = true; data.mkdirMetadataUnavailable = true
            start(); enterName("待核对"); clickText("创建")
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.creation?.unknownTarget != null } }
            main { model.fileOperations.createDirectory() }; delay(150)
            assertEquals(2, data.mkdirAttempts.size); assertEquals("待核对", input().text)
            capture("directory-unknown-result")
            data.mkdirMetadataUnavailable = false; clickText("核对创建结果")
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.creation == null } }
            assertEquals(2, data.mkdirAttempts.size)
            val oldScope = model.state.value.previewScope
            main { model.connectDraft("Owned library UI fixture", source.url, BackendKind.NAS, "two", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy && it.accountName == "two" } }
            main { model.startDirectoryCreation(oldScope) }
            assertNull(model.fileOperations.state.value.creation)
        }
    }
    @Test fun missingCreatePermissionHidesEntryAndStopsDirectCommandBeforePost(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { liveDirectoryListing = true; createAllowed = false }
        fixture(data) {
            assertTrue(device.wait(Until.gone(By.desc("新建文件夹")), 5000))
            main { model.startDirectoryCreation(model.state.value.previewScope); model.fileOperations.directoryName("不应创建"); model.fileOperations.createDirectory() }
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.creation?.error != null } }
            assertTrue(data.mkdirAttempts.isEmpty()); assertFalse(data.files.containsKey("/不应创建"))
        }
    }
    @Test fun keyboardKeepsCreationAndExistingFolderActionsPhysicallyVisible(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { liveDirectoryListing = true }; data.file("/已存在", true)
        fixture(data) {
            start(); enterName("已存在")
            val ime = keyboardBounds()
            val create = text("创建").visibleBounds; val cancel = text("取消").visibleBounds
            assertTrue("Create must stay above the actual IME", create.bottom <= ime.top)
            assertTrue("Cancel must stay above the actual IME", cancel.bottom <= ime.top)
            clickText("创建")
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.creation?.existing != null } }
            val existing = text("打开已有文件夹").visibleBounds
            assertTrue("Existing-folder action must stay above IME", existing.bottom <= keyboardBounds().top)
            capture("directory-ime-actions-visible")
            clickText("打开已有文件夹")
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/已存在" } }
        }
    }
}
