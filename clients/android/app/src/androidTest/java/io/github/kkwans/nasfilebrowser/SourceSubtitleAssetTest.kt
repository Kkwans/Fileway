package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSubtitleAssetTest {
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "owned-font-assets-${System.nanoTime()}").apply { check(mkdirs()) }

    @Test fun fontAboveOldLimitIsCompleteAndCallerOwnedWhileInvalidBodiesLeaveNoSpool() = runBlocking {
        val root = directory()
        Server().use { server ->
            try {
                val file = SourceSubtitleAsset.download(root, server.url + "/large", SUBTITLE_FONT_BYTES_LIMIT.toLong())
                assertEquals(9L * 1024 * 1024, file.length())
                assertTrue(file.inputStream().use { it.read() } == 87)
                assertTrue(file.delete())
                for (path in listOf("/too-large", "/truncated", "/unknown-length")) {
                    val limit = if (path == "/unknown-length") 8L * 1024 * 1024 else SUBTITLE_FONT_BYTES_LIMIT.toLong()
                    val result = runCatching { SourceSubtitleAsset.download(root, server.url + path, limit) }
                    assertTrue("Invalid body accepted: $path", result.isFailure)
                    assertTrue("Failed body leaked its owned spool", root.listFiles()!!.isEmpty())
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun stalledBodyCancellationIsBoundedAndClearsItsFile() = runBlocking {
        val root = directory()
        Server().use { server ->
            try {
                val job = launch(Dispatchers.Default) { SourceSubtitleAsset.download(root, server.url + "/stall", 1024) }
                assertTrue(server.stalled.await(5, TimeUnit.SECONDS))
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(root.listFiles()!!.isEmpty())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun fontBatchKeepsTwoDiskReadersOneRegistrationAndDoesNotFailOnOneAttachment() = runBlocking {
        val root = directory(); val active = AtomicInteger(); val peak = AtomicInteger()
        val registering = AtomicInteger(); val registerPeak = AtomicInteger()
        val accepted = mutableSetOf<String>()
        try {
            val fonts = (0..5).map { SourceSubtitleFont("$it", "Owned$it.ttf") }
            val complete = SourceSubtitleFonts.load(fonts,
                prepare = { font ->
                    val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                    try {
                        delay(40)
                        if (font.id == "1") error("Owned missing attachment")
                        File(root, font.id).apply { writeText("owned") }
                    } finally { active.decrementAndGet() }
                },
                register = { font, file ->
                    val count = registering.incrementAndGet(); registerPeak.updateAndGet { maxOf(it, count) }
                    try { delay(20); assertEquals("owned", file.readText()); accepted += font.id; true }
                    finally { registering.decrementAndGet() }
                })
            assertFalse(complete)
            assertEquals(setOf("0", "2", "3", "4", "5"), accepted)
            assertEquals(2, peak.get()); assertEquals(1, registerPeak.get())
            assertTrue(root.listFiles()!!.isEmpty())
            val partial = SourceSubtitleFonts.load(fonts.take(2),
                prepare = { font -> File(root, font.id).apply { writeText("owned") } },
                register = { _, _ -> delay(5000); true }, timeoutMs = 100)
            assertFalse(partial)
            assertTrue("Timed-out registration leaked queued files", root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val stalled = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        private val worker = Thread({
            while (!listener.isClosed) try {
                val peer = listener.accept(); sockets += peer
                Thread({
                    peer.use {
                        try {
                            val input = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val path = input.readLine().split(' ')[1]
                            while (!input.readLine().isNullOrBlank()) { }
                            val size = when (path) { "/too-large" -> 33L * 1024 * 1024; "/truncated", "/stall" -> 100L; else -> 9L * 1024 * 1024 }
                            val output = peer.getOutputStream()
                            val length = if (path == "/unknown-length") "" else "Content-Length: $size\r\n"
                            output.write(("HTTP/1.1 200 OK\r\n" + length + "Connection: close\r\n\r\n").toByteArray())
                            if (path == "/too-large") { output.flush(); return@use }
                            if (path == "/truncated" || path == "/stall") {
                                output.write(87); output.flush()
                                if (path == "/stall") { stalled.countDown(); release.await(10, TimeUnit.SECONDS) }
                            } else {
                                val block = ByteArray(32768) { 87 }
                                repeat((size / block.size).toInt()) { output.write(block) }
                            }
                        } catch (_: Exception) { }
                    }
                }, "Owned-font-body").apply { isDaemon = true; start() }
            } catch (_: Exception) { }
        }, "Owned-font-listener").apply { isDaemon = true; start() }
        override fun close() { release.countDown(); listener.close(); synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }; worker.join(1000) }
    }
}
