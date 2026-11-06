package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.ExternalPgs
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PgsDiskIndexTest {
    private val directory get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test fun eightCompleteTracksKeepRandomAccessWithinTvBudgetAndReleaseOnlyTheirIndexes() = runBlocking {
        val root = File(directory, "owned-eight-pgs-${System.nanoTime()}").apply { check(mkdirs()) }
        val source = File(root, "owned.sup")
        val timelines = mutableListOf<ExternalPgs.Timeline>()
        try {
            // >20k display sets each, using the same source solely to avoid
            // copying a large synthetic movie eight times on an occupied phone.
            ExternalPgsTest.writeLargeSup(source, fillerCaptions = 1)
            repeat(8) { timelines += ExternalPgs.index(source) }
            assertEquals(8, root.listFiles()!!.count { it.name.startsWith("external-pgs-index-") })
            assertTrue("Eight complete indexes exceed the TV budget", timelines.sumOf { it.retainedBytes } < 16L * 1024 * 1024)
            for (timeline in timelines.reversed()) {
                assertEquals(20_007, timeline.eventCount)
                for ((at, width) in listOf(3_500_000L to 32, 10_500_000L to 512, 2_500_000L to 64)) {
                    val frame = timeline.decode(timeline.floorIndex(at))
                    val image = frame.cues.single().bitmap!!
                    try { assertEquals(width, image.width); assertTrue(android.graphics.Color.red(image.getPixel(0, 0)) > 180) }
                    finally { image.recycle() }
                }
                assertTrue(timeline.decode(timeline.floorIndex(11_500_000)).cues.isEmpty())
            }
            timelines.forEach { it.close(); it.close() }
            assertEquals("Only caller-owned SUP should remain", listOf(source), root.listFiles()!!.toList())
        } finally {
            timelines.forEach { it.close(); it.close() }
            root.deleteRecursively()
        }
    }

    @Test fun canceledCompactionDoesNotLeakIndexOrTakeOwnershipOfSource() = runBlocking {
        val root = File(directory, "owned-cancel-index-${System.nanoTime()}").apply { check(mkdirs()) }
        val source = File(root, "owned.sup").apply { writeBytes(ExternalPgsTest.ownedSup()) }
        try {
            ExternalPgs.IndexBuilder(source).use { builder ->
                builder.advance(source.length(), finished = true)
                val job = launch {
                    currentCoroutineContext().cancel()
                    builder.snapshot(deleteOnClose = true)
                    fail("Canceled disk-index compaction succeeded")
                }
                job.join()
                assertTrue(job.isCancelled)
                assertEquals(listOf(source), root.listFiles()!!.toList())
                builder.snapshot(deleteOnClose = true).use { assertTrue(it.complete) }
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
