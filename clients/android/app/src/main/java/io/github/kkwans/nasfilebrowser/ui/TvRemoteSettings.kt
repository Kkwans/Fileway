package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kkwans.fileway.remote.RemoteSnapshot
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TvRemoteSettings(model: TvControlModel, phone: ClientModel, snapshot: RemoteSnapshot,
    busy: Boolean, tvName: String) {
    val preferences = snapshot.content.optJSONObject("preferences") ?: JSONObject()
    val cache = snapshot.content.optJSONObject("cache")
    val updates = snapshot.content.optJSONObject("updates")
    val scope = rememberCoroutineScope()
    var deviceInfo by remember { mutableStateOf<String?>(null) }
    fun send(operation: String, payload: JSONObject = JSONObject()) = model.work {
        model.remoteRequest(snapshot, operation, payload)
    }
    fun set(key: String, value: Any) = send("app.setPreference", JSONObject().put("key", key).put("value", value))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (listOf("dark", "gridColumns", "safePercent").any(preferences::has)) item {
            SettingsGroup("显示") {
                if (preferences.has("dark")) TvSettingSwitch("深色外观", preferences.optBoolean("dark")) { set("dark", it) }
                if (preferences.has("gridColumns")) TvSettingSlider("文件列数", preferences.optInt("gridColumns"), 2..6) { set("gridColumns", it) }
                if (preferences.has("safePercent")) TvSettingSlider("安全边距", preferences.optInt("safePercent"), 0..8, "%") { set("safePercent", it) }
            }
        }
        if (listOf("seekStepSeconds", "detailsHoldMillis").any(preferences::has)) item {
            SettingsGroup("遥控操作") {
                if (preferences.has("seekStepSeconds")) TvSettingSlider("快进 / 后退", preferences.optInt("seekStepSeconds"), 3..30, " 秒") { set("seekStepSeconds", it) }
                if (preferences.has("detailsHoldMillis")) TvSettingSlider("查看详情长按", preferences.optInt("detailsHoldMillis") / 250,
                    2..8, format = { (it * .25).toString() + " 秒" }) { set("detailsHoldMillis", it * 250) }
            }
        }
        if (listOf("controlsTimeoutSeconds", "clockAlways", "speedAlways", "decoder").any(preferences::has)) item {
            SettingsGroup("播放") {
                if (preferences.has("controlsTimeoutSeconds")) TvSettingSlider("播放控件自动隐藏",
                    preferences.optInt("controlsTimeoutSeconds"), 2..30, " 秒") { set("controlsTimeoutSeconds", it) }
                if (preferences.has("clockAlways")) TvSettingSwitch("常驻北京时间", preferences.optBoolean("clockAlways")) { set("clockAlways", it) }
                if (preferences.has("speedAlways")) TvSettingSwitch("常驻缓冲速度", preferences.optBoolean("speedAlways")) { set("speedAlways", it) }
                if (preferences.has("decoder")) Column(Modifier.padding(12.dp)) {
                    Text("视频解码器", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoDecodePolicy.entries.forEach { policy ->
                            FilterChip(preferences.optString("decoder") == policy.name,
                                onClick = { set("decoder", policy.name) }, label = { Text(policy.label) })
                        }
                    }
                }
            }
        }
        if (cache != null) item {
            SettingsGroup("缓冲缓存") {
                TvRemoteCacheSettings(model, snapshot, cache, tvName)
            }
        }
        if (snapshot.capabilities.optInt("settingsControl") > 0) item {
            SettingsGroup("设备与更新") {
                SettingsAction("设备信息", "查看 TV 的系统、解码和显示能力", R.drawable.ic_storage) {
                    scope.launch {
                        try { deviceInfo = model.remoteRequest(snapshot, "settings.deviceInfo", JSONObject()).getString("details") }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { deviceInfo = failure.message ?: "暂时无法读取设备信息" }
                    }
                }
                val phase = updates?.optString("phase")
                val message = updates?.optString("message")?.takeUnless { it == "null" || it.isBlank() }
                SettingsAction("检查 TV 更新", message ?: "下载完成后在 TV 上确认安装", R.drawable.ic_download,
                    enabled = phase !in setOf("CHECKING", "PREPARING", "DOWNLOADING", "VERIFYING")) {
                    send("settings.updates", JSONObject().put("action", "check"))
                }
                if (phase == "DOWNLOADING" || phase == "PREPARING" || phase == "VERIFYING") {
                    SettingsAction("取消 TV 更新下载", readableSize(updates?.optLong("received") ?: 0), R.drawable.ic_download) {
                        send("settings.updates", JSONObject().put("action", "cancel"))
                    }
                }
                if (phase == "AVAILABLE") updates?.optJSONArray("offers")?.let { offers ->
                    repeat(offers.length()) { index ->
                        val offer = offers.getJSONObject(index)
                        SettingsAction("下载 TV " + offer.optString("version"), "在 TV 上确认安装", R.drawable.ic_download) {
                            send("settings.updates", JSONObject().put("action", "download").put("id", offer.getString("id")))
                        }
                    }
                }
            }
        }
        item {
            val source = phone.tvAuthorizationContext()
            SettingsGroup("账号与连接") {
                snapshot.content.optJSONObject("session")?.let { session ->
                    Text("TV 当前登录 · " + session.optString("username"), Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                SettingsAction("将手机当前账号用于 TV", if (busy) "正在授权…" else "在 TV 上独立登录",
                    R.drawable.ic_person, enabled = !busy && source != null, navigation = false) { model.authorize(phone, source) }
                SettingsAction("连接另一部手机", "在 TV 上打开连接入口", R.drawable.ic_person) { send("device.enablePairing") }
            }
        }
    }
    deviceInfo?.let { details ->
        AlertDialog(onDismissRequest = { deviceInfo = null }, title = { Text("TV 设备信息") },
            text = { Text(details) }, confirmButton = { FilewayTextButton(onClick = { deviceInfo = null }) { Text("关闭") } })
    }
}

@Composable private fun TvSettingSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onCheckedChange = change)
    }
}

@Composable private fun TvSettingSlider(label: String, value: Int, range: IntRange, unit: String = "",
    format: (Int) -> String = { it.toString() + unit }, change: (Int) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(value, pending) { if (value == pending) pending = null }
    LaunchedEffect(pending) {
        if (pending != null) { delay(4500); pending = null }
    }
    val shown = draft ?: (pending ?: value).coerceIn(range).toFloat()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("$label · " + format(shown.roundToInt()))
        Slider(shown, onValueChange = { draft = it }, onValueChangeFinished = {
            draft?.roundToInt()?.let { pending = it; change(it) }
            draft = null
        }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TvRemoteCacheSettings(model: TvControlModel, snapshot: RemoteSnapshot, cache: JSONObject,
    tvName: String) {
    val limit = cache.optLong("limitBytes") / 1_048_576
    val minimum = cache.optLong("minMiB", 500)
    val maximum = cache.optLong("maxMiB", 20_480)
    var draft by remember(limit) { mutableStateOf(limit.toString()) }
    var confirm by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun change(operation: String, payload: JSONObject) = scope.launch {
        if (pending) return@launch
        pending = true
        error = null
        try { model.remoteRequest(snapshot, operation, payload) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "TV 缓存操作未完成" }
        finally { pending = false }
    }
    fun save(mib: Long) = change("app.setPreference", JSONObject().put("key", "cacheMiB").put("value", mib))
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(readableSize(cache.optLong("usedBytes")) + " / " + readableSize(cache.optLong("limitBytes")) +
            " · " + tvRemoteSpeed(cache.optLong("bytesPerSecond")))
        if (snapshot.capabilities.optInt("cacheControl") == 0) {
            Text("此 TV 版本仅提供缓存状态", style = MaterialTheme.typography.bodySmall)
        } else {
            val clearing = cache.optBoolean("clearing")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(500L, 2048L, 4096L, 10240L, 20480L).filter { it in minimum..maximum }.forEach { mib ->
                    FilterChip(limit == mib, onClick = { save(mib) }, enabled = !pending,
                        label = { Text(if (mib < 1024) "$mib MB" else (mib / 1024).toString() + " GB") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("缓存上限（MB）") })
                FilewayButton(onClick = { draft.toLongOrNull()?.let { save(it) } },
                    enabled = !pending && draft.toLongOrNull()?.let { it in minimum..maximum && it != limit } == true) { Text("应用") }
            }
            if (cache.has("rewindMinutes") && cache.has("exitMinutes")) {
                Text("保留时间", style = MaterialTheme.typography.titleSmall)
                TvRemoteCacheMinutes("回退窗口（分钟）", cache.optInt("rewindMinutes"), 30, pending) {
                    change("app.setPreference", JSONObject().put("key", "cacheRewindMinutes").put("value", it))
                }
                TvRemoteCacheMinutes("退出后保留（分钟）", cache.optInt("exitMinutes"), 1440, pending) {
                    change("app.setPreference", JSONObject().put("key", "cacheExitMinutes").put("value", it))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilewayOutlinedButton(onClick = { confirm = true }, enabled = !pending && !clearing) { Text("清理 TV 缓存") }
                if (cache.optBoolean("prefetchPaused")) FilewayTextButton(onClick = {
                    change("cache.resume", JSONObject())
                }, enabled = !pending) { Text("恢复预缓冲") }
            }
            if (clearing) Text("正在清理，剩余 " + readableSize(cache.optLong("usedBytes")))
            if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            val message = error ?: cache.optString("error").takeUnless { it.isBlank() || it == "null" }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理 TV 视频缓存？") },
        text = { Text("目标 " + tvName + "，已用 " + readableSize(cache.optLong("usedBytes")) + "。当前播放继续，后台预缓冲暂停。") },
        dismissButton = { FilewayTextButton(onClick = { confirm = false }) { Text("取消") } },
        confirmButton = { FilewayTextButton(onClick = {
            confirm = false
            change("cache.clear", JSONObject().put("confirmed", true))
        }) { Text("清理") } })
}

@Composable private fun TvRemoteCacheMinutes(label: String, value: Int, maximum: Int, pending: Boolean, save: (Int) -> Unit) {
    var input by remember(value) { mutableStateOf(value.toString()) }
    val minutes = input.toIntOrNull()?.takeIf { it in 0..maximum }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(input, { input = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(label) }, supportingText = { Text("0–$maximum") })
        FilewayButton(onClick = { minutes?.let(save) }, enabled = !pending && minutes != null && minutes != value) {
            Text("应用")
        }
    }
}
