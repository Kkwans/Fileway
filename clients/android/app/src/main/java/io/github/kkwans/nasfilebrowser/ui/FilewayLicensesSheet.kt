package io.github.kkwans.nasfilebrowser.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read the maintained license assets packaged with this exact build. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FilewayLicensesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { files = withContext(Dispatchers.IO) { context.assets.list("licenses").orEmpty().sorted() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法读取本版本的许可证" }
    }
    LaunchedEffect(selected) {
        text = null; error = null
        selected?.let { file ->
            try { text = withContext(Dispatchers.IO) { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "无法读取此许可证" }
        }
    }
    BackHandler(selected != null) { selected = null }
    ModalBottomSheet(onDismissRequest = { if (selected != null) selected = null else onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(if (selected == null) "鸣谢与许可证" else selected.orEmpty(), style = MaterialTheme.typography.titleLarge) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (selected == null) {
                item {
                    Text("Fileway 使用的开源组件与图标来源。下方许可证随本版本提供。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lordicon.com/")).addCategory(Intent.CATEGORY_BROWSABLE)); error = null }
                        catch (_: Exception) { error = "无法打开 Lordicon 链接" }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Animated icons by Lordicon.com") }
                }
                items(files, key = { it }) { file ->
                    TextButton(onClick = { selected = file }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(file, Modifier.fillMaxWidth()) }
                }
            } else {
                if (text == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                text?.let { content -> item { Text(content, style = MaterialTheme.typography.bodySmall) } }
            }
            item { TextButton(onClick = { if (selected == null) onDismiss() else selected = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (selected == null) "返回关于" else "返回许可证列表")
            } }
        }
    }
}
