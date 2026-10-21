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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.mikepenz.markdown.compose.MarkdownElement
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import io.github.kkwans.nasfilebrowser.app.DocumentImageBinding
import io.github.kkwans.nasfilebrowser.app.DocumentImageLease
import io.github.kkwans.nasfilebrowser.app.DocumentImageLimitException
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

internal fun markdownExternalIntent(destination: String): Intent? = markdownExternalLink(destination)?.let {
    Intent(Intent.ACTION_VIEW, Uri.parse(it).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
}

/** Opt-in owned-test observations only. No document/clipboard contents or raw
 * exception messages are exposed, and no observer can change the copy result. */
internal data class MarkdownCodeDiagnostic(val phase: String, val instance: Int, val copied: Boolean,
    val failed: Boolean, val exceptionType: String? = null, val geometry: String? = null)
internal val LocalMarkdownCodeDiagnostics = staticCompositionLocalOf<((MarkdownCodeDiagnostic) -> Unit)?> { null }
private val LocalReaderNoteScope = staticCompositionLocalOf<Int?> { null }
private val LocalReaderImageReferences = staticCompositionLocalOf<Map<Int, MarkdownImageReference>> { emptyMap() }
private val LocalReaderMathFormulas = staticCompositionLocalOf<Map<Int, MarkdownMathFormula>> { emptyMap() }
private val LocalReaderMathResults = staticCompositionLocalOf<Map<String, MarkdownMathResult>> { emptyMap() }
private val LocalReaderMathEmPx = staticCompositionLocalOf { 16f }
private fun footnoteUri(reference: MarkdownFootnoteReference) = "fileway-internal:${reference.tag}"

/** Only security and explicit unsupported-content handling override upstream inline rendering. */
internal fun markdownSafetyAnnotator(document: MarkdownDocument,
    imageDestinations: Map<Int, MarkdownImageReference> = emptyMap(), imageSource: ResourceRef? = null,
    mathFormulas: Map<Int, MarkdownMathFormula> = emptyMap(), mathResults: Map<String, MarkdownMathResult> = emptyMap()): MarkdownAnnotator = markdownAnnotator { content, node ->
    when {
        node.startOffset in mathFormulas && (node.type == GFMElementTypes.INLINE_MATH || node.type == GFMElementTypes.BLOCK_MATH) -> {
            val formula = mathFormulas.getValue(node.startOffset)
            appendMarkdownMath(formula, mathResults[formula.key]); true
        }
        document.extensions.references[node.startOffset]?.endOffset == node.endOffset -> {
            val reference = document.extensions.references.getValue(node.startOffset)
            val tableCell = generateSequence(node.parent) { it.parent }.any { it.type == GFMTokenTypes.CELL }
            if (tableCell) withLink(LinkAnnotation.Url(footnoteUri(reference))) { append("[${reference.number}]") }
            else appendInlineContent(reference.tag, "[${reference.number}]")
            true
        }
        node.type == MarkdownElementTypes.IMAGE -> {
            val destination = imageDestinations[node.startOffset]?.destination
            // The upstream table's BasicText does not create image inline
            // content. Retain alt text there rather than showing an internal key.
            val tableCell = generateSequence(node.parent) { it.parent }.any { it.type == GFMTokenTypes.CELL }
            if (!tableCell && imageSource != null && destination != null && resolveMarkdownImage(imageSource, destination) is MarkdownImageResolution.Nas)
                appendInlineContent(MARKDOWN_TAG_IMAGE_URL, node.startOffset.toString())
            else append(markdownImageLabel(content, node))
            true
        }
        node.type.name == "HTML_TAG" || node.type == GFMElementTypes.INLINE_MATH -> {
            append(node.getTextInNode(content)); true
        }
        node.type == MarkdownElementTypes.INLINE_LINK || node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK ||
            node.type == MarkdownElementTypes.FULL_REFERENCE_LINK -> {
            val label = node.findChildOfType(MarkdownElementTypes.LINK_TEXT) ?: node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
            val target = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content)
                ?: node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)?.let(document.state.referenceLinkHandler::find)
            if (target.isNullOrEmpty() && (node.getTextInNode(content).startsWith("[^") ||
                    node.getTextInNode(content).toString().equals("[TOC]", ignoreCase = true))) {
                append(node.getTextInNode(content)); true // Undefined notes retain the exact marker.
            } else if (target == null || (markdownExternalLink(target) == null && document.navigation.resolve(target) == null)) {
                append(label?.getUnescapedTextInNode(content)?.removePrefix("[")?.removeSuffix("]") ?: node.getTextInNode(content)); true
            } else false
        }
        node.type == MarkdownElementTypes.AUTOLINK || node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val target = node.getUnescapedTextInNode(content).removePrefix("<").removeSuffix(">")
            if (markdownExternalLink(target) == null && document.navigation.resolve(target) == null) { append(target); true } else false
        }
        else -> false
    }
}

private sealed interface ReaderTarget {
    data class Heading(val heading: MarkdownHeading) : ReaderTarget
    data class Note(val offset: Int) : ReaderTarget
    data class Reference(val reference: MarkdownFootnoteReference) : ReaderTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownBody(document: MarkdownDocument, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier,
    imageBinding: DocumentImageBinding? = null, imageCache: CacheController? = null, imageSource: ResourceRef? = null,
    outlineOpen: Boolean = false, onDismissOutline: () -> Unit = {}) {
    val listState = key(document) { rememberLazyListState() }
    val headingRequesters = remember(document) { mutableStateMapOf<Int, BringIntoViewRequester>() }
    val noteRequesters = remember(document) { mutableStateMapOf<Int, BringIntoViewRequester>() }
    val referenceRequesters = remember(document) { mutableStateMapOf<String, BringIntoViewRequester>() }
    val blockRequesters = remember(document) { mutableStateMapOf<String, BringIntoViewRequester>() }
    val allReferences = remember(document) { document.extensions.references.values.toList() +
        document.extensions.footnotes.flatMap { it.body?.extensions?.references?.values.orEmpty() } }
    val referencesByUri = remember(allReferences) { allReferences.associateBy(::footnoteUri) }
    val lastReferences = remember(document) { mutableStateMapOf<Int, MarkdownFootnoteReference>() }
    var returnHistory by remember(document) { mutableStateOf<List<MarkdownFootnoteReference>>(emptyList()) }
    var requested by remember(document) { mutableStateOf<Pair<Int, ReaderTarget>?>(null) }
    fun request(target: ReaderTarget) { requested = ((requested?.first ?: 0) + 1) to target }
    fun navigate(heading: MarkdownHeading) {
        returnHistory = emptyList(); request(ReaderTarget.Heading(heading))
    }
    fun openFootnote(reference: MarkdownFootnoteReference) {
        if (reference !in allReferences) return
        lastReferences[reference.definitionOffset] = reference
        returnHistory = (returnHistory + reference).takeLast(64)
        request(ReaderTarget.Note(reference.definitionOffset))
    }
    fun returnTo(reference: MarkdownFootnoteReference) {
        if (reference !in allReferences) return
        returnHistory = if (returnHistory.lastOrNull() == reference) returnHistory.dropLast(1) else emptyList()
        request(ReaderTarget.Reference(reference))
    }
    val mainOffset = if (document.extensions.frontMatter == null) 0 else 1
    val notesOffset = mainOffset + document.state.node.children.size
    LaunchedEffect(document, requested) {
        when (val target = requested?.second ?: return@LaunchedEffect) {
            is ReaderTarget.Heading -> {
                if (target.heading !in document.navigation.headings) return@LaunchedEffect
                listState.scrollToItem(mainOffset + target.heading.rootIndex)
                snapshotFlow { headingRequesters[target.heading.startOffset] }.filterNotNull().first().bringIntoView()
            }
            is ReaderTarget.Note -> {
                val index = document.extensions.footnotes.indexOfFirst { it.startOffset == target.offset }
                if (index < 0) return@LaunchedEffect
                listState.scrollToItem(notesOffset + index)
                snapshotFlow { noteRequesters[target.offset] }.filterNotNull().first().bringIntoView()
            }
            is ReaderTarget.Reference -> {
                val reference = target.reference
                if (reference !in allReferences) return@LaunchedEffect
                val index = reference.scope?.let { scope -> document.extensions.footnotes.indexOfFirst { it.startOffset == scope }
                    .takeIf { it >= 0 }?.let { notesOffset + it } } ?: (mainOffset + reference.rootIndex)
                listState.scrollToItem(index)
                // Table BasicText has no inline-content slot. Its actual root
                // block remains a safe return target rather than guessing glyphs.
                snapshotFlow { if (reference.inline) referenceRequesters[reference.tag]
                    else blockRequesters["${reference.scope ?: "main"}/${reference.rootIndex}"] }
                    .filterNotNull().first().bringIntoView()
            }
        }
    }
    BackHandler(enabled = returnHistory.isNotEmpty() && !outlineOpen) { returnHistory.lastOrNull()?.let(::returnTo) }
    BackHandler(enabled = outlineOpen, onBack = onDismissOutline)
    if (outlineOpen) ModalBottomSheet(onDismissRequest = onDismissOutline,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("目录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onDismissOutline, Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.art_close), "关闭目录")
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                itemsIndexed(document.navigation.headings, key = { _, heading -> heading.startOffset }) { index, heading ->
                    val choose = { onDismissOutline(); navigate(heading) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = choose)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = "目录项 ${index + 1}，${heading.title}，${heading.level}级标题"
                            onClick { choose(); true }
                        }.padding(start = (20 + (heading.level - 1) * 14).dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
                        Text(heading.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
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
    val footnoteInlineContent = markdownInlineContent(allReferences.associate { reference ->
        val ordinal = document.extensions.footnotes.firstOrNull { it.startOffset == reference.definitionOffset }
            ?.references?.indexOf(reference)?.plus(1) ?: 1
        reference.tag to InlineTextContent(Placeholder((reference.number.toString().length * 8 + 12).sp, 20.sp,
            PlaceholderVerticalAlign.AboveBaseline)) {
            val requester = remember(reference.tag) { BringIntoViewRequester() }
            DisposableEffect(reference.tag, referenceRequesters) {
                referenceRequesters[reference.tag] = requester
                onDispose { if (referenceRequesters[reference.tag] === requester) referenceRequesters.remove(reference.tag) }
            }
            val choose = { openFootnote(reference) }
            Text("[${reference.number}]", Modifier.bringIntoViewRequester(requester).clickable(onClick = choose)
                .clearAndSetSemantics {
                    role = Role.Button; contentDescription = "脚注 ${reference.number}，第 $ordinal 处引用"
                    onClick { choose(); true }
                }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    })
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val resolvedTypeface by resolver.resolve(body.fontFamily, body.fontWeight ?: FontWeight.Normal,
        body.fontStyle ?: FontStyle.Normal, body.fontSynthesis ?: FontSynthesis.All)
    val emPx = with(density) { body.fontSize.toPx() }
    val exPx = remember(resolvedTypeface, emPx) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = resolvedTypeface as? android.graphics.Typeface ?: android.graphics.Typeface.DEFAULT
            textSize = emPx
        }
        android.graphics.Rect().also { paint.getTextBounds("x", 0, 1, it) }.height().toFloat().coerceAtLeast(.5f)
    }
    var readerWidthPx by remember(document) { mutableFloatStateOf(0f) }
    val mathScopes = remember(document) { markdownMathScopes(document) }
    val mathFormulas = remember(mathScopes) { mathScopes.values.flatMap { it.values } }
    val tooManyFormulas = mathFormulas.size > MarkdownMathBudget.FORMULAS
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb() and 0xffffff
    val metrics = MarkdownMathMetrics(emPx, exPx, (readerWidthPx - with(density) { 40.dp.toPx() }).coerceAtLeast(1f),
        java.lang.String.format(java.util.Locale.ROOT, "#%06x", foreground))
    val mathResults = if (readerWidthPx > 0f && !tooManyFormulas) rememberMarkdownMathResults(document, mathFormulas, metrics) else emptyMap()
    val mathBinding = rememberMarkdownMathBinding(mathResults, emPx, footnoteInlineContent.inlineContent.keys)
    val inlineContent = markdownInlineContent(footnoteInlineContent.inlineContent + mathBinding.inlineContent)
    val uriHandler = remember(document, onOpenLink) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri in referencesByUri) referencesByUri[uri]?.let(::openFootnote)
                else if (uri.startsWith('#')) document.navigation.resolve(uri)?.let(::navigate)
                else markdownExternalLink(uri)?.let(onOpenLink)
            }
        }
    }
    val references = remember(document) { markdownExtensionImageReferences(document).associateBy { it.startOffset } }
    val canReadImages = imageBinding != null && imageCache != null && imageSource != null
    val transformer = if (canReadImages) remember(imageBinding, imageCache, imageSource, references) {
        NasMarkdownImageTransformer(imageBinding!!, imageCache!!, imageSource!!, references)
    } else remember { NoOpImageTransformerImpl() }
    val annotator = remember(document, canReadImages, imageSource, references, mathScopes, mathResults) {
        markdownSafetyAnnotator(document, if (canReadImages) references else emptyMap(), if (canReadImages) imageSource else null,
            mathScopes[null].orEmpty(), mathResults)
    }
    val components = markdownComponents(
        text = { model ->
            if (model.node.hasReaderMath()) ReaderAnnotatedMath(model.content, model.node, model.typography.text,
                taskLabelSemantics(model.content, model.node))
            else MarkdownText(model.node.getUnescapedTextInNode(model.content),
                modifier = taskLabelSemantics(model.content, model.node), style = model.typography.text)
        },
        paragraph = { model ->
            val display = model.node.children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
                .singleOrNull()?.takeIf { it.type == GFMElementTypes.BLOCK_MATH }?.let { LocalReaderMathFormulas.current[it.startOffset] }
            if (display != null) MarkdownMathBlock(display, LocalReaderMathResults.current[display.key])
            else if (model.node.hasReaderMath()) ReaderAnnotatedMath(model.content, model.node, model.typography.paragraph,
                taskLabelSemantics(model.content, model.node))
            else MarkdownParagraph(model.content, model.node,
                modifier = taskLabelSemantics(model.content, model.node), style = model.typography.paragraph)
        },
        heading1 = spacedHeading(CurrentComponentsBridge.heading1, headingRequesters),
        heading2 = spacedHeading(CurrentComponentsBridge.heading2, headingRequesters),
        heading3 = spacedHeading(CurrentComponentsBridge.heading3, headingRequesters),
        heading4 = spacedHeading(CurrentComponentsBridge.heading4, headingRequesters),
        heading5 = spacedHeading(CurrentComponentsBridge.heading5, headingRequesters),
        heading6 = spacedHeading(CurrentComponentsBridge.heading6, headingRequesters),
        setextHeading1 = spacedHeading(CurrentComponentsBridge.setextHeading1, headingRequesters),
        setextHeading2 = spacedHeading(CurrentComponentsBridge.setextHeading2, headingRequesters),
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
        image = { model ->
            val reference = LocalReaderImageReferences.current[model.node.startOffset]
            val resolved = if (imageSource != null && reference?.destination != null) resolveMarkdownImage(imageSource, reference.destination) else null
            if (canReadImages && resolved is MarkdownImageResolution.Nas) {
                LocalImageTransformer.current.transform(model.node.startOffset.toString())?.let { image ->
                    Image(image.painter, image.contentDescription, image.modifier, image.alignment, image.contentScale)
                }
            } else Text(markdownImageLabel(model.content, model.node), style = model.typography.text)
        },
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
            val formula = LocalReaderMathFormulas.current[model.node.startOffset]
            if (formula != null && (type == GFMElementTypes.INLINE_MATH || type == GFMElementTypes.BLOCK_MATH))
                MarkdownMathBlock(formula, LocalReaderMathResults.current[formula.key])
            else if (type == MarkdownElementTypes.HTML_BLOCK || type.name == "HTML_TAG" ||
                type == GFMElementTypes.INLINE_MATH || type == GFMElementTypes.BLOCK_MATH)
                Text(model.node.getTextInNode(model.content).toString(), style = model.typography.code,
                    color = LocalMarkdownColors.current.text)
        },
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().onSizeChanged { readerWidthPx = it.width.toFloat() }) {
            if (document.hasImages && !canReadImages)
                ReaderNotice("图片暂未加载，可切换源码查看地址。")
            if ((transformer as? NasMarkdownImageTransformer)?.limitReached == true)
                ReaderNotice("图片超过读取上限（单张 GIF 10 MiB、原图合计 24 MiB），暂未加载；原文件未改变，可在源码中查看地址。")
            if (document.hasLiteralHtml) ReaderNotice("HTML 按原文显示，不执行脚本或加载其中资源。")
            if (tooManyFormulas) ReaderNotice("公式超过安全排版数量限制，已保留原公式。")
            else if (mathFormulas.isNotEmpty() && mathResults.isEmpty()) ReaderNotice("公式正在排版，原文保留。")
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalReaderImageReferences provides references,
                    LocalReaderMathFormulas provides mathScopes[null].orEmpty(), LocalReaderMathResults provides mathResults,
                    LocalReaderMathEmPx provides emPx) {
                    if (document.state.content.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有可呈现的正文，可切换源码查看。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Markdown(state = document.state, typography = typography,
                        modifier = Modifier.fillMaxSize(),
                        padding = markdownPadding(block = 10.dp, list = 6.dp, listItemTop = 4.dp, listItemBottom = 4.dp, listIndent = 18.dp),
                        dimens = markdownDimens(tableMaxWidth = 840.dp, tableCellWidth = 160.dp, tableCellPadding = 12.dp),
                        imageTransformer = transformer, annotator = annotator, components = components, inlineContent = inlineContent,
                        extendedSpans = mathBinding.extendedSpans,
                        success = { state, resolved, bodyModifier ->
                            // 0.39.2 exposes no LazyListState on LazyMarkdownSuccess.
                            // Keep its exact nodes/keys/public element renderer and
                            // add only the application-owned scroll state.
                            LazyColumn(modifier = bodyModifier, state = listState,
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                                document.extensions.frontMatter?.let { metadata -> item(key = "yaml-front-matter") {
                                    var expanded by remember(document) { mutableStateOf(false) }
                                    val toggle = { expanded = !expanded }
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = toggle)
                                        .clearAndSetSemantics {
                                            role = Role.Button; contentDescription = "YAML 元数据"
                                            stateDescription = if (expanded) "已展开" else "已折叠"
                                            onClick { toggle(); true }
                                        }, verticalAlignment = Alignment.CenterVertically) {
                                        Text("YAML 元数据", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (expanded) {
                                        if (metadata.raw.length <= 16_384 && metadata.raw.count { it == '\n' } <= 512)
                                            ReaderCode(metadata.raw, "yaml", typography.code)
                                        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                                            items(metadata.raw.chunked(4096)) { chunk -> Text(chunk, style = typography.code) }
                                        }
                                    }
                                } }
                                itemsIndexed(state.node.children, key = { _, node -> node.startOffset }) { index, node ->
                                    ReaderBlock("main/$index", blockRequesters) {
                                        if (node.startOffset in document.extensions.tocOffsets) {
                                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                                Text("目录", style = MaterialTheme.typography.titleSmall)
                                                if (document.navigation.headings.isEmpty()) Text("暂无标题", style = typography.text)
                                                document.navigation.headings.forEach { heading ->
                                                    val choose = { navigate(heading) }
                                                    FilewayTextButton(choose, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                                        .padding(start = ((heading.level - 1) * 12).dp)
                                                        .clearAndSetSemantics {
                                                            role = Role.Button; contentDescription = "正文目录：${heading.title}"
                                                            onClick { choose(); true }
                                                        }) {
                                                        Text(heading.title, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                            }
                                        } else MarkdownElement(node, resolved, state.content)
                                    }
                                }
                                items(document.extensions.footnotes, key = { "footnote/${it.startOffset}" }) { note ->
                                    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val requester = remember(note.startOffset) { BringIntoViewRequester() }
                                        DisposableEffect(note.startOffset, noteRequesters) {
                                            noteRequesters[note.startOffset] = requester
                                            onDispose { if (noteRequesters[note.startOffset] === requester) noteRequesters.remove(note.startOffset) }
                                        }
                                        Text(note.number?.let { "脚注 $it" } ?: if (note.duplicate) "重复脚注：${note.label}" else "未引用脚注：${note.label}",
                                            Modifier.bringIntoViewRequester(requester).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                                        val noteDocument = note.body
                                        if (noteDocument == null) ReaderCode(note.raw, "markdown", typography.code)
                                        else {
                                            val noteImages = remember(noteDocument) { markdownExtensionImageReferences(noteDocument).associateBy { it.startOffset } }
                                            // Reuse the same capability binding/pool and unchanged image transformer.
                                            // Each Markdown composition owns its own local AST-offset namespace.
                                            val noteTransformer = if (canReadImages) remember(noteDocument, imageBinding, imageCache, imageSource) {
                                                NasMarkdownImageTransformer(imageBinding!!, imageCache!!, imageSource!!, noteImages)
                                            } else transformer
                                            val noteMath = mathScopes[note.startOffset].orEmpty()
                                            val noteAnnotator = remember(noteDocument, canReadImages, imageSource, noteMath, mathResults) {
                                                markdownSafetyAnnotator(noteDocument, if (canReadImages) noteImages else emptyMap(), if (canReadImages) imageSource else null,
                                                    noteMath, mathResults)
                                            }
                                            if ((noteTransformer as? NasMarkdownImageTransformer)?.limitReached == true)
                                                ReaderNotice("图片超过读取上限（单张 GIF 10 MiB、原图合计 24 MiB），暂未加载；原文件未改变，可在源码中查看地址。")
                                            CompositionLocalProvider(LocalReaderNoteScope provides note.startOffset,
                                                LocalReaderImageReferences provides noteImages, LocalReaderMathFormulas provides noteMath) {
                                                Markdown(state = noteDocument.state, typography = typography, modifier = Modifier.fillMaxWidth(),
                                                    padding = markdownPadding(block = 10.dp, list = 6.dp, listItemTop = 4.dp, listItemBottom = 4.dp, listIndent = 18.dp),
                                                    dimens = markdownDimens(tableMaxWidth = 840.dp, tableCellWidth = 160.dp, tableCellPadding = 12.dp),
                                                    imageTransformer = noteTransformer, annotator = noteAnnotator, components = components, inlineContent = inlineContent,
                                                    extendedSpans = mathBinding.extendedSpans,
                                                    success = { noteState, noteComponents, noteModifier ->
                                                        Column(noteModifier) { noteState.node.children.forEachIndexed { index, child ->
                                                            ReaderBlock("${note.startOffset}/$index", blockRequesters) {
                                                                MarkdownElement(child, noteComponents, noteState.content)
                                                            }
                                                        } }
                                                    })
                                            }
                                        }
                                        val back = lastReferences[note.startOffset] ?: note.references.firstOrNull()
                                        if (back != null) {
                                            val choose = { returnTo(back) }
                                            FilewayTextButton(choose, Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
                                                role = Role.Button; contentDescription = "返回脚注 ${note.number} 的引用"
                                                onClick { choose(); true }
                                            }) { Text("返回引用") }
                                        }
                                    }
                                }
                            }
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

private fun spacedHeading(delegate: MarkdownComponent,
    requesters: MutableMap<Int, BringIntoViewRequester>): MarkdownComponent = { model ->
    val requester = remember(model.node) { BringIntoViewRequester() }
    if (LocalReaderNoteScope.current == null) DisposableEffect(model.node, requesters) {
        requesters[model.node.startOffset] = requester
        onDispose { if (requesters[model.node.startOffset] === requester) requesters.remove(model.node.startOffset) }
    }
    Box(Modifier.bringIntoViewRequester(requester).padding(top = 16.dp, bottom = 6.dp)
        .semantics(mergeDescendants = true) { heading() }) {
        if (!model.node.hasReaderMath()) delegate(model)
        else {
            val style = when (model.node.type) {
                MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> model.typography.h1
                MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> model.typography.h2
                MarkdownElementTypes.ATX_3 -> model.typography.h3
                MarkdownElementTypes.ATX_4 -> model.typography.h4
                MarkdownElementTypes.ATX_5 -> model.typography.h5
                else -> model.typography.h6
            }
            val child = model.node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT)
                ?: model.node.findChildOfType(MarkdownTokenTypes.SETEXT_CONTENT) ?: model.node
            ReaderAnnotatedMath(model.content, child, style, Modifier.semantics { heading() })
        }
    }
}

private fun ASTNode.hasReaderMath() = markdownDescendants().any {
    it.type == GFMElementTypes.INLINE_MATH || it.type == GFMElementTypes.BLOCK_MATH
}

/** The upstream inline builder retains emphasis, links, images and footnotes.
 * Only formula layout uses the native math wrapper and its actual text baseline. */
@Composable
private fun ReaderAnnotatedMath(content: String, node: ASTNode, style: TextStyle, modifier: Modifier = Modifier) {
    val settings = annotatorSettings()
    val text = remember(content, node, style, settings) { buildAnnotatedString {
        pushStyle(style.toSpanStyle()); buildMarkdownAnnotatedString(content, node, settings); pop()
    } }
    val density = LocalDensity.current
    val scale = with(density) { style.fontSize.toPx() } / LocalReaderMathEmPx.current
    val original = LocalReaderMathResults.current
    val results = remember(original, scale) { original.mapValues { (_, result) ->
        if (result is MarkdownMathResult.Rendered) result.copy(widthPx = result.widthPx * scale,
            heightPx = result.heightPx * scale, ascentPx = result.ascentPx * scale, depthPx = result.depthPx * scale) else result
    } }
    MarkdownMathText(text, results, modifier, style)
}

@Composable
private fun ReaderBlock(key: String, requesters: MutableMap<String, BringIntoViewRequester>, content: @Composable () -> Unit) {
    val requester = remember(key) { BringIntoViewRequester() }
    DisposableEffect(key, requesters) {
        requesters[key] = requester
        onDispose { if (requesters[key] === requester) requesters.remove(key) }
    }
    Box(Modifier.fillMaxWidth().bringIntoViewRequester(requester)) { content() }
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
                val cellStyle = style.copy(
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = when (alignments.getOrNull(index)) {
                        MarkdownAlignment.CENTER -> TextAlign.Center
                        MarkdownAlignment.RIGHT -> TextAlign.Right
                        else -> TextAlign.Start
                    })
                if (cell.hasReaderMath()) ReaderAnnotatedMath(content, cell, cellStyle)
                else MarkdownTableBasicText(content, cell, cellStyle, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

/** Reuses the existing authenticated NAS preview and Coil loader. The renderer
 * receives a painter/local capability, never a server URL or auth headers. */
private class NasMarkdownImageTransformer(private val binding: DocumentImageBinding, private val cache: CacheController,
    private val source: ResourceRef, private val references: Map<Int, MarkdownImageReference>) : ImageTransformer {
    var limitReached by mutableStateOf(false)
        private set
    @Composable override fun transform(link: String): ImageData? {
        val reference = link.toIntOrNull()?.let(references::get) ?: return null
        val destination = reference.destination ?: return null
        val target = resolveMarkdownImage(source, destination) as? MarkdownImageResolution.Nas ?: return null
        return key(binding.key, target.wirePath) {
            val settings by cache.state.collectAsStateWithLifecycle()
            val context = LocalContext.current.applicationContext
            val placeholder = painterResource(R.drawable.ic_image)
            var owned by remember { mutableStateOf<DocumentImageLease?>(null) }
            var failed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                var lease: DocumentImageLease? = null
                try {
                    lease = binding.load(target.wirePath)
                    check(binding.current()) { "文档来源已切换" }
                    owned = lease
                    awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failed = true; if (error is DocumentImageLimitException) limitReached = true }
                finally { lease?.let { image -> withContext(NonCancellable) { runCatching { binding.release(image.lease) } } } }
            }
            val asset = owned
            val current = binding.current()
            if (asset == null || !current) {
                ImageData(placeholder,
                    contentDescription = "图片：${reference.alt.ifEmpty { "未提供说明" }} · ${if (failed) "暂未加载" else "正在加载"}")
            } else {
                val request = remember(asset, settings.revision) {
                    val file = asset.file
                    val cacheKey = cache.key(binding.accountKey, target.wirePath,
                        "markdown/${binding.key}/${source.wirePath}/${source.size}/${source.modified}/${file.size}/${file.modified}/" +
                            if (asset.data != null) "gif-bounded" else "contain")
                    ImageRequest.Builder(context).data(asset.data ?: asset.lease.url).size(512, 512)
                        .memoryCacheKey(cacheKey).diskCacheKey(cacheKey)
                        .diskCachePolicy(if (settings.settings.thumbnailMB == 0L) CachePolicy.DISABLED else CachePolicy.ENABLED).build()
                }
                val painter = rememberAsyncImagePainter(request, imageLoader = cache.thumbnailLoader.value,
                    placeholder = placeholder, error = placeholder, contentScale = ContentScale.Fit)
                val state by painter.state.collectAsState()
                val phase = when (state) {
                    is AsyncImagePainter.State.Success -> if (asset.data != null) "原图已加载" else "预览已加载"
                    is AsyncImagePainter.State.Error -> "暂未加载"
                    else -> "正在加载"
                }
                ImageData(if (binding.current()) painter else placeholder,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    contentDescription = "图片：${reference.alt.ifEmpty { "未提供说明" }} · $phase", contentScale = ContentScale.Fit)
            }
        }
    }
}
