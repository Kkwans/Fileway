package io.github.kkwans.nasfilebrowser.data

import androidx.compose.ui.text.AnnotatedString
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.ui.markdownSafetyAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.getTextInNode
import org.junit.Assert.*
import org.junit.Test

class MarkdownExtensionsTest {
    private fun read(source: String): MarkdownDocument =
        (markdownPreview(decodeDocumentText(source.toByteArray())) as MarkdownPreview.Reading).document

    @Test fun officialFootnotesNumberByFirstReferenceAndNormalizeRepeatedUnicodeLabels() {
        val source = "Second[^二] first[^One] again[^ONE] unknown[^missing].\n\n" +
            "[^One]: First body.\n\n[^二]: Second body.\n\n[^unused]: Retained unused body.\n"
        val document = read(source)
        assertEquals(source, document.state.content)
        assertEquals(listOf(1, 2, 2), document.extensions.references.values.sortedBy { it.startOffset }.map { it.number })
        assertEquals(listOf("二", "One", "unused"), document.extensions.footnotes.map { it.label })
        assertEquals(listOf(1, 2, null), document.extensions.footnotes.map { it.number })
        val first = document.extensions.footnotes[1]
        assertEquals(2, first.references.size)
        assertEquals("First body.", first.body!!.state.content)
        val marker = document.state.node.markdownDescendants().first { it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK && it.getTextInNode(source).toString() == "[^missing]" }
        val builder = AnnotatedString.Builder()
        assertTrue(markdownSafetyAnnotator(document).annotate!!.invoke(builder, source, marker))
        assertEquals("[^missing]", builder.toAnnotatedString().text)
        assertTrue(document.extensions.footnotes.last().raw.contains("Retained unused body"))
    }

    @Test fun multilineDefinitionReusesRichBodyParsingWithoutLosingItsIndentationOrOriginalSource() {
        val source = "[^note]\n\n[^note]: First **bold** line.\n    Continued *emphasis*.\n\n" +
            "    - First list item\n    - Second list item\n\n    ```kotlin\n    println(1)\n    ```\n"
        val document = read(source)
        val note = document.extensions.footnotes.single()
        val body = note.body!!
        assertEquals("First **bold** line.\nContinued *emphasis*.\n\n- First list item\n- Second list item\n\n```kotlin\nprintln(1)\n```", body.state.content)
        val types = body.state.node.markdownDescendants().map { it.type }.toList()
        assertTrue(MarkdownElementTypes.STRONG in types)
        assertTrue(MarkdownElementTypes.EMPH in types)
        assertTrue(MarkdownElementTypes.UNORDERED_LIST in types)
        assertTrue(MarkdownElementTypes.CODE_FENCE in types)
        assertTrue(note.raw.contains("    println(1)"))
        assertEquals(source.indexOf("[^note]:"), note.startOffset)
        assertEquals(source, document.state.content)
    }

    @Test fun duplicateAndNestedDefinitionsRemainVisibleAsLocalOriginalText() {
        val source = "Known[^one].\n\n[^one]: First wins.\n\n[^ONE]: Duplicate remains.\n\n" +
            "[^outer]: Outer.\n\n    [^inner]: Nested retained.\n"
        val document = read(source)
        assertEquals(1, document.extensions.references.values.single().number)
        assertEquals("First wins.", document.extensions.footnotes.first().body!!.state.content)
        val duplicate = document.extensions.footnotes.single { it.duplicate }
        assertNull(duplicate.number); assertNull(duplicate.body)
        assertTrue(duplicate.raw.contains("Duplicate remains"))
        assertTrue(document.extensions.footnotes.any { it.raw.contains("Nested retained") && it.body == null })
        assertEquals(source, document.state.content)
    }

    @Test fun closedYamlKeepsExactCrLfAndTagsWhileAnUnclosedHeaderCannotSwallowTheBody() {
        for (closing in listOf("---", "...")) {
            val yaml = "---\r\ntitle: Owned\r\nunsafe: !!java/object:example.Invalid {}\r\n$closing\r\n"
            val source = yaml + "\r\n# Body\r\n\r\nOwned body.\r\n"
            val document = read(source)
            assertEquals(yaml, document.extensions.frontMatter!!.raw)
            assertEquals(source.indexOf("# Body"), document.navigation.headings.single().startOffset)
            assertEquals(source, document.state.content)
        }
        val unclosed = "---\ntitle: Never closed\n\n# Retained\n\nOwned body."
        val document = read(unclosed)
        assertNull(document.extensions.frontMatter)
        assertEquals("Retained", document.navigation.headings.last().title)
        assertEquals(unclosed, document.state.content)
        assertTrue(document.state.node.markdownDescendants().any { it.getTextInNode(unclosed).contains("Owned body") })
    }

    @Test fun onlyAnIndependentRootParagraphIsATocAndItsHeadingOffsetsStillAddressTheOriginalDocument() {
        val source = "---\ntitle: [TOC]\n---\n\n[TOC]\n\n# Start\n\nInline [TOC] stays text.\n\n" +
            "> [TOC]\n\n- [TOC]\n\n`[TOC]`\n\n```md\n[TOC]\n# Fake\n```\n\n## Target\n"
        val document = read(source)
        assertEquals(setOf(source.indexOf("[TOC]", source.indexOf("---", 3) + 3)), document.extensions.tocOffsets)
        assertEquals(listOf("Start", "Target"), document.navigation.headings.map { it.title })
        assertEquals(source.indexOf("## Target"), document.navigation.headings.last().startOffset)
        for (heading in document.navigation.headings)
            assertTrue(document.state.node.children[heading.rootIndex].getTextInNode(source).contains(heading.title))
        assertEquals(source, document.state.content)
    }

    @Test fun referencesInsideFootnotesUseIndependentOffsetsAndDocumentDefinitionsKeepLinkAndImagePolicies() {
        val source = "First[^a].\n\n# Target\n\n[owned]: https://example.invalid/owned\n[image]: ../owned.png\n\n" +
            "[^a]: Body[^b] [safe][owned] [unsafe](javascript:owned) ![Owned][image].\n\n[^b]: Second body.\n"
        val document = read(source)
        val note = document.extensions.footnotes.first()
        val body = note.body!!
        val nested = body.extensions.references.values.single()
        assertEquals("Body[^b] [safe][owned] [unsafe](javascript:owned) ![Owned][image].", body.state.content)
        assertEquals(2, nested.number); assertEquals(note.startOffset, nested.scope)
        assertNotEquals(document.extensions.references.values.single().tag, nested.tag)
        assertEquals("https://example.invalid/owned", body.state.referenceLinkHandler.find("[owned]"))
        val safe = body.state.node.markdownDescendants().single { it.type == MarkdownElementTypes.FULL_REFERENCE_LINK &&
            it.getTextInNode(body.state.content).toString() == "[safe][owned]" }
        assertFalse(markdownSafetyAnnotator(body).annotate!!.invoke(AnnotatedString.Builder(), body.state.content, safe))
        val image = markdownExtensionImageReferences(body).single()
        assertEquals("../owned.png", image.destination)
        val file = ResourceRef("/docs/notes.md", "/docs/notes.md", "notes.md", false, "text", source.length.toLong())
        assertEquals(MarkdownImageResolution.Nas("/owned.png"), resolveMarkdownImage(file, image.destination!!))
        val bad = body.state.node.markdownDescendants().first { it.type == MarkdownElementTypes.INLINE_LINK && it.getTextInNode(body.state.content).startsWith("[unsafe]") }
        val builder = AnnotatedString.Builder()
        assertTrue(markdownSafetyAnnotator(body).annotate!!.invoke(builder, body.state.content, bad))
        assertEquals("unsafe", builder.toAnnotatedString().text)
        assertNull(markdownExternalLink("fileway-internal:${nested.tag}"))
    }

    @Test fun extensionRenderingPreservesTheCompleteOriginalBomBytesAndEditorText() {
        val source = "---\r\ntitle: 中文🙂\r\n---\r\n\r\n[TOC]\r\n\r\n# 标题\r\n\r\nNote[^a].\r\n\r\n[^a]: 脚注正文\r\n\t中文续行[^b]\r\n\r\n[^b]: 第二脚注\r\n"
        for ((encoding, bom) in listOf("UTF-8" to byteArrayOf(-17, -69, -65), "UTF-16LE" to byteArrayOf(-1, -2), "UTF-16BE" to byteArrayOf(-2, -1))) {
            val original = bom + source.toByteArray(charset(encoding))
            val text = decodeDocumentText(original)
            val document = (markdownPreview(text) as MarkdownPreview.Reading).document
            assertEquals(source, document.state.content)
            assertEquals(source.replace("\r\n", "\n"), documentEditorText(text))
            assertArrayEquals(original, encodeEditedDocument(documentEditorText(text), text))
            val note = document.extensions.footnotes.first()
            assertEquals(1, note.number)
            assertEquals(source.indexOf("[^a]"), document.extensions.references.values.single().startOffset)
            val nested = note.body!!.extensions.references.values.single()
            assertEquals(source.indexOf("[^b]"), note.source!!.original(nested.startOffset))
        }
    }

    @Test fun extensionBudgetsAreStillBoundedAndCodeMarkersNeverBecomeFootnotes() {
        val code = "```md\n[^code]: literal\n[TOC]\n---\ntitle: literal\n---\n```\n"
        val document = read(code)
        assertTrue(document.extensions.footnotes.isEmpty()); assertTrue(document.extensions.references.isEmpty())
        assertTrue(document.extensions.tocOffsets.isEmpty()); assertNull(document.extensions.frontMatter)
        val tooMany = (1..257).joinToString("\n\n") { "[^$it]: Body $it." }
        assertTrue(markdownPreview(decodeDocumentText(tooMany.toByteArray())) is MarkdownPreview.SourceOnly)
        val large = "# Start\n" + "x".repeat(MARKDOWN_READING_LIMIT.toInt())
        val decoded = decodeDocumentText(large.toByteArray())
        assertTrue(markdownPreview(decoded) is MarkdownPreview.SourceOnly)
        assertEquals(large, decoded.text)
    }

    @Test fun formulaScopesReuseActualAstNodesAndMapCrLfFootnotesBackToOriginalOffsets() {
        val dollar = '$'
        val source = ("# 标题 ${dollar}x^2${dollar}\n\nPlain ${dollar}a+b${dollar}.\n\n" +
            "| Formula |\n| --- |\n| ${dollar}c+d${dollar} |\n\n[^one]\n\n" +
            "[^one]: 中文 ${dollar}\\sqrt{4}${dollar}.\n\n```tex\n${dollar}not-math${dollar}\n```\n").replace("\n", "\r\n")
        val document = read(source)
        val scopes = markdownMathScopes(document)
        val formulas = scopes.values.flatMap { it.values }
        assertEquals(listOf("x^2", "a+b", "c+d", "\\sqrt{4}"), formulas.sortedBy { it.sourceOrder }.map { it.tex })
        assertEquals(formulas.size, formulas.map { it.key }.toSet().size)
        assertEquals(source.indexOf("${dollar}\\sqrt{4}${dollar}"), formulas.single { it.tex == "\\sqrt{4}" }.sourceOrder)
        assertTrue(formulas.none { it.source.contains("not-math") })
        val formula = scopes.getValue(null).values.first()
        val node = document.state.node.markdownDescendants().single { it.startOffset == scopes.getValue(null).entries.first().key &&
            it.type == org.intellij.markdown.flavours.gfm.GFMElementTypes.INLINE_MATH }
        val result = MarkdownMathResult.Rendered("<svg/>", 10f, 10f, 8f, 2f)
        val builder = AnnotatedString.Builder()
        assertTrue(markdownSafetyAnnotator(document, mathFormulas = scopes.getValue(null), mathResults = mapOf(formula.key to result))
            .annotate!!.invoke(builder, source, node))
        assertEquals(formula.source, builder.toAnnotatedString().text)
        assertEquals(formula.key, builder.toAnnotatedString().getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, formula.source.length).single().item)
        val pending = AnnotatedString.Builder()
        markdownSafetyAnnotator(document, mathFormulas = scopes.getValue(null)).annotate!!.invoke(pending, source, node)
        assertEquals(formula.source, pending.toAnnotatedString().text)
    }
}
