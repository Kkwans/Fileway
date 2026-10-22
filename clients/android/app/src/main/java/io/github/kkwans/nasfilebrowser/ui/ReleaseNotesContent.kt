package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.markdownDimens
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.update.AppRelease
import io.github.kkwans.nasfilebrowser.update.UpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/** Native markdown inside the update page's one outer scroll; no network image binding. */
@Composable internal fun ReleaseNotesCard(release: AppRelease) {
    val context = LocalContext.current
    fun open(uri: String) {
        val intent = markdownExternalIntent(uri)
        try {
            if (intent != null) context.startActivity(intent)
            else android.widget.Toast.makeText(context, "此链接暂不支持打开", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show() }
    }
    val uriHandler = remember(context) { object : UriHandler { override fun openUri(uri: String) { open(uri) } } }
    val preview by produceState<MarkdownPreview?>(null, release.tag, release.notes) {
        value = withContext(Dispatchers.Default) { runCatching { markdownPreview(decodeDocumentText(release.notes.toByteArray(Charsets.UTF_8))) }
            .getOrElse { MarkdownPreview.SourceOnly("说明暂以原文显示") } }
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(release.displayVersion, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (release.preview) "预览版" else "正式版", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ open(UpdatePolicy.REPOSITORY + "/releases/tag/" + release.tag) }) {
                    Icon(painterResource(R.drawable.ic_link), "打开${release.displayVersion}完整发布说明", Modifier.size(20.dp))
                }
            }
            if (release.notesTruncated) Text("说明较长，完整内容可通过标题右侧链接查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (release.notes.isBlank()) Text("此版本未提供更新说明。", style = MaterialTheme.typography.bodyMedium)
            else when (val result = preview) {
                null -> {
                    var showParsing by remember(release.tag) { mutableStateOf(false) }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(350); showParsing = true }
                    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                        if (showParsing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                is MarkdownPreview.SourceOnly -> {
                    Text("${result.reason}；完整内容可通过标题右侧链接查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(release.notes, style = MaterialTheme.typography.bodyMedium, maxLines = 160,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
                is MarkdownPreview.Reading -> {
                    val doc = result.document
                    if (release.notes.isBlank()) Text("此版本未提供更新说明。", style = MaterialTheme.typography.bodyMedium)
                    // Do not silently omit extension definitions masked by the document reader.
                    else if (doc.extensions.frontMatter != null || doc.extensions.footnotes.isNotEmpty() || doc.extensions.tocOffsets.isNotEmpty() || doc.hasLiteralMath) {
                        Text("扩展语法以原文显示；完整说明可通过标题右侧链接查看。", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(release.notes, style = MaterialTheme.typography.bodyMedium, maxLines = 160,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    } else {
                        val body = MaterialTheme.typography.bodyMedium
                        val heading = body.copy(fontWeight = FontWeight.SemiBold)
                        val code = body.copy(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
                        val typography = markdownTypography(h1 = MaterialTheme.typography.titleLarge, h2 = MaterialTheme.typography.titleMedium,
                            h3 = heading, h4 = heading, h5 = heading, h6 = heading,
                            text = body, paragraph = body, ordered = body, bullet = body, list = body, quote = body,
                            code = code, inlineCode = body.copy(fontFamily = FontFamily.Monospace), table = code)
                        val components = markdownComponents(
                            image = { model -> Text(markdownImageLabel(model.content, model.node), style = MaterialTheme.typography.bodySmall) },
                            codeFence = { MarkdownCodeFence(it.content, it.node, it.typography.code) { value, language, style -> ReaderCode(value, language, style) } },
                            codeBlock = { MarkdownCodeBlock(it.content, it.node, it.typography.code) { value, language, style -> ReaderCode(value, language, style) } },
                            table = {
                                val alignments = remember(it.content, it.node) { markdownTableAlignments(it.content, it.node) }
                                MarkdownTable(it.content, it.node, it.typography.table,
                                    headerBlock = { content, row, width, style -> ReaderTableRow(content, row, width, style, alignments, true) },
                                    rowBlock = { content, row, width, style -> ReaderTableRow(content, row, width, style, alignments, false) })
                            },
                            custom = { type, model ->
                                if (type == MarkdownElementTypes.HTML_BLOCK || type.name == "HTML_TAG")
                                    Text(model.node.getTextInNode(model.content).toString(), style = model.typography.code)
                            },
                        )
                        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                            SelectionContainer {
                                Markdown(state = doc.state, typography = typography, modifier = Modifier.fillMaxWidth(),
                                    padding = markdownPadding(block = 6.dp, list = 4.dp, listItemTop = 2.dp, listItemBottom = 2.dp, listIndent = 16.dp),
                                    dimens = markdownDimens(tableMaxWidth = 840.dp, tableCellWidth = 160.dp, tableCellPadding = 12.dp),
                                    annotator = remember(doc) { markdownSafetyAnnotator(doc) }, imageTransformer = remember { NoOpImageTransformerImpl() },
                                    components = components, success = { state, resolved, modifier ->
                                        Column(modifier.fillMaxWidth()) {
                                            state.node.children.forEach { node ->
                                                if (node.type != MarkdownTokenTypes.EOL && node.type != MarkdownTokenTypes.WHITE_SPACE)
                                                    MarkdownElement(node, resolved, state.content)
                                            }
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}
