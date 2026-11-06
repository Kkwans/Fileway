package io.github.kkwans.nasfilebrowser.player

import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Private, disposable event metadata, never a persisted or imported format.
 * Only timestamps/record offsets stay on heap; the selected event is read under
 * Timeline's reader lock. This also keeps inactive, verified tracks seekable. */
internal class PgsDiskIndex private constructor(private val file: File,
    private val input: RandomAccessFile, private val length: Long,
    private val times: LongArray, private val offsets: LongArray) : AbstractList<ExternalPgs.FileDisplaySet>(), Closeable {
    override val size get() = times.size
    val retainedBytes get() = 256L * 1024 + size.toLong() * 16
    fun timeAt(index: Int) = times[index]

    override fun get(index: Int): ExternalPgs.FileDisplaySet {
        require(index in indices && input.length() == length) { "SUP event index changed" }
        val end = if (index == lastIndex) length else offsets[index + 1]
        input.seek(offsets[index])
        fun bytes(expected: IntRange): ByteArray {
            val count = input.readInt()
            require(count in expected && count.toLong() <= end - input.filePointer) { "Invalid SUP index field" }
            return ByteArray(count).also(input::readFully)
        }
        val header = bytes(11..11)
        val paletteSize = input.readInt()
        require(paletteSize == -1 || paletteSize in 2..1282)
        val palette = if (paletteSize == -1) null else {
            require(paletteSize.toLong() <= end - input.filePointer)
            ByteArray(paletteSize).also(input::readFully)
        }
        val count = input.readInt()
        require(count in 0..2)
        val objects = List(count) {
            val width = input.readInt(); val height = input.readInt()
            require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000)
            val parts = input.readInt()
            require(parts in 1..(MAX_BYTES / 64) && parts.toLong() * 12 <= end - input.filePointer)
            val picture = ExternalPgs.FilePicture(width, height, List(parts) {
                val offset = input.readLong(); val size = input.readInt()
                require(offset >= 0 && size in 3..65538)
                ExternalPgs.FilePart(offset, size)
            })
            val reference = bytes(8..8)
            val crop = if (input.readBoolean()) {
                val x = input.readInt(); val y = input.readInt(); val w = input.readInt(); val h = input.readInt()
                require(x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= width && y.toLong() + h <= height)
                ExternalPgs.Crop(x, y, w, h)
            } else null
            ExternalPgs.FileObject(picture, reference, crop)
        }
        require(input.filePointer == end) { "Invalid SUP index record" }
        return ExternalPgs.FileDisplaySet(times[index], header, palette, objects)
    }

    override fun close() {
        try { input.close() }
        finally { check(!file.exists() || file.delete()) { "SUP event index cleanup failed" } }
    }

    companion object {
        private const val MAX_BYTES = 32 * 1024 * 1024
        private const val RESERVE = 16L * 1024 * 1024
        suspend fun create(directory: File, values: List<ExternalPgs.FileDisplaySet>): PgsDiskIndex {
            currentCoroutineContext().ensureActive()
            if (directory.usableSpace < RESERVE) throw ExternalSubtitleStorageException()
            val file = File.createTempFile("external-pgs-index-", ".bin", directory)
            var input: RandomAccessFile? = null
            try {
                val times = LongArray(values.size); val offsets = LongArray(values.size)
                DataOutputStream(file.outputStream().buffered()).use { out ->
                    fun bytes(data: ByteArray) { out.writeInt(data.size); out.write(data) }
                    values.forEachIndexed { index, value ->
                        currentCoroutineContext().ensureActive()
                        if (index % 128 == 0 && directory.usableSpace < RESERVE) throw ExternalSubtitleStorageException()
                        times[index] = value.startTimeUs; offsets[index] = out.size().toLong()
                        bytes(value.header)
                        out.writeInt(value.palette?.size ?: -1); value.palette?.let(out::write)
                        out.writeInt(value.objects.size)
                        value.objects.forEach { item ->
                            out.writeInt(item.picture.width); out.writeInt(item.picture.height)
                            out.writeInt(item.picture.parts.size)
                            item.picture.parts.forEach { part -> out.writeLong(part.offset); out.writeInt(part.length) }
                            bytes(item.reference)
                            out.writeBoolean(item.crop != null)
                            item.crop?.let { out.writeInt(it.x); out.writeInt(it.y); out.writeInt(it.width); out.writeInt(it.height) }
                        }
                        check(out.size() <= MAX_BYTES) { "SUP event index exceeds disk budget" }
                    }
                }
                currentCoroutineContext().ensureActive()
                input = RandomAccessFile(file, "r")
                return PgsDiskIndex(file, input, input.length(), times, offsets)
            } catch (failure: Throwable) {
                runCatching { input?.close() }; file.delete()
                throw failure
            }
        }
    }
}
