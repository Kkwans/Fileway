package io.github.kkwans.nasfilebrowser.player

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ExternalSubtitleInputTest {
    private class CountingOutput : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length }
    }

    private fun virtualRead(length: Long): (ByteArray, Int, Int) -> Int {
        var remaining = length
        return { _, _, requested ->
            if (remaining == 0L) -1 else minOf(remaining, requested.toLong()).toInt().also { remaining -= it }
        }
    }

    @Test fun exactFiveHundredMiBAcceptsEofWithoutAllocatingTheWholeSource() {
        val output = CountingOutput()
        var buffer: ByteArray? = null
        var reads = 0
        val source = virtualRead(EXTERNAL_SUP_BYTES_LIMIT)
        val count = copyExternalSubtitle({ data, offset, length ->
            if (buffer == null) buffer = data else assertSame(buffer, data)
            assertTrue(data.size <= 64 * 1024)
            reads++
            source(data, offset, length)
        }, output, EXTERNAL_SUP_BYTES_LIMIT) { }
        assertEquals(524_288_000L, count)
        assertEquals(count, output.bytes)
        assertTrue(reads > 1)
    }

    @Test fun oneByteAboveTheSupLimitIsRejectedWithoutWritingTheExtraByte() {
        val output = CountingOutput()
        val error = runCatching {
            copyExternalSubtitle(virtualRead(EXTERNAL_SUP_BYTES_LIMIT + 1), output, EXTERNAL_SUP_BYTES_LIMIT) { }
        }.exceptionOrNull()
        assertTrue(error is ExternalSubtitleLimitException)
        assertTrue(error!!.message!!.contains("500 MiB"))
        assertEquals(EXTERNAL_SUP_BYTES_LIMIT, output.bytes)
    }

    @Test fun textReadKeepsItsIndependentBound() {
        val output = CountingOutput()
        val error = runCatching {
            copyExternalSubtitle(virtualRead(EXTERNAL_TEXT_BYTES_LIMIT + 1), output, EXTERNAL_TEXT_BYTES_LIMIT) { }
        }.exceptionOrNull()
        assertTrue(error is ExternalSubtitleLimitException)
        assertTrue(error!!.message!!.contains("16 MiB"))
        assertEquals(EXTERNAL_TEXT_BYTES_LIMIT, output.bytes)
    }

    @Test fun smallAndShortReadsKeepEveryOriginalByte() {
        val expected = ByteArray(123_457) { (it % 251).toByte() }
        val input = ByteArrayInputStream(expected)
        val output = ByteArrayOutputStream()
        val count = copyExternalSubtitle({ data, offset, length -> input.read(data, offset, minOf(length, 37)) },
            output, EXTERNAL_SUP_BYTES_LIMIT) { }
        assertEquals(expected.size.toLong(), count)
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test fun cancellationBeforeWritingAReadChunkDoesNotPublishIt() {
        val output = CountingOutput()
        var checks = 0
        val error = runCatching {
            copyExternalSubtitle(virtualRead(EXTERNAL_SUP_BYTES_LIMIT), output, EXTERNAL_SUP_BYTES_LIMIT) {
                if (++checks == 4) throw CancellationException("owned cancellation")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(64L * 1024, output.bytes)
    }

    @Test fun aNonProgressingOrInvalidSourceFailsInsteadOfSpinning() {
        for (value in listOf(0, -2, 65_537)) {
            val output = CountingOutput()
            assertNotNull(runCatching {
                copyExternalSubtitle({ _, _, _ -> value }, output, EXTERNAL_SUP_BYTES_LIMIT) { }
            }.exceptionOrNull())
            assertEquals(0L, output.bytes)
        }
    }
}
