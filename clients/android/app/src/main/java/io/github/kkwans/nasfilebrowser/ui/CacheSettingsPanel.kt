package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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

@Composable internal fun CacheSettingsPanel(model: ClientModel, feedback: (String) -> Unit) {
    val state by model.cache.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf<String?>(null) }
    val enabled = !state.loading && !state.busy
    LaunchedEffect(Unit) { model.cache.refresh() }
    fun edit(key: String, value: Long) { number = key; input = value.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("图片缓存") {
            SettingsAction("缓存图片管理", "查看与管理全部缓存图片", R.drawable.ic_image) { model.tab("cache-images") }
            SettingsAction("默认图片质量", settings.imageQuality.label, R.drawable.ic_visibility, enabled) { quality = true }
            SettingsAction("图片缓存容量", "已用 ${readableSize(state.images)} / ${settings.imageMB} MB${if (settings.imageMB == 0L) " · 已关闭" else ""}", R.drawable.ic_storage, enabled) { edit("image", settings.imageMB) }
            SettingsAction("清理图片缓存", "清理本机所有图片缓存，服务器文件保留", R.drawable.ic_refresh, enabled, navigation = false) { clear = "image" }
        }
        SettingsGroup("缩略图缓存") {
            SettingsAction("容量上限", "已用 ${readableSize(state.thumbnails)} / ${settings.thumbnailMB} MB", R.drawable.ic_storage, enabled) { edit("thumbnail", settings.thumbnailMB) }
            SettingsAction("清理缩略图缓存", "移除此设备已缓存的文件预览", R.drawable.ic_refresh, enabled, navigation = false) { clear = "thumbnail" }
        }
        SettingsGroup("视频播放缓存") {
            SettingsAction("容量上限", "已用 ${readableSize(state.playback)} / ${settings.playbackMB} MB${if (settings.playbackMB == 0L) " · 已关闭" else ""}", R.drawable.ic_storage, enabled) { edit("playback", settings.playbackMB) }
            SettingsAction("自动清理周期", "${settings.cleanupHours} 小时 · 保留正在播放的文件", R.drawable.ic_history, enabled) { edit("hours", settings.cleanupHours.toLong()) }
            SettingsAction("清理播放缓存", "保留正在播放的缓存", R.drawable.ic_refresh, enabled, navigation = false) { clear = "playback" }
        }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FilewayTextButton(onClick = { model.cache.retry() }, enabled = enabled) { Text("重试缓存初始化") }
        }
    }
    number?.let { kind ->
        val title = when (kind) { "thumbnail" -> "缩略图容量上限"; "playback" -> "视频缓存容量上限"; "image" -> "图片缓存容量上限"; else -> "自动清理周期" }
        val maximum = when (kind) { "image" -> 51200L; "hours" -> 720L; else -> 10240L }
        val minimum = if (kind == "hours") 1L else 0L
        val value = input.toLongOrNull()
        val valid = value != null && value in minimum..maximum
        FileOperationSheet(title, { number = null }, footer = {
            FilewayTextButton({ number = null }) { Text("取消") }
            FilewayButton({
                if (valid) {
                    val parsed = requireNotNull(value)
                    model.cache.save(when (kind) { "thumbnail" -> settings.copy(thumbnailMB = parsed); "playback" -> settings.copy(playbackMB = parsed); "image" -> settings.copy(imageMB = parsed); else -> settings.copy(cleanupHours = parsed.toInt()) })
                    number = null
                }
            }, enabled = valid && enabled) { Text("保存") }
        }) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (kind == "hours") "小时" else "MB") }, isError = !valid,
                    supportingText = { Text(if (valid) "$minimum–$maximum${if (kind == "hours") " 小时" else " MB"}" else "请输入 $minimum–$maximum 的整数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = MaterialTheme.typography.bodyMedium)
                Text(if (kind == "hours") "系统省电可能延后清理；正在播放的文件会保留。" else "0 关闭磁盘缓存。超限时自动淘汰最久未读取项。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (quality) FileOperationSheet("默认图片质量", { quality = false }) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) { ImageQuality.entries.forEach { option ->
            FilewaySelectionRow(option.label, selected = settings.imageQuality == option, enabled = enabled) {
                model.cache.save(settings.copy(imageQuality = option)); quality = false
            }
        } }
    }
    clear?.let { kind -> FileOperationSheet("清理缓存？", { clear = null }, footer = {
        FilewayTextButton({ clear = null }) { Text("取消") }
        FilewayButton({
            clear = null
            scope.launch {
                try { model.cache.clear(kind); feedback(if (kind == "playback" && model.cache.state.value.protected > 0) "已清理，正在播放的缓存已保留" else "缓存已清理") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback("清理失败，请重试") }
            }
        }, enabled = enabled) { Text("清理") }
    }) {
        Text(if (kind == "playback") "仅清理未在播放的本机缓存，服务器文件不受影响。" else "清理此设备的缓存，服务器文件不受影响。",
            style = MaterialTheme.typography.bodyMedium)
    } }
}
