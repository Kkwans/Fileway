package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OperationHistoryPresentationTest {
    @Test fun absoluteTargetShowsObjectWithoutDecodingDisplayValues() {
        assertEquals(HistoryTargetDisplay("文件%20#?.txt", "/资料/%2F"), operationHistoryTarget("/资料/%2F/文件%20#?.txt"))
        assertEquals(HistoryTargetDisplay("folder", "/资料"), operationHistoryTarget("/资料/folder/"))
    }
    @Test fun unixBackslashRemainsPartOfTheName() {
        assertEquals(HistoryTargetDisplay("a\\b.txt", "/"), operationHistoryTarget("/a\\b.txt"))
    }
    @Test fun windowsKeepsDriveAndOriginalSeparators() {
        assertEquals(HistoryTargetDisplay("a.txt", "C:\\"), operationHistoryTarget("C:\\a.txt"))
        assertEquals(HistoryTargetDisplay("a.txt", "D:/资料"), operationHistoryTarget("D:/资料/a.txt"))
        assertEquals(HistoryTargetDisplay("a.txt", "\\\\nas\\share"), operationHistoryTarget("\\\\nas\\share\\a.txt"))
    }
    @Test fun rootsAreNotShownAsMissingNames() {
        listOf("/", "C:\\", "D:/").forEach { assertEquals(HistoryTargetDisplay(it, null), operationHistoryTarget(it)) }
    }
    @Test fun nonPathsAndMultilineTargetsAreNotInventedIntoOneObject() {
        listOf("任务42", "relative/name", "/a\n/b", "https://fixture.invalid/a", "").forEach {
            assertEquals(HistoryTargetDisplay(it, null), operationHistoryTarget(it))
        }
    }
}
