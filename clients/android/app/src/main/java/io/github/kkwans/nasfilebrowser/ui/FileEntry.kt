package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.data.CompactGridSize
import io.github.kkwans.nasfilebrowser.data.collectionPath
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import java.util.Locale

/** Shared file card for directory and verified search results. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FileEntry(model: ClientModel, file: ResourceRef, layout: FileLayout, enabled: Boolean,
    open: () -> Unit, details: () -> Unit, location: String? = null, metadata: @Composable (() -> Unit)? = null,
    selected: Boolean? = null, compactGridSize: CompactGridSize = CompactGridSize.MEDIUM) {
    val colors = FilewayTheme.colors
    val tags by model.tags.state.collectAsStateWithLifecycle()
    val associated = if (file.downloadId.isNotEmpty()) emptyList() else tags.items.filter { collectionPath(file.path) in it.paths }
    val action = if (selected != null) Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { open() }
        else Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = open,
            onLongClick = details, onLongClickLabel = "查看完整名称与路径")
    val centered = layout == FileLayout.COVER || layout == FileLayout.COMPACT
    @Composable fun artwork(modifier: Modifier) {
        if (file.mediaKind() in setOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
            // Preview failures are signalled by the existing lease component; black media remains media.
            MaterialTheme(colorScheme = colors) {
                MediaThumbnail(model, file, modifier.clip(RoundedCornerShape(8.dp)),
                    showStatusText = layout == FileLayout.COVER || layout == FileLayout.DETAIL,
                    contentScale = if (layout == FileLayout.UNBOUNDED) ContentScale.Fit else ContentScale.Crop,
                    naturalAspect = layout == FileLayout.UNBOUNDED)
            }
        } else Box((if (layout == FileLayout.UNBOUNDED) modifier.height(96.dp) else modifier)
            .background(colors.surfaceContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            val iconSize = when (layout) {
                FileLayout.COMPACT -> (compactGridSize.previewSizeDp * .66f).dp
                FileLayout.COVER, FileLayout.UNBOUNDED -> 52.dp
                FileLayout.DETAIL -> 44.dp
                FileLayout.LIST -> 28.dp
            }
            Icon(painterResource(fileArtworkIcon(file)), null, Modifier.size(iconSize),
                tint = if (file.directory) colors.primary else colors.onSurfaceVariant)
        }
    }
    @Composable fun caption(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            FileNameText(file.name,
                style = if (layout == FileLayout.COMPACT) FilewayTheme.text.compactName else FilewayTheme.text.fileName,
                color = colors.onSurface,
                maxLines = when (layout) { FileLayout.COVER -> 1; FileLayout.COMPACT -> compactGridSize.nameLines; FileLayout.UNBOUNDED -> Int.MAX_VALUE; else -> 2 },
                centered = centered, modifier = Modifier.fillMaxWidth())
            // Compact is deliberately image + name only, including custom metadata supplied by callers.
            if (layout != FileLayout.COMPACT) {
                if (metadata != null) metadata() else {
                    val modified = displayModified(file.modified) ?: "时间未提供"
                    val horizontal = layout == FileLayout.LIST || layout == FileLayout.DETAIL
                    if (horizontal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(modified, Modifier.weight(1f), style = FilewayTheme.text.metadata,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!file.directory) Text(readableSize(file.size), style = FilewayTheme.text.metadata,
                            color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    else if (!file.directory) Text(readableSize(file.size), Modifier.fillMaxWidth(), style = FilewayTheme.text.metadata,
                        color = colors.onSurfaceVariant, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                    if (!location.isNullOrBlank()) Text(location, Modifier.fillMaxWidth(), style = FilewayTheme.text.metadata,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                    // Time has its own slot so long result paths can never displace it.
                    if (!horizontal) Text(modified, Modifier.fillMaxWidth(), style = FilewayTheme.text.metadata,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                }
            }
        }
    }
    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(action),
        shape = RoundedCornerShape(12.dp), color = if (selected == true) colors.primaryContainer else colors.surface,
        border = if (selected == true) BorderStroke(2.dp, colors.primary) else null) {
        Column {
            when (layout) {
                FileLayout.COVER -> Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    artwork(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                    caption(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 10.dp, bottom = 4.dp))
                }
                FileLayout.COMPACT -> BoxWithConstraints(Modifier.fillMaxWidth().padding(10.dp)) {
                    val previewSize = minOf(compactGridSize.previewSizeDp.dp, maxWidth)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        artwork(Modifier.size(previewSize))
                        caption(Modifier.fillMaxWidth())
                    }
                }
                FileLayout.UNBOUNDED -> Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    artwork(Modifier.fillMaxWidth())
                    caption(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 10.dp, bottom = 4.dp))
                }
                FileLayout.DETAIL -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    artwork(Modifier.fillMaxWidth(.40f).aspectRatio(16f / 9f))
                    caption(Modifier.weight(1f))
                }
                FileLayout.LIST -> Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    artwork(Modifier.size(44.dp))
                    caption(Modifier.weight(1f))
                    if (selected == null) IconButton(onClick = details, enabled = enabled,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "文件操作：${file.name}" }) {
                        Icon(painterResource(R.drawable.ic_more_vert), null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                    } else Spacer(Modifier.width(12.dp))
                }
            }
            if (layout == FileLayout.UNBOUNDED && associated.isNotEmpty()) FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                associated.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(6.dp).background(metadataColor(tag.color, colors.primary), CircleShape))
                        Text(tag.name, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { contentDescription = "文件标签：${tag.name}" })
                    }
                }
            }
        }
    }
}

private fun fileArtworkIcon(file: ResourceRef): Int = fileArtworkIcon(file.name, file.directory, file.type)
