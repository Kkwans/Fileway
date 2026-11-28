package io.github.kkwans.fileway.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvUpdateDirectoryTest {
    @Test
    fun defaultAndCustomDirectoriesAreNormalized() {
        assertEquals("Documents/Fileway", normalizeTvUpdateDirectory("Documents/Fileway"))
        assertEquals("Documents/Fileway", normalizeTvUpdateDirectory(" /Documents/Fileway/ "))
        assertEquals("Documents/My TV Updates", normalizeTvUpdateDirectory("Documents/My TV Updates"))
    }

    @Test
    fun unsafeOrEmptyDirectoriesAreRejected() {
        assertNull(normalizeTvUpdateDirectory(""))
        assertNull(normalizeTvUpdateDirectory("Documents/../Download"))
        assertNull(normalizeTvUpdateDirectory("/etc"))
        assertNull(normalizeTvUpdateDirectory("Documents\\Fileway"))
    }
}
