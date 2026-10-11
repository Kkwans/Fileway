package io.github.kkwans.nasfilebrowser.data

import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import kotlinx.coroutines.CancellationException
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerBlock
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI
import java.util.Locale

internal const val MARKDOWN_READING_LIMIT = 2L * 1024 * 1024
private const val MAX_RENDER_NODES = 20_000
private const val MAX_BLOCK_CHARACTERS = 16_384
private const val MAX_BLOCK_LINES = 512

internal fun isMarkdownDocument(file: ResourceRef): Boolean = FileCategory.MARKDOWN.matches(file.name, file.directory)

/** No relative resolution, server headers, capability URLs or Intent schemes. */
internal fun markdownExternalLink(destination: String): String? {
    if (destination.isEmpty() || destination.any { it <= ' ' || it == '\u007F' }) return null
    val uri = try { URI(destination) } catch (_: java.net.URISyntaxException) { return null }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") || uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null || uri.port !in -1..65535 || uri.rawAuthority?.contains('\\') == true) return null
    val host = uri.host.lowercase(Locale.ROOT).removeSuffix(".")
    if (host == "localhost" || host.endsWith(".localhost")) return null
    if (host.startsWith('[')) {
        val pieces = markdownIpv6Pieces(host.removePrefix("[").removeSuffix("]")) ?: return null
        if (pieces.take(7).all { it == 0 } && pieces[7] in 0..1) return null
        // IPv4-mapped and legacy compatible spellings must obey the same rule.
        if (pieces.take(5).all { it == 0 } && pieces[5] in setOf(0, 0xffff) &&
            (pieces[6] ushr 8 == 127 || pieces[6] == 0 && pieces[7] == 0)) return null
    } else {
        val last = host.substringAfterLast('.')
        val numeric = last.isNotEmpty() && (last.all { it in '0'..'9' } ||
            last.startsWith("0x") && last.drop(2).all { it in "0123456789abcdef" })
        if (numeric) {
            // Browsers accept integer, short, octal and hex IPv4 spellings.
            // Accept only unambiguous decimal octets, without DNS resolution.
            val octets = markdownIpv4Octets(host) ?: return null
            if (octets[0] == 127 || octets.all { it == 0 }) return null
        }
    }
    return uri.toASCIIString()
}

private fun markdownIpv4Octets(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    return parts.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
            part.length > 1 && part.startsWith('0')) return null
        part.toInt().takeIf { it <= 255 } ?: return null
    }
}

/** URI already validates the literal; expand it numerically without any resolver. */
private fun markdownIpv6Pieces(literal: String): List<Int>? {
    if ('%' in literal) return null // Scoped addresses have no portable browser meaning.
    var address = literal
    if ('.' in address) {
        val octets = markdownIpv4Octets(address.substringAfterLast(':')) ?: return null
        address = address.substringBeforeLast(':') + ":" +
            ((octets[0] shl 8) or octets[1]).toString(16) + ":" + ((octets[2] shl 8) or octets[3]).toString(16)
    }
    fun pieces(value: String): List<Int>? {
        if (value.isEmpty()) return emptyList()
        return value.split(':').map { part ->
            if (part.isEmpty() || part.length > 4 || part.any { it !in "0123456789abcdef" }) return null
            part.toInt(16)
        }
    }
    val halves = address.split("::")
    if (halves.size > 2) return null
    val left = pieces(halves[0]) ?: return null
    if (halves.size == 1) return left.takeIf { it.size == 8 }
    val right = pieces(halves[1]) ?: return null
    val missing = 8 - left.size - right.size
    return if (missing > 0) left + List(missing) { 0 } + right else null
}

internal fun markdownTaskChecked(content: String, node: ASTNode): Boolean =
    node.getTextInNode(content).toString().trim().equals("[x]", ignoreCase = true)

/** The renderer's own parsed state, not an application-owned rendering tree. */
internal data class MarkdownDocument(val state: State.Success, val hasImages: Boolean, val hasInactiveLinks: Boolean,
    val hasLiteralHtml: Boolean, val hasLiteralMath: Boolean, val navigation: MarkdownNavigation,
    val extensions: MarkdownExtensions = MarkdownExtensions())
internal sealed interface MarkdownPreview {
    data class Reading(val document: MarkdownDocument) : MarkdownPreview
    data class SourceOnly(val reason: String) : MarkdownPreview
}
internal enum class MarkdownAlignment { LEFT, CENTER, RIGHT }

/** Use the upstream parser's pipe splitter only on its validated separator node. */
internal fun markdownTableAlignments(content: String, table: ASTNode): List<MarkdownAlignment> {
    val separator = table.findChildOfType(GFMTokenTypes.TABLE_SEPARATOR) ?: return emptyList()
    val parts = GitHubTableMarkerBlock.splitByPipes(separator.getTextInNode(content)).toMutableList()
    if (parts.firstOrNull()?.isBlank() == true) parts.removeAt(0)
    if (parts.lastOrNull()?.isBlank() == true) parts.removeAt(parts.lastIndex)
    return parts.map { part ->
        val cell = part.trim()
        when {
            cell.startsWith(':') && cell.endsWith(':') -> MarkdownAlignment.CENTER
            cell.endsWith(':') -> MarkdownAlignment.RIGHT
            else -> MarkdownAlignment.LEFT
        }
    }
}

internal fun ASTNode.markdownDescendants(): Sequence<ASTNode> = sequence {
    val pending = ArrayDeque<ASTNode>()
    pending.addLast(this@markdownDescendants)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        yield(current)
        current.children.asReversed().forEach(pending::addLast)
    }
}

internal fun markdownImageLabel(content: String, image: ASTNode): String {
    val label = image.markdownDescendants().firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        ?.getUnescapedTextInNode(content)?.removePrefix("[")?.removeSuffix("]").orEmpty()
    return "〔图片" + label.takeIf { it.isNotBlank() }?.let { "：" + it }.orEmpty() + " · 暂未加载〕"
}

/** Parse off-main, validate native layout budgets, then hand the same AST to the upstream renderer. */
internal fun markdownPreview(document: DocumentText): MarkdownPreview = markdownPreview(document, true, MarkdownRenderBudget())

private class MarkdownRenderBudget(var nodes: Int = 0)
private fun markdownPreview(document: DocumentText, extended: Boolean, budget: MarkdownRenderBudget,
    inheritedLinks: List<Pair<String, String>> = emptyList(), footnoteReferences: List<MarkdownSourceRange> = emptyList()): MarkdownPreview {
    if (maxOf(document.byteSize, document.text.length.toLong()) > MARKDOWN_READING_LIMIT)
        return MarkdownPreview.SourceOnly("Markdown 超过 2 MiB 阅读渲染上限，已显示完整源码；原文件未改变。")
    return try {
        val source = document.text
        val extensionInput = if (extended) markdownExtensionInput(source) else null
        val projection = markdownFootnoteProjection(extensionInput?.projection ?: source,
            extensionInput?.references?.filter { it.scope == null }?.map { it.range } ?: footnoteReferences)
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(projection)
        val references = ReferenceLinkHandlerImpl()
        val sharedLinks = extensionInput?.links ?: inheritedLinks
        sharedLinks.forEach { (label, target) -> references.store(label,
            target.takeIf { markdownInternalFragment(it) != null } ?: markdownExternalLink(target)) }
        var images = false
        var html = false
        var math = false
        val pending = ArrayDeque<Pair<ASTNode, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            check(++budget.nodes <= MAX_RENDER_NODES && depth <= 64)
            val type = node.type
            if (type == MarkdownElementTypes.PARAGRAPH || type == MarkdownElementTypes.CODE_FENCE ||
                type == MarkdownElementTypes.CODE_BLOCK || type == MarkdownElementTypes.HTML_BLOCK ||
                type == MarkdownElementTypes.BLOCK_QUOTE || type == MarkdownElementTypes.ORDERED_LIST ||
                type == MarkdownElementTypes.UNORDERED_LIST || type == MarkdownElementTypes.ATX_1 ||
                type == MarkdownElementTypes.ATX_2 || type == MarkdownElementTypes.ATX_3 ||
                type == MarkdownElementTypes.ATX_4 || type == MarkdownElementTypes.ATX_5 ||
                type == MarkdownElementTypes.ATX_6 || type == MarkdownElementTypes.SETEXT_1 || type == MarkdownElementTypes.SETEXT_2) {
                val text = node.getTextInNode(source)
                check(text.length <= MAX_BLOCK_CHARACTERS && text.count { it == '\n' } <= MAX_BLOCK_LINES)
            }
            if (type == MarkdownElementTypes.ORDERED_LIST || type == MarkdownElementTypes.UNORDERED_LIST)
                check(node.markdownDescendants().count { it.type == MarkdownElementTypes.LIST_ITEM } <= 256)
            if (type == GFMElementTypes.TABLE) {
                val cells = node.markdownDescendants().filter { it.type == GFMTokenTypes.CELL }.toList()
                check(cells.size <= 1024 && cells.sumOf { it.endOffset - it.startOffset } <= 4096)
                check(node.children.count { it.type == GFMElementTypes.ROW } <= 64)
                check((node.findChildOfType(GFMElementTypes.HEADER)?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0) <= 16)
            }
            if (type == MarkdownElementTypes.LINK_DEFINITION) {
                val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(source)
                val target = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(source)
                if (label != null && sharedLinks.isEmpty()) references.store(label,
                    target?.takeIf { markdownInternalFragment(it) != null } ?: target?.let(::markdownExternalLink))
            }
            if (type == MarkdownElementTypes.IMAGE) images = true
            if (type == MarkdownElementTypes.HTML_BLOCK || type.name == "HTML_TAG") html = true
            if (type == GFMElementTypes.INLINE_MATH || type == GFMElementTypes.BLOCK_MATH) math = true
            node.children.asReversed().forEach { pending.addLast(it to depth + 1) }
        }
        val navigation = markdownNavigation(source, root)
        // Only actual targets in this exact document gain clickable fragments.
        val inactive = root.markdownDescendants().any { node ->
            val target = when {
                node.type == MarkdownElementTypes.LINK_DESTINATION -> node.getUnescapedTextInNode(source)
                node.type == MarkdownElementTypes.AUTOLINK || node.type == GFMTokenTypes.GFM_AUTOLINK ->
                    node.getUnescapedTextInNode(source).removePrefix("<").removeSuffix(">")
                else -> null
            }
            target != null && markdownExternalLink(target) == null && navigation.resolve(target) == null
        }
        val extensions = extensionInput?.let { input -> buildMarkdownExtensions(input, root, navigation) { body, bodyReferences ->
            (markdownPreview(decodeDocumentText(body.toByteArray(Charsets.UTF_8)), false, budget, sharedLinks, bodyReferences) as? MarkdownPreview.Reading)?.document
        } } ?: MarkdownExtensions()
        check(budget.nodes <= MAX_RENDER_NODES)
        val bodies = extensions.footnotes.mapNotNull { it.body }
        MarkdownPreview.Reading(MarkdownDocument(State.Success(root, source, true, references), images || bodies.any { it.hasImages },
            inactive || bodies.any { it.hasInactiveLinks }, html || bodies.any { it.hasLiteralHtml }, math || bodies.any { it.hasLiteralMath }, navigation, extensions))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        MarkdownPreview.SourceOnly("此 Markdown 的结构暂不适合阅读渲染，已显示完整源码；原文件未改变。")
    } catch (_: StackOverflowError) {
        MarkdownPreview.SourceOnly("此 Markdown 的嵌套结构过深，已显示完整源码；原文件未改变。")
    }
}
