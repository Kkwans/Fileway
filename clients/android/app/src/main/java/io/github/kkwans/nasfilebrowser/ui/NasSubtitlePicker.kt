package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.data.directoryTrail
import kotlinx.coroutines.CancellationException
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
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (busy) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("正在加载")
                }
                error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error); FilewayTextButton(onClick = { attempt++ }) { Text("重试") } }
                if (!busy && error == null && files.isEmpty()) Text("这个目录没有支持的字幕文件", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(files, key = { it.wirePath.ifEmpty { it.path } }) { file ->
                FileEntry(model, file, FileLayout.LIST, !busy, {
                    if (file.directory) { path = file.path; wire = file.wirePath }
                    else scope.launch {
                        busy = true; error = null
                        try { model.addExternalSubtitle(file); chosen() }
                        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "字幕加载失败，请重试" }
                        finally { busy = false }
                    }
                }, { details = file })
            }
        }
    }
    details?.let { FileDetailsDialog(it, onDismiss = { details = null }) }
}
