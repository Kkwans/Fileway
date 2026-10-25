package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.MediaQueue
import io.github.kkwans.nasfilebrowser.app.mediaKey

/** Renders the existing immutable snapshot; navigation remains owned by the caller. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerQueuePanel(
    model: ClientModel,
    queue: MediaQueue,
    playing: Boolean,
    currentDurationMs: Long,
    choose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        key(queue.snapshotId) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
                state = rememberLazyListState(initialFirstVisibleItemIndex = queue.index),
                contentPadding = PaddingValues(bottom = 20.dp)) {
                itemsIndexed(queue.items, key = { _, item -> item.mediaKey }) { index, file ->
                    var expandedName by remember(queue.snapshotId, file.mediaKey) { mutableStateOf(false) }
                    val current = index == queue.index
                    val status = if (current) {
                        if (playing) "正在播放" else "当前视频"
                    } else ""
                    val duration = if (current && currentDurationMs > 0) clock(currentDurationMs) else null
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) FilewayTheme.colors.primary.copy(alpha = .10f) else Color.Transparent)
                        .combinedClickable(role = Role.RadioButton, onClick = { choose(index) },
                            onLongClickLabel = if (expandedName) "收起完整名称" else "展开完整名称",
                            onLongClick = { expandedName = !expandedName })
                        // One complete target avoids announcing the preview and title twice.
                        // The full name remains available even when its visual label truncates.
                        .clearAndSetSemantics {
                            contentDescription = "第 ${index + 1} 项，${file.name}"
                            selected = current
                            stateDescription = if (current) "已选择，$status" + (duration?.let { "，时长 $it" } ?: "") else "未选择"
                            role = Role.RadioButton
                            onClick("选择视频") { choose(index); true }
                            onLongClick(if (expandedName) "收起完整名称" else "展开完整名称") {
                                expandedName = !expandedName; true
                            }
                        }.heightIn(min = 80.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(12.dp))) {
                            MediaThumbnail(model, file, Modifier.matchParentSize(), showStatusText = false,
                                contentScale = ContentScale.Crop)
                            if (duration != null) Text(duration,
                                Modifier.align(Alignment.BottomEnd).padding(3.dp)
                                    .clip(RoundedCornerShape(3.dp)).background(FilewayTheme.status.mediaCanvas.copy(alpha = .88f))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Clip)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.name, color = if (current) FilewayTheme.colors.primary else FilewayTheme.status.mediaContent,
                                fontSize = 14.sp, lineHeight = 20.sp,
                                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                                maxLines = if (expandedName) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${index + 1}", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1)
                                if (current) {
                                    FilewayIcon(if (playing) R.drawable.art_play else R.drawable.art_check,
                                        null, Modifier.size(14.dp), tint = FilewayTheme.colors.primary)
                                    Text(status, color = FilewayTheme.colors.primary, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
