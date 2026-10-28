package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.data.wireQueryPath
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaQueryCompatibilityTest {
    @Test fun legacyQueryKeepsTheFileNameInsteadOfSplittingItsDelimiters() {
        val path = "/电影/A & B+50%#?.mkv"
        val wire = "/%E7%94%B5%E5%BD%B1/A%20&%20B+50%25%23%3F.mkv"
        val value = wireQueryPath(path, wire)
        assertEquals(path, URLDecoder.decode(value, "UTF-8"))
        assertEquals(false, value.contains('&'))
        assertEquals(false, value.contains('+'))
    }
    @Test fun wireOctetsAreNotDecodedAndReencodedThroughUnicode() {
        assertEquals("/%FF/raw%2525%2B%26%3D.mkv", wireQueryPath("/lossy replacement", "/%FF/raw%2525+&=.mkv"))
    }
    @Test fun ordinaryFallbackStillProducesARealLegacyPathParameter() {
        assertEquals("/a b+c.mkv", URLDecoder.decode(wireQueryPath("/a b+c.mkv", ""), "UTF-8"))
    }
}
