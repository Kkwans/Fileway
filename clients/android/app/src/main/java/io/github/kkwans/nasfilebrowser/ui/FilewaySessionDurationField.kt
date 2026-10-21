package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R

private enum class SessionUnit(val label: String, val minutes: Double) {
    MINUTES("分钟", 1.0), HOURS("小时", 60.0), DAYS("天", 1440.0)
}
private fun durationNumber(value: Double): String =
    if (value.isFinite() && value.toLong().toDouble() == value) value.toLong().toString() else value.toString()

/** Display units are local UI only; the controller still owns its minute draft. */
@Composable internal fun FilewaySessionDurationField(scope: String, minuteText: String, enabled: Boolean, valid: Boolean,
    change: (String) -> Unit) {
    val initial = minuteText.toDoubleOrNull()
    var unit by rememberSaveable(scope) { mutableStateOf(when {
        initial != null && initial % 1440.0 == 0.0 -> SessionUnit.DAYS
        initial != null && initial % 60.0 == 0.0 -> SessionUnit.HOURS
        else -> SessionUnit.MINUTES
    }) }
    var input by rememberSaveable(scope) { mutableStateOf(initial?.let { durationNumber(it / unit.minutes) } ?: minuteText) }
    var lastPosted by rememberSaveable(scope) { mutableStateOf(minuteText) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(minuteText) {
        if (minuteText != lastPosted) {
            input = minuteText.toDoubleOrNull()?.let { durationNumber(it / unit.minutes) } ?: minuteText
            lastPosted = minuteText
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { text ->
                input = text
                val minutes = text.toDoubleOrNull()?.times(unit.minutes)
                val posted = minutes?.let(::durationNumber) ?: text
                lastPosted = posted
                change(posted)
            }, label = { Text("不活跃自动退出") }, enabled = enabled, singleLine = true, isError = !valid,
                shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
            Box {
                FilewayTextButton({ open = true }, enabled = enabled, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(unit.label)
                    Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp))
                }
                FilewayMenu(open, { open = false }, SessionUnit.entries.map {
                    FilewayMenuEntry(it.name, it.label, selected = it == unit)
                }, selection = FilewayMenuSelection.Single) { entry ->
                    val next = SessionUnit.valueOf(entry.key)
                    val minutes = input.toDoubleOrNull()?.times(unit.minutes)
                    unit = next
                    if (minutes != null) input = durationNumber(minutes / next.minutes)
                    open = false
                }
            }
        }
        Text(if (valid) "10 分钟至 30 天，按有效访问重新计算" else "请输入 10 分钟至 30 天之间的期限",
            style = MaterialTheme.typography.bodySmall,
            color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}
