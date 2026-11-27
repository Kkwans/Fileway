package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaLibraryKind
import io.github.kkwans.nasfilebrowser.data.MediaLibraryRole
import io.github.kkwans.nasfilebrowser.data.MediaLibrarySummary
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryRowPresentationTest {
    @Test
    fun ownerMovieRowDescribesManagement() {
        assertEquals("电影 · 管理目录与资料", mediaLibraryRowSubtitle(library(MediaLibraryKind.MOVIE, MediaLibraryRole.OWNER)))
    }

    @Test
    fun sharedSeriesRowDescribesSharing() {
        assertEquals("电视剧 · 共享媒体库", mediaLibraryRowSubtitle(library(MediaLibraryKind.SERIES, MediaLibraryRole.VIEWER)))
    }

    @Test
    fun unknownKindDoesNotInventType() {
        assertEquals("类型未知 · 共享媒体库", mediaLibraryRowSubtitle(library(MediaLibraryKind.UNKNOWN, MediaLibraryRole.VIEWER)))
    }

    private fun library(kind: MediaLibraryKind, role: MediaLibraryRole) =
        MediaLibrarySummary("lib_1", "影视库", 1, role, false, kind)
}
