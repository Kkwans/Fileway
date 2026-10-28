package io.github.kkwans.nasfilebrowser.player

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** SUP framing and display-set state only; Media3 still decodes PGS palettes/RLE. */
@androidx.annotation.OptIn(UnstableApi::class)
object ExternalPgs {
    private const val MAX_BITMAP_BYTES = 32L * 1024 * 1024
    private const val MAX_INDEX_BYTES = 32L * 1024 * 1024
    class PictureData(val width: Int, val height: Int, val segments: List<ByteArray>)
    class Crop(val x: Int, val y: Int, val width: Int, val height: Int)
    class ObjectData(val picture: PictureData, val reference: ByteArray, val crop: Crop?)
    class DisplaySet(val startTimeUs: Long, val header: ByteArray, val palette: ByteArray?, val objects: List<ObjectData>)
    private class Picture(val version: Int, val width: Int, val height: Int, val expected: Int) {
        val segments = mutableListOf<ByteArray>()
        var received = 0
        var complete = false
        var snapshot: PictureData? = null
    }
    private fun u16(data: ByteArray, at: Int) = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
    private fun u32(data: ByteArray, at: Int) = (0..3).fold(0L) { n, i -> (n shl 8) or (data[at + i].toLong() and 255) }
    private fun segment(type: Int, data: ByteArray): ByteArray = byteArrayOf(type.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data

    class FilePart(val offset: Long, val length: Int)
    class FilePicture(val width: Int, val height: Int, val parts: List<FilePart>)
    class FileObject(val picture: FilePicture, val reference: ByteArray, val crop: Crop?)
    class FileDisplaySet(val startTimeUs: Long, val header: ByteArray, val palette: ByteArray?, val objects: List<FileObject>)
    private class IndexedPicture(val version: Int, val width: Int, val height: Int, val expected: Int) {
        val parts = mutableListOf<FilePart>()
        var received = 0
        var complete = false
        var snapshot: FilePicture? = null
    }
    private class IndexedPalette {
        val entries = mutableMapOf<Int, ByteArray>()
        var snapshot: ByteArray? = null
    }

    /** Immutable time index, one serialized file reader, no cached decoded bitmaps.
     * close marks cancellation before waiting for a current bounded decode; call it off main.
     * retainedBytes conservatively budgets metadata, not file bytes or total bitmap heap.
     */
    class Timeline constructor(private val file: File, private val input: RandomAccessFile,
        private val sourceLength: Long, private val values: List<FileDisplaySet>, val retainedBytes: Long,
        private val deleteOnClose: Boolean) : Closeable {
        private val closed = AtomicBoolean()
        private val readerLock = Any()
        val eventCount get() = values.size
        fun floorIndex(timeUs: Long): Int {
            if (closed.get()) throw CancellationException("SUP timeline closed")
            var low = 0; var high = values.lastIndex; var found = -1
            while (low <= high) {
                val middle = low + (high - low) / 2
                if (values[middle].startTimeUs <= timeUs) { found = middle; low = middle + 1 }
                else high = middle - 1
            }
            return found
        }
        fun decode(index: Int, isCancelled: () -> Boolean = { false }): CuesWithTiming = synchronized(readerLock) {
            fun checkpoint() { if (closed.get() || isCancelled()) throw CancellationException("SUP decode canceled") }
            checkpoint()
            require(index in values.indices) { "Invalid SUP event index" }
            require(input.length() == sourceLength) { "Indexed SUP source changed" }
            val value = values[index]
            val output = mutableListOf<Cue>()
            try {
                for (item in value.objects) {
                    checkpoint()
                    val segments = item.picture.parts.map { part ->
                        checkpoint()
                        input.seek(part.offset)
                        val bytes = ByteArray(part.length)
                        input.readFully(bytes)
                        require(bytes[0].toInt() and 255 == 0x15 && u16(bytes, 1) == bytes.size - 3) { "Indexed SUP segment changed" }
                        checkpoint()
                        bytes
                    }
                    val picture = PictureData(item.picture.width, item.picture.height, segments)
                    // Keep the existing Media3 PGS decoder, palette and crop behavior.
                    val decoded = ExternalPgs.decode(DisplaySet(value.startTimeUs, value.header, value.palette,
                        listOf(ObjectData(picture, item.reference, item.crop))))
                    output.addAll(decoded.cues)
                    checkpoint()
                }
                checkpoint()
                CuesWithTiming(output, value.startTimeUs, C.TIME_UNSET)
            } catch (failure: Throwable) {
                // These newly decoded images have not been handed to a SubtitleView.
                output.forEach { it.bitmap?.recycle() }
                throw failure
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(readerLock) {
                try { input.close() }
                finally {
                    if (deleteOnClose && file.exists() && !file.delete()) throw IOException("Owned SUP spool cleanup failed")
                }
            }
        }
    }

    /** Validate SUP framing using bounded headers; compressed ODS stays in the sealed file.
     * A successful return transfers this file reader (and optional deletion) to Timeline.
     * Failure closes only the reader; the caller still owns the original file.
     */
    suspend fun index(file: File, deleteOnClose: Boolean = false): Timeline {
        currentCoroutineContext().ensureActive()
        val input = RandomAccessFile(file, "r")
        try {
            val length = input.length()
            require(length in 13..EXTERNAL_SUP_BYTES_LIMIT) { "Invalid SUP size; maximum is 500 MiB" }
            val pictures = mutableMapOf<Int, IndexedPicture>()
            val palettes = mutableMapOf<Int, IndexedPalette>()
            val output = mutableListOf<FileDisplaySet>()
            // Includes bounded maps/current palette entries and list/array overhead.
            // Per-part/event/object charges intentionally exceed their stored primitive data.
            var retained = 256L * 1024
            fun reserve(bytes: Long) {
                require(bytes >= 0 && retained <= MAX_INDEX_BYTES - bytes) { "SUP index exceeds metadata memory budget" }
                retained += bytes
            }
            var presentation: ByteArray? = null
            var offset = 0L; var ptsUs = 0L; var lastPts = -1L; var wraps = 0L
            val header = ByteArray(13)
            while (offset < length) {
                currentCoroutineContext().ensureActive()
                require(length - offset >= 13) { "Truncated SUP header" }
                input.seek(offset); input.readFully(header)
                require(header[0] == 'P'.code.toByte() && header[1] == 'G'.code.toByte()) { "Invalid SUP header" }
                val type = header[10].toInt() and 255
                val size = u16(header, 11)
                val end = offset + 13 + size
                require(end <= length) { "Truncated SUP segment" }
                when (type) {
                    0x16 -> {
                        require(presentation == null && size >= 11)
                        val payload = ByteArray(size).also(input::readFully)
                        require(u16(payload, 0) in 1..8192 && u16(payload, 2) in 1..8192)
                        val pts = u32(header, 2)
                        if (lastPts > pts && lastPts - pts > 0x80000000L) wraps += 0x100000000L
                        val nextUs = (pts + wraps) * 1_000_000 / 90_000
                        require(lastPts < 0 || nextUs >= ptsUs) { "SUP presentation time moves backwards" }
                        lastPts = pts; ptsUs = nextUs
                        if (payload[7].toInt() and 0x80 != 0) { pictures.clear(); palettes.clear() }
                        require((payload[10].toInt() and 255) <= 2) { "Unsupported PGS object count" }
                        presentation = payload
                    }
                    0x14 -> {
                        require(size >= 2 && (size - 2) % 5 == 0)
                        val payload = ByteArray(size).also(input::readFully)
                        val palette = palettes.getOrPut(payload[0].toInt() and 255) { IndexedPalette() }
                        require(palettes.size <= 8)
                        for (at in 2 until size step 5) {
                            val id = payload[at].toInt() and 255
                            val entry = payload.copyOfRange(at, at + 5)
                            if (palette.entries[id]?.contentEquals(entry) != true) {
                                palette.entries[id] = entry; palette.snapshot = null
                            }
                        }
                    }
                    0x15 -> {
                        require(size >= 4)
                        val lead = ByteArray(minOf(size, 11)).also(input::readFully)
                        val id = u16(lead, 0); val flags = lead[3].toInt() and 255
                        val base = flags and 0x80 != 0
                        if (base) {
                            require(size >= 11)
                            val objectLength = ((lead[4].toInt() and 255) shl 16) or u16(lead, 5)
                            val width = u16(lead, 7); val height = u16(lead, 9)
                            require(objectLength in 5..4 * 1024 * 1024 && width > 0 && height > 0 && width.toLong() * height <= 4_000_000)
                            reserve(256)
                            pictures[id] = IndexedPicture(lead[2].toInt() and 255, width, height, objectLength - 4)
                        }
                        val picture = requireNotNull(pictures[id])
                        require(!picture.complete && picture.version == (lead[2].toInt() and 255))
                        picture.received += size - if (base) 11 else 4
                        require(picture.received <= picture.expected)
                        reserve(64)
                        picture.parts.add(FilePart(offset + 10, size + 3))
                        if (flags and 0x40 != 0) { require(picture.received == picture.expected); picture.complete = true }
                        require(pictures.size <= 64)
                    }
                    0x80 -> {
                        require(size == 0)
                        val pcs = requireNotNull(presentation)
                        val objects = mutableListOf<FileObject>()
                        var bitmapBytes = 0L
                        var paletteData: ByteArray? = null
                        var at = 11
                        repeat(pcs[10].toInt() and 255) {
                            require(at + 8 <= pcs.size)
                            val ref = pcs.copyOfRange(at, at + 8)
                            val picture = requireNotNull(pictures[u16(ref, 0)])
                            require(picture.complete)
                            val paletteId = pcs[9].toInt() and 255
                            val palette = requireNotNull(palettes[paletteId])
                            if (paletteData == null) paletteData = palette.snapshot ?: ByteArrayOutputStream().apply {
                                write(byteArrayOf(paletteId.toByte(), 0))
                                palette.entries.toSortedMap().values.forEach { write(it) }
                            }.toByteArray().also { reserve(it.size.toLong() + 32); palette.snapshot = it }
                            val cropped = ref[3].toInt() and 0x80 != 0
                            ref[3] = 0; at += 8
                            val crop = if (cropped) {
                                require(at + 8 <= pcs.size)
                                val x = u16(pcs, at); val y = u16(pcs, at + 2)
                                val w = u16(pcs, at + 4); val h = u16(pcs, at + 6)
                                require(w > 0 && h > 0 && x + w <= picture.width && y + h <= picture.height)
                                at += 8; Crop(x, y, w, h)
                            } else null
                            val fullBytes = picture.width.toLong() * picture.height * 4
                            val visibleBytes = crop?.let { it.width.toLong() * it.height * 4 } ?: fullBytes
                            require(bitmapBytes + fullBytes + (if (crop != null) visibleBytes else 0) <= MAX_BITMAP_BYTES) { "SUP display set exceeds subtitle memory budget" }
                            bitmapBytes += visibleBytes
                            val image = picture.snapshot ?: FilePicture(picture.width, picture.height, picture.parts.toList()).also { picture.snapshot = it }
                            reserve(256)
                            objects.add(FileObject(image, ref, crop))
                        }
                        require(at == pcs.size)
                        reserve(512)
                        if (output.lastOrNull()?.startTimeUs == ptsUs) output.removeAt(output.lastIndex)
                        output.add(FileDisplaySet(ptsUs, pcs.copyOfRange(0, 11).apply { this[10] = 1 }, paletteData, objects))
                        presentation = null
                    }
                    0x17 -> Unit
                    else -> error("Unsupported SUP segment")
                }
                offset = end
            }
            currentCoroutineContext().ensureActive()
            require(input.length() == length && presentation == null && output.isNotEmpty()) { "Incomplete or changed SUP timeline" }
            return Timeline(file, input, length, output, retained, deleteOnClose)
        } catch (failure: Throwable) {
            try { input.close() } catch (close: Throwable) { failure.addSuppressed(close) }
            throw failure
        }
    }

    /** Validate the complete timeline, retaining shared compressed objects. */
    suspend fun parse(data: ByteArray): List<DisplaySet> {
        require(data.size in 13..16 * 1024 * 1024) { "Invalid SUP size" }
        val pictures = mutableMapOf<Int, Picture>()
        val palettes = mutableMapOf<Int, MutableMap<Int, ByteArray>>()
        val output = mutableListOf<DisplaySet>()
        var presentation: ByteArray? = null
        var offset = 0
        var ptsUs = 0L
        var lastPts = -1L
        var wraps = 0L
        while (offset < data.size) {
            currentCoroutineContext().ensureActive()
            require(data.size - offset >= 13 && data[offset] == 'P'.code.toByte() && data[offset + 1] == 'G'.code.toByte()) { "Invalid SUP header" }
            val type = data[offset + 10].toInt() and 255
            val size = u16(data, offset + 11)
            val end = offset + 13 + size
            require(end <= data.size) { "Truncated SUP segment" }
            val payload = data.copyOfRange(offset + 13, end)
            when (type) {
                0x16 -> {
                    require(presentation == null && size >= 11 && u16(payload, 0) in 1..8192 && u16(payload, 2) in 1..8192)
                    val pts = u32(data, offset + 2)
                    if (lastPts > pts && lastPts - pts > 0x80000000L) wraps += 0x100000000L
                    val nextUs = (pts + wraps) * 1_000_000 / 90_000
                    require(lastPts < 0 || nextUs >= ptsUs) { "SUP presentation time moves backwards" }
                    lastPts = pts; ptsUs = nextUs
                    if (payload[7].toInt() and 0x80 != 0) { pictures.clear(); palettes.clear() }
                    require((payload[10].toInt() and 255) <= 2) { "Unsupported PGS object count" }
                    presentation = payload
                }
                0x14 -> {
                    require(size >= 2 && (size - 2) % 5 == 0)
                    val palette = palettes.getOrPut(payload[0].toInt() and 255) { mutableMapOf() }
                    require(palettes.size <= 8)
                    for (i in 2 until size step 5) palette[payload[i].toInt() and 255] = payload.copyOfRange(i, i + 5)
                }
                0x15 -> {
                    require(size >= 4)
                    val id = u16(payload, 0); val flags = payload[3].toInt() and 255
                    val base = flags and 0x80 != 0
                    if (base) {
                        require(size >= 11)
                        val length = ((payload[4].toInt() and 255) shl 16) or u16(payload, 5)
                        val width = u16(payload, 7); val height = u16(payload, 9)
                        require(length in 5..4 * 1024 * 1024 && width > 0 && height > 0 && width.toLong() * height <= 4_000_000)
                        pictures[id] = Picture(payload[2].toInt() and 255, width, height, length - 4)
                    }
                    val picture = requireNotNull(pictures[id])
                    require(!picture.complete && picture.version == (payload[2].toInt() and 255))
                    picture.received += size - if (base) 11 else 4
                    require(picture.received <= picture.expected)
                    picture.segments.add(segment(type, payload))
                    if (flags and 0x40 != 0) { require(picture.received == picture.expected); picture.complete = true }
                    require(pictures.size <= 64)
                }
                0x80 -> {
                    require(size == 0)
                    val pcs = requireNotNull(presentation)
                    val objects = mutableListOf<ObjectData>()
                    var bitmapBytes = 0L
                    var paletteData: ByteArray? = null
                    var at = 11
                    repeat(pcs[10].toInt() and 255) {
                        require(at + 8 <= pcs.size)
                        val ref = pcs.copyOfRange(at, at + 8)
                        val picture = requireNotNull(pictures[u16(ref, 0)])
                        require(picture.complete)
                        val paletteId = pcs[9].toInt() and 255
                        val palette = requireNotNull(palettes[paletteId])
                        if (paletteData == null) paletteData = ByteArrayOutputStream().apply {
                            write(byteArrayOf(paletteId.toByte(), 0)); palette.toSortedMap().values.forEach { write(it) }
                        }.toByteArray()
                        val cropped = ref[3].toInt() and 0x80 != 0
                        ref[3] = 0
                        at += 8
                        val crop = if (cropped) {
                            require(at + 8 <= pcs.size)
                            val x = u16(pcs, at); val y = u16(pcs, at + 2)
                            val w = u16(pcs, at + 4); val h = u16(pcs, at + 6)
                            require(w > 0 && h > 0 && x + w <= picture.width && y + h <= picture.height)
                            at += 8
                            Crop(x, y, w, h)
                        } else null
                        // Bound each visible display set and the transient full
                        // object needed to decode a crop, rather than film length.
                        val fullBytes = picture.width.toLong() * picture.height * 4
                        val visibleBytes = crop?.let { it.width.toLong() * it.height * 4 } ?: fullBytes
                        require(bitmapBytes + fullBytes + (if (crop != null) visibleBytes else 0) <= MAX_BITMAP_BYTES) { "SUP display set exceeds subtitle memory budget" }
                        bitmapBytes += visibleBytes
                        val image = picture.snapshot ?: PictureData(picture.width, picture.height, picture.segments.toList()).also { picture.snapshot = it }
                        objects.add(ObjectData(image, ref, crop))
                    }
                    require(at == pcs.size)
                    if (output.lastOrNull()?.startTimeUs == ptsUs) output.removeAt(output.lastIndex)
                    output.add(DisplaySet(ptsUs, pcs.copyOfRange(0, 11).apply { this[10] = 1 }, paletteData, objects))
                    require(output.size <= 20_000)
                    presentation = null
                }
                0x17 -> Unit // Window geometry is already represented by PCS object positions.
                else -> error("Unsupported SUP segment")
            }
            offset = end
        }
        require(presentation == null && output.isNotEmpty()) { "Incomplete SUP display set" }
        return output
    }

    /** Count shared object bytes once, including headers and palette snapshots. */
    fun encodedBytes(values: List<DisplaySet>): Long {
        val arrays = IdentityHashMap<ByteArray, Boolean>()
        fun remember(bytes: ByteArray?) { if (bytes != null) arrays[bytes] = true }
        values.forEach { value ->
            remember(value.header); remember(value.palette)
            value.objects.forEach { item -> remember(item.reference); item.picture.segments.forEach(::remember) }
        }
        return arrays.keys.sumOf { it.size.toLong() }
    }

    /** Only the current display set is expanded; Media3 owns palette/RLE decoding. */
    fun decode(value: DisplaySet): CuesWithTiming {
        val decoder = PgsParser()
        val cues = value.objects.map { item ->
            val packet = ByteArrayOutputStream().apply {
                write(segment(0x16, value.header + item.reference)); write(segment(0x14, requireNotNull(value.palette)))
                item.picture.segments.forEach { write(it) }; write(segment(0x80, byteArrayOf()))
            }.toByteArray()
            var decoded: Cue? = null
            decoder.parse(packet, SubtitleParser.OutputOptions.allCues()) { decoded = it.cues.singleOrNull() }
            val cue = requireNotNull(decoded) { "Invalid PGS bitmap" }
            val crop = item.crop
            if (crop == null) cue else {
                val original = requireNotNull(cue.bitmap)
                val image = Bitmap.createBitmap(original, crop.x, crop.y, crop.width, crop.height)
                if (image !== original) original.recycle()
                cue.buildUpon().setBitmap(image).setSize(crop.width.toFloat() / u16(value.header, 0))
                    .setBitmapHeight(crop.height.toFloat() / u16(value.header, 2)).build()
            }
        }
        return CuesWithTiming(cues, value.startTimeUs, C.TIME_UNSET)
    }
}
