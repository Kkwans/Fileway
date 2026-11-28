package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaLibraryRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryLoadPolicyTest {
    @Test
    fun libraryListAndDetailShowCoreLoading() {
        assertTrue(mediaLibraryShowsCoreLoading("libraries"))
        assertTrue(mediaLibraryShowsCoreLoading("library"))
        assertFalse(mediaLibraryShowsCoreLoading("create"))
    }

    @Test
    fun staleAuxiliaryResultCannotOverwriteAnotherLibrary() {
        assertTrue(mediaLibraryAuxiliaryResultIsCurrent("lib_2", "lib_2", "library", "lib_2"))
        assertFalse(mediaLibraryAuxiliaryResultIsCurrent("lib_2", "lib_1", "library", "lib_2"))
        assertFalse(mediaLibraryAuxiliaryResultIsCurrent("lib_2", "lib_2", "libraries", "lib_2"))
        assertFalse(mediaLibraryAuxiliaryResultIsCurrent("lib_2", "lib_2", "library", "lib_1"))
    }

    @Test
    fun createFlowHasOnePrimaryAction() {
        assertEquals("创建媒体库", mediaLibraryCreatePrimaryAction(folderCount = 0, busy = false))
        assertEquals("创建并扫描", mediaLibraryCreatePrimaryAction(folderCount = 2, busy = false))
        assertEquals("仅创建媒体库", mediaLibraryCreatePrimaryAction(folderCount = 61, busy = false))
        assertEquals("正在保存…", mediaLibraryCreatePrimaryAction(folderCount = 2, busy = true))
    }

    @Test
    fun settingsCanOpenASpecificLibraryDirectly() {
        assertEquals("library", mediaLibraryManagerInitialScreen(createInitially = false, selectedLibraryId = "lib_1"))
        assertEquals("libraries", mediaLibraryManagerInitialScreen(createInitially = false, selectedLibraryId = null))
        assertEquals("libraries", mediaLibraryManagerInitialScreen(createInitially = false, selectedLibraryId = " "))
    }

    @Test
    fun createFlowStillTakesPriorityOverALibrarySelection() {
        assertEquals("create", mediaLibraryManagerInitialScreen(createInitially = true, selectedLibraryId = "lib_1"))
    }

    @Test
    fun directLibraryEntryReturnsToCallerInsteadOfLibraryList() {
        assertFalse(mediaLibraryManagerBackGoesToList(directLibraryId = "lib_1", selectedId = "lib_1"))
        assertTrue(mediaLibraryManagerBackGoesToList(directLibraryId = null, selectedId = "lib_1"))
        assertTrue(mediaLibraryManagerBackGoesToList(directLibraryId = "lib_1", selectedId = "lib_2"))
    }

    @Test
    fun onlyOwnerDetailNeedsAuxiliaryLoading() {
        assertTrue(mediaLibraryNeedsAuxiliaryLoad("library", MediaLibraryRole.OWNER))
        assertFalse(mediaLibraryNeedsAuxiliaryLoad("library", MediaLibraryRole.VIEWER))
        assertFalse(mediaLibraryNeedsAuxiliaryLoad("libraries", MediaLibraryRole.OWNER))
    }
}
