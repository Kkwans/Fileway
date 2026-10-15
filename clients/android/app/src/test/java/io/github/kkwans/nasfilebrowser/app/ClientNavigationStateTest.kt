package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.CompactGridSize
import org.junit.Assert.*
import org.junit.Test

class ClientNavigationStateTest {
    @Test fun legacyTaskRoutesHaveOneMainDestination() {
        listOf("downloads", "uploads", "tasks", "history").forEach {
            assertEquals(ClientMainDestination.TASKS, ClientDestination.forRoute(it)?.main)
        }
        assertEquals(ClientDestination.UPLOADS, ClientDestination.forRoute("taskcenter", TaskCenterSection.UPLOADS))
        assertEquals(ClientDestination.HISTORY, ClientDestination.forRoute("taskcenter", TaskCenterSection.HISTORY))
        assertNull(ClientDestination.forRoute("unexpected-route"))
        assertNull(ClientDestination.SETTINGS.main)
        assertEquals(4, ClientMainDestination.entries.size)
    }

    @Test fun displayAdapterUsesCurrentStateRatherThanRememberingAnotherRoute() {
        val original = ClientState(tab = "library", librarySection = LibrarySection.TAGS)
        assertEquals(ClientDestination.LIBRARY, original.destination)
        assertEquals(ClientDestination.SEARCH, original.copy(searchDisplayed = true).destination)
        assertEquals(ClientDestination.DOWNLOADS, original.copy(tab = "downloads").destination)
        assertEquals(ClientDestination.ABOUT, original.copy(tab = "about").destination)
    }

    @Test fun auxiliaryEntriesRespectCurrentCapabilitiesButLocalToolsWorkOffline() {
        assertFalse(ClientDestination.ACCOUNT.allowed(false, true, true))
        assertFalse(ClientDestination.ADMIN_USERS.allowed(true, false, true))
        assertFalse(ClientDestination.SERVER_SETTINGS.allowed(false, true, true))
        assertFalse(ClientDestination.SHELL.allowed(true, true, false))
        assertTrue(ClientDestination.SHELL.allowed(true, false, true))
        listOf(ClientDestination.SETTINGS, ClientDestination.ABOUT, ClientDestination.UPDATES,
            ClientDestination.DOWNLOADS, ClientDestination.UPLOADS).forEach {
            assertTrue(it.allowed(false, false, false))
        }
    }

    @Test fun auxiliaryReturnRestoresTheActualNestedSource() {
        val history = ClientNavigationHistory()
        val files = frame(ClientReturnKind.AUXILIARY, "files")
        val settings = frame(ClientReturnKind.AUXILIARY, "settings")
        history.push("account-generation", files)
        history.push("account-generation", settings)
        assertEquals(settings, history.pop("account-generation"))
        assertEquals(files, history.pop("account-generation"))
        assertNull(history.pop("account-generation"))
    }

    @Test fun taskDetailCloseDoesNotConsumeAnUnrelatedDirectoryOrigin() {
        val history = ClientNavigationHistory()
        val tagDirectory = frame(ClientReturnKind.DIRECTORY, "library")
        val report = frame(ClientReturnKind.TASK_DETAIL, "library")
        history.push("owner", tagDirectory)
        assertNull(history.pop("owner", ClientReturnKind.TASK_DETAIL))
        assertEquals(tagDirectory, history.peek("owner"))
        history.push("owner", report)
        assertEquals(report, history.pop("owner", ClientReturnKind.TASK_DETAIL))
        assertEquals(tagDirectory, history.peek("owner"))
    }

    @Test fun changingSessionCannotRestoreAnotherAccountsOrigin() {
        val history = ClientNavigationHistory()
        history.push("old-owner", frame(ClientReturnKind.DIRECTORY, "library"))
        assertNull(history.pop("new-owner"))
        assertNull(history.peek("old-owner"))
    }

    @Test fun hiddenSearchIsRetainedOnlyWhileAValidSourceNeedsIt() {
        val history = ClientNavigationHistory()
        val search = frame(ClientReturnKind.DIRECTORY, "files").let {
            it.copy(point = it.point.copy(searchDisplayed = true))
        }
        history.push("owner", search)
        assertTrue(history.keepsSearch("owner"))
        history.push("owner", frame(ClientReturnKind.AUXILIARY, "settings"))
        history.pop("owner", ClientReturnKind.AUXILIARY)
        assertTrue(history.keepsSearch("owner"))
        history.pop("owner", ClientReturnKind.DIRECTORY)
        assertFalse(history.keepsSearch("owner"))
    }

    @Test fun directoryOriginRetainsOpaqueIdentityAndPreviousHistory() {
        val state = ClientState(tab = "library", librarySection = LibrarySection.TAGS,
            path = "/显示目录", wirePath = "/opaque%2Fidentity", fileCategory = FileCategory.IMAGE)
        val point = ClientNavigationPoint.from(state)
        val history = ClientNavigationHistory()
        val entry = ClientNavigationReturn(ClientReturnKind.DIRECTORY, point, listOf("/" to "/"))
        history.push("owner", entry)
        val restored = history.pop("owner")!!
        assertEquals("/opaque%2Fidentity", restored.point.wirePath)
        assertEquals(LibrarySection.TAGS, restored.point.library)
        assertEquals(FileCategory.IMAGE, restored.point.category)
        assertEquals(listOf("/" to "/"), restored.directoryHistory)
    }

    @Test fun pageKeysDoNotAliasOpaquePathsOrQueriesContainingSeparators() {
        assertNotEquals(clientPageStateKey("a", "b:c"), clientPageStateKey("a:b", "c"))
        assertNotEquals(clientPageStateKey("ab", "c"), clientPageStateKey("a", "bc"))
        assertEquals(clientPageStateKey("搜索", "%2F原值"), clientPageStateKey("搜索", "%2F原值"))
    }

    @Test fun compactSizeDefaultAndStoredValuesPreserveTheThreeTierContract() {
        assertEquals(CompactGridSize.MEDIUM, ClientState().compactGridSize)
        assertEquals(CompactGridSize.MEDIUM, CompactGridSize.fromStored(null))
        assertEquals(CompactGridSize.MEDIUM, CompactGridSize.fromStored("invalid"))
        assertEquals(CompactGridSize.LARGE, CompactGridSize.fromStored("large"))
        assertEquals(listOf(4, 3, 2), CompactGridSize.entries.map { it.columns })
        assertEquals(listOf(2, 2, 1), CompactGridSize.entries.map { it.nameLines })
        assertTrue(CompactGridSize.entries.zipWithNext().all { (a, b) -> a.previewSizeDp < b.previewSizeDp })
    }

    private fun frame(kind: ClientReturnKind, route: String) =
        ClientNavigationReturn(kind, ClientNavigationPoint.from(ClientState(tab = route)))
}
