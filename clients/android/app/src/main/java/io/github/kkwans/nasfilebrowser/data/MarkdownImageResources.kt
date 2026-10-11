package io.github.kkwans.nasfilebrowser.data

import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import java.util.Locale

internal sealed interface MarkdownImageResolution {
    data class Nas(val wirePath: String) : MarkdownImageResolution
    data class Unavailable(val reason: String) : MarkdownImageResolution
}

/** A destination in the already parsed upstream AST, never a second Markdown parser. */
internal data class MarkdownImageReference(val startOffset: Int, val destination: String?, val alt: String)

internal fun markdownImageReferences(document: MarkdownDocument): List<MarkdownImageReference> {
    val content = document.state.content
    val nodes = document.state.node.markdownDescendants().toList()
    // Text links deliberately reject relative references. Image references need
    // their original destination, and are checked by the resource resolver.
    val references = ReferenceLinkHandlerImpl()
    val seen = mutableSetOf<String>()
    nodes.filter { it.type == MarkdownElementTypes.LINK_DEFINITION }.forEach { node ->
        val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)
        val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content)
        if (label != null && seen.add(label.lowercase(Locale.ROOT))) references.store(label, destination)
    }
    return nodes.filter { it.type == MarkdownElementTypes.IMAGE }.map { image ->
        val children = image.markdownDescendants().toList()
        val label = children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }?.getUnescapedTextInNode(content)
        val destination = children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.getUnescapedTextInNode(content)
            ?: label?.let(references::find)?.takeIf { it.isNotEmpty() }
        val alt = (children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }?.getUnescapedTextInNode(content) ?: label)
            ?.removePrefix("[")?.removeSuffix("]").orEmpty()
        MarkdownImageReference(image.startOffset, destination, alt)
    }
}

/** Resolve only same-account NAS resource paths. No URI resolver or DNS can
 * reinterpret opaque parent bytes, '+' or an encoded filename separator. */
internal fun resolveMarkdownImage(document: ResourceRef, destination: String): MarkdownImageResolution {
    if (document.downloadId.isNotEmpty()) return MarkdownImageResolution.Unavailable("本机图片需原目录读取授权，暂未加载。")
    if (destination.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(destination))
        return MarkdownImageResolution.Unavailable("外部图片暂不自动加载，可在源码中查看地址。")
    return try {
        require(destination.isNotEmpty() && !destination.endsWith('/') &&
            destination.none { it < ' ' || it == '\u007F' || it == '\\' || it == '?' || it == '#' } &&
            Charsets.UTF_8.newEncoder().canEncode(destination))
        val source = taskResourceTarget(document.path, documentWirePath(document), allowOpaque = true)
        val segments = if (destination.startsWith('/')) mutableListOf<String>()
            else source.wirePath.substringBeforeLast('/').split('/').filter { it.isNotEmpty() }.toMutableList()
        val relative = destination.removePrefix("/")
        for (segment in relative.split('/')) {
            require(segment.isNotEmpty())
            // Decode navigation tokens only; other bytes use the shared wire
            // validator and are never reconstructed from display text.
            when (segment.replace("%2e", ".", ignoreCase = true)) {
                "." -> Unit
                ".." -> { require(segments.isNotEmpty()); segments.removeAt(segments.lastIndex) }
                else -> {
                    val encoded = encodeMarkdownPathSegment(segment)
                    resourceWireBytes("/$encoded")
                    segments.add(encoded)
                }
            }
        }
        require(segments.isNotEmpty())
        MarkdownImageResolution.Nas(recentAccessWireIdentity("/" + segments.joinToString("/")))
    } catch (_: IllegalArgumentException) {
        MarkdownImageResolution.Unavailable("图片路径无效或超出根目录，暂未加载。")
    } catch (_: IllegalStateException) {
        MarkdownImageResolution.Unavailable("图片路径无效或超出根目录，暂未加载。")
    }
}

/** Preserve existing percent escapes; encode literal chunks with the existing
 * project encoder. Validation below decodes each escape exactly once. */
private fun encodeMarkdownPathSegment(segment: String): String = buildString {
    var from = 0
    var index = 0
    while (index < segment.length) {
        if (segment[index] == '%') {
            append(SearchResult.encodePath(segment.substring(from, index)))
            require(index + 2 < segment.length && segment[index + 1].digitToIntOrNull(16) != null &&
                segment[index + 2].digitToIntOrNull(16) != null)
            append(segment, index, index + 3)
            index += 3
            from = index
        } else index++
    }
    append(SearchResult.encodePath(segment.substring(from)))
}
