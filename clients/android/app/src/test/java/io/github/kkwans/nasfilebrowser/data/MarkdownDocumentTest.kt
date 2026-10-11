package io.github.kkwans.nasfilebrowser.data

import androidx.compose.ui.text.AnnotatedString
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.ui.markdownSafetyAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentTest {
    private fun read(text: String): MarkdownDocument = (markdownPreview(decodeDocumentText(text.toByteArray())) as MarkdownPreview.Reading).document

    @Test fun loopbackAliasesUnspecifiedAndAmbiguousNumericHostsNeverBecomeExternalLinks() {
        val blocked = listOf("localhost.", "a.localhost.", "LOCALHOST", "127.12.34.56", "127.0.0.1.",
            "2130706433", "0x7f000001", "017700000001", "0177.0.0.1", "0x7f.0.0.1", "127.1", "127.0.1",
            "0", "0x0", "00.0.0.0", "0.0.0.0.", "192.168.001.1", "192.168.1", "4294967296", "example.123",
            "[::1]", "[0:0:0:0:0:0:0:1]", "[0000:0000:0000:0000:0000:0000:0000:0001]",
            "[::]", "[0:0:0:0:0:0:0:0]", "[::ffff:127.0.0.1]", "[::FFFF:7f00:1]",
            "[0:0:0:0:0:ffff:7f12:3456]", "[::127.0.0.1]", "[::7f00:1]", "[::ffff:0.0.0.0]",
            "[fe80::1%25owned]")
        for (host in blocked) for (scheme in listOf("http", "https"))
            assertNull(host, markdownExternalLink("$scheme://$host:8080/owned?q=one#part"))
    }

    @Test fun ordinaryPublicAndIntranetLinksKeepTheirPathEscapesQueryAndFragment() {
        for (host in listOf("example.invalid", "localhost.example.invalid", "nas", "nas.lan", "192.168.1.25", "10.0.0.8",
            "172.16.1.4", "100.64.0.8", "8.8.8.8", "[2001:db8::1]", "[fd00::1234]", "[::ffff:192.168.1.25]")) {
            val link = "https://$host:8443/a%2Fb?q=a%26b#section"
            assertEquals(host, link, markdownExternalLink(link))
        }
    }

    @Test fun referenceAndAutolinkLoopbacksAreInactiveWhileTheOriginalSourceRemainsIntact() {
        val source = "[owned][target]\n\n[target]: http://2130706433/owned\n\n<http://[0:0:0:0:0:0:0:1]/owned>"
        val document = read(source)
        assertTrue(document.hasInactiveLinks)
        assertEquals(source, document.state.content)
        val reference = document.state.node.markdownDescendants().first { it.type == MarkdownElementTypes.FULL_REFERENCE_LINK }
        val label = reference.findChildOfType(MarkdownElementTypes.LINK_LABEL)!!.getUnescapedTextInNode(source)
        assertEquals("", document.state.referenceLinkHandler.find(label))
        val builder = AnnotatedString.Builder()
        assertTrue(markdownSafetyAnnotator(document).annotate!!.invoke(builder, source, reference))
        assertEquals("owned", builder.toAnnotatedString().text)
    }

    @Test fun allHeadingLevelsSetextAndUppercaseTaskCompletionRetainTheirMeaning() {
        val source = (1..6).joinToString("\n\n") { "#".repeat(it) + " Heading $it" } +
            "\n\nSetext one\n===\n\nSetext two\n---\n\n- [x] lower\n- [X] upper\n- [ ] waiting\n"
        val document = read(source)
        val nodes = document.state.node.markdownDescendants().toList()
        for (type in listOf(MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2)) assertTrue(type.toString(), nodes.any { it.type == type })
        assertEquals(listOf(true, true, false), nodes.filter { it.type == GFMTokenTypes.CHECK_BOX }.map { markdownTaskChecked(source, it) })
        assertEquals(source, document.state.content)
    }

    @Test fun upstreamGfmParserRetainsHeadingsEmphasisListsQuotesCodeTablesAndTaskNodes() {
        val tick = 96.toChar()
        val source = listOf("# Title **bold**", "",
            "A *slanted* and **strong** and ~~removed~~ paragraph with " + tick + "a < b" + tick + ".", "",
            "> Quoted", ">", "> - [x] complete", "> - [ ] waiting", "",
            "7. seventh", "   - nested", "", "~~~kotlin example", "println(\"<tag>\")", "~~~", "",
            "| Name | Value |", "| :--- | ---: |", "| a\\|b | **two** |").joinToString("\n")
        val result = read(source)
        val nodes = result.state.node.markdownDescendants().toList()
        assertEquals(source, result.state.content)
        assertTrue(nodes.any { it.type == MarkdownElementTypes.ATX_1 })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.STRONG && it.getTextInNode(source).contains("strong") })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.EMPH && it.getTextInNode(source).contains("slanted") })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.CODE_SPAN && it.getTextInNode(source).contains("a < b") })
        assertTrue(nodes.any { it.type == GFMElementTypes.STRIKETHROUGH })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.BLOCK_QUOTE })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.ORDERED_LIST && it.getTextInNode(source).startsWith("7.") })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.UNORDERED_LIST })
        assertEquals(listOf("[x]", "[ ]"), nodes.filter { it.type == GFMTokenTypes.CHECK_BOX }.map { it.getTextInNode(source).toString().trim() })
        assertTrue(nodes.single { it.type == MarkdownElementTypes.CODE_FENCE }.getTextInNode(source).contains("println"))
        val table = nodes.single { it.type == GFMElementTypes.TABLE }
        assertEquals(listOf(MarkdownAlignment.LEFT, MarkdownAlignment.RIGHT), markdownTableAlignments(source, table))
        assertTrue(table.markdownDescendants().any { it.type == GFMTokenTypes.CELL && it.getUnescapedTextInNode(source).trim() == "a|b" })
    }

    @Test fun securityAdapterKeepsHtmlLiteralImagesInactiveAndRejectsUnsafeNativeLinkAnnotations() {
        val source = """
            <script>alert('owned')</script>

            Inline <img src="https://fixture.invalid/track" onerror="alert(1)"> HTML.

            ![Owned alt](https://fixture.invalid/private.png)

            [safe](https://example.invalid/doc?q=one#part) [bad](javascript:alert%281%29) [local](../file.md)
        """.trimIndent()
        val result = read(source)
        val nodes = result.state.node.markdownDescendants().toList()
        assertTrue(result.hasLiteralHtml); assertTrue(result.hasImages); assertTrue(result.hasInactiveLinks)
        assertTrue(nodes.any { it.type == MarkdownElementTypes.HTML_BLOCK && it.getTextInNode(source).contains("<script>") })
        assertTrue(markdownImageLabel(source, nodes.first { it.type == MarkdownElementTypes.IMAGE }).contains("Owned alt"))
        val annotate = markdownSafetyAnnotator(result).annotate!!
        val unsafe = nodes.first { it.type == MarkdownElementTypes.INLINE_LINK &&
            it.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(source)?.startsWith("javascript:") == true }
        val builder = AnnotatedString.Builder()
        assertTrue(annotate.invoke(builder, source, unsafe))
        assertEquals("bad", builder.toAnnotatedString().text)
        val safe = nodes.first { it.type == MarkdownElementTypes.INLINE_LINK &&
            it.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(source)?.startsWith("https:") == true }
        assertFalse(annotate.invoke(AnnotatedString.Builder(), source, safe))
        for (value in listOf("javascript:alert(1)", "data:text/html,x", "intent://example/#Intent;end", "file:///etc/passwd",
            "content://owned/file", "//example.invalid/path", "../notes.md", "#title", "https://user:pass@example.invalid/",
            "https://example.invalid/\nnext", "http://127.0.0.1:1234/stream/secret", "http://[::1]/stream/secret", "http://localhost/"))
            assertNull(value, markdownExternalLink(value))
        assertEquals("https://example.invalid/%E4%B8%AD%E6%96%87", markdownExternalLink("https://example.invalid/中文"))
    }

    @Test fun exactByteLimitAndComplexityFallbackKeepCompleteSearchableEditableSource() {
        val atLimit = decodeDocumentText(" ".repeat(MARKDOWN_READING_LIMIT.toInt()).toByteArray())
        val boundary = markdownPreview(atLimit)
        assertFalse(boundary is MarkdownPreview.SourceOnly && boundary.reason.contains("2 MiB"))
        val source = "# Start\n" + "x".repeat(MARKDOWN_READING_LIMIT.toInt()) + "\ntail-owned"
        val large = decodeDocumentText(source.toByteArray())
        val fallback = markdownPreview(large) as MarkdownPreview.SourceOnly
        assertTrue(fallback.reason.contains("2 MiB"))
        assertEquals(source, large.blocks.joinToString("") { it.text })
        assertEquals(source.indexOf("tail-owned"), searchDocumentText(large.text, "tail-owned").matches.single().start)
        assertArrayEquals(source.toByteArray(), encodeEditedDocument(documentEditorText(large), large))
        val complex = decodeDocumentText(("|" + "c|".repeat(17) + "\n|" + "---|".repeat(17) + "\n").toByteArray())
        assertTrue(markdownPreview(complex) is MarkdownPreview.SourceOnly)
        assertTrue(markdownPreview(decodeDocumentText("a".repeat(16_385).toByteArray())) is MarkdownPreview.SourceOnly)
        assertTrue(markdownPreview(decodeDocumentText("- short\n".repeat(257).toByteArray())) is MarkdownPreview.SourceOnly)
    }

    @Test fun footnotesTocAndYamlUseReadingWithoutChangingTheOriginalSource() {
        for (source in listOf("Footnote[^one]\n\n[^one]: Keep this body", "[TOC]\n\n# Heading", "---\ntitle: Owned\n---\n\n# Heading")) {
            val document = decodeDocumentText(source.toByteArray())
            assertTrue(markdownPreview(document) is MarkdownPreview.Reading)
            assertEquals(source, document.text)
        }
    }

    @Test fun allMarkdownExtensionsUseTextReaderAndBomRoundTripsAreUnchangedByRendering() {
        for (extension in listOf("md", "markdown", "mdown", "mkd")) {
            val name = "README." + extension
            val file = ResourceRef("/�/" + name, "/%FF/" + name, name, false, "", 20)
            assertTrue(isMarkdownDocument(file))
            assertEquals(DocumentPreviewKind.TEXT, documentPreviewKind(file))
            assertEquals(file.wirePath, documentWirePath(file))
        }
        val original = "# 中文\r\n\r\n**bold** 🙂\r\n"
        for ((encoding, bom) in listOf("UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            val bytes = bom + original.toByteArray(charset(encoding))
            val decoded = decodeDocumentText(bytes)
            assertEquals(bytes.size.toLong(), decoded.byteSize)
            assertTrue(markdownPreview(decoded) is MarkdownPreview.Reading)
            assertEquals(original, decoded.text)
            assertArrayEquals(bytes, encodeEditedDocument(documentEditorText(decoded), decoded))
        }
    }
}
