package io.github.kkwans.fileway.tv.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaCatalogHomeRow
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPageState
import io.github.kkwans.nasfilebrowser.data.MediaCatalogRowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMediaHomeRowPolicyTest {
    @Test
    fun catalogRowsPreserveOrderAndBoundEachLazyItem() {
        val cards = (1..13).map { TvMediaCard("item_$it", "影片 $it") }

        val rows = tvMediaCatalogRows(cards, columns = 6)

        assertEquals(listOf(6, 6, 1), rows.map { it.size })
        assertEquals(cards.map { it.id }, rows.flatten().map { it.id })
    }

    @Test
    fun catalogRowsNeverUseAnInvalidColumnCount() {
        val cards = listOf(TvMediaCard("item_1", "影片 1"), TvMediaCard("item_2", "影片 2"))

        assertEquals(listOf(1, 1), tvMediaCatalogRows(cards, columns = 0).map { it.size })
    }

    @Test
    fun emptyCatalogKeepsCategoryNavigationVisible() {
        val shelf = TvMediaShelf("catalog", "我的片库")

        assertTrue(tvMediaCatalogShelfVisible(shelf, listOf(TvMediaCategory("movie", "电影"))))
        assertFalse(tvMediaCatalogShelfVisible(shelf, emptyList()))
    }

    @Test
    fun serverRowsKeepTheirOrderAndUseReadableTitles() {
        val rows = listOf(
            homeRow(MediaCatalogRowId.RECENTLY_ADDED),
            homeRow(MediaCatalogRowId.RECENTLY_UPDATED_SERIES),
        )

        val shelves = rows.map { tvMediaHomeShelf(it, emptyList()) }

        assertEquals(listOf("recently-added", "recently-updated-series"), shelves.map { it?.id })
        assertEquals(listOf("最近入库", "最近更新剧集"), shelves.map { it?.title })
    }

    @Test
    fun unsupportedAndContinueRowsDoNotBecomeDuplicateShelves() {
        assertNull(tvMediaHomeShelf(homeRow(MediaCatalogRowId.CONTINUE), emptyList()))
        assertNull(tvMediaHomeShelf(homeRow(MediaCatalogRowId.FAVORITES), emptyList()))
        assertNull(tvMediaHomeShelf(homeRow(MediaCatalogRowId.UNKNOWN), emptyList()))
    }

    @Test
    fun rowPaginationAppendsWithoutDroppingAuthorizedItems() {
        val first = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("item_1", "一")),
            hasMore = true,
        )
        val next = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("item_2", "二")),
        )

        val merged = mergeTvMediaHomeRow(first, next, append = true, key = TvMediaCard::id)

        assertEquals(listOf("item_1", "item_2"), merged.items.map { it.id })
        assertFalse(merged.hasMore)
        assertNull(merged.cursor)
        assertFalse(merged.loading)
    }

    @Test
    fun rowRefreshReplacesStaleItemsAndDeduplicatesTheNewPage() {
        val current = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("old", "旧内容")),
            hasMore = true,
        )
        val next = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("new", "新内容"), TvMediaCard("new", "新内容")),
        )

        val merged = mergeTvMediaHomeRow(current, next, append = false, key = TvMediaCard::id)

        assertEquals(listOf("new"), merged.items.map { it.id })
    }

    @Test
    fun failedRefreshKeepsLastAuthorizedRowAndExposesRetry() {
        val current = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("item_1", "一")),
        )

        val failed = current.copy(error = "连接失败", loading = false, retryAppend = false)

        assertEquals(listOf("item_1"), failed.items.map { it.id })
        assertEquals("连接失败", failed.error)
        assertFalse(failed.retryAppend)
    }

    @Test
    fun failedRowSyncKeepsLastAuthorizedItemsAndResetsRetryMode() {
        val current = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("item_1", "一")),
            hasMore = true,
            retryAppend = true,
        )
        val incoming = TvMediaHomeRowState<TvMediaCard>(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            error = "列表尚在准备，请稍后重试。",
        )

        val synced = syncTvMediaHomeRows(listOf(current), listOf(incoming)).single()

        assertEquals(listOf("item_1"), synced.items.map { it.id })
        assertEquals(incoming.error, synced.error)
        assertFalse(synced.hasMore)
        assertFalse(synced.retryAppend)
    }

    @Test
    fun partialEmptyRowSyncKeepsAuthorizedItemsUntilContinuationCompletes() {
        val current = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("item_1", "一")),
        )
        val incoming = TvMediaHomeRowState<TvMediaCard>(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            hasMore = true,
        )

        val synced = syncTvMediaHomeRows(listOf(current), listOf(incoming)).single()

        assertEquals(listOf("item_1"), synced.items.map { it.id })
        assertTrue(synced.hasMore)
        assertNull(synced.error)
    }

    @Test
    fun readyEmptyRowSyncRemovesStaleAuthorizedItems() {
        val current = TvMediaHomeRowState(
            rowId = MediaCatalogRowId.RECENTLY_ADDED,
            items = listOf(TvMediaCard("old", "旧内容")),
        )
        val incoming = TvMediaHomeRowState<TvMediaCard>(rowId = MediaCatalogRowId.RECENTLY_ADDED)

        val synced = syncTvMediaHomeRows(listOf(current), listOf(incoming)).single()

        assertTrue(synced.items.isEmpty())
        assertNull(synced.error)
    }

    @Test
    fun rowSyncUsesServerOrderAndRemovesOmittedRows() {
        val added = TvMediaHomeRowState<TvMediaCard>(rowId = MediaCatalogRowId.RECENTLY_ADDED)
        val updated = TvMediaHomeRowState<TvMediaCard>(rowId = MediaCatalogRowId.RECENTLY_UPDATED_SERIES)

        val synced = syncTvMediaHomeRows(listOf(added, updated), listOf(updated))

        assertEquals(listOf(MediaCatalogRowId.RECENTLY_UPDATED_SERIES), synced.map { it.rowId })
    }

    @Test
    fun partialEmptyRowsRemainVisibleWhenAnotherPageExists() {
        val shelf = tvMediaHomeShelf(
            homeRow(MediaCatalogRowId.RECENTLY_ADDED, state = MediaCatalogPageState.PARTIAL, hasMore = true),
            emptyList(),
        )

        assertTrue(shelf!!.hasMore)
        assertNull(shelf.error)
        assertTrue(tvMediaShelfVisible(shelf))
    }

    @Test
    fun preparingRowsExposeRetryWithoutDiscardingExistingItems() {
        val shelf = tvMediaHomeShelf(
            homeRow(MediaCatalogRowId.RECENTLY_ADDED, state = MediaCatalogPageState.PREPARING),
            listOf(TvMediaCard("item_1", "一")),
        )

        assertFalse(shelf!!.loading)
        assertEquals("列表尚在准备，请稍后重试。", shelf.error)
        assertEquals(listOf("item_1"), shelf.items.map { it.id })
    }

    private fun homeRow(id: MediaCatalogRowId, state: MediaCatalogPageState = MediaCatalogPageState.READY, hasMore: Boolean = false) = MediaCatalogHomeRow(
        id = id,
        kind = when (id) {
            MediaCatalogRowId.HERO -> "hero"
            MediaCatalogRowId.RECENTLY_ADDED -> "recently-added"
            MediaCatalogRowId.RECENTLY_UPDATED_SERIES -> "recently-updated-series"
            MediaCatalogRowId.UNMATCHED -> "unmatched"
            MediaCatalogRowId.CONTINUE -> "continue"
            MediaCatalogRowId.FAVORITES -> "favorites"
            MediaCatalogRowId.UNKNOWN -> "unknown"
        },
        state = state,
        items = emptyList(),
        nextCursor = null,
        hasMore = hasMore,
        revision = "revision_1",
        continuing = emptyList(),
    )
}
