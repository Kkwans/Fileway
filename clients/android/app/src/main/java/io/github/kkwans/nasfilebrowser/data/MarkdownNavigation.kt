package io.github.kkwans.nasfilebrowser.data

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class MarkdownHeading(
    val startOffset: Int,
    val rootIndex: Int,
    val level: Int,
    val title: String,
    val fragment: String?,
)

/** Offsets and root indices address this exact parsed document, never another file. */
internal class MarkdownNavigation(headings: List<MarkdownHeading>) {
    val headings: List<MarkdownHeading> = headings.toList()
    private val byFragment = this.headings.mapNotNull { heading -> heading.fragment?.let { it to heading } }.toMap()
    fun resolve(destination: String): MarkdownHeading? = markdownInternalFragment(destination)?.let(byFragment::get)
}

/** Decode once as a URI fragment, not a form value ('+' must never become space).
 * IDs in this adapter contain letters, decimal digits and '-'; no paths, schemes,
 * controls, query, custom HTML IDs or double-encoded navigation are admitted. */
internal fun markdownInternalFragment(destination: String): String? {
    if (!destination.startsWith('#') || destination.length !in 2..16_384) return null
    return try {
        val uri = URI(destination)
        if (uri.scheme != null || uri.rawAuthority != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null) return null
        val raw = uri.rawFragment ?: return null
        val bytes = ByteArrayOutputStream(raw.length)
        var offset = 0
        while (offset < raw.length) {
            if (raw[offset] == '%') {
                if (offset + 2 >= raw.length) return null
                val high = raw[offset + 1].digitToIntOrNull(16) ?: return null
                val low = raw[offset + 2].digitToIntOrNull(16) ?: return null
                bytes.write(high * 16 + low)
                offset += 3
            } else {
                val end = raw.indexOf('%', offset).takeIf { it >= 0 } ?: raw.length
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(raw, offset, end))
                while (encoded.hasRemaining()) bytes.write(encoded.get().toInt())
                offset = end
            }
        }
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        decoded.takeIf { it.isNotEmpty() && it.codePoints().allMatch { point -> point == '-'.code || Character.isLetterOrDigit(point) } }
    } catch (_: java.net.URISyntaxException) {
        null
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}

/** Reuse the already budget-validated JBM AST and the renderer's public inline
 * annotator. This is an index and a narrow ID adapter, not a Markdown parser.
 *
 * The common-title rule follows observed Web Lute 1.7.6 behavior: preserve case,
 * replace each non-letter/digit code point with '-', append '-' on collision.
 * Complex titles whose Lute text extraction differs (code/math/HTML/entities,
 * escapes or explicit IDs) remain usable in the outline by exact source offset.
 * Their unknown ID can affect subsequent collision suffixes, so fragments after
 * that boundary are deliberately not guessed. No Unicode normalization occurs. */
internal fun markdownNavigation(content: String, root: ASTNode): MarkdownNavigation {
    val references = ReferenceLinkHandlerImpl()
    root.markdownDescendants().filter { it.type == MarkdownElementTypes.LINK_DEFINITION }.forEach { node ->
        val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)
        if (label != null) references.store(label,
            node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content))
    }
    val settings = DefaultAnnotatorSettings(TextLinkStyles(), SpanStyle(), markdownAnnotator { source, node ->
        when {
            node.type == MarkdownTokenTypes.WHITE_SPACE || node.type == MarkdownTokenTypes.EOL -> {
                append(node.getTextInNode(source)); true
            }
            node.type == MarkdownElementTypes.IMAGE -> {
                append(node.markdownDescendants().firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }?.getUnescapedTextInNode(source)
                    ?.removePrefix("[")?.removeSuffix("]").orEmpty()); true
            }
            node.type == MarkdownElementTypes.HTML_BLOCK || node.type.name == "HTML_TAG" ||
                node.type == GFMElementTypes.INLINE_MATH || node.type == GFMElementTypes.BLOCK_MATH -> {
                append(node.getTextInNode(source)); true
            }
            else -> false
        }
    }, referenceLinkHandler = references)
    val headings = mutableListOf<MarkdownHeading>()
    val used = mutableSetOf<String>()
    var reliableNamespace = true
    root.children.forEachIndexed { rootIndex, block ->
        block.markdownDescendants().forEach { node ->
            val level = when (node.type) {
                MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1
                MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 2
                MarkdownElementTypes.ATX_3 -> 3
                MarkdownElementTypes.ATX_4 -> 4
                MarkdownElementTypes.ATX_5 -> 5
                MarkdownElementTypes.ATX_6 -> 6
                else -> return@forEach
            }
            val body = node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT)
                ?: node.findChildOfType(MarkdownTokenTypes.SETEXT_CONTENT)
            val raw = body?.getTextInNode(content)?.toString().orEmpty().trim()
            val complex = raw.any { it in "&\\{}\r\n" } || node.markdownDescendants().any {
                it.type == MarkdownElementTypes.CODE_SPAN || it.type == MarkdownElementTypes.IMAGE || it.type.name == "HTML_TAG" ||
                    it.type == GFMElementTypes.INLINE_MATH || it.type == GFMElementTypes.BLOCK_MATH
            }
            if (complex) reliableNamespace = false
            val inline = body?.let { AnnotatedString.Builder().apply {
                buildMarkdownAnnotatedString(content, it, settings)
            }.toAnnotatedString().text.trim() }.orEmpty()
            var fragment: String? = null
            if (reliableNamespace) {
                val candidate = StringBuilder()
                inline.trimStart('#').codePoints().forEach { point ->
                    if (Character.isLetterOrDigit(point)) candidate.appendCodePoint(point) else candidate.append('-')
                }
                while (!used.add(candidate.toString())) candidate.append('-')
                fragment = candidate.toString().takeIf { it.isNotEmpty() }
            }
            val title = inline.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                .ifEmpty { "未命名标题" }
            headings.add(MarkdownHeading(node.startOffset, rootIndex, level, title, fragment))
        }
    }
    return MarkdownNavigation(headings)
}
