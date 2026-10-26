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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
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
            if (selected) FilewayIcon(R.drawable.art_check, null, Modifier.size(20.dp), tint = FilewayTheme.colors.primary)
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
                .background(filewayGlassLayerColor()).then(Modifier.filewayGlassFrame())
                .clickable(enabled = enabled, role = Role.Button, onClick = click)
                .heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                FilewayIcon(icon, null, Modifier.size(22.dp), tint = FilewayTheme.status.mediaContent.copy(alpha = if (enabled) 1f else .38f))
                Text(title, color = FilewayTheme.status.mediaContent.copy(alpha = if (enabled) 1f else .38f), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        action(R.drawable.ic_edit, "字幕调整", true, onAdjust)
        if (!downloadLocal) action(R.drawable.ic_server, "服务器字幕", !loading && !readingDocument, onServerSubtitle)
        action(R.drawable.ic_phone, "本机字幕", !loading && !readingDocument, onLocalSubtitle)
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
    canSave: Boolean = true,
    saving: Boolean = false,
    onFooterHeight: (Int) -> Unit = {},
) {
    val format = codec.lowercase(Locale.ROOT)
    val authorStyled = format.contains("ssa") || format == "ass" || format.endsWith("/ass") || format.endsWith("/x-ass")
    val bitmap = listOf("pgs", "dvbsub", "vobsub").any { format.contains(it) }
    val textAppearance = !authorStyled && !bitmap
    val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
    val font = fonts.resolve(appearance.fontKey)
    LaunchedEffect(textAppearance) {
        if (textAppearance) SubtitleFontCatalog.request() else onFooterHeight(0)
    }
    val content = FilewayTheme.status.mediaContent
    val secondary = FilewayTheme.status.mediaSecondary
    val container = FilewayTheme.colors.surfaceContainerHigh
    @Composable fun heading(title: String, value: String? = null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = content)
            value?.let { Text(it, style = FilewayTheme.text.metadata, color = secondary) }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.then(if (textAppearance) Modifier.weight(1f, fill = false) else Modifier)
            .fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            heading("字幕时间", "每步 0.5 秒")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilewayIconButton({ onDelay(delayMs - 500) }, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(container)) {
                    FilewayIcon(R.drawable.ic_arrow_back, "字幕提前0.5秒", Modifier.size(22.dp))
                }
                FilewayTonalButton({ onDelay(0); onOffsetText("") }, Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content)) {
                    FilewayIcon(R.drawable.ic_refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        delayMs > 0 -> "延后 ${delayMs / 1000.0} 秒"
                        delayMs < 0 -> "提前 ${-delayMs / 1000.0} 秒"
                        else -> "无偏移"
                    }, Modifier.semantics { contentDescription = "字幕时间偏移，点击归零" }, style = MaterialTheme.typography.bodyMedium)
                }
                FilewayIconButton({ onDelay(delayMs + 500) }, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(container)) {
                    FilewayIcon(R.drawable.ic_arrow_forward, "字幕延后0.5秒", Modifier.size(22.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(offsetText, onOffsetText, Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp)).background(container)
                    .semantics { contentDescription = "字幕偏移秒数，正数延后，范围正负600秒" }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = content),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(FilewayTheme.colors.primary),
                    // Signed decimal input must allow minus; numeric IMEs often omit it.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    decorationBox = { inner -> Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (offsetText.isEmpty()) Text("精确偏移（秒，±600）", style = MaterialTheme.typography.bodyMedium, color = secondary)
                        inner()
                    } })
                FilewayIconButton(onApplyOffset, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(FilewayTheme.colors.primary.copy(alpha = .12f))) {
                    FilewayIcon(R.drawable.ic_check, "应用字幕偏移", Modifier.size(22.dp), tint = FilewayTheme.colors.primary)
                }
            }
            offsetError?.let { Text(it, style = FilewayTheme.text.metadata, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(color = secondary.copy(alpha = .16f))
            heading("字幕外观")
            when {
                authorStyled -> Text("ASS / SSA 保留作者字体、位置与动画。时间偏移仍可调整。", style = MaterialTheme.typography.bodyMedium, color = secondary)
                bitmap -> Text("位图字幕使用片源图像，不应用文字外观。时间偏移仍可调整。", style = MaterialTheme.typography.bodyMedium, color = secondary)
                else -> {
                    Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(12.dp)).background(container)
                        .padding(12.dp), contentAlignment = Alignment.BottomCenter) {
                        Text("栖卷 · 字幕预览 Aa", Modifier.padding(bottom = 48.dp * appearance.bottomPadding)
                            .background(Color.Black.copy(alpha = appearance.backgroundOpacity)),
                            color = Color.White, fontSize = (18 * appearance.scale).sp, lineHeight = (26 * appearance.scale).sp,
                            fontFamily = FontFamily(font.typeface ?: android.graphics.Typeface.DEFAULT))
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(container)
                        .clickable(role = Role.Button, onClick = onChooseFont).heightIn(min = 56.dp).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("字体", style = MaterialTheme.typography.bodyMedium, color = content)
                        Text(font.label, Modifier.weight(1f), style = FilewayTheme.text.metadata, color = secondary,
                            maxLines = 2, textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis)
                        FilewayIcon(R.drawable.ic_arrow_forward, "选择字幕字体", Modifier.size(20.dp), tint = secondary)
                    }
                    if (fonts.failed || fonts.missing(appearance.fontKey)) Text("字体不可用时使用字幕默认，可重新选择。", style = FilewayTheme.text.metadata, color = secondary)
                    SubtitleAppearanceSlider("字号", "${(appearance.scale * 100).roundToInt()}%", appearance.scale, .5f, 2f) {
                        onAppearance(appearance.copy(scale = it))
                    }
                    SubtitleAppearanceSlider("底边距", "${(appearance.bottomPadding * 100).roundToInt()}%", appearance.bottomPadding, 0f, .4f) {
                        onAppearance(appearance.copy(bottomPadding = it))
                    }
                    heading("显示区域")
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextSubtitleArea.entries.forEach { area ->
                            val chosen = appearance.area == area
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (chosen) FilewayTheme.colors.primary.copy(alpha = .16f) else container)
                                .selectable(chosen, role = Role.RadioButton) { onAppearance(appearance.copy(area = area)) }
                                .heightIn(min = 48.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                                Text(if (area == TextSubtitleArea.PICTURE) "画面内" else "包含黑边", style = MaterialTheme.typography.bodyMedium,
                                    color = if (chosen) FilewayTheme.colors.primary else content)
                            }
                        }
                    }
                    if (appearance.area == TextSubtitleArea.STAGE) Text("不改变视频大小；没有黑边时仍在画面内显示。", style = FilewayTheme.text.metadata, color = secondary)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("黑色背景", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = content)
                        Switch(appearance.backgroundOpacity > 0f, { onAppearance(appearance.copy(backgroundOpacity = if (it) .5f else 0f)) },
                            Modifier.semantics { contentDescription = "字幕黑色背景" })
                    }
                    if (appearance.backgroundOpacity > 0f) SubtitleAppearanceSlider("不透明度", "${(appearance.backgroundOpacity * 100).roundToInt()}%",
                        appearance.backgroundOpacity, 0f, 1f) { onAppearance(appearance.copy(backgroundOpacity = it)) }
                }
            }
        }
        if (textAppearance) {
            HorizontalDivider(color = secondary.copy(alpha = .16f))
            Row(Modifier.fillMaxWidth().onSizeChanged { onFooterHeight(it.height) }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilewayOutlinedButton({ onAppearance(TextSubtitleAppearance()) }, Modifier.weight(1f).heightIn(min = 48.dp),
                    enabled = appearance != TextSubtitleAppearance(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    FilewayIcon(R.drawable.ic_refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("恢复默认")
                }
                FilewayButton(onSaveAppearance, Modifier.weight(1f).heightIn(min = 48.dp), enabled = canSave && !saving,
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    FilewayIcon(R.drawable.ic_check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("保存为默认")
                }
            }
        }
    }
}

@Composable private fun SubtitleAppearanceSlider(title: String, valueText: String, value: Float, minimum: Float, maximum: Float, change: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = FilewayTheme.status.mediaContent)
            Text(valueText, style = FilewayTheme.text.metadata, color = FilewayTheme.status.mediaSecondary)
        }
        PlayerSlider(value, maximum, "字幕$title", true, change, minimum = minimum, description = valueText)
    }
}

/** One full bounded scrolling font list, with no nested fixed-height picker. */
@Composable internal fun PlayerSubtitleFontPicker(
    appearance: TextSubtitleAppearance,
    onChoose: (TextSubtitleAppearance) -> Unit,
    defaultFontKey: String,
    saving: Boolean,
    onUseOnce: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
    val current = fonts.resolve(appearance.fontKey)
    LaunchedEffect(Unit) { SubtitleFontCatalog.request() }
    Column(Modifier.fillMaxWidth()) {
    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(vertical = 12.dp)) {
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
    HorizontalDivider(color = FilewayTheme.status.mediaSecondary.copy(alpha = .16f))
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilewayOutlinedButton(onUseOnce, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !saving) {
            FilewayIcon(R.drawable.ic_visibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("本次使用")
        }
        FilewayButton(onSetDefault, Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = !saving && appearance.fontKey != defaultFontKey) {
            FilewayIcon(R.drawable.ic_check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (saving) "保存中…" else if (appearance.fontKey == defaultFontKey) "已是默认" else "设为默认")
        }
    }
    }
}
