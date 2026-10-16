package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.TaskCenterSection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Owns global insets and navigation once; each group supplies content only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TaskCenterScaffold(model: ClientModel, section: TaskCenterSection, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, bottomBar = { ClientNavigation(model, "taskcenter") }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            FilewayPageHeader("任务中心")
            PrimaryTabRow(selectedTabIndex = section.ordinal) {
                TaskCenterSection.entries.forEach { group ->
                    Tab(selected = group == section, onClick = { model.taskCenterSection(group) },
                        modifier = Modifier.semantics { contentDescription = "${group.label}分组" },
                        text = { Text(group.label) })
                }
            }
            content()
        }
    }
}

/** Material dates use UTC midnight; task/history ranges use the device's local calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TaskCenterDatePicker(value: Long, onDay: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val day = if (value > 0) Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
    val state = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton({
            state.selectedDateMillis?.let { onDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            onDismiss()
        }, enabled = state.selectedDateMillis != null) { Text("确定") }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } }) { DatePicker(state = state) }
}

@Composable internal fun TaskConnectionGuide(onConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Icon(painterResource(R.drawable.ic_network), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text("连接服务器后查看后台任务", style = MaterialTheme.typography.titleMedium)
        Text("后台任务属于当前服务器和账号。连接后可查看进度、详情与处理结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onConnect) { Text("连接服务器") }
    }
}
