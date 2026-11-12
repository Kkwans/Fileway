package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MimeTypes
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.fileway.remote.RemoteSnapshot
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import io.github.kkwans.nasfilebrowser.app.TvSubtitleSource
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.TextSubtitleArea
import io.github.kkwans.nasfilebrowser.data.parsePlaybackRate
import io.github.kkwans.nasfilebrowser.data.parseSubtitleDelayMillis
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow
import io.github.kkwans.nasfilebrowser.ui.designsystem.drawMediaProgressTrack
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private enum class RemoteMediaPanel(val title:String, val subtitleSource: TvSubtitleSource? = null) {
    AUDIO("音轨"), SUBTITLE("字幕"), SERVER_SUBTITLES("TV 当前服务器", TvSubtitleSource.SERVER),
    LOCAL_SUBTITLES("TV 本机字幕", TvSubtitleSource.LOCAL), SIDECARS("同目录外挂"),
    APPEARANCE("字幕调整"), FONT("字幕字体"),
    SPEED("播放速度"), OPTIONS("播放设置"), QUEUE("队列"),
}

/** The same phone components and source-fenced actions across video, music and images. */
@Composable internal fun TvPhoneMediaControls(model: TvControlModel, snapshot: RemoteSnapshot, compact: Boolean = false, onBrowse: () -> Unit) {
    val owner="${snapshot.connectionId}/${snapshot.sessionEpoch}/${snapshot.mediaEpoch}"
    var panel by remember(owner){mutableStateOf<RemoteMediaPanel?>(null)}
    val player=snapshot.content.optJSONObject("playback")?:JSONObject()
    val queue=snapshot.content.optJSONObject("queue")?:JSONObject()
    val index=queue.optInt("index",-1);val count=queue.optInt("count",0)
    fun send(operation:String,payload:JSONObject=JSONObject())=model.commandFor(snapshot,operation,payload)
    if(snapshot.scene !in setOf("VIDEO","AUDIO","IMAGE")) {
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("TV 当前没有打开媒体",style=MaterialTheme.typography.titleMedium)
            FilewayOutlinedButton(onClick=onBrowse){Text("浏览 TV 文件")}
        };return
    }
    val compactTransport = compact && snapshot.scene != "IMAGE"
    val transportWidth = if (compactTransport) 480.dp else Dp.Unspecified
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compactTransport) 4.dp else 12.dp)) {
        val mediaName=snapshot.content.optJSONObject("media")?.optString("name")?.takeIf{it.isNotBlank()&&it!="null"}?:"TV 媒体"
        val mediaLabel=when(snapshot.scene){"VIDEO"->"视频";"AUDIO"->"音乐";else->"图片"}
        val mediaIcon=when(snapshot.scene){"VIDEO"->Art.drawable.fileway_ic_video;"AUDIO"->Art.drawable.fileway_ic_audio;else->Art.drawable.fileway_ic_image}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Surface(Modifier.size(if (compactTransport) 40.dp else 48.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface){
                Box(contentAlignment=Alignment.Center){FilewayIcon(mediaIcon,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.primary)}
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text(mediaName, style = MaterialTheme.typography.titleMedium,
                    maxLines = if (compactTransport) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                if (!compactTransport) Text(mediaLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (compactTransport) Box(Modifier.widthIn(max = 176.dp)) { RemotePlaybackStatus(player, maxLines = 2) }
        }
        if(snapshot.scene=="IMAGE") {
            val image=snapshot.content.optJSONObject("image")?:JSONObject()
            val actual=image.optDouble("scale",1.0).toFloat().coerceIn(1f,3f)
            var preview by remember(owner){mutableStateOf<Float?>(null)}
            var requested by remember(owner){mutableStateOf<Float?>(null)}
            LaunchedEffect(actual,requested){if(requested?.let{kotlin.math.abs(actual-it)<.02f}==true)requested=null}
            LaunchedEffect(requested){if(requested!=null){delay(5000);requested=null}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                FilewayIconButton(onClick={send("image.previous")},enabled=index>0){FilewayIcon(Art.drawable.fileway_ic_skip_previous,"上一张")}
                Text(if(index>=0)"${index+1} / $count"else "",style=MaterialTheme.typography.titleMedium)
                FilewayIconButton(onClick={send("image.next")},enabled=index>=0&&index<count-1){FilewayIcon(Art.drawable.fileway_ic_skip_next,"下一张")}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("缩放 ${((preview?:requested?:actual)*100).toInt()}%",Modifier.weight(1f))
                FilewayTextButton(onClick={send("image.setTransform",JSONObject().put("scale",1).put("x",0).put("y",0))}){Text("适合屏幕")}
            }
            PlayerSlider(preview?:requested?:actual,3f,"TV 图片缩放",true,{preview=it},finish={preview?.let{requested=it;send("image.setTransform",JSONObject().put("scale",it).put("x",image.optDouble("x")).put("y",image.optDouble("y")))};preview=null},minimum=1f,mediaSurface=false,description="${((preview?:requested?:actual)*100).toInt()}%")
            if(actual>1f) {
                Text("移动图片",style=MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    listOf("左" to (-.2 to 0.0),"上" to (0.0 to -.2),"下" to (0.0 to .2),"右" to (.2 to 0.0)).forEach{(label,delta)->
                        FilewayOutlinedButton(onClick={send("image.setTransform",JSONObject().put("scale",actual).put("x",(image.optDouble("x")+delta.first).coerceIn(-1.0,1.0)).put("y",(image.optDouble("y")+delta.second).coerceIn(-1.0,1.0)))}){Text(label)}
                    }
                }
            }
        } else {
            val knownDuration=player.optLong("durationMs").coerceAtLeast(0)
            val duration=knownDuration.coerceAtLeast(1)
            val position=player.optLong("positionMs").coerceAtLeast(0).let{if(knownDuration>0)it.coerceAtMost(duration)else it}
            var preview by remember(owner){mutableStateOf<Float?>(null)}
            var requested by remember(owner){mutableStateOf<Long?>(null)}
            LaunchedEffect(position,player.optBoolean("seeking"),player.optBoolean("preparing"),player.optString("seekError"),requested){
                if(requested?.let{kotlin.math.abs(position-it)<1000}==true&&!player.optBoolean("seeking")&&!player.optBoolean("preparing"))requested=null
                if(player.optString("seekError").let{it.isNotBlank()&&it!="null"})requested=null
            }
            LaunchedEffect(requested){if(requested!=null){delay(125000);requested=null}}
            val shown=preview?:requested?.toFloat()?:position.toFloat()
            val timeline: @Composable (Modifier) -> Unit = { modifier ->
                PlayerSlider(if (knownDuration > 0) shown else 0f, duration.toFloat(), "TV 播放进度",
                    player.optBoolean("seekable") && knownDuration > 0, { preview = it },
                    finish = {
                        preview?.let { requested = it.toLong(); send("playback.seekTo", JSONObject().put("positionMs", it.toLong())) }
                        preview = null
                    }, modifier = modifier,
                    bufferedValue = if (knownDuration > 0) player.optLong("bufferedMs").toFloat().coerceIn(0f, duration.toFloat()) else null,
                    mediaSurface = false, description = "${tvRemoteTime(shown.toLong())} / ${if (knownDuration > 0) tvRemoteTime(duration) else "时长未知"}")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tvRemoteTime(shown.toLong()),style=MaterialTheme.typography.bodyMedium)
                if (compactTransport) timeline(Modifier.weight(1f))
                else Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { RemotePlaybackStatus(player) }
                Text(if(knownDuration>0)tvRemoteTime(duration)else"--:--",style=MaterialTheme.typography.bodyMedium)
            }
            if (!compactTransport) timeline(Modifier)
            val controls = Modifier.align(Alignment.CenterHorizontally)
                .widthIn(max = transportWidth).fillMaxWidth()
            Row(controls,horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                FilewayIconButton(onClick={send("queue.previous")},enabled=index>0){FilewayIcon(Art.drawable.fileway_ic_skip_previous,"上一项")}
                val step=snapshot.content.optJSONObject("preferences")?.optInt("seekStepSeconds",10)?.coerceIn(3,30)?:10
                FilewayTextButton(onClick={send("playback.seekBy",JSONObject().put("deltaMs",-step*1000L))},enabled=player.optBoolean("seekable")){Text("−${step}s")}
                FilledIconButton(onClick={send(if(player.optBoolean("playing"))"playback.pause"else"playback.play")},modifier=Modifier.size(if (compactTransport) 56.dp else 64.dp)) {
                    FilewayIcon(if(player.optBoolean("playing"))Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,if(player.optBoolean("playing"))"暂停"else"播放",Modifier.size(32.dp))
                }
                FilewayTextButton(onClick={send("playback.seekBy",JSONObject().put("deltaMs",step*1000L))},enabled=player.optBoolean("seekable")){Text("+${step}s")}
                FilewayIconButton(onClick={send("queue.next")},enabled=index>=0&&index<count-1){FilewayIcon(Art.drawable.fileway_ic_skip_next,"下一项")}
            }
            Row(controls,horizontalArrangement=Arrangement.SpaceEvenly) {
                RemoteMediaAction("音轨",Art.drawable.fileway_ic_audio){panel=RemoteMediaPanel.AUDIO}
                if(snapshot.scene=="VIDEO")RemoteMediaAction("字幕",Art.drawable.fileway_ic_subtitles){panel=RemoteMediaPanel.SUBTITLE}
                RemoteMediaAction("${player.optDouble("rate",1.0)}×",Art.drawable.fileway_ic_speed){panel=RemoteMediaPanel.SPEED}
                RemoteMediaAction("设置",Art.drawable.fileway_ic_settings){panel=RemoteMediaPanel.OPTIONS}
            }
        }
        if (count > 0) {
            FilewayOutlinedButton(
                onClick = { panel = RemoteMediaPanel.QUEUE },
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = transportWidth).fillMaxWidth(),
            ) {
                FilewayIcon(Art.drawable.fileway_ic_playlist_play, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (index >= 0) "队列 · ${index + 1} / $count" else "队列 · $count 项")
            }
        }
        if(snapshot.scene=="VIDEO")snapshot.content.optJSONObject("cache")?.let{RemoteCacheStatus(it,player.optLong("durationMs").coerceAtLeast(0))}
    }
    val selectedSource = panel?.subtitleSource
    val canBrowseSubtitles = selectedSource?.supportedBy(snapshot.capabilities) ?: true
    LaunchedEffect(canBrowseSubtitles, selectedSource) {
        if (!canBrowseSubtitles) panel = RemoteMediaPanel.SUBTITLE
    }
    panel?.let { selected ->
        when (selected) {
            RemoteMediaPanel.SUBTITLE, RemoteMediaPanel.SIDECARS, RemoteMediaPanel.APPEARANCE, RemoteMediaPanel.FONT ->
                RemoteSubtitleSheet(model, snapshot, selected, { panel = null }, { panel = it })
            RemoteMediaPanel.SERVER_SUBTITLES, RemoteMediaPanel.LOCAL_SUBTITLES -> if (canBrowseSubtitles && selectedSource != null) {
                key(selectedSource, selectedSource.storageRevision(snapshot.content)) {
                    TvRemoteSubtitlePicker(model, snapshot, selectedSource, selected.title) { panel = RemoteMediaPanel.SUBTITLE }
                }
            }
            else -> RemoteMediaSheet(model, snapshot, selected) { panel = null }
        }
    }
}

@Composable private fun RemoteMediaAction(label:String,icon:Int,onClick:()->Unit) {
    FilewayTextButton(onClick=onClick){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){FilewayIcon(icon,null,Modifier.size(24.dp));Text(label,style=MaterialTheme.typography.labelMedium)}}
}

@Composable private fun RemotePlaybackStatus(player: JSONObject, maxLines: Int = Int.MAX_VALUE) {
    val error=player.optString("seekError").takeIf{it.isNotBlank()&&it!="null"}
    val text=error?:when {
        player.optBoolean("preparing")->"正在准备播放…"
        player.optBoolean("waitingForBuffer")->"缓冲 ${player.optInt("buffering").coerceIn(0,100)}%"
        player.optBoolean("seeking")->"正在跳转…"
        else->null
    }
    text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun RemoteCacheStatus(cache:JSONObject,duration:Long){
    val ranges=cache.optJSONArray("ranges")?.let{values->
        (0 until values.length()).mapNotNull{index->values.optJSONObject(index)?.let{range->
            val start=range.optLong("startMs",-1);val end=range.optLong("endMs",-1)
            if(start>=0&&end>start&&duration>0&&start<duration)start to end.coerceAtMost(duration)else null
        }}
    }.orEmpty()
    val error=cache.optString("error").takeIf{it.isNotBlank()&&it!="null"}
    val bytes=cache.optLong("usedBytes",-1)
    val limit=cache.optLong("limitBytes",-1)
    val speed=cache.optLong("bytesPerSecond",-1)
    if(ranges.isNotEmpty()||error!=null||bytes>=0&&limit>0){
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
            if(bytes>=0&&limit>0)Text("TV 缓存 ${readableSize(bytes)} / ${readableSize(limit)}"+(if(speed>0)" · ${readableSize(speed)}/s"else""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(ranges.isNotEmpty()){
                Text("TV 报告的缓存时间段",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val description=ranges.joinToString("，"){(start,end)->"${tvRemoteTime(start)} 至 ${tvRemoteTime(end)}"}
                val track=MaterialTheme.colorScheme.onSurface.copy(alpha=.18f)
                val cached=MaterialTheme.colorScheme.primary.copy(alpha=.55f)
                Canvas(Modifier.fillMaxWidth().height(8.dp).semantics{contentDescription="TV 已缓存时间段";stateDescription=description}){
                    drawMediaProgressTrack(0f,track,cached,cachedRanges=ranges.map{(start,end)->start.toFloat()/duration to end.toFloat()/duration},downloadedColor=cached,thickness=3.dp,thumbRadius=0.dp)
                }
            }
            error?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable private fun RemoteMediaSheet(model:TvControlModel,snapshot:RemoteSnapshot,panel:RemoteMediaPanel,onClose:()->Unit) {
    val player=snapshot.content.optJSONObject("playback")?:JSONObject()
    val queue=snapshot.content.optJSONObject("queue")?:JSONObject()
    val choices=when(panel){
        RemoteMediaPanel.AUDIO->rememberMediaChoices(model,snapshot,"audio",player.optInt("audioCount"))
        RemoteMediaPanel.QUEUE->rememberMediaChoices(model,snapshot,"queue",queue.optInt("count"))
        else->null
    }
    val scroll=remember(panel){LazyListState()}
    LaunchedEffect(panel,choices?.rows) {
        val selected=choices?.rows?.indexOfFirst { row->when(panel){
            RemoteMediaPanel.QUEUE->row.optInt("index")==queue.optInt("index")
            RemoteMediaPanel.AUDIO->row.optInt("id")==player.optInt("selectedAudio")
            else->false
        }}?:-1
        if(selected>=0){withFrameNanos{};scroll.scrollToItem(selected)}
    }
    fun send(operation:String,payload:JSONObject=JSONObject())=model.commandFor(snapshot,operation,payload)
    FileOperationSheet(if(panel==RemoteMediaPanel.QUEUE)"队列 · ${queue.optInt("count")}"else panel.title,onClose) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),state=scroll,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            when(panel) {
                RemoteMediaPanel.AUDIO -> mediaChoiceRows(requireNotNull(choices),{it.optInt("id")==player.optInt("selectedAudio")}) {
                    send("audio.select",JSONObject().put("id",it.getInt("id")).put("source",it.optBoolean("source")));onClose()
                }
                RemoteMediaPanel.QUEUE -> mediaChoiceRows(requireNotNull(choices),{it.optInt("index")==queue.optInt("index")}){send("queue.select",JSONObject().put("index",it.getInt("index")));onClose()}
                RemoteMediaPanel.SPEED -> {
                    items(listOf(.5,.75,1.0,1.25,1.5,2.0)){rate->FilewaySelectionRow("${rate}×",selected=kotlin.math.abs(player.optDouble("rate",1.0)-rate)<.01,onClick={send("playback.setRate",JSONObject().put("rate",rate));onClose()})}
                    item {
                        var text by remember{mutableStateOf(player.optDouble("rate",1.0).toString())}
                        OutlinedTextField(text,{text=it},label={Text("自定义速度（0.1–5）")},shape=RoundedCornerShape(12.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),singleLine=true)
                        FilewayButton(onClick={parsePlaybackRate(text)?.let{send("playback.setRate",JSONObject().put("rate",it));onClose()}},enabled=parsePlaybackRate(text)!=null){Text("应用")}
                    }
                }
                RemoteMediaPanel.OPTIONS -> {
                    item{Text("应用音量 ${player.optInt("volume")}%",style=MaterialTheme.typography.titleSmall)}
                    item{var volume by remember{mutableStateOf<Float?>(null)};PlayerSlider(volume?:player.optInt("volume").toFloat(),100f,"TV 应用音量",true,{volume=it},finish={volume?.let{send("playback.setVolume",JSONObject().put("volume",it.toInt()))};volume=null},mediaSurface=false)}
                    item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilewayOutlinedButton(onClick={send("playback.setVolume",JSONObject().put("volume",(player.optInt("volume")-1).coerceAtLeast(0)))}){Text("−1%")};FilewayOutlinedButton(onClick={send("playback.setVolume",JSONObject().put("volume",(player.optInt("volume")+1).coerceAtMost(100)))}){Text("+1%")}}}
                    if(snapshot.scene=="VIDEO") {
                        item{Text("动态范围",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleSmall)}
                        items(listOf("AUTO" to "自动","NATIVE" to "原生 HDR","SDR" to "SDR 兼容")){(mode,title)->FilewaySelectionRow(title,selected=player.optString("hdrMode")==mode,enabled=mode!="SDR"||player.optBoolean("sdrAvailable"),onClick={send("playback.setHdrMode",JSONObject().put("mode",mode));onClose()})}
                    }
                }
                else -> Unit // Subtitle pages use the shared player layout.
            }
        }
    }
}

@Composable
private fun RemoteSubtitleSheet(
    model: TvControlModel,
    snapshot: RemoteSnapshot,
    panel: RemoteMediaPanel,
    onClose: () -> Unit,
    onPanel: (RemoteMediaPanel) -> Unit,
) {
    val player = snapshot.content.optJSONObject("playback") ?: JSONObject()
    val subtitleLoading = player.optBoolean("subtitleLoading")
    val panelLists = snapshot.capabilities.optInt("mediaPanels") == 1
    val trackRevision = remember(player) {
        player.optJSONArray("sourceSubtitles")?.toString() to player.optJSONArray("subtitles")?.toString()
    }
    val trackChoices = rememberMediaChoices(model, snapshot, "subtitles", player.optInt("subtitleCount"), trackRevision)
    val tracks = remember(trackChoices.rows) {
        trackChoices.rows.orEmpty().mapNotNull(::remoteSubtitleTrack).distinctBy { it.key }
    }
    val selectedSource = player.optInt("selectedSourceSubtitle", -1)
    val selectedNative = player.optInt("selectedSubtitle", -1)
    // The native rendering ID of a source subtitle can also appear in the player list.
    val selectedTrack = tracks.firstOrNull { it.source && it.id == selectedSource }
        ?: tracks.firstOrNull { !it.source && it.id == selectedNative }
    val off = player.has("selectedSubtitle") && selectedSource < 0 && selectedNative < 0
    val appearanceJson = player.optJSONObject("subtitleAppearance")
    val appearance = remember(appearanceJson?.toString()) { remoteSubtitleAppearance(appearanceJson) }
    val back = when (panel) {
        RemoteMediaPanel.APPEARANCE, RemoteMediaPanel.SIDECARS -> RemoteMediaPanel.SUBTITLE
        RemoteMediaPanel.FONT -> RemoteMediaPanel.APPEARANCE
        else -> null
    }
    val tracksScroll = remember { LazyListState() }
    val sidecarsScroll = remember { LazyListState() }
    val modelNotice by model.notice.collectAsStateWithLifecycle()
    val initialNotice = remember { modelNotice }
    val commandNotice = modelNotice?.takeUnless { it == initialNotice }
    val tvNotice = snapshot.content.label("notice")
    val status: @Composable () -> Unit = {
        if (subtitleLoading || tvNotice != null || commandNotice != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subtitleLoading) RemoteSubtitleLoading(
                    player.optString("subtitleLoadDetail").take(80).ifBlank { "电视正在准备字幕…" },
                )
                tvNotice?.let { RemoteSubtitleNote(it) }
                commandNotice?.takeUnless { it == tvNotice }?.let { RemoteSubtitleNote(it, error = true) }
            }
        }
    }
    fun send(operation: String, payload: JSONObject = JSONObject()) = model.commandFor(snapshot, operation, payload)

    FileOperationSheet(panel.title, { if (back == null) onClose() else onPanel(back) },
        back = back != null, backDescription = "返回${back?.title.orEmpty()}") {
        when (panel) {
            RemoteMediaPanel.SUBTITLE -> {
                val rows = remember(tracks) {
                    val titles = tracks.groupingBy { it.title.lowercase(Locale.ROOT) }.eachCount()
                    listOf(PlayerTrackChoice("off", "关闭字幕")) + tracks.mapIndexed { index, track ->
                        val base = track.title.ifBlank { "字幕 ${index + 1}" }
                        val title = if (titles.getValue(track.title.lowercase(Locale.ROOT)) > 1 && track.title.isNotBlank())
                            "$base · ${index + 1}" else base
                        PlayerTrackChoice(track.key, title, playerTrackDetails(track.language, remoteSubtitleFormatLabel(track.codec), base))
                    }
                }
                val hasSidecars = player.optInt("sidecarCount") > 0 || (player.optJSONArray("sidecars")?.length() ?: 0) > 0
                val canBrowseServer = TvSubtitleSource.SERVER.supportedBy(snapshot.capabilities)
                val canBrowseLocal = TvSubtitleSource.LOCAL.supportedBy(snapshot.capabilities)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(), state = tracksScroll) {
                    item(key = "actions") {
                        PlayerSubtitleActions(buildList {
                            add(PlayerSubtitleAction(Art.drawable.fileway_ic_edit, "字幕调整", { onPanel(RemoteMediaPanel.APPEARANCE) }))
                            if (canBrowseServer)
                                add(PlayerSubtitleAction(Art.drawable.fileway_ic_server, "服务器字幕",
                                    { onPanel(RemoteMediaPanel.SERVER_SUBTITLES) }))
                            if (canBrowseLocal)
                                add(PlayerSubtitleAction(Art.drawable.fileway_ic_folder, "本机字幕",
                                    { onPanel(RemoteMediaPanel.LOCAL_SUBTITLES) }))
                            if (hasSidecars && !canBrowseServer)
                                add(PlayerSubtitleAction(Art.drawable.fileway_ic_server, "同目录外挂", { onPanel(RemoteMediaPanel.SIDECARS) }))
                        }, mediaSurface = false)
                    }
                    if (hasSidecars && canBrowseServer) item(key = "sidecar-shortcut") {
                        FilewayTextButton(onClick = { onPanel(RemoteMediaPanel.SIDECARS) }) { Text("选择同目录字幕") }
                    }
                    if (!canBrowseServer) item(key = "server-subtitle-upgrade") {
                        RemoteSubtitleNote("要从其他目录添加字幕，请更新电视端和当前连接的服务器。")
                    }
                    item(key = "status") { status() }
                    playerTrackChoices(rows, if (off) "off" else selectedTrack?.key, mediaSurface = false) { key ->
                        if (key == "off") send("subtitle.disable")
                        else tracks.firstOrNull { it.key == key }?.let { track ->
                            send("subtitle.select", JSONObject().put("id", track.id).put("source", track.source))
                        }
                    }
                    subtitleListStatus(trackChoices, "当前没有已加载的字幕轨道")
                }
            }
            RemoteMediaPanel.SIDECARS -> {
                val sidecars = rememberMediaChoices(model, snapshot, "sidecars", player.optInt("sidecarCount"),
                    player.optJSONArray("sidecars")?.toString())
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = sidecarsScroll) {
                    item(key = "status") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteSubtitleNote("选择后在电视上加载，已选轨道可在字幕页查看。")
                            status()
                        }
                    }
                    val files = sidecars.rows.orEmpty().mapNotNull { row ->
                        row.label("id")?.let { id -> PlayerTrackChoice(id, row.label("name") ?: "字幕文件") }
                    }.distinctBy { it.key }
                    items(files, key = { "sidecar:${it.key}" }) { file ->
                        PlayerTrackRow(file.title, "", selected = null, pending = false,
                            mediaSurface = false, enabled = !subtitleLoading) {
                            send("subtitle.selectNasFile", JSONObject().put("id", file.key))
                        }
                    }
                    subtitleListStatus(sidecars, "当前目录没有可用的外挂字幕")
                }
            }
            RemoteMediaPanel.APPEARANCE -> {
                val formatNote = remoteSubtitleFormatNote(subtitleLoading, off, selectedTrack?.codec, appearance)
                RemoteSubtitleAdjustments(
                    player.optLong("subtitleDelayMs"), appearance.takeIf { formatNote == null }, formatNote,
                    fontLabel = appearanceJson?.label("fontLabel")?.takeIf { panelLists },
                    onFont = { onPanel(RemoteMediaPanel.FONT) }, status = status,
                    onDelay = { send("subtitle.setDelay", JSONObject().put("delayMs", it)) },
                    onAppearance = { send("subtitle.setAppearance", it) },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            RemoteMediaPanel.FONT -> {
                val fonts = rememberMediaChoices(model, snapshot, "fonts", 0)
                val rows = remember(fonts.rows) {
                    fonts.rows.orEmpty().mapNotNull { row ->
                        row.label("id")?.let { id -> row.label("title")?.let { title -> PlayerTrackChoice("font:$id", title) } }
                    }.distinctBy { it.key }
                }
                val supported = remoteSubtitleFormatNote(subtitleLoading, off, selectedTrack?.codec, appearance) == null
                val fontScroll = remember { LazyListState() }
                LaunchedEffect(rows) {
                    val selected = rows.indexOfFirst { it.key == "font:${appearance?.fontKey}" }
                    if (selected >= 0) fontScroll.scrollToItem(selected + 1)
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(), state = fontScroll) {
                    item(key = "status") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteSubtitleNote(if (supported) "字体保存在电视上，实际字形以电视显示为准。"
                                else "当前字幕不可调整字体。")
                            status()
                        }
                    }
                    playerTrackChoices(rows, appearance?.fontKey?.let { "font:$it" }, mediaSurface = false, enabled = supported) { key ->
                        if (key != "font:${appearance?.fontKey}")
                            send("subtitle.setAppearance", JSONObject().put("fontId", key.removePrefix("font:")))
                    }
                    subtitleListStatus(fonts, "电视没有提供可选字体")
                }
            }
            else -> Unit
        }
    }
}

private data class RemoteSubtitleTrack(val id: Int, val source: Boolean, val title: String, val codec: String, val language: String) {
    val key: String get() = if (source) "source:$id" else "native:$id"
}

private fun remoteSubtitleTrack(row: JSONObject): RemoteSubtitleTrack? {
    val id = row.optInt("id", -1).takeIf { it >= 0 } ?: return null
    return RemoteSubtitleTrack(id, row.optBoolean("source"), row.label("title").orEmpty(),
        row.label("codec").orEmpty(), row.label("language").orEmpty())
}

private fun JSONObject.label(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

@Composable private fun RemoteSubtitleNote(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun RemoteSubtitleLoading(text: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(150); visible = true }
    if (visible) Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        RemoteSubtitleNote(text)
    }
}

private fun LazyListScope.subtitleListStatus(choices: MediaChoices, empty: String) {
    val error = choices.error
    when {
        error != null -> item(key = "list-error") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                RemoteSubtitleNote(error, error = true)
                FilewayTextButton(onClick = choices.retry) { Text("重试") }
            }
        }
        choices.rows == null -> item(key = "list-loading") {
            RemoteSubtitleLoading("正在读取电视列表…", Modifier.padding(12.dp))
        }
        choices.rows.isEmpty() -> item(key = "list-empty") { Box(Modifier.padding(12.dp)) { RemoteSubtitleNote(empty) } }
    }
}

private data class MediaChoices(val rows:List<JSONObject>?,val error:String?,val retry:()->Unit)

@Composable private fun rememberMediaChoices(model:TvControlModel,snapshot:RemoteSnapshot,kind:String,count:Int,revision:Any?=null):MediaChoices {
    var rows by remember(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch,kind,count,revision){mutableStateOf<List<JSONObject>?>(null)}
    var error by remember(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch,kind,count,revision){mutableStateOf<String?>(null)}
    var retry by remember(kind){mutableIntStateOf(0)}
    LaunchedEffect(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch,kind,count,revision,retry) {
        try {
            error=null
            rows=if(snapshot.capabilities.optInt("mediaPanels")==1)model.mediaList(snapshot,kind)else legacyMediaRows(snapshot,kind)
        } catch (failure: TimeoutCancellationException) {
            error = "读取电视列表超时，可重试"
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "列表暂不可用"
        }
    }
    return MediaChoices(rows,error){retry++}
}

private fun LazyListScope.mediaChoiceRows(state:MediaChoices,selected:(JSONObject)->Boolean,empty:String="暂无可用选项",onChoose:(JSONObject)->Unit) {
    if(state.rows==null&&state.error==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
    items(state.rows.orEmpty(),key={"${it.optString("id")}/${it.optBoolean("source")}/${it.optInt("index",-1)}"}) { row->
        FilewaySelectionRow(row.optString("title",row.optString("name")),subtitle=listOf(row.optString("language"),row.optString("codec")).filter{it.isNotBlank()}.joinToString(" · ").ifBlank{null},selected=selected(row),onClick={onChoose(row)})
    }
    state.error?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error);FilewayOutlinedButton(onClick=state.retry){Text("重试")}}}
    if(state.rows?.isEmpty()==true&&state.error==null)item{Text(empty,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}

private fun legacyMediaRows(snapshot:RemoteSnapshot,kind:String):List<JSONObject> {
    val player=snapshot.content.optJSONObject("playback")?:JSONObject()
    fun JSONArray?.rows()=this?.let{(0 until length()).map{JSONObject(getJSONObject(it).toString())}}.orEmpty()
    return when(kind) {
        "audio"->player.optJSONArray("audio").rows()
        "subtitles"->{val source=player.optJSONArray("sourceSubtitles").rows();if(source.isNotEmpty())source.onEach{it.put("source",true)}else player.optJSONArray("subtitles").rows().filter{it.optInt("id")>=0}}
        "sidecars"->player.optJSONArray("sidecars").rows()
        "queue"->snapshot.content.optJSONObject("queue")?.optJSONArray("items").rows().onEachIndexed{index,row->row.put("index",index)}
        else->error("请升级 TV 客户端后使用此选项")
    }
}

private fun remoteSubtitleAppearance(value: JSONObject?): TextSubtitleAppearance? {
    if (value == null) return null
    val area = TextSubtitleArea.entries.firstOrNull { it.name == value.optString("area") } ?: return null
    val font = value.label("fontId") ?: return null
    val scale = value.optDouble("scale").toFloat()
    val margin = value.optDouble("bottomPadding").toFloat()
    val opacity = value.optDouble("backgroundOpacity").toFloat()
    if (scale !in .5f..2f || margin !in 0f.. .4f || opacity !in 0f..1f) return null
    return TextSubtitleAppearance(scale, margin, area, opacity, font)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun remoteSubtitleFormatLabel(codec: String): String = when (codec.trim().lowercase(Locale.ROOT)) {
    "srt", "subrip", MimeTypes.APPLICATION_SUBRIP -> "SRT"
    "ass", "ssa", "application/ass", "application/x-ass", MimeTypes.TEXT_SSA -> "ASS"
    "vtt", "webvtt", MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT -> "WebVTT"
    "pgs", "hdmv_pgs_subtitle", MimeTypes.APPLICATION_PGS -> "PGS"
    "dvbsub", "dvb_subtitle", MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    "vobsub", "dvd_subtitle", MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    else -> codec.takeUnless { '/' in it }.orEmpty()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun remoteSubtitleFormatNote(loading: Boolean, off: Boolean, codec: String?, appearance: TextSubtitleAppearance?): String? {
    if (loading) return "字幕准备完成后会显示适用的外观设置。"
    if (off) return "选择一条字幕后可查看适用的外观设置。"
    return when (codec?.trim()?.lowercase(Locale.ROOT)) {
        "ass", "ssa", "application/ass", "application/x-ass", MimeTypes.TEXT_SSA -> SubtitleAuthorStyleNote
        "pgs", "hdmv_pgs_subtitle", "dvbsub", "dvb_subtitle", "vobsub", "dvd_subtitle",
        MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB -> SubtitleBitmapStyleNote
        "srt", "subrip", "vtt", "webvtt", "mov_text", "tx3g", "ttml", "sami", "application/x-sami",
        MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT,
        MimeTypes.APPLICATION_TX3G, MimeTypes.APPLICATION_TTML ->
            if (appearance == null) "电视未报告普通文字字幕的外观设置。" else null
        else -> "电视未确认当前轨道支持文字外观，仍可调整字幕时间。"
    }
}

@Composable private fun RemoteSubtitleAdjustments(
    delayMs: Long,
    appearance: TextSubtitleAppearance?,
    formatNote: String?,
    fontLabel: String?,
    onFont: () -> Unit,
    status: @Composable () -> Unit,
    onDelay: (Long) -> Unit,
    onAppearance: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    var offset by remember { mutableStateOf("") }
    var offsetError by remember { mutableStateOf<String?>(null) }
    SubtitleAdjustmentContent(
        delayMs = delayMs,
        offsetText = offset,
        onOffsetText = { offset = it; offsetError = null },
        offsetError = offsetError,
        onDelay = { onDelay(it.coerceIn(-600000, 600000)) },
        onApplyOffset = {
            val parsed = parseSubtitleDelayMillis(offset)
            if (parsed == null) offsetError = "请输入 -600 到 600 秒"
            else { onDelay(parsed); offsetError = null }
        },
        appearance = appearance,
        formatNote = formatNote,
        onAppearance = { next ->
            if (appearance != null) {
                val patch = JSONObject()
                if (appearance.scale != next.scale) patch.put("scale", next.scale)
                if (appearance.bottomPadding != next.bottomPadding) patch.put("bottomPadding", next.bottomPadding)
                if (appearance.area != next.area) patch.put("area", next.area.name)
                if (appearance.backgroundOpacity != next.backgroundOpacity) patch.put("backgroundOpacity", next.backgroundOpacity)
                if (patch.length() > 0) onAppearance(patch)
            }
        },
        fontLabel = fontLabel,
        onChooseFont = onFont,
        modifier = modifier,
        mediaSurface = false,
        commitSliders = true,
        previewLabel = "样式示意 · Aa",
        fontHint = "外观更改会保存到电视，实际字形以电视显示为准。",
        status = status,
        reset = {
            SubtitleAppearanceActions(onReset = { onAppearance(JSONObject().put("reset", true)) }, canReset = true)
        },
    )
}

internal fun tvRemoteTime(ms:Long):String {
    val seconds=ms.coerceAtLeast(0)/1000
    return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)else"%d:%02d".format(seconds/60,seconds%60)
}
