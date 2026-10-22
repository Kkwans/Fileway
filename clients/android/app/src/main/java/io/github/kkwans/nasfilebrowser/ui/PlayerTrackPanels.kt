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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
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
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (footer != null) item(key = "actions-footer") { footer() }
    }
}

@Composable
private fun PlayerTrackRow(title: String, detail: String, selected: Boolean, pending: Boolean, fontFamily: FontFamily? = null, sample: String? = null, choose: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
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
            Text(title, color = if (selected) FilewayTheme.colors.primary else FilewayTheme.status.mediaContent, fontSize = 15.sp, lineHeight = 22.sp,
                fontFamily = fontFamily, fontWeight = if (fontFamily == null && selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sample != null) Text(sample, fontFamily = fontFamily, fontSize = 16.sp, lineHeight = 24.sp, color = FilewayTheme.status.mediaContent)
            if (detail.isNotBlank()) Text(detail, color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pending) Text("正在切换", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (pending) CircularProgressIndicator(Modifier.size(16.dp), color = FilewayTheme.status.mediaSecondary, strokeWidth = 2.dp)
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(painterResource(R.drawable.art_check), null, Modifier.size(20.dp), tint = FilewayTheme.colors.primary)
        }
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable fun action(icon: Int, title: String, enabled: Boolean, click: () -> Unit) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .background(FilewayTheme.colors.surfaceContainerHigh)
                .clickable(enabled = enabled, role = Role.Button, onClick = click)
                .heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                Icon(painterResource(icon), null, Modifier.size(22.dp), tint = FilewayTheme.status.mediaContent.copy(alpha = if (enabled) 1f else .38f))
                Text(title, color = FilewayTheme.status.mediaContent.copy(alpha = if (enabled) 1f else .38f), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        action(R.drawable.ic_edit, "字幕调整", true, onAdjust)
        if (!downloadLocal) action(R.drawable.ic_folder, "服务器外挂", !loading && !readingDocument, onServerSubtitle)
        action(R.drawable.ic_subtitles, "本地字幕", !loading && !readingDocument, onLocalSubtitle)
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
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("字幕时间", Modifier.weight(1f), color = FilewayTheme.status.mediaContent, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        Text(when {
            delayMs > 0 -> "延后 ${delayMs / 1000.0} 秒"
            delayMs < 0 -> "提前 ${-(delayMs / 1000.0)} 秒"
            else -> "无偏移"
        }, color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun step(icon: Int, label: String, click: () -> Unit) {
                IconButton(click, Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(FilewayTheme.colors.surfaceContainerHigh)) {
                    Icon(painterResource(icon), label, Modifier.size(22.dp), tint = FilewayTheme.colors.primary)
                }
            }
            step(R.drawable.ic_arrow_back, "字幕提前0.5秒") { onDelay(delayMs - 500) }
            step(R.drawable.ic_refresh, "字幕时间归零") { onDelay(0); onOffsetText("") }
            step(R.drawable.ic_arrow_forward, "字幕延后0.5秒") { onDelay(delayMs + 500) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(offsetText, onOffsetText, Modifier.weight(1f).heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp)).background(FilewayTheme.colors.surfaceContainerHigh)
                .semantics { contentDescription = "字幕偏移秒数，正数延后，范围正负600秒" }, singleLine = true,
                textStyle = TextStyle(color = FilewayTheme.status.mediaContent, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(FilewayTheme.colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { inner -> Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (offsetText.isEmpty()) Text("偏移秒数，±600", color = FilewayTheme.status.mediaSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                    inner()
                } })
            IconButton(onApplyOffset, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(FilewayTheme.colors.primary.copy(alpha = .12f))) {
                Icon(painterResource(R.drawable.ic_check), "应用字幕偏移", Modifier.size(22.dp), tint = FilewayTheme.colors.primary)
            }
        }
        if (offsetError != null) Text(offsetError, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp)
        HorizontalDivider(color = FilewayTheme.status.mediaSecondary.copy(alpha = .16f))
        Text("字幕外观", color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        when {
            authorStyled -> Text("ASS / SSA 使用作者字体、位置与动画，保留原始样式。时间偏移仍可调整。",
                color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            bitmap -> Text("PGS / DVB / VobSub 位图字幕使用片源图像，不支持文字字号与底边距调整。时间偏移仍可调整。",
                color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            else -> {
                val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
                val font = fonts.resolve(appearance.fontKey)
                LaunchedEffect(Unit) { SubtitleFontCatalog.request() }
                Column(Modifier.fillMaxWidth()) {
                Text("文本字号 ${(appearance.scale * 100).roundToInt()}%", color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                PlayerSlider(appearance.scale, 2f, "文本字幕字号", true,
                    { onAppearance(appearance.copy(scale = it)) }, minimum = .5f,
                    description = "${(appearance.scale * 100).roundToInt()}%")
                }
                Column(Modifier.fillMaxWidth()) {
                Text("底边距 ${(appearance.bottomPadding * 100).roundToInt()}%", color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                PlayerSlider(appearance.bottomPadding, .4f, "文本字幕底边距", true,
                    { onAppearance(appearance.copy(bottomPadding = it)) },
                    description = "${(appearance.bottomPadding * 100).roundToInt()}%")
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp)).background(FilewayTheme.colors.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
                    Text("字幕外观预览", Modifier.padding(bottom = 72.dp * appearance.bottomPadding)
                        .background(Color.Black.copy(alpha = appearance.backgroundOpacity)),
                        color = Color.White, fontSize = (18 * appearance.scale).sp, lineHeight = (26 * appearance.scale).sp,
                        fontFamily = FontFamily(font.typeface ?: android.graphics.Typeface.DEFAULT))
                }
                Text("字幕显示区域", color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                PlayerTrackRow("画面内", "", appearance.area == TextSubtitleArea.PICTURE, false) {
                    onAppearance(appearance.copy(area = TextSubtitleArea.PICTURE))
                }
                PlayerTrackRow("整个播放区域（含黑边）", "不改变视频大小；没有黑边时仍在画面内显示", appearance.area == TextSubtitleArea.STAGE, false) {
                    onAppearance(appearance.copy(area = TextSubtitleArea.STAGE))
                }
                Column(Modifier.fillMaxWidth()) {
                Text("背景不透明度 ${(appearance.backgroundOpacity * 100).roundToInt()}%" +
                    if (appearance.backgroundOpacity == 0f) " · 无背景" else " · 黑色", color = FilewayTheme.status.mediaSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                PlayerSlider(appearance.backgroundOpacity, 1f, "字幕背景不透明度", true,
                    { onAppearance(appearance.copy(backgroundOpacity = it)) },
                    description = "${(appearance.backgroundOpacity * 100).roundToInt()}%")
                }
                FilewayTextButton({ onAppearance(appearance.copy(backgroundOpacity = if (appearance.backgroundOpacity == 0f) .5f else 0f)) },
                    Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = buttonColors) {
                    Text(if (appearance.backgroundOpacity == 0f) "使用半透明黑背景" else "无背景")
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FilewayTheme.colors.surfaceContainerHigh)
                    .clickable(role = Role.Button, onClick = onChooseFont).heightIn(min = 56.dp).padding(12.dp)
                    .semantics { contentDescription = "选择字幕字体"; stateDescription = font.label },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("字幕字体", color = FilewayTheme.status.mediaContent, fontSize = 14.sp, lineHeight = 20.sp)
                        Text(font.label, color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(20.dp), tint = FilewayTheme.status.mediaSecondary)
                }
                if (fonts.loading) Text("正在读取系统可用字体…", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                if (fonts.failed) Text("系统字体暂时不可用，可使用字幕默认或跟随系统。", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                if (fonts.missing(appearance.fontKey)) Text("所选字体已不可用，当前使用字幕默认；可重新选择。", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                Text("即时预览，保存仅用于本设备", color = FilewayTheme.status.mediaSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilewayTextButton(onClick = { onAppearance(TextSubtitleAppearance()) }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = buttonColors) { Text("重置预览") }
                    FilewayTextButton(onClick = onSaveAppearance, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = buttonColors) { Text("保存到此设备") }
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
            val family = remember(option.key, option.typeface) { FontFamily(option.typeface ?: android.graphics.Typeface.DEFAULT) }
            PlayerTrackRow(option.label, "", current.key == option.key, false, fontFamily = family, sample = "栖卷 · 字幕预览 Aa 0123") {
                onChoose(appearance.copy(fontKey = option.key))
            }
        }
    }
}
