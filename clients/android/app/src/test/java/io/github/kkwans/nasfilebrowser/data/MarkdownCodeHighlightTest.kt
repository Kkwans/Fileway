package io.github.kkwans.nasfilebrowser.data

import androidx.compose.ui.graphics.Color
import io.github.kkwans.nasfilebrowser.ui.MARKDOWN_CODE_HIGHLIGHT_LIMIT
import io.github.kkwans.nasfilebrowser.ui.markdownCodeContrast
import io.github.kkwans.nasfilebrowser.ui.markdownCodeLanguages
import io.github.kkwans.nasfilebrowser.ui.markdownHighlightedCode
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MarkdownCodeHighlightTest {
    @Test fun realKotlinTokensKeepUnicodeCrLfAndExactCopySelectionOffsets() {
        val source = "// 中文🐱\r\nval message = \"原文🐱 é\"\r\nprintln(message)\r\n"
        val highlighted = markdownHighlightedCode(source, "kotlin", Color(0xFFF5F5F5))
        assertEquals(source, highlighted.text)
        val keyword = source.indexOf("val")
        val quoted = source.indexOf('"')
        assertTrue(highlighted.spanStyles.any { it.start == keyword && it.end == keyword + 3 })
        assertTrue(highlighted.spanStyles.any { it.start == quoted && it.end == source.lastIndexOf('"') + 1 })
        assertEquals("原文🐱 é", highlighted.subSequence(quoted + 1, source.lastIndexOf('"')).text)
        for (span in highlighted.spanStyles) {
            assertTrue(span.start in 0..source.length && span.end in span.start..source.length)
            for (offset in listOf(span.start, span.end)) if (offset in 1 until source.length)
                assertFalse(Character.isHighSurrogate(source[offset - 1]) && Character.isLowSurrogate(source[offset]))
        }
    }

    @Test fun prismWebGrammarsAndFenceAliasesProduceActualTokenOffsets() {
        for ((language, source, keyword) in listOf(
            Triple("JS", "const answer = 42;", "const"),
            Triple("ts title=owned", "interface Owned {}", "interface"),
            Triple("py", "def owned():\n    return 42", "def"),
            Triple("java", "public class Owned {}", "public"),
            Triple("c++", "class Owned {};", "class"),
            Triple("c#", "public class Owned {}", "public"),
            Triple("rs", "fn owned() {}", "fn"),
            Triple("kt", "val owned = 42", "val"),
            Triple("json", "{\"owned\": true}", "\"owned\""),
            Triple("sql", "SELECT owned FROM records", "SELECT"),
            Triple("yml", "owned: true\n", "owned"),
            Triple("css", ".owned { color: red; }", ".owned"),
            Triple("xml", "<owned>中文🐱</owned>", "<owned"),
            Triple("HTML", "<owned>中文🐱</owned>", "<owned"),
            Triple("md", "# title\n**owned**\n", "**owned**"),
        )) {
            val highlighted = markdownHighlightedCode(source, language, Color(0xFF202124))
            assertEquals(source, highlighted.text)
            val start = source.indexOf(keyword)
            assertTrue("$language must use a real keyword token", highlighted.spanStyles.any { it.start == start && it.end == start + keyword.length })
        }
    }

    @Test fun supplementalGrammarsRetainTheSevenLanguagesMissingFromPrism() {
        for ((language, source, keyword) in listOf(
            Triple("rust", "fn owned() {}", "fn"),
            Triple("ruby", "def owned\nend", "def"),
            // Pinned SHELL colors builtins such as echo; its table does not include if/then/fi.
            Triple("bash", "if true; then\n echo owned\nfi", "echo"),
            Triple("perl", "my \$owned = 42;", "my"),
            Triple("php", "<?php function owned() {}", "function"),
            Triple("coffee", "return 42", "return"),
            Triple("ts", "interface Owned {}", "interface"),
        )) {
            val highlighted = markdownHighlightedCode(source, language, Color.White)
            assertEquals(source, highlighted.text)
            val start = source.indexOf(keyword)
            assertTrue("$language must retain its real supplemental grammar", highlighted.spanStyles.any {
                it.start == start && it.end == start + keyword.length
            })
        }
        val languages = markdownCodeLanguages()
        assertEquals("25 actual Prism canonical grammars plus seven supplemental languages", 32, languages.size)
        assertTrue(languages.containsAll(listOf("json", "markup", "sql", "yaml", "css", "markdown",
            "rust", "ruby", "shell", "perl", "php", "coffeescript", "typescript")))
    }

    @Test fun unknownUnlabelledAndExplicitPlaintextStayExactWithoutAGuessedGrammar() {
        val source = "<tag attr=\"中文🐱\"> &amp;\r\nSELECT 1; {\"key\": true}\n"
        for (language in listOf(null, "", "plaintext", "text", "unknown-owned", "DEFAULT")) {
            val highlighted = markdownHighlightedCode(source, language, Color.White)
            assertEquals(source, highlighted.text)
            assertTrue("$language must not receive a guessed grammar", highlighted.spanStyles.isEmpty())
        }
    }

    @Test fun largeBlocksFallBackWithoutTruncationAndCancellationIsNotSwallowed() {
        for (source in listOf("val x = 1" + " ".repeat(MARKDOWN_CODE_HIGHLIGHT_LIMIT), "val x = 1\n".repeat(513))) {
            val highlighted = markdownHighlightedCode(source, "kt", Color.White)
            assertEquals(source, highlighted.text); assertTrue(highlighted.spanStyles.isEmpty())
        }
        var checkpoints = 0
        val expected = CancellationException("owned-cancel")
        try {
            markdownHighlightedCode("val owned = 42", "kt", Color.White) { if (++checkpoints == 2) throw expected }
            fail("Cancellation after engine work must prevent publication")
        } catch (actual: CancellationException) { assertSame(expected, actual) }
    }

    @Test fun themeSwitchKeepsAllTokenColorsReadableOnTheActualPanel() {
        val source = "// comment\nval owned = \"text\" + 42\n"
        val backgrounds = listOf(Color(0xFFF1F3F4), Color(0xFF202124), Color(0xFF536275))
        val results = backgrounds.map { background ->
            markdownHighlightedCode(source, "kotlin", background).also { highlighted ->
                assertEquals(source, highlighted.text)
                assertTrue(highlighted.spanStyles.size > 1)
                highlighted.spanStyles.forEach { assertTrue("Token contrast >=4.5", markdownCodeContrast(it.item.color, background) >= 4.5f) }
            }
        }
        assertNotEquals(results[0].spanStyles, results[1].spanStyles)
        for (background in backgrounds) {
            val highlighted = markdownHighlightedCode("fn owned() { let message = \"text\"; }", "rust", background)
            assertTrue(highlighted.spanStyles.size > 1)
            highlighted.spanStyles.forEach { assertTrue(markdownCodeContrast(it.item.color, background) >= 4.5f) }
        }
    }
}
