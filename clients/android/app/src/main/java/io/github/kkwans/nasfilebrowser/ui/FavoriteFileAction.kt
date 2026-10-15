package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

private fun favoriteColor(value: String, fallback: Color): Color = try { Color(android.graphics.Color.parseColor(value)) } catch (_: Exception) { fallback }

@Composable internal fun FavoriteFileAction(model: ClientModel, file: ResourceRef, enabled: Boolean = true) {
    val state by model.favorites.state.collectAsStateWithLifecycle()
    var choosing by remember(file, state.scope) { mutableStateOf(false) }
    var chosenGroup by remember(file, state.scope) { mutableStateOf("") }
    val foreground = LocalFileOperationForeground.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismiss() { choosing = false; foreground?.back() }
    LaunchedEffect(file, state.scope) { model.favorites.refresh() }
    val existing = model.favorites.favorite(file)
    if (foreground == null || foreground.destination == FileOperationDestination.DETAILS) {
        FileActionIcon(R.drawable.ic_bookmark, if (existing == null) "加入收藏" else "取消收藏",
            enabled && state.loaded && !state.loading && !state.changing && state.error == null, existing != null, caption = "收藏") {
            if (existing != null) model.favorites.remove(existing) else {
                focus.clearFocus(); keyboard?.hide(); chosenGroup = ""
                if (foreground == null) choosing = true else foreground.open(FileOperationDestination.FAVORITE, "加入收藏")
            }
        }
    }
    if (choosing || foreground?.destination == FileOperationDestination.FAVORITE) FileOperationSheet("加入收藏", ::dismiss,
        canDismiss = !state.changing, back = foreground != null, footer = {
            TextButton(::dismiss, enabled = !state.changing, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            Button({ model.favorites.add(file, chosenGroup, ::dismiss) }, enabled = enabled && !state.changing && state.loaded,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.changing) "正在保存" else "保存收藏") }
        }) {
            Text(file.name, style = FilewayTheme.text.fileName)
            Text("选择收藏夹", style = MaterialTheme.typography.labelLarge)
            if (state.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(chosenGroup.isEmpty(), enabled = !state.changing, role = Role.RadioButton) { chosenGroup = "" }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        Text("未分组", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(chosenGroup.isEmpty(), null, enabled = !state.changing)
                    }
                }
                items(state.groups, key = { it.id }) { group ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(chosenGroup == group.id, enabled = !state.changing, role = Role.RadioButton) { chosenGroup = group.id }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(favoriteColor(group.color, MaterialTheme.colorScheme.primary), CircleShape))
                        Text(group.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(chosenGroup == group.id, null, enabled = !state.changing)
                    }
                }
            }
    }
}
