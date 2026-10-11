package io.github.kkwans.nasfilebrowser.data

import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/** Extract only parser-recognized formula nodes. Code/HTML/metadata and local
 * literal fallbacks never acquire a TeX execution path. Keys are document-local
 * and sourceOrder traces deindented footnotes back to the original UTF-16 offset. */
internal fun markdownMathScopes(document: MarkdownDocument): Map<Int?, Map<Int, MarkdownMathFormula>> {
    fun collect(body: MarkdownDocument, scope: Int?, original: (Int) -> Int): Map<Int, MarkdownMathFormula> =
        body.state.node.markdownDescendants().filter {
            it.type == GFMElementTypes.INLINE_MATH || it.type == GFMElementTypes.BLOCK_MATH
        }.mapNotNull { node ->
            val source = node.getTextInNode(body.state.content).toString()
            val delimiters = source.takeWhile { it == '$' }.length
            if (delimiters == 0 || source.length < delimiters * 2 || !source.endsWith("$".repeat(delimiters))) return@mapNotNull null
            node.startOffset to MarkdownMathFormula("math/${scope?.let { "note-$it" } ?: "main"}/${node.startOffset}",
                source.substring(delimiters, source.length - delimiters), source, node.type == GFMElementTypes.BLOCK_MATH,
                original(node.startOffset))
        }.toMap()
    val result = linkedMapOf<Int?, Map<Int, MarkdownMathFormula>>()
    result[null] = collect(document, null) { it }
    document.extensions.footnotes.forEach { note -> note.body?.let { body ->
        result[note.startOffset] = collect(body, note.startOffset) { local -> note.source?.original(local) ?: note.startOffset }
    } }
    return result
}
