package io.github.kkwans.fileway.tv.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Test

class TvMediaEpisodePresentationTest {
    @Test
    fun episodeTitleUsesRealNameOrNumberOnly() {
        assertEquals("第 1 集 · 叉叉叉叉", tvMediaEpisodeTitle("第 1 集", "叉叉叉叉"))
        assertEquals("第 1 集", tvMediaEpisodeTitle("第 1 集", "Loki.2021.EP01.4K.X264.mkv"))
        assertEquals("第 2 集", tvMediaEpisodeTitle("第 2 集", "Show.S02E03.1080p.mkv"))
    }
}
