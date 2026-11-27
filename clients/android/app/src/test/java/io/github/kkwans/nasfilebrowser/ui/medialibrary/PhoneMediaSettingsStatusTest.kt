package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaCatalogHomeState
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMediaSettingsStatusTest {
    @Test
    fun noLibrariesShowsEmptyState() {
        val status = mediaSettingsStatus(0, MediaCatalogHomeState.NO_LIBRARIES)
        assertEquals(MediaSettingsStatusKind.EMPTY, status.kind)
        assertEquals("尚未创建媒体库", status.title)
    }

    @Test
    fun scanningShowsBusyState() {
        val status = mediaSettingsStatus(2, MediaCatalogHomeState.SCANNING)
        assertEquals(MediaSettingsStatusKind.BUSY, status.kind)
        assertEquals("正在扫描", status.title)
    }

    @Test
    fun readyStateUsesRealLibraryCount() {
        val status = mediaSettingsStatus(2, MediaCatalogHomeState.READY)
        assertEquals(MediaSettingsStatusKind.READY, status.kind)
        assertEquals("2 个媒体库可浏览。", status.message)
    }

    @Test
    fun offlineStateKeepsCachedContentDiscoverable() {
        val status = mediaSettingsStatus(2, MediaCatalogHomeState.SOURCE_OFFLINE)
        assertEquals(MediaSettingsStatusKind.WARNING, status.kind)
        assertEquals("已收录资料仍可浏览。", status.message)
    }

    @Test
    fun unknownStateDoesNotClaimReadiness() {
        val status = mediaSettingsStatus(2, null)
        assertEquals(MediaSettingsStatusKind.BUSY, status.kind)
        assertEquals("正在读取影视状态", status.title)
    }
}
