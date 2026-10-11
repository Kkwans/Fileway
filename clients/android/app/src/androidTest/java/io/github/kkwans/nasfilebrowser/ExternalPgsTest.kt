package io.github.kkwans.nasfilebrowser

import android.view.ViewGroup
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.ExternalPgs
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExternalPgsTest {
    @Test fun longFilmSupKeepsAllCaptionAndClearEventsWithoutExpandingEveryBitmap(): Unit = runBlocking {
        // 240 authored 512x80 captions would occupy 37.5 MiB if all decoded
        // together, although the SUP reuses one small RLE-compressed object.
        val values = ExternalPgs.parse(longSup())
        assertEquals(480, values.size)
        assertTrue("Shared RLE objects must stay compressed", ExternalPgs.encodedBytes(values) < 64 * 1024)
        for (index in listOf(0, 200, 478, 0)) {
            val caption = ExternalPgs.decode(values[index])
            val image = caption.cues.single().bitmap!!
            try {
                assertEquals((2L + index) * 1_000_000, caption.startTimeUs)
                assertEquals(512, image.width); assertEquals(80, image.height)
                assertEquals(0, android.graphics.Color.alpha(image.getPixel(0, 0)))
                assertTrue(android.graphics.Color.red(image.getPixel(300, 0)) > 180)
            } finally { image.recycle() }
            assertTrue("The clear event must replace a prior caption", ExternalPgs.decode(values[index + 1]).cues.isEmpty())
        }
    }
    @Test fun realPgsDecoderPreservesSupTimingCachedObjectsCropAndClear(): Unit = runBlocking {
        val cues = ExternalPgs.parse(ownedSup()).map(ExternalPgs::decode)
        assertEquals(listOf(2_000_000L, 3_000_000L, 4_000_000L), cues.map { it.startTimeUs })
        assertTrue(cues.all { it.durationUs == C.TIME_UNSET })
        val first = cues[0].cues.single()
        assertEquals(64, first.bitmap!!.width)
        assertEquals(12, first.bitmap!!.height)
        assertEquals(64f / 384, first.position, .0001f)
        assertTrue(android.graphics.Color.red(first.bitmap!!.getPixel(0, 0)) > 180)
        val cropped = cues[1].cues.single()
        assertEquals(32, cropped.bitmap!!.width)
        assertEquals(6, cropped.bitmap!!.height)
        assertEquals(96f / 384, cropped.position, .0001f)
        assertTrue("Clear PCS must replace the preceding bitmap", cues[2].cues.isEmpty())
    }
    @Test fun truncatedDisplaySetFailsBeforeASelectedTrackCanBeAttached(): Unit = runBlocking {
        for (bytes in listOf(ownedSup().dropLast(1).toByteArray(), byteArrayOf(80, 71))) {
            try { ExternalPgs.parse(bytes); fail("Corrupt SUP accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun compressedTimelineSeeksClearsAndAppliesDelayWithoutLosingEarlierCaptions(): Unit = runBlocking {
        val values = ExternalPgs.parse(longSup())
        val events = Channel<Boolean>(Channel.UNLIMITED)
        val ready = CompletableDeferred<Unit>()
        val eventCount = AtomicInteger()
        val lastVisible = AtomicReference<Boolean?>()
        val rendererFailure = AtomicReference<String?>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var ownedLayer: MediaSubtitleLayer? = null
        suspend fun stage(label: String, action: suspend () -> Unit) {
            try { withTimeout(5000) { action() } }
            catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                throw AssertionError("Compressed SUP stage=$label, attached=${ownedLayer?.isAttachedToWindow}, " +
                    "events=${eventCount.get()}, lastVisible=${lastVisible.get()}, rendererFailure=${rendererFailure.get()}", error)
            }
        }
        try {
            scenario.onActivity { activity ->
                val root = activity.window.decorView as ViewGroup
                val layer = MediaSubtitleLayer(activity).also { ownedLayer = it }
                layer.showText = { cues ->
                    val visible = cues.isNotEmpty()
                    eventCount.incrementAndGet(); lastVisible.set(visible); events.trySend(visible)
                }
                layer.failed = {
                    rendererFailure.set("Compressed SUP rendering failed")
                    val error = AssertionError(rendererFailure.get())
                    ready.completeExceptionally(error); events.close(error)
                }
                root.addView(layer, ViewGroup.LayoutParams(384, 216))
                assertTrue("Owned subtitle layer must be attached before View.post callbacks", layer.isAttachedToWindow)
                layer.externalPgs("external:owned", values) { ready.complete(Unit) }
            }
            val layer = requireNotNull(ownedLayer)
            suspend fun expect(label: String, visible: Boolean) = stage("$label expectedVisible=$visible") {
                while (events.receive() != visible) { }
            }
            suspend fun at(position: Long, visible: Boolean, delay: Long = 0) {
                while (events.tryReceive().isSuccess) { }
                withContext(Dispatchers.Main) { layer.delayMs = delay; layer.requestFrame(position) }
                expect("frame positionMs=$position delayMs=$delay", visible)
            }
            stage("externalPgs ready callback") { ready.await() }
            withContext(Dispatchers.Main) { layer.select(null, "external:owned", 2500) }
            expect("initial selection positionMs=2500", true)
            at(480_500, true)
            at(480_500, false, delay = 1000)
            at(480_500, true)
            at(481_500, false)
            at(2500, true)
            withContext(Dispatchers.Main) { layer.select(null, null, 2500) }
            expect("explicit subtitle off", false)
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.Main) {
                    ownedLayer?.let { layer -> layer.close(); (layer.parent as? ViewGroup)?.removeView(layer) }
                }
            } finally { try { scenario.close() } finally { events.close() } }
        }
    }
    @Test fun canceledLongSupParseDoesNotFinishAndAttachATrack(): Unit = runBlocking {
        val input = longSup()
        var canceled = false
        val job = launch {
            currentCoroutineContext().cancel()
            try { ExternalPgs.parse(input); fail("Canceled SUP parse completed") }
            catch (_: CancellationException) { canceled = true }
        }
        job.join()
        assertTrue(canceled)
    }
    @Test fun fileIndexSeeksBeyondOldSizeAndEventLimitsPreservingCropClearAndEarlierObjects(): Unit = runBlocking {
        val file = File.createTempFile("owned-large-sup-", ".sup", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            withContext(Dispatchers.IO) { writeLargeSup(file) }
            assertTrue("Actual valid SUP must exceed the old whole-track budget", file.length() > 32L * 1024 * 1024)
            val timeline = withContext(Dispatchers.IO) { ExternalPgs.index(file) }
            try {
                assertEquals(20_606, timeline.eventCount)
                assertTrue("Metadata must not retain the compressed movie-sized source", timeline.retainedBytes < file.length() / 2)
                assertTrue(timeline.retainedBytes <= 32L * 1024 * 1024)
                assertEquals(-1, timeline.floorIndex(1_999_999))
                fun image(atUs: Long, width: Int, height: Int) {
                    val index = timeline.floorIndex(atUs)
                    assertEquals(index, timeline.floorIndex(atUs))
                    val frame = timeline.decode(index)
                    assertEquals(C.TIME_UNSET, frame.durationUs)
                    val cue = frame.cues.single(); val bitmap = cue.bitmap!!
                    try {
                        assertEquals(width, bitmap.width); assertEquals(height, bitmap.height)
                        assertTrue(android.graphics.Color.red(bitmap.getPixel(0, 0)) > 180)
                        assertTrue(android.graphics.Color.green(bitmap.getPixel(0, 0)) > 180)
                    } finally { bitmap.recycle() }
                }
                image(2_500_000, 64, 12)
                image(3_500_000, 32, 6)
                assertTrue(timeline.decode(timeline.floorIndex(4_000_000)).cues.isEmpty())
                assertTrue("The far live event must follow over 20k clear events", timeline.floorIndex(10_500_000) > 20_000)
                image(10_500_000, 512, 128)
                assertTrue(timeline.decode(timeline.floorIndex(11_500_000)).cues.isEmpty())
                image(2_500_000, 64, 12)
                var checkpoints = 0
                try {
                    timeline.decode(timeline.floorIndex(4_000_012)) { ++checkpoints >= 5 }
                    fail("Cancellation between the two real ODS fragments was ignored")
                } catch (_: CancellationException) { assertEquals(5, checkpoints) }
                image(2_500_000, 64, 12)
            } finally { withContext(Dispatchers.IO) { timeline.close(); timeline.close() } }
            assertTrue("Default close must preserve the caller's file", file.isFile)
            try { timeline.floorIndex(2_500_000); fail("Closed reader remained usable") }
            catch (_: CancellationException) { }
        } finally { file.delete() }
    }

    @Test fun fileIndexPreservesWrappedPtsAndCachedCropAfterRandomSeek(): Unit = runBlocking {
        val bytes = ownedSup()
        var offset = 0
        while (offset < bytes.size) {
            val seconds = (2..5).first { second ->
                val pts = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[offset + 2 + i].toLong() and 255) }
                pts == second * 90_000L
            }
            val pts = (0xffff0000L + (seconds - 2) * 90_000L) and 0xffffffffL
            for (i in 0..3) bytes[offset + 2 + i] = (pts ushr (24 - i * 8)).toByte()
            offset += 13 + ((bytes[offset + 11].toInt() and 255) shl 8) + (bytes[offset + 12].toInt() and 255)
        }
        val file = File.createTempFile("owned-wrap-sup-", ".sup", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(bytes)
            val timeline = ExternalPgs.index(file)
            try {
                val firstUs = 0xffff0000L * 1_000_000 / 90_000
                val cropUs = (0xffff0000L + 90_000) * 1_000_000 / 90_000
                val clearUs = (0xffff0000L + 180_000) * 1_000_000 / 90_000
                assertEquals(-1, timeline.floorIndex(firstUs - 1))
                for ((at, width) in listOf(cropUs to 32, firstUs to 64)) {
                    val frame = timeline.decode(timeline.floorIndex(at))
                    assertEquals(at, frame.startTimeUs)
                    val bitmap = frame.cues.single().bitmap!!
                    try { assertEquals(width, bitmap.width) } finally { bitmap.recycle() }
                }
                assertTrue(timeline.decode(timeline.floorIndex(clearUs)).cues.isEmpty())
            } finally { timeline.close() }
        } finally { file.delete() }
    }

    @Test fun malformedFileIndexNeverTakesOwnershipAndChangedSourceFailsAtDecode(): Unit = runBlocking {
        val file = File.createTempFile("owned-invalid-sup-", ".sup", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            for (bytes in listOf(ownedSup().dropLast(1).toByteArray(), byteArrayOf(80, 71))) {
                file.writeBytes(bytes)
                try { ExternalPgs.index(file, deleteOnClose = true); fail("Malformed SUP index accepted") }
                catch (_: IllegalArgumentException) { }
                assertTrue("Index failure must leave cleanup to its caller", file.isFile)
            }
            val reset = ownedSup()
            var at = 0; var compositions = 0
            while (at < reset.size) {
                if (reset[at + 10].toInt() and 255 == 0x16 && ++compositions == 2) reset[at + 13 + 7] = 0x80.toByte()
                at += 13 + ((reset[at + 11].toInt() and 255) shl 8) + (reset[at + 12].toInt() and 255)
            }
            file.writeBytes(reset)
            try { ExternalPgs.index(file); fail("An epoch reset reused an object from the prior epoch") }
            catch (_: IllegalArgumentException) { }
            file.writeBytes(ownedSup())
            val timeline = ExternalPgs.index(file)
            try {
                RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }
                try { timeline.decode(0); fail("Changed indexed source accepted") }
                catch (_: IllegalArgumentException) { }
            } finally { timeline.close() }
        } finally { file.delete() }
    }

    @Test fun cancelledIndexLeavesCallerFileAndOwnedCloseDeletesOnlyItsSpool(): Unit = runBlocking {
        val file = File.createTempFile("owned-cancel-sup-", ".sup", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(ownedSup())
            var canceled = false
            val job = launch {
                currentCoroutineContext().cancel()
                try { ExternalPgs.index(file, deleteOnClose = true); fail("Canceled SUP indexing completed") }
                catch (_: CancellationException) { canceled = true }
            }
            job.join(); assertTrue(canceled); assertTrue(file.isFile)
            val timeline = ExternalPgs.index(file, deleteOnClose = true)
            timeline.close(); timeline.close()
            assertFalse("Owned close must release its exact spool", file.exists())
        } finally { file.delete() }
    }

    companion object {
        /** Stream a real ~39MiB SUP, not a giant APK asset/ByteArray. The 12s movie can
         * seek 2.5s=64x12, 3.5s=crop32x6, 10.5s=512x128, 11.5s=clear, then back.
         * Extra clear events expose the old 20k registration limit independently of size.
         */
        fun writeLargeSup(file: File, fillerCaptions: Int = 600, trailingClears: Int = 20_001) {
            require(fillerCaptions in 1..600 && trailingClears in 0..20_001)
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.write(ownedSup())
                fun segment(pts: Long, type: Int, payload: ByteArray) {
                    require(payload.size <= 65535)
                    out.writeByte(80); out.writeByte(71); out.writeInt(pts.toInt()); out.writeInt(0)
                    out.writeByte(type); out.writeShort(payload.size); out.write(payload)
                }
                fun pcs(count: Int, composition: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { data ->
                        data.writeShort(1920); data.writeShort(1080); data.writeByte(0x10); data.writeShort(composition)
                        data.writeByte(0); data.writeByte(0); data.writeByte(0); data.writeByte(count)
                        if (count != 0) {
                            data.writeShort(1); data.writeByte(0); data.writeByte(0); data.writeShort(64); data.writeShort(900)
                        }
                    }
                }.toByteArray()
                val pixels = ByteArray((512 + 2) * 128)
                repeat(128) { row -> pixels.fill(1, row * 514, row * 514 + 512) }
                val length = pixels.size + 4
                val firstSize = 60000
                repeat(fillerCaptions) { index ->
                    val pts = 360001L + index * 300L
                    val version = ((index + 1) and 255).toByte()
                    segment(pts, 0x16, pcs(1, index + 4))
                    segment(pts, 0x15, byteArrayOf(0, 1, version, 0x80.toByte(),
                        (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(), 2, 0, 0, 0x80.toByte()) + pixels.copyOfRange(0, firstSize))
                    segment(pts, 0x15, byteArrayOf(0, 1, version, 0x40) + pixels.copyOfRange(firstSize, pixels.size))
                    segment(pts, 0x80, byteArrayOf())
                }
                repeat(trailingClears) { index ->
                    val pts = 630000L + index * 8L
                    segment(pts, 0x16, pcs(0, index)); segment(pts, 0x80, byteArrayOf())
                }
                segment(900000, 0x16, pcs(1, 1)); segment(900000, 0x80, byteArrayOf())
                segment(990000, 0x16, pcs(0, 2)); segment(990000, 0x80, byteArrayOf())
            }
        }
        fun longSup(): ByteArray {
            val output = ByteArrayOutputStream()
            fun segment(at: Int, type: Int, payload: ByteArray) {
                output.write(byteArrayOf(80, 71))
                val pts = at * 90_000
                for (shift in listOf(24, 16, 8, 0)) output.write(pts ushr shift)
                output.write(ByteArray(4)); output.write(type)
                output.write(payload.size ushr 8); output.write(payload.size); output.write(payload)
            }
            val header = byteArrayOf(7, -128, 4, 56, 0, 0, 1, 0, 0, 0, 1)
            val pixels = ByteArrayOutputStream().apply {
                repeat(80) { write(byteArrayOf(0, 65, 0, 0, -63, 0, 1, 0, 0)) }
            }.toByteArray()
            val length = pixels.size + 4
            repeat(240) { index ->
                val at = 2 + index * 2
                segment(at, 0x16, header.copyOf().apply { this[7] = if (index == 0) -128 else 0 } + byteArrayOf(0, 1, 0, 0, 0, 64, 3, -124))
                if (index == 0) {
                    segment(at, 0x14, byteArrayOf(0, 0, 1, -21, -128, -128, -1))
                    segment(at, 0x15, byteArrayOf(0, 1, 0, -64, (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(), 2, 0, 0, 80) + pixels)
                }
                segment(at, 0x80, byteArrayOf())
                segment(at + 1, 0x16, header.copyOf().apply { this[10] = 0 })
                segment(at + 1, 0x80, byteArrayOf())
            }
            return output.toByteArray()
        }
        /** Authored white bitmap at 2s, cached/cropped reposition at 3s, clear at 4s. */
        fun ownedSup(): ByteArray {
            val output = ByteArrayOutputStream()
            fun segment(at: Int, type: Int, payload: ByteArray) {
                output.write(byteArrayOf(80, 71))
                val pts = at * 90_000
                for (shift in listOf(24, 16, 8, 0)) output.write(pts ushr shift)
                output.write(ByteArray(4)); output.write(type)
                output.write(payload.size ushr 8); output.write(payload.size); output.write(payload)
            }
            val header = byteArrayOf(1, -128, 0, -40, 0, 0, 1, -128, 0, 0, 1)
            segment(2, 0x16, header + byteArrayOf(0, 1, 0, 0, 0, 64, 0, -116))
            segment(2, 0x14, byteArrayOf(0, 0, 1, -21, -128, -128, -1))
            segment(2, 0x15, byteArrayOf(0, 1, 0, -64, 0, 3, 4, 0, 64, 0, 12) + ByteArray(64 * 12) { 1 })
            segment(2, 0x80, byteArrayOf())
            segment(3, 0x16, header.copyOf().apply { this[7] = 0 } +
                byteArrayOf(0, 1, 0, -128, 0, 96, 0, -116, 0, 0, 0, 0, 0, 32, 0, 6))
            segment(3, 0x80, byteArrayOf())
            segment(4, 0x16, header.copyOf().apply { this[7] = 0; this[10] = 0 })
            segment(4, 0x80, byteArrayOf())
            return output.toByteArray()
        }
    }
}
