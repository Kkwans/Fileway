package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.SourceSubtitleAssets
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSubtitleAssetsTest {
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "owned-session-assets-${System.nanoTime()}").apply { check(mkdirs()) }
    private fun file(root: File, bytes: Int) = File.createTempFile("owned-", ".bin", root).apply { writeBytes(ByteArray(bytes) { 42 }) }

    @Test fun mergedReadersSurviveOneCancellationAndReuseWithoutCrossingSession(): Unit = runBlocking {
        val root = directory(); val store = SourceSubtitleAssets(64)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        suspend fun prepare(): File { calls.incrementAndGet(); entered.complete(Unit); finish.await(); return file(root, 8) }
        try {
            val first = async { store.acquire("source:owned:6", 16, ::prepare) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { store.acquire("source:owned:6", 16, ::prepare) }
            first.cancelAndJoin(); assertFalse(second.isCompleted)
            finish.complete(Unit)
            val held = second.await(); assertEquals(8, held.file.length().toInt()); held.close(); held.close()
            store.acquire("source:owned:6", 16) { error("Decoder recreation downloaded again") }.use {
                assertTrue(it.file.isFile)
            }
            assertEquals(1, calls.get())
            SourceSubtitleAssets(64).use { other ->
                other.acquire("source:owned:6", 16) { calls.incrementAndGet(); file(root, 8) }.use {
                    assertTrue(it.file.isFile)
                }
            }
            assertEquals(2, calls.get())
            store.close(); assertTrue(root.listFiles()!!.isEmpty())
            assertTrue(runCatching { store.acquire("source:owned:6", 16, ::prepare) }.isFailure)
        } finally { store.close(); root.deleteRecursively() }
    }

    @Test fun reservationsEvictOnlyInactiveFilesAndClosePreservesActiveReader(): Unit = runBlocking {
        val root = directory(); val store = SourceSubtitleAssets(10)
        try {
            val pinned = store.acquire("a", 6) { file(root, 6) }
            val old = pinned.file
            assertTrue(runCatching { store.acquire("b", 5) { error("No budget must mean no download") } }.isFailure)
            assertTrue(old.isFile)
            pinned.close()
            val next = store.acquire("b", 5) { file(root, 3) }
            assertFalse(old.exists())
            val exact = store.acquire("c", 7) { file(root, 7) }
            assertEquals(10L, root.listFiles()!!.sumOf { it.length() })
            next.close()
            store.close()
            assertTrue(exact.file.isFile); assertEquals(7, exact.file.readBytes().size)
            assertEquals(1, root.listFiles()!!.size)
            exact.close(); assertTrue(root.listFiles()!!.isEmpty())
        } finally { store.close(); root.deleteRecursively() }
    }

    @Test fun cancelledLateAndInvalidFilesAreDeletedAndFailuresCanRetry(): Unit = runBlocking {
        val root = directory(); val store = SourceSubtitleAssets(32)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        try {
            val waiting = async {
                store.acquire("late", 16) {
                    withContext(NonCancellable) { entered.complete(Unit); finish.await(); file(root, 4).also { returned.complete(Unit) } }
                }
            }
            entered.await(); waiting.cancelAndJoin(); finish.complete(Unit)
            withTimeout(2000) {
                returned.await()
                while (root.listFiles()!!.any { it.length() == 4L }) delay(10)
            }
            assertTrue(runCatching { store.acquire("bad", 2) { file(root, 3) } }.isFailure)
            store.acquire("bad", 2) { file(root, 2) }.use { assertEquals(2, it.file.readBytes().size) }
            store.close()
            withTimeout(2000) { while (root.listFiles()!!.isNotEmpty()) delay(10) }
        } finally { finish.complete(Unit); store.close(); root.deleteRecursively() }
    }
}
