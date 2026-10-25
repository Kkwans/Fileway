package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.data.DefaultPlaybackRatePresets
import io.github.kkwans.nasfilebrowser.data.parsePlaybackRate

@Composable internal fun PlayerSpeedHome(
    rate: Float,
    holdRate: Float,
    presets: List<Float>,
    onPreset: (Float) -> Unit,
    onEdit: (Boolean) -> Unit,
    onEditPresets: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rate !in presets) Text("当前 ${rate}×", style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        presets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { value ->
                    val colors = if (rate == value) ButtonDefaults.filledTonalButtonColors(
                        containerColor = FilewayTheme.colors.primaryContainer, contentColor = FilewayTheme.colors.primary)
                        else ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = FilewayTheme.status.mediaContent)
                    FilewayTonalButton(
                        onClick = { onPreset(value) }, colors = colors,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = rate == value },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) { Text("${value}×") }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilewayOutlinedButton(onClick = { onEdit(false) }, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("自定义播放速度") }
            FilewayIconButton(onClick = onEditPresets, modifier = Modifier.size(48.dp)) {
                FilewayIcon(R.drawable.ic_edit, "编辑倍速预设", Modifier.size(22.dp))
            }
        }
        HorizontalDivider()
        Text("长按倍速 · ${holdRate}×", style = MaterialTheme.typography.titleMedium)
        Text("松手立即恢复长按前的速度", style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        FilewayOutlinedButton(onClick = { onEdit(true) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("调整长按倍速")
        }
    }
}

private data class SpeedPresetDraft(val id: Int, val text: String)

@Composable internal fun PlayerSpeedPresetsEditor(
    presets: List<Float>, saving: Boolean, saveError: String?, onSave: (List<Float>) -> Unit,
) {
    var nextId by remember { mutableIntStateOf(presets.size) }
    val rows = remember { mutableStateListOf<SpeedPresetDraft>().apply {
        addAll(presets.mapIndexed { index, value -> SpeedPresetDraft(index, value.toString()) })
    } }
    val parsed = rows.map { parsePlaybackRate(it.text) }
    val valid = rows.isNotEmpty() && parsed.all { it != null }
    Column(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.id }) { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(row.text, { value ->
                        val index = rows.indexOfFirst { it.id == row.id }
                        if (index >= 0) rows[index] = row.copy(text = value)
                    }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                        singleLine = true, enabled = !saving,
                        isError = parsePlaybackRate(row.text) == null,
                        label = { Text("倍速") },
                        suffix = { Text("×") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    FilewayIconButton(onClick = { rows.removeAll { it.id == row.id } }, enabled = !saving,
                        modifier = Modifier.size(48.dp)) {
                        FilewayIcon(R.drawable.ic_trash, "移除此倍速预设", Modifier.size(22.dp))
                    }
                }
            }
            item {
                FilewayOutlinedButton(onClick = { rows.add(SpeedPresetDraft(nextId++, "")) }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    FilewayIcon(R.drawable.ic_add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("添加倍速")
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(saveError ?: if (rows.isEmpty()) "至少保留一个预设" else if (!valid) "请输入 0.1–5 之间的倍速" else "保存到本机 · 按倍速排序，重复项合并",
                style = FilewayTheme.text.metadata,
                color = if (saveError != null || !valid) MaterialTheme.colorScheme.error else FilewayTheme.status.mediaSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilewayOutlinedButton(onClick = {
                    rows.clear()
                    rows.addAll(DefaultPlaybackRatePresets.map { SpeedPresetDraft(nextId++, it.toString()) })
                }, enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("恢复默认") }
                FilewayButton(onClick = { onSave(parsed.filterNotNull()) }, enabled = valid && !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (saving) "正在保存…" else "保存") }
            }
        }
    }
}

@Composable internal fun PlayerSpeedEditor(
    value: String,
    hold: Boolean,
    error: String?,
    saving: Boolean,
    onValue: (String) -> Unit,
    onApply: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = value, onValueChange = onValue,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(if (hold) "长按倍速" else "自定义倍速") },
            supportingText = { Text(error ?: "支持 0.1–5 倍速") },
            isError = error != null, enabled = !saving, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        FilewayButton(onClick = onApply, enabled = !saving, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (saving) "正在保存…" else if (hold) "保存到此设备" else "应用")
        }
    }
}
