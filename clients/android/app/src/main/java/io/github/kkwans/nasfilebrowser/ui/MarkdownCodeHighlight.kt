package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import dev.snipme.highlights.model.SyntaxTheme
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal const val MARKDOWN_CODE_HIGHLIGHT_LIMIT = 16_384
private const val MAX_HIGHLIGHT_LINES = 512
private const val MAX_HIGHLIGHT_SPANS = 8192
private const val MAX_PRISM_NODES = 32768
private const val MAX_PRISM_DEPTH = 64

/** These seven grammars are absent from the fixed Prism4j 2.0.0 bundle. */
private val supplementalLanguages = mapOf(
    "rust" to SyntaxLanguage.RUST, "ruby" to SyntaxLanguage.RUBY, "shell" to SyntaxLanguage.SHELL,
    "perl" to SyntaxLanguage.PERL, "php" to SyntaxLanguage.PHP, "coffeescript" to SyntaxLanguage.COFFEESCRIPT,
    "typescript" to SyntaxLanguage.TYPESCRIPT,
)

/** Canonical names from the generated upstream locator, plus the real supplemental enum entries. */
internal fun markdownCodeLanguages(): Set<String> = MarkdownCodeGrammarFactory.languages() + supplementalLanguages.keys

/** Fence aliases only; the published engine owns all tokenization. */
private fun codeLanguage(info: String?): String? {
    val name = info?.trim()?.takeWhile { !it.isWhitespace() }?.lowercase(Locale.ROOT) ?: return null
    return when (name) {
        "kt", "kts" -> "kotlin"
        "js", "node", "nodejs" -> "javascript"
        "ts" -> "typescript"
        "py" -> "python"
        "sh", "bash", "zsh" -> "shell"
        "c++", "cc", "cxx", "h++", "hpp" -> "cpp"
        "c#", "cs" -> "csharp"
        "rs" -> "rust"
        "rb" -> "ruby"
        "pl" -> "perl"
        "golang" -> "go"
        "coffee" -> "coffeescript"
        "yml" -> "yaml"
        "md" -> "markdown"
        else -> name
    }
}

internal fun markdownCodeContrast(foreground: Color, background: Color): Float {
    val a = foreground.luminance()
    val b = background.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

/** Retain the upstream palette's hue where possible against the actual opaque panel color. */
private fun readableCodeColor(rgb: Int, background: Color): Color {
    val original = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFF))
    if (markdownCodeContrast(original, background) >= 4.5f) return original
    val destination = if (markdownCodeContrast(Color.Black, background) >= markdownCodeContrast(Color.White, background)) Color.Black else Color.White
    for (step in 1..32) {
        val candidate = lerp(original, destination, step / 32f)
        if (markdownCodeContrast(candidate, background) >= 4.5f) return candidate
    }
    return destination
}

private fun prismTokenColor(type: String, theme: SyntaxTheme): Int? = when (type) {
    "comment", "prolog", "doctype", "cdata" -> theme.comment
    "punctuation" -> theme.punctuation
    "property", "tag", "constant", "symbol", "deleted", "selector", "key", "boolean", "number" -> theme.literal
    "string", "char", "attr-value", "regex", "inserted", "scalar" -> theme.string
    "operator", "entity", "url" -> theme.mark
    "atrule", "attr-name", "keyword", "important" -> theme.keyword
    "function", "class-name", "builtin", "annotation" -> theme.metadata
    else -> null
}

/** Traverse mature token nodes, validating UTF16 offsets against the unchanged source. */
private fun prismCode(code: String, prism: Prism4j, grammar: Prism4j.Grammar, theme: SyntaxTheme,
    background: Color, checkCancelled: () -> Unit): AnnotatedString {
    val nodes = prism.tokenize(code, grammar)
    checkCancelled()
    return buildAnnotatedString {
        append(code)
        if (code.isNotEmpty()) addStyle(SpanStyle(color = readableCodeColor(theme.code, background)), 0, code.length)
        var visited = 0
        var spans = 0
        fun visit(children: List<Prism4j.Node>, initial: Int, limit: Int, depth: Int): Int {
            require(depth <= MAX_PRISM_DEPTH)
            var offset = initial
            children.forEach { node ->
                checkCancelled()
                require(++visited <= MAX_PRISM_NODES)
                val literal = when (node) {
                    is Prism4j.Text -> node.literal()
                    is Prism4j.Syntax -> node.matchedString()
                    else -> error("Unknown Prism node")
                }
                val end = offset + node.textLength()
                require(node.textLength() == literal.length && end <= limit &&
                    code.regionMatches(offset, literal, 0, literal.length))
                if (node is Prism4j.Syntax) {
                    val rgb = node.alias()?.let { prismTokenColor(it, theme) } ?: prismTokenColor(node.type(), theme)
                    val weight = if (node.type() == "bold" || node.type() == "important") FontWeight.Bold else null
                    val italic = if (node.type() == "italic") FontStyle.Italic else null
                    fun splitsSurrogate(index: Int) = index in 1 until code.length &&
                        Character.isHighSurrogate(code[index - 1]) && Character.isLowSurrogate(code[index])
                    if (offset < end && !splitsSurrogate(offset) && !splitsSurrogate(end) &&
                        (rgb != null || weight != null || italic != null)) {
                        require(++spans <= MAX_HIGHLIGHT_SPANS)
                        addStyle(SpanStyle(color = rgb?.let { readableCodeColor(it, background) } ?: Color.Unspecified,
                            fontWeight = weight, fontStyle = italic), offset, end)
                    }
                    require(visit(node.children(), offset, end, depth + 1) == end)
                }
                offset = end
            }
            return offset
        }
        require(visit(nodes, 0, code.length, 0) == code.length)
    }
}

/** No normalization, decoding or text reconstruction: spans always refer to the exact source. */
internal fun markdownHighlightedCode(code: String, language: String?, background: Color,
    checkCancelled: () -> Unit = {}): AnnotatedString {
    checkCancelled()
    val name = codeLanguage(language) ?: return AnnotatedString(code)
    if (code.length > MARKDOWN_CODE_HIGHLIGHT_LIMIT || code.count { it == '\n' } >= MAX_HIGHLIGHT_LINES)
        return AnnotatedString(code)
    return try {
        val theme = SyntaxThemes.atom(darkMode = background.luminance() < 0.5f)
        // No shared mutable grammar cache across blocks or background workers.
        val prism = MarkdownCodeGrammarFactory.create()
        val grammar = prism.grammar(name)
        if (grammar != null) return prismCode(code, prism, grammar, theme, background, checkCancelled)
        val syntax = supplementalLanguages[name] ?: return AnnotatedString(code)
        // Fresh builder per request avoids the engine's mutable incremental snapshot and cross-block state.
        val tokens = Highlights.Builder().code(code).language(syntax).theme(theme).build().getHighlights()
        checkCancelled()
        if (tokens.size > MAX_HIGHLIGHT_SPANS) return AnnotatedString(code)
        buildAnnotatedString {
            append(code)
            if (code.isNotEmpty()) addStyle(SpanStyle(color = readableCodeColor(theme.code, background)), 0, code.length)
            tokens.forEach { token ->
                checkCancelled()
                val start = token.location.start
                val end = token.location.end
                fun splitsSurrogate(index: Int) = index in 1 until code.length &&
                    Character.isHighSurrogate(code[index - 1]) && Character.isLowSurrogate(code[index])
                if (token is ColorHighlight && start >= 0 && end <= code.length && start < end &&
                    !splitsSurrogate(start) && !splitsSurrogate(end)) {
                    addStyle(SpanStyle(color = readableCodeColor(token.rgb, background)), start, end)
                }
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (_: StackOverflowError) { AnnotatedString(code) }
    catch (_: Exception) { AnnotatedString(code) }
}

/** Re-key state as well as work: changed code/language/theme never displays a prior block's spans. */
@Composable
internal fun rememberMarkdownCodeHighlight(code: String, language: String?, opaqueBackground: Color): AnnotatedString =
    key(code, language, opaqueBackground) {
        val highlighted by produceState(AnnotatedString(code), code, language, opaqueBackground) {
            value = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                markdownHighlightedCode(code, language, opaqueBackground) { context.ensureActive() }
            }
        }
        highlighted
    }
