package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.renameNameError

@Composable internal fun CreateDirectoryDialog(model: ClientModel) {
    val state by model.fileOperations.state.collectAsStateWithLifecycle()
    val draft = state.creation ?: return
    val focus = remember(state.scope, draft.parent) { FocusRequester() }
    val nameError = renameNameError(draft.name)
    val editable = !state.changing && draft.unknownTarget == null
    val canCreate = editable && nameError == null
    LaunchedEffect(state.scope, draft.parent) { focus.requestFocus() }
    FileOperationDialog("新建文件夹", model.fileOperations::closeDirectoryCreation, canDismiss = !state.changing, footer = {
        TextButton(model.fileOperations::closeDirectoryCreation, enabled = !state.changing, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        Button(model.fileOperations::createDirectory, enabled = canCreate, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.changing) "正在创建" else "创建") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FileOperationLocation("创建位置", draft.parent.path)
            OutlinedTextField(draft.name, model.fileOperations::directoryName, Modifier.fillMaxWidth().focusRequester(focus), label = { Text("文件夹名称") },
                singleLine = true, readOnly = !editable, isError = nameError != null && draft.name.isNotEmpty(),
                supportingText = { Text(if (draft.name.isEmpty()) "在当前服务器目录创建" else nameError ?: "不会覆盖同名文件或文件夹") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (canCreate) model.fileOperations.createDirectory() }))
            draft.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (draft.existing != null) TextButton(model::openExistingCreationDirectory, enabled = !state.changing) { Text("打开已有文件夹") }
            if (draft.unknownTarget != null) TextButton(model.fileOperations::checkDirectoryCreation, enabled = !state.changing) { Text("核对创建结果") }
        }
    }
}
