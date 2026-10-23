package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error as inputError
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

@Composable internal fun PlayerSpeedHome(
    rate: Float,
    holdRate: Float,
    onPreset: (Float) -> Unit,
    onEdit: (Boolean) -> Unit,
) {
    val presets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)
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
        FilewaySearchField(
            value, onValue, "输入倍速",
            Modifier.fillMaxWidth().focusRequester(focus)
                .border(1.dp, if (error == null) Color.Transparent else MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium)
                .semantics {
                    contentDescription = if (hold) "长按倍速" else "自定义倍速"
                    if (error != null) inputError(error)
                },
            enabled = !saving, status = "×", actionIcon = R.drawable.ic_check,
            actionLabel = if (hold) "保存长按倍速" else "应用播放倍速",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
            containerColor = FilewayTheme.colors.surfaceContainerHigh, submit = onApply,
        )
        Text(error ?: if (saving) "正在保存…" else if (hold) "支持 0.1–5 倍速 · 保存到本机" else "支持 0.1–5 倍速",
            style = FilewayTheme.text.metadata,
            color = if (error != null) MaterialTheme.colorScheme.error else FilewayTheme.status.mediaSecondary)
    }
}
