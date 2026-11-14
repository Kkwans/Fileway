package io.github.kkwans.fileway.tv

import android.view.KeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.*
import java.util.Locale
import kotlinx.coroutines.flow.first

internal fun tvPlaybackRate(rate: Float): String =
    String.format(Locale.ROOT, "%.2f", rate).removeSuffix("0") + "×"

private fun tvTrackLanguage(language: String): String? = when (language.lowercase(Locale.ROOT)) {
    "", "und", "unknown" -> null
    "zh", "zho", "chi" -> "中文"
    "en", "eng" -> "英语"
    "ja", "jpn" -> "日语"
    "ko", "kor" -> "韩语"
    "fr", "fra", "fre" -> "法语"
    "de", "deu", "ger" -> "德语"
    "es", "spa" -> "西班牙语"
    else -> language
}

private fun tvTrackFormat(codec: String): String? = when (codec.lowercase(Locale.ROOT)) {
    "" -> null
    "audio/mp4a-latm", "aac" -> "AAC"
    "audio/ac3", "ac3" -> "Dolby Digital"
    "audio/eac3", "eac3" -> "Dolby Digital Plus"
    "audio/opus", "opus" -> "Opus"
    "audio/flac", "flac" -> "FLAC"
    "audio/mpeg", "mp3" -> "MP3"
    "application/x-subrip", "subrip", "srt" -> "SRT"
    "text/vtt", "webvtt" -> "WebVTT"
    "text/x-ssa" -> "ASS / SSA"
    "ass", "ssa" -> codec.uppercase(Locale.ROOT)
    "application/pgs", "hdmv_pgs_subtitle", "pgs" -> "PGS"
    else -> codec
}

/** The settings panel and its compact child share the same live labels and values. */
internal fun tvPlayerSettingsRows(
    state: TvMediaState, player: PlayerState, resize: Int, onExit: () -> Unit, onNavigate: (TvPlayerPage) -> Unit,
): List<TvPlayerRow.Choice> {
    val audio = state.file?.mediaKind() == MediaKind.AUDIO
    fun destination(target: TvPlayerPage, value: String? = null) = TvPlayerRow.Choice(
        key = target.name, title = target.title, value = value, destination = true, selectable = false,
        icon = when (target) {
            TvPlayerPage.SPEED -> Art.drawable.fileway_ic_speed
            TvPlayerPage.VOLUME -> Art.drawable.fileway_ic_volume_up
            TvPlayerPage.RATIO -> Art.drawable.fileway_ic_fit_screen
            TvPlayerPage.HDR -> Art.drawable.fileway_ic_image
            else -> Art.drawable.fileway_ic_info
        },
        action = { onNavigate(target) },
    )
    return buildList {
        add(destination(TvPlayerPage.SPEED, tvPlaybackRate(player.rate)))
        add(destination(TvPlayerPage.VOLUME, "${player.volume}%"))
        if (!audio) {
            add(destination(TvPlayerPage.RATIO, listOf("适合屏幕", "拉伸", "裁切")[resize]))
            if (!state.catalogSource) add(destination(TvPlayerPage.HDR, state.mode.title))
        }
        add(destination(TvPlayerPage.INFO))
        add(TvPlayerRow.Choice(key = "exit", title = "退出播放", icon = Art.drawable.fileway_ic_logout, selectable = false, destination = true, action = onExit))
    }
}

@Composable internal fun ColumnScope.TvPlayerMenuContent(
    model: TvModel, page: TvPlayerPage, state: TvMediaState, player: PlayerState, cache: TvCacheState, resize: Int,
    quick: Boolean,
    focus: TvPlayerPanelFocus, onResize: (Int) -> Unit, onNavigate: (TvPlayerPage) -> Unit, onBack: () -> Unit,
    onClose: () -> Unit, onExit: () -> Unit, onBrowserBack: ((() -> Unit)?) -> Unit,
    operationErrors: List<String>, subtitleLoading: Boolean, onTrackOperation: (TvPlayerPage) -> Unit,
) {
    val appearance by model.media.playbackPreferences.textSubtitleAppearance.collectAsStateWithLifecycle()
    val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
    val closeLatest by rememberUpdatedState(onClose)
    fun choice(
        key: String, title: String, selected: Boolean = false, supporting: String? = null,
        icon: Int? = null, value: String? = null, destination: Boolean = false, enabled: Boolean = true,
        selectable: Boolean = true, action: () -> Unit,
    ) = TvPlayerRow.Choice(key, title, selected, enabled, supporting, value, icon, destination, selectable, {
        if (page == TvPlayerPage.AUDIO || page == TvPlayerPage.SUBTITLE) onTrackOperation(page)
        action()
        if (quick && page in listOf(TvPlayerPage.SUBTITLE, TvPlayerPage.AUDIO)) onBack()
    })
    fun destination(target: TvPlayerPage, value: String? = null, icon: Int? = null, title: String = target.title) =
        choice(target.name, title, value = value, icon = icon, destination = true, selectable = false) { onNavigate(target) }
    fun track(key: String, title: String, selected: Boolean, language: String, codec: String,
        origin: String? = null, fileName: String? = null, enabled: Boolean = true, action: () -> Unit,
    ): TvPlayerRow.Choice {
        val languageName = tvTrackLanguage(language)
        val format = tvTrackFormat(codec)
        val detail = listOfNotNull(origin, languageName, format).joinToString(" · ").ifBlank { null }
        return choice(key, title, selected, supporting = fileName, enabled = enabled, action = action)
            .copy(value = detail, accessibilityDetail = listOfNotNull(fileName, origin, languageName ?: "语言未标注", format).joinToString("，"))
    }
    val rows: List<TvPlayerRow> = when (page) {
        TvPlayerPage.SETTINGS -> tvPlayerSettingsRows(state, player, resize, onExit, onNavigate)
        TvPlayerPage.AUDIO -> buildList {
            if (state.compatible) {
                add(choice("audio/off", "关闭音轨", state.sourceAudioOff, enabled = !state.busy) { model.media.selectSourceAudio(-1) })
                state.sourceAudio.forEach { track ->
                    add(track("source/${track.index}", track.title, !state.sourceAudioOff && state.selectedSourceAudio == track.index,
                        track.language, track.codec, enabled = !state.busy) { model.media.selectSourceAudio(track.index) })
                }
            } else player.audio.forEach { track ->
                val key = "native/${track.id}"
                val selected = player.selectedAudio == track.id
                val select = { model.media.selectAudio(track.id) }
                add(if (track.id == -1) choice(key, "关闭音轨", selected, enabled = !state.busy, action = select)
                    else track(key, track.title, selected, track.language, track.codec, enabled = !state.busy, action = select))
            }
        }
        TvPlayerPage.SUBTITLE -> buildList {
            add(choice("off", "关闭字幕", player.selectedSubtitle == -1 && state.selectedSourceSubtitle == -1) { model.media.subtitlesOff() })
            val source = state.sourceText.takeIf { state.sourceSubtitles }.orEmpty()
            val sidecarKeys = state.sidecars.mapTo(mutableSetOf()) { "external:${it.mediaKey}" }
            val selectedExternal = model.media.player.importedSubtitleIdentity(player.selectedSubtitle)?.sourceKey
            val tracks = player.subtitles.filter { it.id >= 0 && model.media.subtitleTrackPublished(it.id) }
                .map { it to model.media.player.importedSubtitleIdentity(it.id) }
            val embedded = tracks.filter { (_, imported) ->
                source.isEmpty() && (imported == null || imported.sourceKey.startsWith("source:"))
            }
            val importedFiles = tracks.filter { (_, imported) -> imported?.sourceKey?.let { it !in sidecarKeys && it.startsWith("external:") } == true }
            fun origin(imported: DecoderExternalSelection?) = if (imported?.sourceKey?.startsWith("external:local:") == true) "本机" else "服务器"
            val origins = mutableSetOf<String>()
            if (source.isNotEmpty() || embedded.isNotEmpty()) origins += "内嵌"
            importedFiles.forEach { (_, imported) -> origins += origin(imported) }
            if (state.sidecars.isNotEmpty()) origins += "同目录"
            val showOrigin = origins.size > 1
            source.forEach { track ->
                add(track("source/${track.index}", track.title, state.selectedSourceSubtitle == track.index,
                    track.language, track.codec, origin = "内嵌".takeIf { showOrigin }) { model.media.selectSourceSubtitle(track.index) })
            }
            embedded.forEach { (track, _) ->
                add(track("native/${track.id}", track.title, player.selectedSubtitle == track.id && state.selectedSourceSubtitle == -1,
                    track.language, track.codec, origin = "内嵌".takeIf { showOrigin }) { model.media.selectPlayerSubtitle(track.id) })
            }
            importedFiles.forEach { (track, imported) ->
                add(track("native/${track.id}", track.title, player.selectedSubtitle == track.id && state.selectedSourceSubtitle == -1,
                    track.language, track.codec, origin = origin(imported).takeIf { showOrigin }) { model.media.selectPlayerSubtitle(track.id) })
            }
            if (state.sidecars.isNotEmpty()) {
                val tracksBySource = tracks.groupBy { (_, imported) -> imported?.sourceKey }
                state.sidecars.forEach { file ->
                    val sourceKey = "external:${file.mediaKey}"
                    val fileTracks = tracksBySource[sourceKey].orEmpty()
                    if (fileTracks.size > 1) fileTracks.forEach { (track, _) ->
                        add(track("native/${track.id}", track.title, player.selectedSubtitle == track.id && state.selectedSourceSubtitle == -1,
                            track.language, track.codec, origin = "同目录".takeIf { showOrigin }, fileName = file.name) { model.media.selectPlayerSubtitle(track.id) })
                    } else add(choice("external/${file.mediaKey}", file.name, state.selectedSourceSubtitle == -1 && selectedExternal == sourceKey,
                        value = "同目录".takeIf { showOrigin }) {
                        model.media.selectExternalSubtitle(file)
                    })
                }
            }
        }
        TvPlayerPage.SUBTITLE_ADJUST -> listOf(
            destination(TvPlayerPage.APPEARANCE, "${(appearance.scale * 100).toInt()}%", Art.drawable.fileway_ic_text),
            destination(TvPlayerPage.SYNC, "${player.subtitleDelayMs} ms", Art.drawable.fileway_ic_history),
        )
        TvPlayerPage.SUBTITLE_SOURCE -> listOf(
            destination(TvPlayerPage.EXTERNAL, icon = Art.drawable.fileway_ic_server, title = "当前服务器"),
            destination(TvPlayerPage.LOCAL_SUBTITLE, icon = Art.drawable.fileway_ic_storage),
        )
        TvPlayerPage.FONT -> fonts.options.map { font ->
            choice(font.key, font.label, appearance.fontKey == font.key, supporting = "栖卷 · Aa 0123") {
                model.media.updateSubtitleAppearance(appearance.copy(fontKey = font.key))
                onBack()
            }.copy(fontFamily = FontFamily(font.typeface ?: android.graphics.Typeface.DEFAULT))
        }
        TvPlayerPage.SPEED -> listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).map { rate ->
            choice("rate/$rate", tvPlaybackRate(rate), kotlin.math.abs(player.rate - rate) < .001f) {
                model.media.player.rate(rate)
                if (quick) onBack()
            }
        }
        TvPlayerPage.HDR -> TvHdrMode.entries.map { mode ->
            choice(mode.name, mode.title, state.mode == mode, enabled = !state.busy && (mode != TvHdrMode.SDR || state.sdrAvailable)) {
                model.media.switchMode(mode)
            }
        }
        TvPlayerPage.RATIO -> listOf("适合屏幕", "拉伸", "裁切").mapIndexed { index, title ->
            choice(index.toString(), title, resize == index) { onResize(index); onBack() }
        }
        TvPlayerPage.QUEUE -> remember(state.queue) {
            state.queue?.let { queue ->
                queue.items.mapIndexed { index, file ->
                    choice("queue/$index/${file.mediaKey}", file.name, index == queue.index, value = (index + 1).toString()) {
                        if (index != queue.index) model.media.navigateQueue(index - queue.index)
                        closeLatest()
                    }
                }
            }.orEmpty()
        }
        else -> emptyList()
    }

    operationErrors.forEach { TvPlayerNote(it, error = true) }
    when (page) {
        TvPlayerPage.SETTINGS -> TvPlayerSettings(rows.filterIsInstance<TvPlayerRow.Choice>(), focus)
        TvPlayerPage.SPEED -> if (quick) {
            TvPlayerChoiceList(rows, focus, Modifier.weight(1f, fill = false))
        } else TvSpeedEditor(rows.filterIsInstance<TvPlayerRow.Choice>(), player.rate, focus) { rate ->
            model.media.player.rate(rate)
        }
        TvPlayerPage.QUEUE -> {
            if (rows.isEmpty()) TvPlayerMessage("播放列表为空", focus)
            else if (quick) TvPlayerChoiceList(rows, focus, Modifier.weight(1f, fill = false))
            else TvPlayerQueueStrip(model, state, player, rows.filterIsInstance<TvPlayerRow.Choice>(), focus)
        }
        TvPlayerPage.APPEARANCE -> TvPlayerScroll {
            val initial = focus.initialKey ?: "scale"
            TvPlayerRange("字号", appearance.scale, .5f..2f, .1f, { "${(it * 100).toInt()}%" },
                focus.target("scale", initial == "scale",
                    Modifier.focusRequester(focus.appearanceScale).focusProperties { up = focus.header })) {
                model.media.updateSubtitleAppearance(appearance.copy(scale = it))
            }
            TvPlayerChoiceRow(
                choice("font", "字体", value = fonts.resolve(appearance.fontKey).label, destination = true, selectable = false) {
                    onNavigate(TvPlayerPage.FONT)
                }, focus.target("font", initial == "font"),
            )
            Text(
                "预览 · 栖卷 Aa 0123",
                Modifier.padding(horizontal = 8.dp)
                    .background(Color.Black.copy(alpha = appearance.backgroundOpacity), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = tvTextStyle(18, 24).copy(fontFamily = FontFamily(fonts.resolve(appearance.fontKey).typeface ?: android.graphics.Typeface.DEFAULT)),
                color = Color.White, maxLines = 1,
            )
            TvPlayerRange("垂直位置", appearance.bottomPadding, 0f.. .4f, .02f, { "距底部 ${(it * 100).toInt()}%" },
                focus.target("position", initial == "position")) {
                model.media.updateSubtitleAppearance(appearance.copy(bottomPadding = it))
            }
            TvPlayerRange("文字背景", appearance.backgroundOpacity, 0f..1f, .05f, { "${(it * 100).toInt()}%" },
                focus.target("background", initial == "background")) {
                model.media.updateSubtitleAppearance(appearance.copy(backgroundOpacity = it))
            }
            val codec = (state.sourceText.firstOrNull { it.index == state.selectedSourceSubtitle }?.codec
                ?: player.subtitles.firstOrNull { it.id == player.selectedSubtitle }?.codec).orEmpty().lowercase()
            if (listOf("ass", "ssa", "pgs", "dvd_subtitle", "dvb_subtitle", "vobsub").any(codec::contains)) {
                TvPlayerNote("当前字幕保留片源样式")
            }
            TvPlayerChoiceRow(
                choice("area", "位置参考", value = if (appearance.area == TextSubtitleArea.PICTURE) "画面内" else "整个画布",
                    selectable = false) {
                    val area = if (appearance.area == TextSubtitleArea.PICTURE) TextSubtitleArea.STAGE else TextSubtitleArea.PICTURE
                    model.media.updateSubtitleAppearance(appearance.copy(area = area))
                }, focus.target("area", initial == "area"),
            )
            TvPlayerChoiceRow(
                choice("reset", "恢复默认外观", selectable = false) { model.media.updateSubtitleAppearance(TextSubtitleAppearance()) },
                focus.target("reset", initial == "reset"),
            )
        }
        TvPlayerPage.SYNC -> TvPlayerScroll {
            Text("当前延迟  ${player.subtitleDelayMs} ms", Modifier.padding(horizontal = 8.dp), style = tvTextStyle(18, 26))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val first = focus.initialKey ?: "earlier"
                TvButton("提前 100 ms", { model.media.player.subtitleDelay((player.subtitleDelayMs - 100).coerceAtLeast(-600000)) },
                    focus.target("earlier", first == "earlier", Modifier.weight(1f).focusProperties { up = focus.header }))
                TvButton("延后 100 ms", { model.media.player.subtitleDelay((player.subtitleDelayMs + 100).coerceAtMost(600000)) },
                    focus.target("later", first == "later", Modifier.weight(1f).focusProperties { up = focus.header }))
            }
            var value by remember { mutableStateOf((player.subtitleDelayMs / 1000.0).toString()) }
            TvField("精确延迟（秒）", value, { value = it.take(16) }, options = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                focusedBorderColor = PanelOutline)
            val parsed = parseSubtitleDelayMillis(value)?.takeIf { it in -600000..600000 }
            TvButton("应用", { parsed?.let(model.media.player::subtitleDelay) }, Modifier.fillMaxWidth(), enabled = parsed != null)
            TvPlayerChoiceRow(choice("reset", "恢复同步", selectable = false) { model.media.player.subtitleDelay(0) }, Modifier)
        }
        TvPlayerPage.VOLUME -> TvPlayerScroll {
            TvPlayerRange("应用音量", player.volume.toFloat(), 0f..model.media.player.maximumVolume.toFloat(), 1f, { "${it.toInt()}%" },
                focus.target("volume", focus.initialKey?.startsWith("header/") != true, Modifier.focusProperties { up = focus.header })) {
                model.media.player.volume(it.toInt())
            }
            TvPlayerNote(if (player.volume > 100) "已启用音频增益" else "当前媒体的输出音量")
        }
        TvPlayerPage.INFO -> TvPlayerInfo(state, player, cache, focus)
        TvPlayerPage.EXTERNAL -> TvSubtitleFiles(model, state, player, focus, onBrowserBack,
            subtitleLoading && operationErrors.isEmpty()) { onTrackOperation(TvPlayerPage.SUBTITLE) }
        TvPlayerPage.LOCAL_SUBTITLE -> TvLocalSubtitleBrowser(model.media, focus, onBack, onBrowserBack)
        else -> {
            if (page == TvPlayerPage.SUBTITLE && subtitleLoading && operationErrors.isEmpty()) {
                TvSubtitleStatus(state.subtitleLoadDetail)
            }
            if (page == TvPlayerPage.AUDIO) {
                if (state.busy) TvPlayerNote("正在读取音轨…")
                if (rows.isEmpty() && !state.busy) TvPlayerMessage("没有可用音轨", focus)
            }
            if (page == TvPlayerPage.FONT) {
                LaunchedEffect(Unit) { SubtitleFontCatalog.request() }
                if (fonts.loading) TvPlayerNote("正在读取系统字体…")
                if (fonts.failed) TvPlayerNote("系统字体读取失败，仍可使用默认字体", error = true)
            }
            if (page == TvPlayerPage.HDR && !state.sdrAvailable) TvPlayerNote("当前片源未提供 SDR 兼容播放")
            if (rows.isNotEmpty()) {
                if (rows.filterIsInstance<TvPlayerRow.Choice>().none { it.enabled }) TvPlayerMessage("暂时无法切换", focus)
                if (page == TvPlayerPage.AUDIO || page == TvPlayerPage.SUBTITLE) {
                    TvPlayerTracks(rows.filterIsInstance<TvPlayerRow.Choice>(), focus, quick,
                        Modifier.weight(1f, fill = !quick), audio = page == TvPlayerPage.AUDIO)
                } else TvPlayerChoiceList(rows, focus, Modifier.weight(1f, fill = !quick))
            } else if (page == TvPlayerPage.AUDIO && state.busy) TvPlayerMessage("正在准备播放", focus)
        }
    }
}

@Composable
private fun TvSpeedEditor(
    presets: List<TvPlayerRow.Choice>, rate: Float, focus: TvPlayerPanelFocus, applyRate: (Float) -> Unit,
) {
    val slider = remember { FocusRequester() }
    val presetFocus = remember(presets.map { it.key }) { presets.associate { it.key to FocusRequester() } }
    val preferred = focus.initialKey ?: presets.firstOrNull { it.selected }?.key ?: "custom-speed"
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scaledWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val split = scaledWidth >= 560.dp
        val lines = presets.chunked(if (scaledWidth >= 720.dp) 6 else 3)
        val readout: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前倍速", style = tvTextStyle(17, 24), color = TvPlayerDesign.secondary)
                Text(tvPlaybackRate(rate),
                    style = tvTextStyle(44, 54, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
                    color = TvPlayerDesign.text, maxLines = 1)
            }
        }
        val editor: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                lines.forEachIndexed { index, line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { preset ->
                            TvSpeedPreset(preset, focus.target(preset.key, preferred == preset.key,
                                Modifier.weight(1f).focusRequester(presetFocus.getValue(preset.key)).focusProperties {
                                    if (index == 0) up = focus.header
                                    if (index == lines.lastIndex) down = slider
                                }))
                        }
                    }
                }
                val nearestPreset = lines.lastOrNull()?.let { line -> line.firstOrNull { it.selected } ?: line.firstOrNull() }
                TvPlayerRange("微调倍速", rate, .1f..5f, .01f, ::tvPlaybackRate,
                    focus.target("custom-speed", preferred == "custom-speed", Modifier.focusRequester(slider)
                        .focusProperties { up = nearestPreset?.key?.let(presetFocus::getValue) ?: focus.header }),
                    expandTrack = true, accelerate = true, onCommit = applyRate)
            }
        }
        val content = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        if (split) Row(content.heightIn(min = maxHeight), horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(148.dp), contentAlignment = Alignment.CenterStart) { readout() }
            Box(Modifier.weight(1f)) { editor() }
        } else Column(content, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            readout()
            editor()
        }
    }
}

@Composable private fun TvSpeedPreset(preset: TvPlayerRow.Choice, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Button(onClick = preset.action, interactionSource = interaction,
        modifier = modifier.heightIn(min = 48.dp).border(2.dp,
            if (focused) TvPlayerDesign.focus else if (preset.selected) TvPlayerDesign.accent else Color.Transparent,
            TvShape)
            .hoverable(interaction).tvTouch(preset.action, interaction = interaction).semantics { selected = preset.selected },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        shape = ButtonDefaults.shape(TvShape), scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(focusedBorder = Border.None),
        colors = ButtonDefaults.colors(
            containerColor = if (preset.selected) TvPlayerDesign.selected else if (hovered) TvPlayerDesign.raised else Color.White.copy(alpha = .04f),
            contentColor = TvPlayerDesign.text, focusedContentColor = TvPlayerDesign.text,
            focusedContainerColor = if (preset.selected) TvPlayerDesign.selected else PanelFocus)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(preset.title, style = tvTextStyle(18, 24, FontWeight.Medium).copy(fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
}

@Composable private fun ColumnScope.TvPlayerQueueStrip(
    model: TvModel, state: TvMediaState, player: PlayerState,
    rows: List<TvPlayerRow.Choice>, focus: TvPlayerPanelFocus,
) {
    val items = state.queue?.items.orEmpty()
    if (items.size == 1 && rows.size == 1) {
        val row = rows.first()
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Button(onClick = row.action, interactionSource = interaction,
            modifier = focus.target(row.key, focus.initialKey?.startsWith("header/") != true,
                Modifier.widthIn(max = 640.dp).fillMaxWidth().focusProperties { up = focus.header })
                .border(2.dp, playerFocusColor(focused), TvShape).tvTouch(row.action, interaction = interaction)
                .semantics { selected = row.selected },
            shape = ButtonDefaults.shape(TvShape), scale = ButtonDefaults.scale(focusedScale = 1f),
            border = ButtonDefaults.border(focusedBorder = Border.None), contentPadding = PaddingValues(12.dp),
            colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = TvPlayerDesign.raised,
                contentColor = TvPlayerDesign.text, focusedContentColor = TvPlayerDesign.text)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvThumbnail(model, items.first(), Modifier.width(128.dp).height(72.dp), shape = TvShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(items.first().name, style = tvTextStyle(20, 28, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${player.phase} · 1 / 1", style = tvTextStyle(16, 24), color = TvPlayerDesign.secondary)
                }
                if (row.selected) Icon(painterResource(Art.drawable.fileway_ic_check), "当前项", Modifier.size(24.dp), tint = TvPlayerDesign.accent)
            }
        }
        return
    }
    val scroll = rememberLazyListState()
    val preferred = rows.firstOrNull { it.enabled && it.key == focus.initialKey }?.key
        ?: rows.firstOrNull { it.enabled && it.selected }?.key ?: rows.firstOrNull { it.enabled }?.key
    val preferredIndex = rows.indexOfFirst { it.key == preferred }
    val prefersHeader = focus.initialKey?.startsWith("header/") == true
    LaunchedEffect(preferredIndex, focus.pending, prefersHeader) {
        if (preferredIndex >= 0 && focus.pending && !prefersHeader) {
            snapshotFlow { scroll.layoutInfo.viewportSize.width }.first { it > 0 }
            if (scroll.layoutInfo.visibleItemsInfo.none { it.index == preferredIndex }) scroll.scrollToItem(preferredIndex)
        }
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val gap = 16.dp
        val cardWidth = ((maxWidth - gap * 3) / 4).coerceIn(168.dp, 240.dp)
        val artworkHeight = cardWidth * (9f / 16f)
        LazyRow(Modifier.fillMaxSize(), state = scroll,
            contentPadding = PaddingValues(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
            itemsIndexed(items, key = { index, file -> "queue/$index/${file.mediaKey}" }) { index, file ->
                val row = rows.getOrNull(index) ?: return@itemsIndexed
                val modifier = focus.target(row.key, row.key == preferred && !prefersHeader,
                    Modifier.width(cardWidth).focusProperties {
                        if (focus.hasHeader) up = focus.header
                        if (index == 0) left = FocusRequester.Cancel
                        if (index == items.lastIndex) right = FocusRequester.Cancel
                    })
                TvPlayerQueueCard(model, file, index, artworkHeight, row, player.playing, player.phase, modifier)
            }
        }
    }
}

@Composable private fun TvPlayerQueueCard(
    model: TvModel, file: ResourceRef, index: Int, artworkHeight: androidx.compose.ui.unit.Dp,
    row: TvPlayerRow.Choice, playing: Boolean, phase: String, modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val artworkShape = TvShape
    var focused by remember { mutableStateOf(false) }
    Button(onClick = row.action, enabled = row.enabled, interactionSource = interaction,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .border(2.dp, playerFocusColor(focused), TvShape).hoverable(interaction, enabled = row.enabled)
            .tvTouch(row.action, row.enabled, interaction).semantics {
                selected = row.selected
                if (row.selected) stateDescription = phase
            },
        contentPadding = PaddingValues(0.dp), shape = ButtonDefaults.shape(TvShape),
        scale = ButtonDefaults.scale(focusedScale = 1f), border = ButtonDefaults.border(focusedBorder = Border.None),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent, contentColor = Color.White, focusedContentColor = Color.White)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(artworkHeight).clip(artworkShape)) {
                TvThumbnail(model, file, Modifier.fillMaxSize(), shape = artworkShape)
                if (row.selected) Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (playing) Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow),
                        null, Modifier.size(18.dp), tint = Color.White)
                }
                if (hovered && !focused) Box(Modifier.matchParentSize().border(1.dp,
                    Color.White.copy(alpha = .52f), artworkShape))
            }
            Text("${index + 1}. ${file.name}", Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                .then(if (focused) Modifier.basicMarquee() else Modifier),
                style = tvTextStyle(18, 26, FontWeight.Medium),
                color = TvPlayerDesign.text,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
        }
    }
}

@Composable private fun TvPlayerSettings(rows: List<TvPlayerRow.Choice>, focus: TvPlayerPanelFocus) {
    val preferred = focus.initialKey ?: rows.firstOrNull()?.key
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 640.dp) 2 else 1
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.chunked(columns).forEachIndexed { index, group ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    for (row in group) TvPlayerChoiceRow(
                        row, focus.target(row.key, row.key == preferred,
                            Modifier.weight(1f).fillMaxHeight().focusProperties { if (index == 0) up = focus.header }),
                        settingsRow = columns == 2,
                    )
                    if (group.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun TvPlayerTracks(
    tracks: List<TvPlayerRow.Choice>, focus: TvPlayerPanelFocus, quick: Boolean, modifier: Modifier, audio: Boolean,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scaledWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = if (!quick && (audio && tracks.size >= 2 && scaledWidth >= 640.dp ||
                    tracks.size >= 4 && scaledWidth >= 744.dp)) 2 else 1
        val width = if (columns == 2 || quick) maxWidth
            else minOf(maxWidth, (maxWidth * .60f).coerceIn(396.dp, 560.dp))
        val gap = 24.dp
        val cellWidth = (width - gap * (columns - 1)) / columns
        val keys = tracks.map { it.key }
        val requesters = remember(keys) { keys.associateWith { FocusRequester() } }
        val active = tracks.filter { it.enabled }.map { it.key }.toSet()
        var previousKeys by remember { mutableStateOf(keys) }
        LaunchedEffect(keys, active) {
            val focusedKey = focus.focusedBodyKey
            if (focusedKey != null && focusedKey !in active) {
                val index = previousKeys.indexOf(focusedKey)
                val adjacent = tracks.withIndex().filter { it.value.enabled }.minByOrNull { (position, _) ->
                    kotlin.math.abs(position - index)
                }?.value?.key
                if (adjacent != null) focus.retarget(adjacent)
                else if (focus.hasHeader) focus.header.requestFocus()
            }
            previousKeys = keys
        }
        val preferred = focus.initialKey?.takeIf { it in active }
            ?: tracks.firstOrNull { it.enabled && it.selected }?.key
            ?: tracks.firstOrNull { it.enabled }?.key
        fun target(index: Int) = tracks.getOrNull(index)?.key?.takeIf { it in active }?.let(requesters::getValue)
        val chrome = focus.initialKey?.let { it.startsWith("header/") || it.startsWith("group/") } == true
        val scroll = rememberScrollState()
        CompositionLocalProvider(LocalBringIntoViewSpec provides FullRowVisibility) {
            Column(Modifier.width(width).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEachIndexed { column, track ->
                            key(track.key) {
                                val index = rowIndex * columns + column
                                TvPlayerChoiceRow(track, focus.target(track.key, track.key == preferred && !chrome,
                                    Modifier.width(cellWidth).fillMaxHeight().focusRequester(requesters.getValue(track.key))
                                        .focusProperties {
                                            left = if (column > 0) target(index - 1) ?: FocusRequester.Cancel
                                                else if (focus.hasSection) focus.section else FocusRequester.Cancel
                                            right = if (column + 1 < row.size) target(index + 1) ?: FocusRequester.Cancel
                                                else FocusRequester.Cancel
                                            up = target(index - columns) ?: if (rowIndex == 0 && focus.hasHeader) focus.header else FocusRequester.Cancel
                                            down = target(index + columns)
                                                ?: target((rowIndex + 1) * columns)
                                                ?: FocusRequester.Cancel
                                        }), trackWidth = cellWidth)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun TvPlayerScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content,
    )
}

@Composable private fun TvPlayerMessage(text: String, focus: TvPlayerPanelFocus) {
    Text(
        text,
        focus.target("message", focus.initialKey?.startsWith("header/") != true,
            Modifier.focusProperties { if (focus.hasHeader) up = focus.header })
            .focusable().padding(12.dp),
        style = tvTextStyle(18, 28), color = TvPlayerDesign.secondary,
    )
}

@Composable private fun TvPlayerNote(text: String, error: Boolean = false) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = tvTextStyle(16, 22),
        color = if (error) Color(0xFFFFC1BA) else Color.White.copy(alpha = .74f),
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable private fun TvPlayerInfo(state: TvMediaState, player: PlayerState, cache: TvCacheState, focus: TvPlayerPanelFocus) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun readable(value: String?): String = when (value?.trim()?.lowercase(Locale.ROOT)) {
        null, "" -> "暂无信息"
        "unknown", "未知" -> "未识别"
        else -> value.orEmpty()
    }
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val step = with(density) { 48.dp.roundToPx() }
    BoxWithConstraints(focus.target("info", focus.initialKey?.startsWith("header/") != true, Modifier.fillMaxSize())
        .onFocusChanged { focused = it.isFocused }
        .tvRemoteKeys { event ->
            val delta = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> step
                KeyEvent.KEYCODE_DPAD_UP -> -step
                else -> 0
            }
            when {
                delta == 0 -> false
                delta < 0 && !scroll.canScrollBackward -> { focus.header.requestFocus(); true }
                else -> { scope.launch { scroll.scrollTo((scroll.value + delta).coerceIn(0, scroll.maxValue)) }; true }
            }
        }.focusable()) {
        val twoColumns = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 640.dp
        @Composable fun Source(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("片源与播放", style = tvTextStyle(20, 28, FontWeight.Medium))
                if (state.file?.mediaKind() == MediaKind.VIDEO) {
                    val range = state.info?.optString("dynamicRange")
                    TvPlayerInfoField("片源动态范围", if (range.equals("sdr", ignoreCase = true)) "SDR" else readable(range))
                    TvPlayerInfoField("请求模式", state.mode.title)
                    TvPlayerInfoField("实际路径", if (state.compatible) "SDR 兼容" else "原文件")
                    TvPlayerInfoField("画面尺寸", if (player.width > 0 && player.height > 0) "${player.width} × ${player.height}" else "暂无信息")
                }
                TvPlayerInfoField("名称", state.file?.name.orEmpty())
                TvPlayerInfoField("路径", state.file?.path.orEmpty())
            }
        }
        @Composable fun Output(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输出与缓存", style = tvTextStyle(20, 28, FontWeight.Medium))
                if (state.file?.mediaKind() == MediaKind.VIDEO) {
                    TvPlayerInfoField("解码输出", tvOutputSignal(player))
                    TvPlayerInfoField("视频解码器", "${readable(player.videoDecoder)} · ${readable(player.videoDecoderKind)}")
                }
                TvPlayerInfoField("音频解码器", readable(player.audioDecoder))
                TvPlayerInfoField("音频输出缓冲", "${player.audioRenderedBuffers} / 跳过 ${player.audioSkippedBuffers}")
                val bufferEnd = player.bufferedPositionMs.takeIf {
                    it > player.positionMs && player.firstFrameRendered
                }
                    ?.plus(state.sourceOffsetMs)
                TvPlayerInfoField("连续缓冲至", bufferEnd?.let(::tvTime) ?: "暂无时间范围")
                TvPlayerInfoField("磁盘缓存 / 上限", "${tvSize(cache.usedBytes)} / ${tvSize(cache.limitBytes)}")
                TvPlayerInfoField("传输速度", tvSpeed(cache.bytesPerSecond))
                val cacheRanges = cache.ranges.firstOrNull()?.let { range ->
                    val first = "${tvTime(range.startMs)}–${tvTime(range.endMs)}"
                    if (cache.ranges.size == 1) first else "$first 等 ${cache.ranges.size} 段"
                }.orEmpty()
                TvPlayerInfoField("缓存时间范围", cacheRanges.ifEmpty { "暂无时间范围" })
                cache.feedback?.let { Text(it, style = tvTextStyle(16, 24), color = TvPlayerDesign.secondary) }
            }
        }
        Column(Modifier.fillMaxSize().padding(end = 16.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Source(Modifier.weight(1f)); Output(Modifier.weight(1f))
            } else { Source(Modifier.fillMaxWidth()); Output(Modifier.fillMaxWidth()) }
        }
        val maxScroll = scroll.maxValue
        if (maxScroll in 1 until Int.MAX_VALUE || focused) {
            Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp)) {
                val trackHeight = size.height
                val range = maxScroll.takeIf { it in 1 until Int.MAX_VALUE }?.toFloat() ?: 0f
                val thumb = (trackHeight * trackHeight / (trackHeight + range))
                    .coerceIn(minOf(24.dp.toPx(), trackHeight), trackHeight)
                val start = if (range == 0f) 0f else (trackHeight - thumb) * scroll.value / range
                val radius = CornerRadius(2.dp.toPx())
                drawRoundRect(TvPlayerDesign.divider, cornerRadius = radius)
                drawRoundRect(if (focused) TvPlayerDesign.focus else TvPlayerDesign.outline,
                    topLeft = Offset(0f, start), size = Size(size.width, thumb), cornerRadius = radius)
            }
        }
    }
}

@Composable private fun TvPlayerInfoField(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.width(136.dp), style = tvTextStyle(16, 24), color = TvPlayerDesign.secondary)
        Text(value, Modifier.weight(1f), style = tvTextStyle(18, 28), color = TvPlayerDesign.text)
    }
}

@Composable private fun TvSubtitleStatus(detail: String?) {
    TvPlaybackFeedback("正在加载字幕", Modifier.padding(bottom = 8.dp), Art.drawable.fileway_ic_subtitles,
        detail?.takeUnless { it == "正在加载字幕" })
}

@Composable private fun ColumnScope.TvSubtitleFiles(
    model: TvModel, state: TvMediaState, player: PlayerState, focus: TvPlayerPanelFocus,
    onBrowserBack: ((() -> Unit)?) -> Unit, subtitleLoading: Boolean, onSelectSubtitle: () -> Unit,
) {
    data class Location(val path: String, val wire: String, val row: String? = null)
    data class Page(val cursor: String?, val result: ScopedSubtitlePage)
    val owner = model.context
    val source = state.file
    val mediaEpoch = model.media.controlEpoch
    val api = remember(owner?.owner) { owner?.api?.let { ScopedSubtitleResources(it) } }
    val scope = rememberCoroutineScope()
    val firstWire = remember(source?.mediaKey) {
        val wire = source?.wirePath?.takeIf { it.isNotEmpty() }
            ?: source?.path?.takeIf { '\uFFFD' !in it }?.let { SearchResult.encodePath(it) }
        wire?.substringBeforeLast('/')?.ifBlank { "/" }?.let {
            runCatching { ScopedSubtitleResources.canonicalWire(recentAccessWireIdentity(it)) }.getOrNull()
        }
    }
    var location by remember {
        mutableStateOf(Location(if (firstWire == null) "/" else source?.path?.substringBeforeLast('/')?.ifBlank { "/" } ?: "/",
            firstWire ?: "/"))
    }
    val history = remember { mutableStateListOf<Location>() }
    val pages = remember { mutableStateListOf<Page>() }
    var pageIndex by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var generation by remember { mutableLongStateOf(0L) }
    var worker by remember { mutableStateOf<Job?>(null) }
    fun isCurrent(token: Long, requested: Location) = generation == token && location == requested &&
        model.context === owner && model.media.controlEpoch == mediaEpoch && model.media.state.value.file?.mediaKey == source?.mediaKey
    fun message(failure: Exception): String = when ((failure as? ServiceException)?.status) {
        401 -> "登录已过期，请重新连接"
        403 -> "当前账号不能读取这个目录"
        404 -> "目录或字幕接口不存在，请检查服务器"
        409 -> "目录内容已变化，请重新读取"
        501 -> "当前文件系统不支持安全字幕浏览"
        else -> failure.message ?: "目录读取失败，请重试"
    }
    fun fail(failure: Exception) {
        val status = (failure as? ServiceException)?.status
        if (status in setOf(401, 403, 404, 409, 501)) { pages.clear(); pageIndex = 0 }
        error = message(failure)
    }
    fun open(next: Location) {
        worker?.cancel(); worker = null
        generation++
        pages.clear(); pageIndex = 0
        error = null
        busy = true
        location = next
        refresh++
        focus.retarget(next.row)
    }
    fun navigate(path: String, wire: String, row: String) {
        history.add(location.copy(row = row))
        open(Location(path, wire))
    }
    fun previous() {
        if (history.isNotEmpty()) open(history.removeAt(history.lastIndex))
    }
    suspend fun readPage(client: ScopedSubtitleResources, wire: String, cursor: String?): ScopedSubtitlePage {
        var attempts = 0
        while (true) {
            try { return client.page(wire, cursor) }
            catch (failure: ServiceException) {
                if (failure.status != 429 || attempts++ >= 2) throw failure
                delay(150L * attempts)
            }
        }
    }
    suspend fun nextVisible(client: ScopedSubtitleResources, wire: String, first: String?): Page {
        var cursor = first
        var last: Page? = null
        repeat(8) {
            val page = Page(cursor, readPage(client, wire, cursor))
            last = page
            if (page.result.entries.isNotEmpty() || page.result.nextCursor == null) return page
            cursor = page.result.nextCursor
        }
        return checkNotNull(last)
    }
    fun turnPage(next: Boolean) {
        val client = api ?: return
        if (busy || pages.isEmpty()) return
        val currentIndex = pageIndex
        val cached = if (next) currentIndex + 1 < pages.size else currentIndex > 0
        val last = pages.last()
        val cursor = if (cached) last.cursor else if (next) last.result.nextCursor else null
        if (!cached && cursor == null) return
        val requested = location
        val token = generation
        busy = true; error = null
        worker = scope.launch {
            try {
                // A cached page is only shown after the latest server cursor
                // rechecks the account policy and directory identity.
                val result = if (cached) Page(cursor, readPage(client, requested.wire, cursor)) else nextVisible(client, requested.wire, cursor)
                if (!isCurrent(token, requested)) return@launch
                if (cached) pageIndex = currentIndex + if (next) 1 else -1
                else {
                    pages.add(result)
                    if (pages.size > 8) pages.removeAt(0)
                    pageIndex = pages.lastIndex
                }
                focus.retarget(null)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (isCurrent(token, requested)) fail(failure)
            } finally { if (isCurrent(token, requested)) busy = false }
        }
    }
    LaunchedEffect(owner?.owner, location.wire, refresh, state.scopedSubtitlePages) {
        val requested = location
        val token = generation
        if (owner == null || api == null) {
            busy = false
            error = "连接不可用"
            return@LaunchedEffect
        }
        if (!state.scopedSubtitlePages) {
            busy = false
            error = "当前服务器未提供安全字幕目录，请更新服务器或检查文件系统"
            return@LaunchedEffect
        }
        busy = true
        error = null
        try {
            api.close(requested.wire)
            val result = nextVisible(api, requested.wire, null)
            if (isCurrent(token, requested)) { pages.clear(); pages.add(result); pageIndex = 0 }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (isCurrent(token, requested)) fail(failure)
        } finally {
            if (isCurrent(token, requested)) busy = false
        }
    }
    DisposableEffect(api, location.wire, state.scopedSubtitlePages) {
        val client = api
        val wire = location.wire
        onDispose {
            worker?.cancel()
            if (client != null && state.scopedSubtitlePages) CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(2_000) { runCatching { client.close(wire) } }
            }
        }
    }
    DisposableEffect(history.size, location.wire) {
        onBrowserBack(if (history.isNotEmpty()) ::previous else null)
        onDispose { onBrowserBack(null) }
    }
    TvPlayerNote(location.path)
    if (subtitleLoading) TvSubtitleStatus(state.subtitleLoadDetail)
    if (delayedBusy(busy)) TvPlayerNote("正在读取目录…")
    error?.let {
        if (!state.scopedSubtitlePages) TvPlayerMessage(it, focus) else TvPlayerNote(it, error = true)
    }
    val selected = model.media.player.importedSubtitleIdentity(player.selectedSubtitle)?.sourceKey
    val shown = pages.getOrNull(pageIndex)?.result
    val entries = shown?.entries.orEmpty()
    val rows = buildList<TvPlayerRow> {
        if (state.scopedSubtitlePages && location.wire != "/") add(TvPlayerRow.Choice("parent", "上一级", icon = Art.drawable.fileway_ic_arrow_back,
            selectable = false, enabled = !busy, action = {
                if (history.isNotEmpty()) previous() else open(Location(location.path.substringBeforeLast('/').ifBlank { "/" },
                    location.wire.substringBeforeLast('/').ifBlank { "/" }, "parent"))
            }))
        if (error != null && state.scopedSubtitlePages) add(TvPlayerRow.Choice("retry", "重试", selectable = false, action = {
            focus.retarget("retry")
            worker?.cancel(); worker = null; generation++; pages.clear(); pageIndex = 0
            refresh++
        }))
        entries.forEach { entry ->
            val file = entry.file
            add(TvPlayerRow.Choice(
                file.mediaKey, file.name, selected = !file.directory && selected == "external:${file.mediaKey}",
                enabled = !busy && (file.directory || file.size > 0), icon = if (file.directory) Art.drawable.fileway_ic_folder else null,
                selectable = !file.directory, destination = file.directory, action = {
                    if (file.directory) navigate(file.path, file.wirePath, file.mediaKey)
                    else {
                        onSelectSubtitle()
                        model.media.selectScopedSubtitle(entry)
                    }
                },
            ))
        }
        if (pageIndex > 0) add(TvPlayerRow.Choice("previous-page", "上一页", selectable = false, enabled = !busy, action = { turnPage(false) }))
        if (shown?.nextCursor != null || pageIndex + 1 < pages.size) add(TvPlayerRow.Choice("next-page",
            if (entries.isEmpty() && pageIndex == pages.lastIndex) "继续查找" else "下一页",
            selectable = false, enabled = !busy, action = { turnPage(true) }))
    }
    if (!busy && entries.isEmpty() && shown?.nextCursor == null && error == null) {
        if (rows.isEmpty()) TvPlayerMessage("此目录没有字幕或子文件夹", focus)
        else TvPlayerNote("此目录没有字幕或子文件夹")
    }
    key(location.wire) { TvPlayerChoiceList(rows, focus, Modifier.weight(1f)) }
}
