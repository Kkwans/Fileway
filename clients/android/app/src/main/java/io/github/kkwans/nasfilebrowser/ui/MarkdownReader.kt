package io.github.kkwans.nasfilebrowser.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.components.CurrentComponentsBridge
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.kkwans.nasfilebrowser.data.*
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal fun markdownExternalIntent(destination: String): Intent? = markdownExternalLink(destination)?.let {
    Intent(Intent.ACTION_VIEW, Uri.parse(it).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
}

/** Only security and explicit unsupported-content handling override upstream inline rendering. */
internal fun markdownSafetyAnnotator(document: MarkdownDocument): MarkdownAnnotator = markdownAnnotator { content, node ->
    when {
        node.type == MarkdownElementTypes.IMAGE -> {
            append(markdownImageLabel(content, node)); true
        }
        node.type.name == "HTML_TAG" || node.type == GFMElementTypes.INLINE_MATH -> {
            append(node.getTextInNode(content)); true
        }
        node.type == MarkdownElementTypes.INLINE_LINK || node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK ||
            node.type == MarkdownElementTypes.FULL_REFERENCE_LINK -> {
            val label = node.findChildOfType(MarkdownElementTypes.LINK_TEXT) ?: node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
            val target = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content)
                ?: node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)?.let(document.state.referenceLinkHandler::find)
            if (target == null || markdownExternalLink(target) == null) {
                append(label?.getUnescapedTextInNode(content)?.removePrefix("[")?.removeSuffix("]") ?: node.getTextInNode(content)); true
            } else false
        }
        node.type == MarkdownElementTypes.AUTOLINK || node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val target = node.getUnescapedTextInNode(content).removePrefix("<").removeSuffix(">")
            if (markdownExternalLink(target) == null) { append(target); true } else false
        }
        else -> false
    }
}

@Composable
internal fun MarkdownBody(document: MarkdownDocument, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp)
    val typography = markdownTypography(
        h1 = body.copy(fontSize = 28.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
        h2 = body.copy(fontSize = 24.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
        h3 = body.copy(fontSize = 21.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        h4 = body.copy(fontSize = 19.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        h5 = body.copy(fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        h6 = body.copy(fontWeight = FontWeight.SemiBold),
        text = body, paragraph = body, ordered = body, bullet = body, list = body,
        quote = body.copy(fontStyle = FontStyle.Normal),
        code = body.copy(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace),
        inlineCode = body.copy(fontFamily = FontFamily.Monospace),
        table = body.copy(fontSize = 14.sp, lineHeight = 23.sp),
    )
    val uriHandler = remember(onOpenLink) {
        object : UriHandler {
            override fun openUri(uri: String) { markdownExternalLink(uri)?.let(onOpenLink) }
        }
    }
    val annotator = remember(document) { markdownSafetyAnnotator(document) }
    val components = markdownComponents(
        heading1 = spacedHeading(CurrentComponentsBridge.heading1),
        heading2 = spacedHeading(CurrentComponentsBridge.heading2),
        heading3 = spacedHeading(CurrentComponentsBridge.heading3),
        heading4 = spacedHeading(CurrentComponentsBridge.heading4),
        heading5 = spacedHeading(CurrentComponentsBridge.heading5),
        heading6 = spacedHeading(CurrentComponentsBridge.heading6),
        setextHeading1 = spacedHeading(CurrentComponentsBridge.setextHeading1),
        setextHeading2 = spacedHeading(CurrentComponentsBridge.setextHeading2),
        blockQuote = { model ->
            Box(Modifier.semantics { isTraversalGroup = true; contentDescription = "引用" }) {
                CurrentComponentsBridge.blockQuote(model)
            }
        },
        checkbox = { model ->
            val checked = markdownTaskChecked(model.content, model.node)
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 4.dp).semantics {
                role = Role.Checkbox
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = if (checked) "已完成，只读" else "未完成，只读"
            })
        },
        image = { Text(markdownImageLabel(it.content, it.node), style = it.typography.text) },
        codeFence = { MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, style -> ReaderCode(code, language, style) } },
        codeBlock = { MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, language, style -> ReaderCode(code, language, style) } },
        table = {
            val alignments = remember(it.content, it.node) { markdownTableAlignments(it.content, it.node) }
            MarkdownTable(it.content, it.node, it.typography.table,
                headerBlock = { content, row, width, style -> ReaderTableRow(content, row, width, style, alignments, true) },
                rowBlock = { content, row, width, style -> ReaderTableRow(content, row, width, style, alignments, false) })
        },
        custom = { type, model ->
            // Definitions were already stored by the parser adapter. Unknown visible
            // blocks (including HTML/math) stay literal rather than disappearing.
            if (type != MarkdownElementTypes.LINK_DEFINITION) Text(model.node.getTextInNode(model.content).toString(),
                style = model.typography.code)
        },
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            if (document.hasImages) ReaderNotice("阅读模式暂不加载内联图片，源码中保留完整图片地址。")
            if (document.hasInactiveLinks) ReaderNotice("相对链接、本机地址和不受支持的链接仅显示文字。")
            if (document.hasLiteralHtml) ReaderNotice("HTML 按原文显示，不执行脚本或加载其中资源。")
            if (document.hasLiteralMath) ReaderNotice("数学公式暂按源码显示。")
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    if (document.state.content.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有可呈现的正文，可切换源码查看。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Markdown(state = document.state, typography = typography,
                        modifier = Modifier.fillMaxSize(),
                        padding = markdownPadding(block = 10.dp, list = 6.dp, listItemTop = 4.dp, listItemBottom = 4.dp, listIndent = 18.dp),
                        dimens = markdownDimens(tableMaxWidth = 840.dp, tableCellWidth = 160.dp, tableCellPadding = 12.dp),
                        imageTransformer = NoOpImageTransformerImpl(), annotator = annotator, components = components,
                        success = { state, resolved, bodyModifier ->
                            LazyMarkdownSuccess(state, resolved, bodyModifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp))
                        })
                }
            }
        }
    }
}

@Composable
private fun ReaderNotice(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun spacedHeading(delegate: MarkdownComponent): MarkdownComponent = { model ->
    Box(Modifier.padding(top = 16.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() }) { delegate(model) }
}

/** Upstream owns fence parsing/background; this adapter supplies 48 dp copy and wrapping controls. */
@Composable
private fun ReaderCode(code: String, language: String?, style: TextStyle) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    var copyFailed by remember(code) { mutableStateOf(false) }
    var wrap by remember(code) { mutableStateOf(true) }
    MarkdownCodeBackground(color = LocalMarkdownColors.current.codeBackground,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(language?.takeWhile { !it.isWhitespace() }?.ifEmpty { "代码" } ?: "代码", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ wrap = !wrap }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (wrap) "横向滚动" else "自动换行") }
                TextButton({
                    try { clipboard.setText(AnnotatedString(code)); copied = true; copyFailed = false }
                    catch (_: Exception) { copyFailed = true }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (copied) "已复制代码" else "复制代码") }
            }
            if (copyFailed) Text("无法复制，请长按选择代码。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (wrap) Text(code, Modifier.fillMaxWidth(), style = style)
            else Box(Modifier.horizontalScroll(rememberScrollState())) {
                // A finite wide canvas prevents packed-constraint overflow on extreme lines.
                Text(code, Modifier.widthIn(max = 1600.dp), style = style)
            }
        }
    }
}

/** Reuse upstream table/container/inline text; adapt its one-line cells for full text and alignment. */
@Composable
private fun ReaderTableRow(content: String, row: ASTNode, width: Dp, style: TextStyle,
    alignments: List<MarkdownAlignment>, header: Boolean) {
    Row(Modifier.widthIn(min = width).fillMaxWidth().height(IntrinsicSize.Min)) {
        row.children.filter { it.type == GFMTokenTypes.CELL }.forEachIndexed { index, cell ->
            Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                MarkdownTableBasicText(content, cell, style.copy(
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = when (alignments.getOrNull(index)) {
                        MarkdownAlignment.CENTER -> TextAlign.Center
                        MarkdownAlignment.RIGHT -> TextAlign.Right
                        else -> TextAlign.Start
                    }), maxLines = Int.MAX_VALUE)
            }
        }
    }
}
