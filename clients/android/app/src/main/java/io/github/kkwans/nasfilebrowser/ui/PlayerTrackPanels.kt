package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.TextSubtitleArea
import io.github.kkwans.nasfilebrowser.data.SubtitleFontCatalog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.player.NativeTrack
import java.util.Locale
import kotlin.math.roundToInt

/** Selection reflects engine confirmation; a request never moves the check mark. */
@Composable
internal fun PlayerTrackList(
    tracks: List<NativeTrack>,
    selected: Int,
    pending: Int?,
    subtitle: Boolean,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    choose: (Int) -> Unit,
) {
    // NativePlayer already supplies -1. Also cover the opening/empty state
    // without creating a second off row after track discovery.
    val choices = tracks.distinctBy { it.id }.let { unique ->
        if (subtitle && unique.none { it.id == -1 }) listOf(NativeTrack(-1, "关闭字幕")) + unique else unique
    }
    LazyColumn(modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (header != null) item(key = "actions") { header() }
        item(key = "heading") {
            Text(if (subtitle) "字幕轨道" else "音轨", Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
        }
        itemsIndexed(choices, key = { _, track -> track.id }) { index, track ->
            val title = when {
                track.id == -1 -> if (subtitle) "关闭字幕" else "关闭音轨"
                track.title.isNotBlank() -> track.title.trim()
                else -> "${if (subtitle) "字幕" else "音轨"} ${index + 1}"
            }
            val repeatedTitle = track.id != -1 && choices.count { it.id != -1 && it.title.trim().equals(track.title.trim(), ignoreCase = true) } > 1
            val displayTitle = title + if (repeatedTitle) " · ${choices.take(index + 1).count { it.id != -1 }}" else ""
            PlayerTrackRow(displayTitle, if (track.id == -1) "" else trackDetails(track, title),
                selected == track.id, pending == track.id) { choose(track.id) }
        }
        if (choices.none { it.id != -1 }) item(key = "empty") {
            Text(if (subtitle) "没有可用字幕，可添加外挂字幕。" else "没有可用音轨。",
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp)
        }
        if (footer != null) item(key = "actions-footer") { footer() }
    }
}

@Composable
private fun PlayerTrackRow(title: String, detail: String, selected: Boolean, pending: Boolean, choose: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(if (selected) FilewayTheme.colors.primary.copy(alpha = .10f) else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = choose)
        .clearAndSetSemantics {
            text = AnnotatedString(title)
            this.selected = selected
            role = Role.RadioButton
            val status = when {
                selected && pending -> "已选择，正在切换"
                pending -> "正在切换"
                selected -> "已选择"
                else -> "未选择"
            }
            stateDescription = if (detail.isBlank()) status else "$status，$detail"
            onClick { choose(); true }
        }.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (selected) FilewayTheme.colors.primary else FilewayTheme.status.mediaContent, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pending) Text("正在切换", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
        }
        if (pending) CircularProgressIndicator(Modifier.size(16.dp), color = FilewayTheme.status.mediaSecondary, strokeWidth = 2.dp)
        if (selected) Icon(painterResource(R.drawable.art_check), null, Modifier.size(20.dp), tint = FilewayTheme.colors.primary)
    }
}

private fun trackDetails(track: NativeTrack, title: String): String {
    fun appearsInTitle(value: String): Boolean = value.isNotBlank() &&
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(value)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(title)
    val language = track.language.trim().takeUnless { it.equals("und", ignoreCase = true) }.orEmpty()
    val languageName = if (language.isBlank()) "" else Locale.forLanguageTag(language.replace('_', '-')).getDisplayLanguage(Locale.ENGLISH)
    return listOf(
        language.takeUnless { appearsInTitle(it) || appearsInTitle(languageName) }.orEmpty(),
        track.codec.trim().takeUnless(::appearsInTitle).orEmpty(),
    ).filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.joinToString(" · ")
}

/** Low-frequency actions follow tracks; adjustments own a separate scrollable page. */
@Composable
internal fun PlayerSubtitleHomeActions(
    downloadLocal: Boolean,
    loading: Boolean,
    readingDocument: Boolean,
    onAdjust: () -> Unit,
    onServerSubtitle: () -> Unit,
    onLocalSubtitle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        SubtitleAction(R.drawable.ic_edit, "字幕调整", "时间偏移与文本外观", true, onAdjust)
        if (!downloadLocal) SubtitleAction(R.drawable.ic_folder, "服务器外挂字幕",
            if (loading) "正在读取字幕" else "浏览当前服务器的字幕文件", !loading && !readingDocument, onServerSubtitle,
            label = "选择外挂字幕")
        SubtitleAction(R.drawable.ic_subtitles, "选择本地字幕", when {
            readingDocument -> "正在读取所选文件"
            loading -> "正在读取字幕"
            else -> "从手机或文档提供方选择"
        }, !loading && !readingDocument, onLocalSubtitle)
        HorizontalDivider(color = FilewayTheme.status.mediaSecondary.copy(alpha = .16f))
    }
}

@Composable
private fun SubtitleAction(icon: Int, title: String, detail: String, enabled: Boolean, click: () -> Unit, label: String = title) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = click)
        .clearAndSetSemantics {
            contentDescription = label
            stateDescription = detail
            role = Role.Button
            if (enabled) onClick { click(); true } else disabled()
        }
        .heightIn(min = 56.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val secondary = FilewayTheme.status.mediaSecondary.copy(alpha = if (enabled) 1f else .5f)
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = secondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = FilewayTheme.status.mediaContent.copy(alpha = if (enabled) 1f else .5f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = secondary, fontSize = 12.sp)
        }
        Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(18.dp), tint = secondary)
    }
}

@Composable
internal fun PlayerSubtitleAdjustments(
    delayMs: Long,
    codec: String,
    offsetText: String,
    onOffsetText: (String) -> Unit,
    offsetError: String?,
    onDelay: (Long) -> Unit,
    onApplyOffset: () -> Unit,
    appearance: TextSubtitleAppearance,
    onAppearance: (TextSubtitleAppearance) -> Unit,
    onSaveAppearance: () -> Unit,
    onChooseFont: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = codec.lowercase(Locale.ROOT)
    val authorStyled = format.contains("ssa") || format == "ass" || format.endsWith("/ass") || format.endsWith("/x-ass")
    val bitmap = listOf("pgs", "dvbsub", "vobsub").any { format.contains(it) }
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = FilewayTheme.colors.primary)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("字幕时间", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(when {
            delayMs > 0 -> "延后 ${delayMs / 1000.0} 秒"
            delayMs < 0 -> "提前 ${-(delayMs / 1000.0)} 秒"
            else -> "无偏移"
        }, color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onDelay(delayMs - 500) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = buttonColors,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("提前 0.5 秒") }
            TextButton(onClick = { onDelay(0); onOffsetText("") }, modifier = Modifier.heightIn(min = 48.dp), colors = buttonColors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text("归零") }
            TextButton(onClick = { onDelay(delayMs + 500) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = buttonColors,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("延后 0.5 秒") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(offsetText, onOffsetText, Modifier.weight(1f), label = { Text("偏移秒数") },
                supportingText = { Text(offsetError ?: "正数延后，负数提前 · -600 到 600 秒") },
                isError = offsetError != null, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            TextButton(onClick = onApplyOffset, modifier = Modifier.heightIn(min = 48.dp), colors = buttonColors) { Text("应用") }
        }
        HorizontalDivider(color = FilewayTheme.status.mediaSecondary.copy(alpha = .16f))
        Text("字幕外观", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        when {
            authorStyled -> Text("ASS / SSA 使用作者字体、位置与动画，保留原始样式。时间偏移仍可调整。",
                color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            bitmap -> Text("PGS / DVB / VobSub 位图字幕使用片源图像，不支持文字字号与底边距调整。时间偏移仍可调整。",
                color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            else -> {
                val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
                val font = fonts.resolve(appearance.fontKey)
                LaunchedEffect(Unit) { SubtitleFontCatalog.request() }
                Text("文本字号 ${(appearance.scale * 100).roundToInt()}%", color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp)
                Slider(appearance.scale, { onAppearance(appearance.copy(scale = it)) },
                    Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "文本字幕字号"; stateDescription = "${(appearance.scale * 100).roundToInt()}%" },
                    valueRange = .5f..2f, colors = SliderDefaults.colors(thumbColor = FilewayTheme.colors.primary, activeTrackColor = FilewayTheme.colors.primary))
                Text("底边距 ${(appearance.bottomPadding * 100).roundToInt()}%", color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp)
                Slider(appearance.bottomPadding, { onAppearance(appearance.copy(bottomPadding = it)) },
                    Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "文本字幕底边距"; stateDescription = "${(appearance.bottomPadding * 100).roundToInt()}%" },
                    valueRange = 0f.. .4f, colors = SliderDefaults.colors(thumbColor = FilewayTheme.colors.primary, activeTrackColor = FilewayTheme.colors.primary))
                Text("外观预览", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
                Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF667384))
                    .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
                    Text("字幕外观预览", Modifier.padding(bottom = 96.dp * appearance.bottomPadding)
                        .background(Color.Black.copy(alpha = appearance.backgroundOpacity)),
                        color = Color.White, fontSize = (18 * appearance.scale).sp, lineHeight = (26 * appearance.scale).sp,
                        fontFamily = FontFamily(font.typeface ?: android.graphics.Typeface.DEFAULT))
                }
                Text("字幕显示区域", color = Color.White, fontSize = 14.sp)
                PlayerTrackRow("画面内", "", appearance.area == TextSubtitleArea.PICTURE, false) {
                    onAppearance(appearance.copy(area = TextSubtitleArea.PICTURE))
                }
                PlayerTrackRow("整个播放区域（含黑边）", "不改变视频大小；没有黑边时仍在画面内显示", appearance.area == TextSubtitleArea.STAGE, false) {
                    onAppearance(appearance.copy(area = TextSubtitleArea.STAGE))
                }
                Text("背景不透明度 ${(appearance.backgroundOpacity * 100).roundToInt()}%" +
                    if (appearance.backgroundOpacity == 0f) " · 无背景" else " · 黑色", color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp)
                Slider(appearance.backgroundOpacity, { onAppearance(appearance.copy(backgroundOpacity = it)) },
                    Modifier.fillMaxWidth().height(48.dp).semantics {
                        contentDescription = "字幕背景不透明度"
                        stateDescription = "${(appearance.backgroundOpacity * 100).roundToInt()}%"
                    }, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = FilewayTheme.colors.primary, activeTrackColor = FilewayTheme.colors.primary))
                TextButton({ onAppearance(appearance.copy(backgroundOpacity = if (appearance.backgroundOpacity == 0f) .5f else 0f)) },
                    Modifier.heightIn(min = 48.dp), colors = buttonColors) {
                    Text(if (appearance.backgroundOpacity == 0f) "使用半透明黑背景" else "无背景")
                }
                Text("字幕字体", color = Color.White, fontSize = 14.sp)
                TextButton(onChooseFont, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "选择字幕字体"; stateDescription = font.label }, colors = buttonColors) {
                    Text(font.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (fonts.loading) Text("正在读取系统可用字体…", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
                if (fonts.failed) Text("系统字体暂时不可用，可使用字幕默认或跟随系统。", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
                if (fonts.missing(appearance.fontKey)) Text("所选字体已不可用，当前使用字幕默认；可重新选择。", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
                Text("调整会即时预览；保存后用于此设备的文本字幕。", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onAppearance(TextSubtitleAppearance()) }, modifier = Modifier.heightIn(min = 48.dp), colors = buttonColors) { Text("重置预览") }
                    TextButton(onClick = onSaveAppearance, modifier = Modifier.heightIn(min = 48.dp), colors = buttonColors) { Text("保存到此设备") }
                }
            }
        }
    }
}


/** One full bounded scrolling font list, with no nested fixed-height picker. */
@Composable internal fun PlayerSubtitleFontPicker(
    appearance: TextSubtitleAppearance,
    onChoose: (TextSubtitleAppearance) -> Unit,
) {
    val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
    val current = fonts.resolve(appearance.fontKey)
    LaunchedEffect(Unit) { SubtitleFontCatalog.request() }
    LazyColumn(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (fonts.loading || fonts.failed || fonts.missing(appearance.fontKey)) item(key = "status") {
            Text(when {
                fonts.loading -> "正在读取系统可用字体…"
                fonts.failed -> "系统字体暂时不可用，可使用字幕默认或跟随系统。"
                else -> "所选字体已不可用，当前使用字幕默认；可重新选择。"
            }, Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        }
        itemsIndexed(fonts.options, key = { _, option -> option.key }) { _, option ->
            PlayerTrackRow(option.label, "", current.key == option.key, false) {
                onChoose(appearance.copy(fontKey = option.key))
            }
        }
    }
}
