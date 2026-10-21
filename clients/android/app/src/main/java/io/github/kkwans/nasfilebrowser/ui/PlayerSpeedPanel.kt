package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

@Composable internal fun PlayerSpeedHome(
    rate: Float,
    holdRate: Float,
    onPreset: (Float) -> Unit,
    onEdit: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("当前 ${rate}×", style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f).chunked(3).forEach { row ->
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
            }
        }
        FilewayOutlinedButton(onClick = { onEdit(false) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("自定义播放速度")
        }
        HorizontalDivider()
        Text("长按倍速 · ${holdRate}×", style = MaterialTheme.typography.titleMedium)
        Text("松手立即恢复长按前的速度", style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        FilewayOutlinedButton(onClick = { onEdit(true) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("调整长按倍速")
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
