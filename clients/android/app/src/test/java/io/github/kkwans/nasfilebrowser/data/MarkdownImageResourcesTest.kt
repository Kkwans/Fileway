package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class MarkdownImageResourcesTest {
    private val document = ResourceRef("/�/笔记/README.md", "/%FF/%E7%AC%94%E8%AE%B0/README.md", "README.md", false, "text", 50, "owned-version")
    private fun wire(destination: String, source: ResourceRef = document) =
        (resolveMarkdownImage(source, destination) as MarkdownImageResolution.Nas).wirePath

    @Test fun relativeRootAndParentImagesRetainOpaqueAncestorBytes() {
        assertEquals("/%FF/%E7%AC%94%E8%AE%B0/images/a.png", wire("images/a.png"))
        assertEquals("/%FF/shared/a.png", wire("../shared/a.png"))
        assertEquals("/images/a.png", wire("/images/a.png"))
        assertEquals("/%FF/%E7%AC%94%E8%AE%B0/a.png", wire("./images/../a.png"))
        assertEquals("/%FF/a.png", wire("%2e%2e/a.png"))
    }

    @Test fun unicodeSpacesPlusAndLiteralEscapesAreEncodedExactlyOnce() {
        assertEquals("/%FF/%E7%AC%94%E8%AE%B0/%E4%B8%AD%E6%96%87%20%2B.png", wire("中文 +.png"))
        assertEquals(wire("中文 +.png"), wire("%e4%b8%ad%e6%96%87%20%2b.png"))
        assertEquals("/%FF/%E7%AC%94%E8%AE%B0/a%252F%3F%23.png", wire("a%252F%3F%23.png"))
        assertEquals("/%FF/%E7%AC%94%E8%AE%B0/%252e%252e/a.png", wire("%252e%252e/a.png"))
        assertEquals("/%FF/%E7%AC%94%E8%AE%B0/%FE.png", wire("%fe.png"))
    }

    @Test fun encodedSeparatorsNulMalformedEscapesAndRootEscapeAreRejected() {
        for (destination in listOf("a%2fb.png", "a%2Fb.png", "a%00.png", "a%", "a%0g.png", "../../../a.png",
            "/../a.png", "/%2e%2e/a.png", "images//a.png", "images/", "", "a.png?raw=1", "a.png#part", "a\\b.png", "a\u0000.png"))
            assertTrue(destination, resolveMarkdownImage(document, destination) is MarkdownImageResolution.Unavailable)
    }

    @Test fun schemeDestinationsAndLocalDocumentsNeverBorrowTheCurrentNasAccount() {
        for (destination in listOf("https://fixture.invalid/a.png", "http://127.0.0.1/a.png", "//fixture.invalid/a.png",
            "data:image/png,owned", "content://owned/a", "file:///a.png", "javascript:owned"))
            assertTrue(destination, resolveMarkdownImage(document, destination) is MarkdownImageResolution.Unavailable)
        assertTrue(resolveMarkdownImage(document.copy(downloadId = "owned-download"), "a.png") is MarkdownImageResolution.Unavailable)
        assertTrue(resolveMarkdownImage(document.copy(wirePath = ""), "a.png") is MarkdownImageResolution.Unavailable)
        assertTrue(resolveMarkdownImage(document.copy(path = "/other/README.md", wirePath = "/one/README.md"), "a.png") is MarkdownImageResolution.Unavailable)
    }

    @Test fun rootDocumentAndUtf8FallbackUseTheSameSharedWireContract() {
        val root = ResourceRef("/README.md", "", "README.md", false, "text", 10)
        assertEquals("/a.png", wire("a.png", root))
        assertTrue(resolveMarkdownImage(root, "../a.png") is MarkdownImageResolution.Unavailable)
        assertEquals("/images/a%2Bb.png", wire("images/a+b.png", root))
    }

    @Test fun inlineFullAndShortcutReferenceImagesUseTheExistingAstAndOriginalDestinations() {
        val source = "![Inline](images/a.png)\n\n![Reference][owned]\n\n![shortcut]\n\n[owned]: ../shared/b.png\n[shortcut]: /images/c.png\n"
        val parsed = (markdownPreview(decodeDocumentText(source.toByteArray())) as MarkdownPreview.Reading).document
        val images = markdownImageReferences(parsed)
        assertEquals(listOf("images/a.png", "../shared/b.png", "/images/c.png"), images.map { it.destination })
        assertEquals(listOf("Inline", "Reference", "shortcut"), images.map { it.alt })
        assertEquals(images.size, images.map { it.startOffset }.distinct().size)
        assertEquals(source, parsed.state.content)
    }
}
