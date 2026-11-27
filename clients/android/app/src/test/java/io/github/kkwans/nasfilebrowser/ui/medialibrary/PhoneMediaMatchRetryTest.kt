package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMediaMatchRetryTest {
    @Test
    fun failedCandidatePageRetriesTheOriginalQueryAndPage() {
        val failed = PhoneMediaFailedSearch("原名", 2)
        assertEquals(failed, phoneMediaMatchRetrySearch(MediaProblem("查询失败", "网络失败", "重试"), failed))
        assertNull(phoneMediaMatchRetrySearch(MediaProblem("无法预览关联", "网络失败", "刷新资料"), failed))
        assertNull(phoneMediaMatchRetrySearch(null, failed))
        assertNull(phoneMediaMatchRetrySearch(MediaProblem("查询失败", "网络失败", "重试"), null))
    }
}
