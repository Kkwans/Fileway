package io.github.kkwans.nasfilebrowser.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SubtitleFontMetadataTest {
    private data class Name(val id: Int, val value: String, val language: Int = 0x0409)

    private fun font(names: List<Name>): ByteArray {
        val strings = names.map { it.value.toByteArray(Charsets.UTF_16BE) }
        val storage = 6 + names.size * 12
        val length = storage + strings.sumOf { it.size }
        val out = ByteBuffer.allocate(28 + length).order(ByteOrder.BIG_ENDIAN)
        out.putInt(0x00010000).putShort(1).putShort(0).putShort(0).putShort(0)
        out.putInt(0x6e616d65).putInt(0).putInt(28).putInt(length)
        out.putShort(0).putShort(names.size.toShort()).putShort(storage.toShort())
        var offset = 0
        names.forEachIndexed { index, name ->
            out.putShort(3).putShort(1).putShort(name.language.toShort()).putShort(name.id.toShort())
                .putShort(strings[index].size.toShort()).putShort(offset.toShort())
            offset += strings[index].size
        }
        strings.forEach { out.put(it) }
        return out.array()
    }

    private fun simple(family: String) = font(listOf(Name(1, family), Name(2, "Regular")))

    @Test fun typographicAndLocalizedNamesAreReadWithoutChangingTheSharedBufferCursorOrOrder() {
        val source = ByteBuffer.wrap(font(listOf(Name(1, "Legacy Sans"), Name(2, "Regular"),
            Name(16, "Owned Sans"), Name(17, "Medium"), Name(16, "真实家族", 0x0804), Name(17, "中等", 0x0804))))
            .order(ByteOrder.LITTLE_ENDIAN).apply { position(5) }
        val result = subtitleFontNames(source, 0)!!
        assertEquals("真实家族", result.family)
        assertEquals("中等", result.subfamily)
        assertEquals("Owned Sans", result.canonicalFamily)
        assertEquals(5, source.position())
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order())
    }

    @Test fun eachCollectionFaceUsesItsOwnDirectoryAndFileRelativeNameOffsets() {
        val first = simple("First Sans")
        val second = simple("第二家族")
        val offsets = listOf(32, 32 + first.size)
        val ttc = ByteBuffer.allocate(32 + first.size + second.size).order(ByteOrder.BIG_ENDIAN)
        ttc.putInt(0x74746366).putInt(0x00010000).putInt(2).putInt(offsets[0]).putInt(offsets[1])
        listOf(first, second).forEachIndexed { index, bytes ->
            ttc.position(offsets[index]); ttc.put(bytes)
            ttc.putInt(offsets[index] + 20, offsets[index] + 28)
        }
        assertEquals("First Sans", subtitleFontNames(ttc, 0)!!.family)
        assertEquals("第二家族", subtitleFontNames(ttc, 1)!!.family)
        assertNull(subtitleFontNames(ttc, -1)); assertNull(subtitleFontNames(ttc, 2))
        assertNull(subtitleFontNames(ByteBuffer.wrap(first), 1))
    }

    @Test fun truncationUnsignedOffsetsBadLengthsAndMalformedNamesFailClosed() {
        val bytes = simple("Owned Sans")
        for (size in 0 until bytes.size) assertNull("truncated at $size", subtitleFontNames(ByteBuffer.wrap(bytes.copyOf(size)), 0))
        fun corrupt(change: (ByteBuffer) -> Unit) {
            val bad = ByteBuffer.wrap(bytes.copyOf()).order(ByteOrder.BIG_ENDIAN)
            change(bad)
            assertNull(subtitleFontNames(bad, 0))
        }
        corrupt { it.putInt(20, -1) } // unsigned table offset must not wrap into a valid index
        corrupt { it.putInt(24, Int.MAX_VALUE) }
        corrupt { it.putShort(4, 257) }
        corrupt { it.putShort(28, 2) }
        corrupt { it.putShort(30, 513) }
        corrupt { it.putShort(32, 6) } // strings cannot overlap name records
        corrupt { it.putShort(42, 3) } // odd UTF-16 byte length
        corrupt { it.putShort(44, (-1).toShort()) }
        corrupt { it.putShort(58, 0xd800.toShort()) } // unpaired UTF-16 surrogate
        assertNull(subtitleFontNames(ByteBuffer.wrap(font(listOf(Name(1, " "), Name(2, "Regular")))), 0))
    }

    @Test fun commonTextFamiliesAreSelectedFromMetadataAndDeclaredLocalesNotFallbackGlyphs() {
        fun names(family: String, style: String = "Regular") = SubtitleFontNames(family, style, family)
        assertTrue(subtitleTextFamily(names("Roboto"), listOf(Locale.ROOT)))
        assertTrue(subtitleTextFamily(names("Source Han Sans SC"), emptyList()))
        assertTrue(subtitleTextFamily(names("思源黑体"), emptyList()))
        assertTrue(subtitleTextFamily(names("Owned Latin"), listOf(Locale.ENGLISH)))
        assertFalse(subtitleTextFamily(names("Noto Sans Arabic"), listOf(Locale.forLanguageTag("und-Arab"))))
        assertFalse(subtitleTextFamily(names("Noto Sans Symbols"), emptyList()))
        assertFalse(subtitleTextFamily(names("Noto Sans Devanagari"), listOf(Locale.forLanguageTag("hi"))))
        assertFalse(subtitleTextFamily(names("Owned Sans", "Condensed"), listOf(Locale.ENGLISH)))
        assertFalse(subtitleTextFamily(names("Unidentified Script"), emptyList()))
    }
}
