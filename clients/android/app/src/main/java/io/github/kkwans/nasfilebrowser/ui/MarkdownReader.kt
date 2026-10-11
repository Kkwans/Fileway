package io.github.kkwans.nasfilebrowser.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.R
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal fun markdownExternalIntent(destination: String): Intent? = markdownExternalLink(destination)?.let {
    Intent(Intent.ACTION_VIEW, Uri.parse(it).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
}

/** Opt-in owned-test observations only. No document/clipboard contents or raw
 * exception messages are exposed, and no observer can change the copy result. */
internal data class MarkdownCodeDiagnostic(val phase: String, val instance: Int, val copied: Boolean,
    val failed: Boolean, val exceptionType: String? = null, val geometry: String? = null)
internal val LocalMarkdownCodeDiagnostics = staticCompositionLocalOf<((MarkdownCodeDiagnostic) -> Unit)?> { null }

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
        text = { model -> MarkdownText(model.node.getUnescapedTextInNode(model.content),
            modifier = taskLabelSemantics(model.content, model.node), style = model.typography.text) },
        paragraph = { model -> MarkdownParagraph(model.content, model.node,
            modifier = taskLabelSemantics(model.content, model.node), style = model.typography.paragraph) },
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
            // The task's actual text node owns its state. The visual marker
            // must not become a separate, unlabelled checkbox for TalkBack.
            Checkbox(checked = checked, onCheckedChange = null,
                modifier = Modifier.padding(end = 4.dp).clearAndSetSemantics {})
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
            // Upstream list rendering visits structural leaves too. Printing
            // every unknown node duplicates bullets and CHECK_BOX source tokens.
            if (type == MarkdownElementTypes.HTML_BLOCK || type.name == "HTML_TAG" ||
                type == GFMElementTypes.INLINE_MATH || type == GFMElementTypes.BLOCK_MATH)
                Text(model.node.getTextInNode(model.content).toString(), style = model.typography.code,
                    color = LocalMarkdownColors.current.text)
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

/** 0.39.2 keeps listItem private; bridge its public text/paragraph modifier.
 * Only the current item's first body owns this checkbox, never nested items.
 * Keep native text selection and LinkAnnotation semantics on the same node. */
private fun taskLabelSemantics(content: String, node: ASTNode): Modifier {
    val item = node.parent?.takeIf { it.type == MarkdownElementTypes.LIST_ITEM } ?: return Modifier
    val firstBody = item.children.firstOrNull {
        it.type == MarkdownElementTypes.PARAGRAPH || it.type == MarkdownTokenTypes.TEXT
    }
    if (firstBody !== node) return Modifier
    val task = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX } ?: return Modifier
    val checked = markdownTaskChecked(content, task)
    return Modifier.semantics(mergeDescendants = true) {
        role = Role.Checkbox
        toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        stateDescription = if (checked) "已完成，只读" else "未完成，只读"
    }
}

/** Upstream owns fence parsing/background; this adapter supplies 48 dp copy and wrapping controls. */
@Composable
private fun ReaderCode(code: String, language: String?, style: TextStyle) {
    val clipboard = LocalClipboardManager.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    // This exact opaque surface is the integration input for the highlighter;
    // token contrast must not be computed against the outer document color.
    val codeSurface = if (dark) Color(0xFF171F2A) else Color(0xFFF8FAFC)
    val codeBorder = if (dark) Color(0xFF2B3646) else Color(0xFFE2E8F0)
    val codeText = if (dark) Color(0xFFE5EAF1) else Color(0xFF1F2937)
    val codeMuted = if (dark) Color(0xFF9BAABC) else Color(0xFF64748B)
    val codeStyle = style.copy(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace, color = codeText)
    val highlighted = rememberMarkdownCodeHighlight(code, language, codeSurface)
    var copied by remember(code) { mutableStateOf(false) }
    var copyFailed by remember(code) { mutableStateOf(false) }
    var wrap by remember(code) { mutableStateOf(true) }
    val observer = LocalMarkdownCodeDiagnostics.current
    val instance = remember(code) { Any() }
    fun observe(phase: String, exceptionType: String? = null, geometry: String? = null) {
        if (observer != null) runCatching {
            observer(MarkdownCodeDiagnostic(phase, System.identityHashCode(instance), copied, copyFailed, exceptionType, geometry))
        }
    }
    if (observer != null) {
        SideEffect { observe("composed") }
        DisposableEffect(instance) { observe("attached"); onDispose { observe("disposed") } }
    }
    val copyModifier = if (observer == null) Modifier.size(48.dp) else Modifier.size(48.dp)
        .onGloballyPositioned { observe("layout", geometry = it.boundsInWindow().toString()) }
        .pointerInput(instance, observer) {
            awaitPointerEventScope {
                while (true) {
                    for ((pass, stage) in listOf(PointerEventPass.Initial to "initial", PointerEventPass.Main to "main")) {
                        val event = awaitPointerEvent(pass)
                        event.changes.forEach { change ->
                            if (change.pressed != change.previousPressed) observe("pointer-$stage-${if (change.pressed) "down" else "up"}",
                                geometry = "${change.position.x},${change.position.y};consumed=${change.isConsumed}")
                        }
                    }
                }
            }
        }
    val copyCode = {
        observe("callback-enter")
        try { clipboard.setText(AnnotatedString(code)); copied = true; copyFailed = false; observe("copy-success") }
        catch (error: Exception) { copyFailed = true; observe("copy-failed", exceptionType = error.javaClass.simpleName) }
    }
    MarkdownCodeBackground(color = codeSurface, border = BorderStroke(1.dp, codeBorder),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(language?.takeWhile { !it.isWhitespace() }?.ifEmpty { "代码" } ?: "代码",
                    Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp), color = codeMuted)
                IconButton({ wrap = !wrap }, modifier = Modifier.size(48.dp).clearAndSetSemantics {
                    contentDescription = if (wrap) "横向滚动代码" else "自动换行代码"
                    stateDescription = if (wrap) "当前自动换行" else "当前横向滚动"
                    role = Role.Button
                    onClick { wrap = !wrap; true }
                }) { Icon(painterResource(R.drawable.ic_wrap_text), null, Modifier.size(20.dp), tint = codeMuted) }
                IconButton(copyCode, modifier = copyModifier.clearAndSetSemantics {
                    contentDescription = if (copied) "已复制代码" else "复制代码"
                    stateDescription = if (copied) "已复制" else "复制完整代码"
                    role = Role.Button
                    onClick { copyCode(); true }
                }) { Icon(painterResource(if (copied) R.drawable.art_check else R.drawable.ic_copy), null,
                    Modifier.size(20.dp), tint = if (copied) codeText else codeMuted) }
            }
            if (copyFailed) Text("无法复制，请长按选择代码。", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (wrap) Text(highlighted, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), style = codeStyle)
            else Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).horizontalScroll(rememberScrollState())) {
                // A finite wide canvas prevents packed-constraint overflow on extreme lines.
                Text(highlighted, Modifier.widthIn(max = 1600.dp), style = codeStyle)
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
