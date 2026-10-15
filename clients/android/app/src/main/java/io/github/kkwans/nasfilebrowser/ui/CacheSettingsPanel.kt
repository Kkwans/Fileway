package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Both identities are immutable: the same account may be reconnected in a new session. */
private data class CacheImageOwner(val account: String, val session: String)

@Composable internal fun CacheSettingsPanel(model: ClientModel, feedback: (String) -> Unit) {
    val state by model.cache.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val account = model.cacheAccount()
    val owner = remember(account, client.previewScope, client.connected) {
        CacheImageOwner(account, client.previewScope).takeIf { client.connected && account.isNotEmpty() && client.previewScope.isNotEmpty() }
    }
    fun current(captured: CacheImageOwner) = model.state.value.connected &&
        model.state.value.previewScope == captured.session && model.cacheAccount() == captured.account
    var number by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf<String?>(null) }
    var managedOwner by remember { mutableStateOf<CacheImageOwner?>(null) }
    var items by remember { mutableStateOf<List<CachedImage>>(emptyList()) }
    var request by remember { mutableStateOf<Job?>(null) }
    var requestVersion by remember { mutableIntStateOf(0) }
    var loadingImages by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val enabled = !state.loading && !state.busy
    fun closeImages() { requestVersion++; request?.cancel(); managedOwner = null; items = emptyList(); loadingImages = false; imageError = null }
    DisposableEffect(owner) { onDispose { closeImages() } }
    LaunchedEffect(Unit) { model.cache.refresh() }
    fun loadImages(captured: CacheImageOwner) {
        request?.cancel()
        val version = ++requestVersion
        managedOwner = captured; loadingImages = true; imageError = null; items = emptyList()
        request = scope.launch {
            try {
                val result = model.cache.cachedImages(captured.account)
                if (current(captured) && managedOwner == captured && requestVersion == version) items = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(captured) && requestVersion == version) imageError = "无法读取图片缓存，请重试" }
            finally { if (current(captured) && requestVersion == version) loadingImages = false }
        }
    }
    fun edit(key: String, value: Long) { number = key; input = value.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("本机缓存 · 缩略图") {
            SettingsAction("容量上限", "已用 ${readableSize(state.thumbnails)} / ${settings.thumbnailMB} MB", R.drawable.ic_storage, enabled) { edit("thumbnail", settings.thumbnailMB) }
            SettingsAction("清理缩略图缓存", "超限自动淘汰最久未读取项", R.drawable.ic_refresh, enabled) { clear = "thumbnail" }
        }
        SettingsGroup("视频播放缓存") {
            SettingsAction("容量上限", "已用 ${readableSize(state.playback)} / ${settings.playbackMB} MB${if (settings.playbackMB == 0L) " · 已关闭" else ""}", R.drawable.ic_storage, enabled) { edit("playback", settings.playbackMB) }
            SettingsAction("自动清理周期", "${settings.cleanupHours} 小时 · 保留正在播放的文件", R.drawable.ic_history, enabled) { edit("hours", settings.cleanupHours.toLong()) }
            SettingsAction("清理播放缓存", "保留正在播放的缓存", R.drawable.ic_refresh, enabled) { clear = "playback" }
        }
        SettingsGroup("图片缓存与查看") {
            SettingsAction("默认图片质量", settings.imageQuality.label, R.drawable.ic_visibility, enabled) { quality = true }
            SettingsAction("图片缓存容量", "已用 ${readableSize(state.images)} / ${settings.imageMB} MB${if (settings.imageMB == 0L) " · 已关闭" else ""}", R.drawable.ic_storage, enabled) { edit("image", settings.imageMB) }
            SettingsAction("缓存图片管理", if (owner == null) "连接服务器后查看当前账号的图片" else "仅查看和移除当前账号的本机图片", R.drawable.ic_folder, enabled && owner != null) {
                owner?.let(::loadImages)
            }
            SettingsAction("清理图片缓存", "清理本机所有图片缓存，服务器文件保留", R.drawable.ic_refresh, enabled) { clear = "image" }
        }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.cache.retry() }, enabled = enabled) { Text("重试缓存初始化") }
        }
    }
    number?.let { kind ->
        val title = when (kind) { "thumbnail" -> "缩略图容量上限"; "playback" -> "视频缓存容量上限"; "image" -> "图片缓存容量上限"; else -> "自动清理周期" }
        val maximum = when (kind) { "image" -> 51200L; "hours" -> 720L; else -> 10240L }
        val minimum = if (kind == "hours") 1L else 0L
        val value = input.toLongOrNull()
        val valid = value != null && value in minimum..maximum
        AlertDialog(onDismissRequest = { number = null }, title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (kind == "hours") "小时" else "MB") }, isError = !valid,
                    supportingText = { Text(if (valid) "$minimum–$maximum${if (kind == "hours") " 小时" else " MB"}" else "请输入 $minimum–$maximum 的整数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(if (kind == "hours") "系统省电可能延后清理；正在播放的文件会保留。" else "0 关闭磁盘缓存。超限时自动淘汰最久未读取项。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = {
            if (valid) {
                val parsed = requireNotNull(value)
                model.cache.save(when (kind) { "thumbnail" -> settings.copy(thumbnailMB = parsed); "playback" -> settings.copy(playbackMB = parsed); "image" -> settings.copy(imageMB = parsed); else -> settings.copy(cleanupHours = parsed.toInt()) }); number = null
            }
        }, enabled = valid && enabled) { Text("保存") } }, dismissButton = { TextButton(onClick = { number = null }) { Text("取消") } })
    }
    if (quality) AlertDialog(onDismissRequest = { quality = false }, title = { Text("默认图片质量") }, text = {
        Column(Modifier.selectableGroup()) { ImageQuality.entries.forEach { option ->
            FilewaySelectionRow(option.label, selected = settings.imageQuality == option, enabled = enabled,
                onClick = { model.cache.save(settings.copy(imageQuality = option)); quality = false })
        } }
    }, confirmButton = { TextButton(onClick = { quality = false }) { Text("关闭") } })
    clear?.let { kind -> AlertDialog(onDismissRequest = { clear = null }, title = { Text("清理缓存？") },
        text = { Text(if (kind == "playback") "仅清理未在播放的本机缓存，服务器文件不受影响。" else "清理此设备的缓存，服务器文件不受影响。") },
        confirmButton = { TextButton(onClick = {
            clear = null
            scope.launch { try { model.cache.clear(kind); feedback(if (kind == "playback" && model.cache.state.value.protected > 0) "已清理，正在播放的缓存已保留" else "缓存已清理") } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { feedback("清理失败，请重试") } }
        }, enabled = enabled) { Text("清理") } }, dismissButton = { TextButton(onClick = { clear = null }) { Text("取消") } }) }
    managedOwner?.takeIf { it == owner }?.let { captured -> AlertDialog(onDismissRequest = ::closeImages, title = { Text("当前账号的缓存图片") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loadingImages) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            imageError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton({ loadImages(captured) }) { Text("重试") } } }
            if (items.isEmpty() && !loadingImages && imageError == null) item { Text("当前账号还没有图片缓存") }
            items(items, key = { it.key }) { image ->
                Column(Modifier.fillMaxWidth()) {
                    Text(image.name, style = MaterialTheme.typography.bodyMedium)
                    Text(readableSize(image.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = {
                            if (current(captured) && image.account == captured.account) {
                                closeImages(); model.open(ResourceRef(image.path, image.wirePath, image.name, false, "image", image.size, image.modified))
                            }
                        }, enabled = !loadingImages) { Text("查看") }
                        TextButton(onClick = {
                            if (current(captured) && image.account == captured.account) {
                                val version = ++requestVersion
                                loadingImages = true; imageError = null
                                request = scope.launch {
                                    try {
                                        model.cache.removeImage(image.key, captured.account)
                                        val remaining = model.cache.cachedImages(captured.account)
                                        if (current(captured) && managedOwner == captured && requestVersion == version) items = remaining
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { if (current(captured) && requestVersion == version) imageError = "移除缓存失败，请重试" }
                                    finally { if (current(captured) && requestVersion == version) loadingImages = false }
                                }
                            }
                        }, enabled = !loadingImages) { Text("移除缓存") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = ::closeImages) { Text("关闭") } }) }
}
