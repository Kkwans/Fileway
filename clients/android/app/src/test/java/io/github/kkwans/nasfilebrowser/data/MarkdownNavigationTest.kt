package io.github.kkwans.nasfilebrowser.data

import androidx.compose.ui.text.AnnotatedString
import io.github.kkwans.nasfilebrowser.ui.markdownSafetyAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.junit.Assert.*
import org.junit.Test

class MarkdownNavigationTest {
    private fun read(source: String): Pair<MarkdownDocument, MarkdownNavigation> {
        val document = (markdownPreview(decodeDocumentText(source.toByteArray())) as MarkdownPreview.Reading).document
        return document to markdownNavigation(document.state.content, document.state.node)
    }

    @Test fun sixAtxLevelsSetextAndNestedHeadingPointToTheOriginalAstAndRootItem() {
        val source = (1..6).joinToString("\n\n") { "#".repeat(it) + " Level$it" } +
            "\n\nSetext one\n===\n\nSetext two\n---\n\n> ## Nested\n\n~~~md\n# Not a heading\n~~~\n"
        val (document, navigation) = read(source)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1, 2, 2), navigation.headings.map { it.level })
        assertEquals(source, document.state.content)
        for (heading in navigation.headings) {
            val rootItem = document.state.node.children[heading.rootIndex]
            val node = rootItem.markdownDescendants().single { it.startOffset == heading.startOffset &&
                it.type in setOf(MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
                    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
                    MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2) }
            assertTrue(node.getTextInNode(source).isNotEmpty())
        }
        assertEquals("Nested", navigation.headings.last().title)
        assertFalse(navigation.headings.any { it.title == "Not a heading" })
    }

    @Test fun commonIdsUseTheObservedPinnedWebCasePunctuationWhitespaceAndDuplicateRules() {
        // Expected IDs independently captured from bundled Web Lute 1.7.6,
        // Vditor 3.10.9, SetHeadingID(true). No mirrored lexer or slug oracle.
        val source = "# Hello World\n\n# Hello World\n\n# Hello-World\n\n# HELLO World!\n\n" +
            "# 中文 标题，测试！\n\n# Café é 🙂\n\n# A+B C/D: E_F.G\n\n# A  B\n\n# ???\n"
        val (_, navigation) = read(source)
        assertEquals(listOf("Hello-World", "Hello-World-", "Hello-World--", "HELLO-World-",
            "中文-标题-测试-", "Café-e---", "A-B-C-D--E-F-G", "A--B", "---"), navigation.headings.map { it.fragment })
        assertEquals("Hello World", navigation.resolve("#Hello-World")!!.title)
        assertNull(navigation.resolve("#hello-world")) // IDs are case sensitive.
        assertEquals(navigation.headings[2], navigation.resolve("#Hello-World--"))
    }

    @Test fun rendererInlineFormattingAndReferenceLabelsAreReusedWithoutMutatingSourceOrReferenceState() {
        val source = "# **Strong** *em* ~~strike~~\n\n## [ref][target]\n\n[target]: https://example.invalid\n"
        val (document, navigation) = read(source)
        assertEquals(listOf("Strong em strike", "ref"), navigation.headings.map { it.title })
        assertEquals(listOf("Strong-em-strike", "ref"), navigation.headings.map { it.fragment })
        assertEquals("https://example.invalid", document.state.referenceLinkHandler.find("[target]"))
        assertEquals(source, document.state.content)
    }

    @Test fun exactUtf8FragmentsDecodeOnceWithoutFormDecodingOrUnicodeNormalization() {
        val (_, navigation) = read("# 中文 标题\n\n# Café\n\n# Café\n\n# A+B\n")
        assertEquals("中文 标题", navigation.resolve("#%E4%B8%AD%E6%96%87-%E6%A0%87%E9%A2%98")!!.title)
        assertEquals("Café", navigation.resolve("#Caf%C3%A9")!!.title)
        assertEquals("Café", navigation.resolve("#Cafe-")!!.title)
        assertEquals("A+B", navigation.resolve("#A-B")!!.title)
        for (fragment in listOf("#A+B", "#%2541-B", "#Cafe%CC%81", "#missing")) assertNull(fragment, navigation.resolve(fragment))
    }

    @Test fun onlyRealHeadingFragmentsReachRendererAnnotationsForInlineAndReferenceLinks() {
        val source = "# Target\n\n[inline](#Target) [reference][owned] [missing](#Absent) [reference missing][missing]\n\n" +
            "[owned]: #Target\n[missing]: #Absent\n"
        val (document, _) = read(source)
        val nodes = document.state.node.markdownDescendants().toList()
        val annotate = markdownSafetyAnnotator(document).annotate!!
        assertEquals("#Target", document.state.referenceLinkHandler.find("[owned]"))
        for (type in listOf(MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK)) {
            val target = nodes.single { node -> node.type == type &&
                (node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(source)?.toString() == "#Target" ||
                    node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getTextInNode(source)?.toString() == "[owned]") }
            assertFalse("A real internal target must use renderer link annotation",
                annotate.invoke(AnnotatedString.Builder(), source, target))
            val missing = nodes.single { node -> node.type == type &&
                (node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(source)?.toString() == "#Absent" ||
                    node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getTextInNode(source)?.toString() == "[missing]") }
            val plain = AnnotatedString.Builder()
            assertTrue(annotate.invoke(plain, source, missing))
            assertEquals(if (type == MarkdownElementTypes.INLINE_LINK) "missing" else "reference missing", plain.toAnnotatedString().text)
        }
        assertNull(document.navigation.resolve("#Absent"))
        assertNull(markdownExternalLink("#Target"))
        assertEquals(source, document.state.content)
    }

    @Test fun dangerousMalformedOrNonFragmentTargetsNeverResolveOrBecomeExternalIntents() {
        val (_, navigation) = read("# owned\n")
        for (target in listOf("owned", "other.md#owned", "/#owned", "//example.invalid/#owned", "https://example.invalid/#owned",
            "javascript:#owned", "intent:#owned", "content:#owned", "#", "##owned", "#owned?x", "#owned/x", "#owned\\x",
            "#%", "#%0", "#%GG", "#%C0%AF", "#%ED%A0%80", "#%FF", "#owned%00", "#owned%0A", "#owned%7F"))
            assertNull(target, navigation.resolve(target))
        assertNull(markdownInternalFragment("#" + "x".repeat(16_384)))
        assertEquals("owned", markdownInternalFragment("#%6Fwned"))
        assertNull(markdownExternalLink("#owned"))
    }

    @Test fun unsupportedHeadingIdsKeepExactOutlineOffsetsAndNeverGuessLaterCollisionSuffixes() {
        for (complex in listOf("code `inline` end", "A &amp; B", "custom {#owned}", "inline <b>HTML</b>", "escaped \\* text", "math $" + "x$")) {
            val (_, navigation) = read("# Before\n\n# $complex\n\n# After\n\n# Before\n")
            assertEquals("Before", navigation.headings.first().fragment)
            assertEquals(4, navigation.headings.size)
            assertTrue(navigation.headings.drop(1).all { it.fragment == null })
            assertTrue(navigation.headings.zipWithNext().all { (before, after) -> before.startOffset < after.startOffset })
            assertNull(complex, navigation.resolve("#After"))
            assertNull(complex, navigation.resolve("#Before-"))
        }
    }

    @Test fun emptyDocumentsAndExtendedSyntaxPreserveOriginalNavigationAndSavedBytes() {
        assertTrue(read("plain paragraph").second.headings.isEmpty())
        for (source in listOf("[TOC]\n\n# Title", "note[^a]\n\n[^a]: text", "---\ntitle: owned\n---\n# Title")) {
            val document = decodeDocumentText(source.toByteArray())
            assertTrue(markdownPreview(document) is MarkdownPreview.Reading)
            assertArrayEquals(source.toByteArray(), encodeEditedDocument(documentEditorText(document), document))
        }
    }
}
