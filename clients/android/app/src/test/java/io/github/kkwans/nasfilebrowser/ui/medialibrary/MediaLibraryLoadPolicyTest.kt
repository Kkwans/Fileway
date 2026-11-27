package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaLibraryRole
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
    fun onlyOwnerDetailNeedsAuxiliaryLoading() {
        assertTrue(mediaLibraryNeedsAuxiliaryLoad("library", MediaLibraryRole.OWNER))
        assertFalse(mediaLibraryNeedsAuxiliaryLoad("library", MediaLibraryRole.VIEWER))
        assertFalse(mediaLibraryNeedsAuxiliaryLoad("libraries", MediaLibraryRole.OWNER))
    }
}
