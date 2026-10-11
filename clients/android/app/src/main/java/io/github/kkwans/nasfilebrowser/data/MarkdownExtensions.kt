package io.github.kkwans.nasfilebrowser.data

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.parser.FrontMatterParser
import org.commonmark.ext.front.matter.parser.RawContentParser
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.node.DefinitionMap
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.SourceSpan
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.utils.getUnescapedTextInNode

internal data class MarkdownSourceRange(val start: Int, val end: Int) {
    fun contains(other: MarkdownSourceRange) = start <= other.start && other.end <= end
}
internal data class MarkdownFrontMatter(val range: MarkdownSourceRange, val raw: String)
internal data class MarkdownFootnoteReference(val startOffset: Int, val endOffset: Int, val number: Int,
    val definitionOffset: Int, val rootIndex: Int, val scope: Int? = null, val inline: Boolean = true) {
    val tag get() = "fileway-footnote/${scope ?: "main"}/$startOffset"
}
internal data class MarkdownFootnote(val startOffset: Int, val label: String, val number: Int?, val raw: String,
    val body: MarkdownDocument?, val references: List<MarkdownFootnoteReference>, val duplicate: Boolean,
    val source: MarkdownMappedSource? = null)
internal data class MarkdownExtensions(val frontMatter: MarkdownFrontMatter? = null,
    val footnotes: List<MarkdownFootnote> = emptyList(), val references: Map<Int, MarkdownFootnoteReference> = emptyMap(),
    val tocOffsets: Set<Int> = emptySet(), val linkDefinitions: List<Pair<String, String>> = emptyList())

/** A display-only deindented source, with every character traced to the original.
 * The original document, its editor text and byte encoding never use this value. */
internal class MarkdownMappedSource(val text: String, private val offsets: IntArray) {
    fun local(original: Int): Int? = offsets.binarySearch(original).takeIf { it >= 0 }
    fun original(local: Int): Int? = offsets.getOrNull(local)
}
internal class MarkdownFootnoteInput(val range: MarkdownSourceRange, val label: String, val raw: String,
    val source: MarkdownMappedSource, val duplicate: Boolean, val literal: Boolean)
internal data class MarkdownFootnoteInputReference(val range: MarkdownSourceRange, val definitionOffset: Int, val scope: Int?)
internal class MarkdownExtensionInput(val projection: String, val frontMatter: MarkdownFrontMatter?,
    val notes: List<MarkdownFootnoteInput>, val references: List<MarkdownFootnoteInputReference>,
    val links: List<Pair<String, String>>)

private fun Node.extensionDescendants(): Sequence<Node> = sequence {
    val pending = ArrayDeque<Pair<Node, Int>>()
    pending.addLast(this@extensionDescendants to 0)
    var count = 0
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        check(++count <= 20_000 && depth <= 64) { "Markdown extension structure exceeds reading budget" }
        yield(node)
        val children = mutableListOf<Node>()
        var child = node.firstChild
        while (child != null) { children.add(child); child = child.next }
        children.asReversed().forEach { pending.addLast(it to depth + 1) }
    }
}
private fun List<SourceSpan>.sourceRange(): MarkdownSourceRange? {
    if (isEmpty()) return null
    return MarkdownSourceRange(minOf { it.inputIndex }, maxOf { it.inputIndex + it.length })
}

/** Uses the official extension parsers; this adapter only extracts their ranges.
 * RawContentParser's ending callback proves closure, including CRLF and `...`.
 * An unclosed YAML-looking header is reparsed without the YAML extension so it
 * cannot swallow the rest of the document. No YAML object construction occurs. */
internal fun markdownExtensionInput(content: String): MarkdownExtensionInput {
    var yamlEnd: Int? = null
    val yaml = YamlFrontMatterExtension.create(FrontMatterParser.Factory {
        val raw = RawContentParser()
        object : FrontMatterParser {
            override fun onNextLine(block: YamlFrontMatterBlock, line: SourceLine) = raw.onNextLine(block, line)
            override fun onEndingSeparator(block: YamlFrontMatterBlock, separator: SourceLine): FrontMatterParser.SeparatorRole {
                val span = requireNotNull(separator.sourceSpan)
                var end = span.inputIndex + span.length
                if (content.getOrNull(end) == '\r') end++
                if (content.getOrNull(end) == '\n') end++
                yamlEnd = end
                return raw.onEndingSeparator(block, separator)
            }
        }
    })
    fun parse(withYaml: Boolean) = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .extensions(if (withYaml) listOf(FootnotesExtension.create(), yaml) else listOf(FootnotesExtension.create()))
        .build().parse(content)
    var root = parse(true)
    var nodes = root.extensionDescendants().toList()
    if (yamlEnd == null && nodes.any { it is YamlFrontMatterBlock }) {
        root = parse(false); nodes = root.extensionDescendants().toList()
    }
    val front = nodes.filterIsInstance<YamlFrontMatterBlock>().firstOrNull()?.let { block ->
        val start = requireNotNull(block.sourceSpans.sourceRange()).start
        val range = MarkdownSourceRange(start, requireNotNull(yamlEnd))
        MarkdownFrontMatter(range, content.substring(range.start, range.end))
    }
    val definitions = nodes.filterIsInstance<FootnoteDefinition>().sortedBy { it.sourceSpans.sourceRange()?.start }
    check(definitions.size <= 256) { "Too many Markdown footnotes" }
    val definitionMap = DefinitionMap(FootnoteDefinition::class.java)
    val first = mutableSetOf<FootnoteDefinition>()
    definitions.forEach { if (definitionMap.putIfAbsent(it.label, it) == null) first.add(it) }
    val ranges = definitions.associateWith { requireNotNull(it.sourceSpans.sourceRange()) }
    // Nested definitions have no common portable rendering rule. Keep the outer
    // definition literally rather than discarding or guessing nested contents.
    val outer = definitions.filter { node -> definitions.none { it !== node && ranges.getValue(it).contains(ranges.getValue(node)) } }
    val notes = outer.map { node ->
        val range = ranges.getValue(node)
        val raw = content.substring(range.start, range.end)
        check(raw.length <= 16_384 && raw.count { it == '\n' } <= 512) { "Footnote exceeds reading block budget" }
        val spans = mutableListOf<SourceSpan>()
        var child = node.firstChild
        while (child != null) { spans.addAll(child.sourceSpans); child = child.next }
        val text = StringBuilder(); val offsets = mutableListOf<Int>()
        var previousEnd: Int? = null
        for (span in spans.sortedBy { it.inputIndex }) {
            val start = span.inputIndex; val end = start + span.length
            check(start >= range.start && end <= range.end)
            previousEnd?.let { before ->
                check(before <= start)
                for (index in before until start) if (content[index] == '\r' || content[index] == '\n') {
                    text.append(content[index]); offsets.add(index)
                }
            }
            for (index in start until end) { text.append(content[index]); offsets.add(index) }
            previousEnd = end
        }
        MarkdownFootnoteInput(range, node.label, raw, MarkdownMappedSource(text.toString(), offsets.toIntArray()),
            node !in first, definitions.any { it !== node && range.contains(ranges.getValue(it)) })
    }
    val byDefinition = notes.associateBy { it.range.start }
    val references = nodes.filterIsInstance<FootnoteReference>().mapNotNull { reference ->
        val definition = definitionMap.get(reference.label) ?: return@mapNotNull null
        val destination = ranges.getValue(definition).start
        if (destination !in byDefinition) return@mapNotNull null
        val range = reference.sourceSpans.sourceRange() ?: return@mapNotNull null
        val scope = notes.firstOrNull { it.range.contains(range) }?.range?.start
        MarkdownFootnoteInputReference(range, destination, scope)
    }.sortedBy { it.range.start }
    check(references.size <= 2048) { "Too many Markdown footnote references" }
    val linkMap = DefinitionMap(LinkReferenceDefinition::class.java)
    nodes.filterIsInstance<LinkReferenceDefinition>().forEach { linkMap.putIfAbsent(it.label, it) }
    val links = linkMap.values().map { "[${it.label}]" to it.destination }
    val projection = content.toCharArray()
    (notes.map { it.range } + listOfNotNull(front?.range)).forEach { range ->
        for (index in range.start until range.end) if (projection[index] != '\r' && projection[index] != '\n') projection[index] = ' '
    }
    return MarkdownExtensionInput(String(projection), front, notes, references, links)
}

/** The same original root item remains the scroll address. Code, quotes, lists,
 * explicit links and inline occurrences are not document-level TOC markers. */
internal fun markdownTocOffsets(content: String, root: ASTNode): Set<Int> = root.children.filter {
    it.type == MarkdownElementTypes.PARAGRAPH && it.getTextInNode(content).toString().trim().equals("[TOC]", ignoreCase = true)
}.mapTo(mutableSetOf()) { it.startOffset }

/** JBM filters horizontal whitespace before preferring a full reference link.
 * It can therefore consume `[^note] [text]` as one node even when CommonMark's
 * official footnote processor consumed only `[^note]`. A display-only TEXT token
 * in existing whitespace prevents that merge. Length/newlines stay exact and
 * every rendered/copied character still comes from the original source string.
 * Only references already proven by the official parser receive this barrier. */
internal fun markdownFootnoteProjection(content: String, references: List<MarkdownSourceRange>): String {
    if (references.isEmpty()) return content
    val projection = content.toCharArray()
    references.forEach { reference ->
        var next = reference.end
        while (content.getOrNull(next) == ' ' || content.getOrNull(next) == '\t') next++
        if (next > reference.end && content.getOrNull(next) == '[') projection[reference.end] = 'x'
    }
    return String(projection)
}

internal fun buildMarkdownExtensions(input: MarkdownExtensionInput, root: ASTNode, navigation: MarkdownNavigation,
    prepareBody: (String, List<MarkdownSourceRange>) -> MarkdownDocument?): MarkdownExtensions {
    val numbers = linkedMapOf<Int, Int>()
    fun number(reference: MarkdownFootnoteInputReference) { numbers.getOrPut(reference.definitionOffset) { numbers.size + 1 } }
    input.references.filter { it.scope == null }.forEach(::number)
    var at = 0
    while (at < numbers.size) {
        val definition = numbers.keys.elementAt(at++)
        if (input.notes.any { it.range.start == definition && !it.literal && !it.duplicate })
            input.references.filter { it.scope == definition }.forEach(::number)
    }
    val references = mutableListOf<MarkdownFootnoteReference>()
    fun mapped(scope: Int?, bodyRoot: ASTNode, source: MarkdownMappedSource? = null): Map<Int, MarkdownFootnoteReference> {
        val nodes = bodyRoot.markdownDescendants().groupBy { it.startOffset }
        return input.references.filter { it.scope == scope }.mapNotNull { ref ->
            val start = if (source == null) ref.range.start else source.local(ref.range.start) ?: return@mapNotNull null
            val end = if (source == null) ref.range.end else (source.local(ref.range.end - 1) ?: return@mapNotNull null) + 1
            val rootIndex = bodyRoot.children.indexOfFirst { it.startOffset <= start && end <= it.endOffset }
            if (rootIndex < 0) return@mapNotNull null
            val node = nodes[start]?.firstOrNull { it.endOffset == end && it.type in setOf(
                MarkdownElementTypes.SHORT_REFERENCE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.LINK_LABEL) }
                ?: return@mapNotNull null
            val ancestors = generateSequence(node.parent) { it.parent }.toList()
            if (ancestors.any { it.type == MarkdownElementTypes.IMAGE }) return@mapNotNull null
            val value = MarkdownFootnoteReference(start, end, numbers[ref.definitionOffset] ?: return@mapNotNull null,
                ref.definitionOffset, rootIndex, scope, ancestors.none { it.type == GFMTokenTypes.CELL })
            references.add(value); start to value
        }.toMap()
    }
    val main = mapped(null, root)
    val prepared = input.notes.associate { note ->
        val bodyReferences = input.references.filter { it.scope == note.range.start }.mapNotNull { reference ->
            val start = note.source.local(reference.range.start) ?: return@mapNotNull null
            val end = (note.source.local(reference.range.end - 1) ?: return@mapNotNull null) + 1
            MarkdownSourceRange(start, end)
        }
        val body = if (!note.duplicate && !note.literal && note.range.start in numbers) prepareBody(note.source.text, bodyReferences)?.let { document ->
            document.copy(navigation = navigation, extensions = MarkdownExtensions(
                references = mapped(note.range.start, document.state.node, note.source), linkDefinitions = input.links))
        } else null
        note.range.start to body
    }
    val notes = input.notes.sortedWith(compareBy<MarkdownFootnoteInput> { numbers[it.range.start] ?: Int.MAX_VALUE }.thenBy { it.range.start }).map { note ->
        MarkdownFootnote(note.range.start, note.label, numbers[note.range.start], note.raw, prepared[note.range.start],
            references.filter { it.definitionOffset == note.range.start }, note.duplicate, note.source)
    }
    return MarkdownExtensions(input.frontMatter, notes, main, markdownTocOffsets(input.projection, root), input.links)
}

/** Footnote bodies inherit the document's ordinary reference definitions. Only
 * actual upstream IMAGE nodes gain a raw image destination; the unchanged NAS
 * resolver and owner-bound loader still decide whether it may be read. */
internal fun markdownExtensionImageReferences(document: MarkdownDocument): List<MarkdownImageReference> {
    val values = markdownImageReferences(document)
    if (document.extensions.linkDefinitions.isEmpty() || values.none { it.destination == null }) return values
    val links = ReferenceLinkHandlerImpl()
    document.extensions.linkDefinitions.forEach { (label, destination) -> links.store(label, destination) }
    val images = document.state.node.markdownDescendants().filter { it.type == MarkdownElementTypes.IMAGE }.associateBy { it.startOffset }
    return values.map { value ->
        if (value.destination != null) value else {
            val label = images[value.startOffset]?.markdownDescendants()?.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                ?.getUnescapedTextInNode(document.state.content)
            value.copy(destination = label?.let(links::find)?.takeIf { it.isNotEmpty() })
        }
    }
}
