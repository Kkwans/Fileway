package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.directoryTrail
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun NasSubtitlePicker(model: ClientModel, movie: ResourceRef, chosen: () -> Unit) {
    val parent = remember(movie) { directoryTrail(movie.path, movie.wirePath).dropLast(1).lastOrNull() }
    var path by remember(movie) { mutableStateOf(parent?.path ?: "/") }
    var wire by remember(movie) { mutableStateOf(parent?.wirePath ?: "/") }
    var files by remember { mutableStateOf<List<ResourceRef>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<ResourceRef?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(movie, path, wire, attempt) {
        busy = true; error = null; files = emptyList()
        try { files = model.subtitleFiles(path, wire) }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "目录读取失败，请重试" }
        finally { busy = false }
    }
    Column {
        DirectoryBreadcrumbs(path, wire, busy, {
            val previous = directoryTrail(path, wire).dropLast(1).lastOrNull()
            if (previous?.wirePath != null) { path = previous.path; wire = previous.wirePath!! }
        }, { crumb -> if (!busy && crumb.wirePath != null) { path = crumb.path; wire = crumb.wirePath!! } })
        SubtitlePickerList {
            item {
                if (busy) SubtitlePickerNotice("正在加载", loading = true)
                error?.let { SubtitlePickerNotice(it, error = true, actionLabel = "重试") { attempt++ } }
                if (!busy && error == null && files.isEmpty()) SubtitlePickerNotice("这个目录没有支持的字幕文件")
            }
            items(files, key = { it.wirePath.ifEmpty { it.path } }) { file ->
                SubtitlePickerRow(file.name, file.directory, !busy, {
                    if (file.directory) { path = file.path; wire = file.wirePath }
                    else scope.launch {
                        busy = true; error = null
                        try { model.addExternalSubtitle(file); chosen() }
                        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "字幕加载失败，请重试" }
                        finally { busy = false }
                    }
                }, icon = fileArtworkIcon(file.name, file.directory, file.type), onDetails = { details = file }) {
                    val colors = FilewayTheme.colors
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        displayModified(file.modified)?.let { modified ->
                            Text(modified, Modifier.weight(1f), style = FilewayTheme.text.metadata,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!file.directory) Text(readableSize(file.size), style = FilewayTheme.text.metadata,
                            color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
    details?.let { FileDetailsDialog(it, model = model, onDismiss = { details = null }) }
}

@Composable internal fun SubtitlePickerList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable internal fun SubtitlePickerRow(
    name: String,
    directory: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    icon: Int = fileArtworkIcon(name, directory),
    onDetails: (() -> Unit)? = null,
    metadata: (@Composable () -> Unit)? = null,
) {
    val colors = FilewayTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Surface(Modifier.fillMaxWidth().clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onOpen),
        shape = shape, color = colors.surface) {
        Row(Modifier.heightIn(min = 76.dp).padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(colors.surfaceContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center) {
                FilewayIcon(icon, null, Modifier.size(28.dp), tint = if (directory) colors.primary else colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FileNameText(name, style = FilewayTheme.text.fileName, color = colors.onSurface,
                    maxLines = if (onDetails == null) Int.MAX_VALUE else 2, centered = false, modifier = Modifier.fillMaxWidth())
                metadata?.invoke()
            }
            if (onDetails != null) {
                FilewayIconButton(onClick = onDetails, enabled = enabled,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "文件操作：$name" }) {
                    FilewayIcon(R.drawable.ic_more_actions, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable internal fun SubtitlePickerNotice(
    message: String,
    loading: Boolean = false,
    error: Boolean = false,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
) {
    if (loading) {
        var visible by remember(message) { mutableStateOf(false) }
        LaunchedEffect(message) { delay(150); visible = true }
        if (!visible) return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && action != null) FilewayTextButton(onClick = action) { Text(actionLabel) }
    }
}
