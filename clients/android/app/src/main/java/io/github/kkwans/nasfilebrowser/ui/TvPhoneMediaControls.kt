package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.fileway.remote.RemoteSnapshot
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import io.github.kkwans.nasfilebrowser.data.parsePlaybackRate
import io.github.kkwans.nasfilebrowser.data.parseSubtitleDelayMillis
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private enum class RemoteMediaPanel(val title:String) {
    AUDIO("音轨"), SUBTITLE("字幕"), APPEARANCE("字幕外观与同步"), FONT("字幕字体"), SPEED("播放速度"), OPTIONS("播放设置"), QUEUE("队列"),
}

/** The same phone components and source-fenced actions across video, music and images. */
@Composable internal fun TvPhoneMediaControls(model:TvControlModel,snapshot:RemoteSnapshot,onBrowse:()->Unit) {
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
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(snapshot.content.optJSONObject("media")?.optString("name").orEmpty(),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
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
            val duration=player.optLong("durationMs").coerceAtLeast(1)
            val position=player.optLong("positionMs").coerceIn(0,duration)
            var preview by remember(owner){mutableStateOf<Float?>(null)}
            var requested by remember(owner){mutableStateOf<Long?>(null)}
            LaunchedEffect(position,player.optBoolean("seeking"),player.optBoolean("preparing"),player.optString("seekError"),requested){
                if(requested?.let{kotlin.math.abs(position-it)<1000}==true&&!player.optBoolean("seeking")&&!player.optBoolean("preparing"))requested=null
                if(player.optString("seekError").let{it.isNotBlank()&&it!="null"})requested=null
            }
            LaunchedEffect(requested){if(requested!=null){delay(125000);requested=null}}
            val shown=preview?:requested?.toFloat()?:position.toFloat()
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(tvRemoteTime(shown.toLong()),style=MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).padding(horizontal=8.dp),contentAlignment=Alignment.Center){RemotePlaybackStatus(player)}
                Text(tvRemoteTime(duration),style=MaterialTheme.typography.bodyMedium)
            }
            PlayerSlider(shown,duration.toFloat(),"TV 播放进度",player.optBoolean("seekable"),{preview=it},finish={preview?.let{requested=it.toLong();send("playback.seekTo",JSONObject().put("positionMs",it.toLong()))};preview=null},
                bufferedValue=player.optLong("bufferedMs").toFloat().coerceIn(0f,duration.toFloat()),mediaSurface=false,description="${tvRemoteTime(shown.toLong())} / ${tvRemoteTime(duration)}")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                FilewayIconButton(onClick={send("queue.previous")},enabled=index>0){FilewayIcon(Art.drawable.fileway_ic_skip_previous,"上一项")}
                val step=snapshot.content.optJSONObject("preferences")?.optInt("seekStepSeconds",10)?.coerceIn(3,30)?:10
                FilewayTextButton(onClick={send("playback.seekBy",JSONObject().put("deltaMs",-step*1000L))},enabled=player.optBoolean("seekable")){Text("−${step}s")}
                FilledIconButton(onClick={send(if(player.optBoolean("playing"))"playback.pause"else"playback.play")},modifier=Modifier.size(64.dp)) {
                    FilewayIcon(if(player.optBoolean("playing"))Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,if(player.optBoolean("playing"))"暂停"else"播放",Modifier.size(32.dp))
                }
                FilewayTextButton(onClick={send("playback.seekBy",JSONObject().put("deltaMs",step*1000L))},enabled=player.optBoolean("seekable")){Text("+${step}s")}
                FilewayIconButton(onClick={send("queue.next")},enabled=index>=0&&index<count-1){FilewayIcon(Art.drawable.fileway_ic_skip_next,"下一项")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                RemoteMediaAction("音轨",Art.drawable.fileway_ic_audio){panel=RemoteMediaPanel.AUDIO}
                if(snapshot.scene=="VIDEO")RemoteMediaAction("字幕",Art.drawable.fileway_ic_subtitles){panel=RemoteMediaPanel.SUBTITLE}
                RemoteMediaAction("${player.optDouble("rate",1.0)}×",Art.drawable.fileway_ic_speed){panel=RemoteMediaPanel.SPEED}
                RemoteMediaAction("设置",Art.drawable.fileway_ic_settings){panel=RemoteMediaPanel.OPTIONS}
            }
        }
        if(count>0)FilewayOutlinedButton(onClick={panel=RemoteMediaPanel.QUEUE},modifier=Modifier.fillMaxWidth()){FilewayIcon(Art.drawable.fileway_ic_playlist_play,null,Modifier.size(22.dp));Spacer(Modifier.width(8.dp));Text("队列 · ${index+1} / $count")}
    }
    panel?.let{selected->RemoteMediaSheet(model,snapshot,selected,{panel=null},{panel=it})}
}

@Composable private fun RemoteMediaAction(label:String,icon:Int,onClick:()->Unit) {
    FilewayTextButton(onClick=onClick){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){FilewayIcon(icon,null,Modifier.size(24.dp));Text(label,style=MaterialTheme.typography.labelMedium)}}
}

@Composable private fun RemotePlaybackStatus(player:JSONObject) {
    val error=player.optString("seekError").takeIf{it.isNotBlank()&&it!="null"}
    val text=error?:when {
        player.optBoolean("preparing")->"正在准备播放…"
        player.optBoolean("waitingForBuffer")->"缓冲 ${player.optInt("buffering").coerceIn(0,100)}%"
        player.optBoolean("seeking")->"正在跳转…"
        else->null
    }
    text?.let{Text(it,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(error!=null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
}

@Composable private fun RemoteMediaSheet(model:TvControlModel,snapshot:RemoteSnapshot,panel:RemoteMediaPanel,onClose:()->Unit,onPanel:(RemoteMediaPanel)->Unit) {
    val player=snapshot.content.optJSONObject("playback")?:JSONObject()
    val queue=snapshot.content.optJSONObject("queue")?:JSONObject()
    val back=when(panel){RemoteMediaPanel.APPEARANCE->RemoteMediaPanel.SUBTITLE;RemoteMediaPanel.FONT->RemoteMediaPanel.APPEARANCE;else->null}
    val kind=when(panel){RemoteMediaPanel.AUDIO->"audio";RemoteMediaPanel.SUBTITLE->"subtitles";RemoteMediaPanel.FONT->"fonts";RemoteMediaPanel.QUEUE->"queue";else->null}
    val choices=kind?.let{rememberMediaChoices(model,snapshot,it,when(it){"audio"->player.optInt("audioCount");"subtitles"->player.optInt("subtitleCount");"queue"->queue.optInt("count");else->0})}
    val sidecars=if(panel==RemoteMediaPanel.SUBTITLE)rememberMediaChoices(model,snapshot,"sidecars",player.optInt("sidecarCount"))else null
    val scroll=remember(panel){LazyListState()}
    LaunchedEffect(panel,choices?.rows) {
        val selected=choices?.rows?.indexOfFirst { row->when(panel){
            RemoteMediaPanel.QUEUE->row.optInt("index")==queue.optInt("index")
            RemoteMediaPanel.AUDIO->row.optInt("id")==player.optInt("selectedAudio")
            RemoteMediaPanel.FONT->row.optString("id")==player.optJSONObject("subtitleAppearance")?.optString("fontId")
            else->false
        }}?:-1
        if(selected>=0){withFrameNanos{};scroll.scrollToItem(selected)}
    }
    fun send(operation:String,payload:JSONObject=JSONObject())=model.commandFor(snapshot,operation,payload)
    FileOperationSheet(if(panel==RemoteMediaPanel.QUEUE)"队列 · ${queue.optInt("count")}"else panel.title,{if(back!=null)onPanel(back)else onClose()},back=back!=null,backDescription="返回${back?.title.orEmpty()}") {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),state=scroll,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            when(panel) {
                RemoteMediaPanel.AUDIO -> mediaChoiceRows(requireNotNull(choices),{it.optInt("id")==player.optInt("selectedAudio")}) {
                    send("audio.select",JSONObject().put("id",it.getInt("id")).put("source",it.optBoolean("source")));onClose()
                }
                RemoteMediaPanel.SUBTITLE -> {
                    item{FilewaySelectionRow("关闭字幕",selected=player.optInt("selectedSubtitle",-1)==-1&&player.optInt("selectedSourceSubtitle",-1)==-1,onClick={send("subtitle.disable");onClose()})}
                    if(player.optBoolean("subtitleLoading"))item{Text("正在准备字幕…",style=MaterialTheme.typography.bodyMedium)}
                    mediaChoiceRows(requireNotNull(choices),{row->row.optInt("id")==player.optInt(if(row.optBoolean("source"))"selectedSourceSubtitle"else"selectedSubtitle",-1)}) {
                        send("subtitle.select",JSONObject().put("id",it.getInt("id")).put("source",it.optBoolean("source")));onClose()
                    }
                    item{Text("同目录外挂",Modifier.padding(top=12.dp,bottom=4.dp),style=MaterialTheme.typography.titleSmall)}
                    mediaChoiceRows(requireNotNull(sidecars),{false},"没有同目录外挂字幕"){send("subtitle.selectNasFile",JSONObject().put("id",it.getString("id")));onClose()}
                    item{FilewayOutlinedButton(onClick={onPanel(RemoteMediaPanel.APPEARANCE)},modifier=Modifier.fillMaxWidth()){Text("外观与同步")}}
                }
                RemoteMediaPanel.FONT -> {
                    val appearance=player.optJSONObject("subtitleAppearance")?:JSONObject()
                    mediaChoiceRows(requireNotNull(choices),{it.optString("id")==appearance.optString("fontId")}){send("subtitle.setAppearance",JSONObject().put("fontId",it.getString("id")));onPanel(RemoteMediaPanel.APPEARANCE)}
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
                RemoteMediaPanel.APPEARANCE -> {
                    item{RemoteSubtitleAppearance(model,snapshot,{onPanel(RemoteMediaPanel.FONT)})}
                }
            }
        }
    }
}

private data class MediaChoices(val rows:List<JSONObject>?,val error:String?,val retry:()->Unit)

@Composable private fun rememberMediaChoices(model:TvControlModel,snapshot:RemoteSnapshot,kind:String,count:Int):MediaChoices {
    var rows by remember(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch,kind,count){mutableStateOf<List<JSONObject>?>(null)}
    var error by remember(kind){mutableStateOf<String?>(null)}
    var retry by remember(kind){mutableIntStateOf(0)}
    LaunchedEffect(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch,kind,count,retry) {
        try {
            error=null
            rows=if(snapshot.capabilities.optInt("mediaPanels")==1)model.mediaList(snapshot,kind)else legacyMediaRows(snapshot,kind)
        }catch(failure:Exception){if(failure is CancellationException&&failure !is TimeoutCancellationException)throw failure;error=failure.message?:"列表暂不可用"}
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

@Composable private fun RemoteSubtitleAppearance(model:TvControlModel,snapshot:RemoteSnapshot,onFonts:()->Unit) {
    val player=snapshot.content.optJSONObject("playback")?:JSONObject()
    val appearance=player.optJSONObject("subtitleAppearance")
    fun send(operation:String,payload:JSONObject)=model.commandFor(snapshot,operation,payload)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("同步 ${player.optLong("subtitleDelayMs")} ms",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilewayOutlinedButton(onClick={send("subtitle.setDelay",JSONObject().put("delayMs",(player.optLong("subtitleDelayMs")-100).coerceAtLeast(-600000)))}){Text("提前 100 ms")};FilewayOutlinedButton(onClick={send("subtitle.setDelay",JSONObject().put("delayMs",(player.optLong("subtitleDelayMs")+100).coerceAtMost(600000)))}){Text("延后 100 ms")}}
        var delay by remember{mutableStateOf((player.optLong("subtitleDelayMs")/1000.0).toString())}
        OutlinedTextField(delay,{delay=it},label={Text("精确延迟（秒）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth(),singleLine=true)
        FilewayOutlinedButton(onClick={parseSubtitleDelayMillis(delay)?.takeIf{it in -600000..600000}?.let{send("subtitle.setDelay",JSONObject().put("delayMs",it))}},enabled=parseSubtitleDelayMillis(delay)?.let{it in -600000..600000}==true){Text("应用时间")}
        if(appearance!=null) {
            Text("普通文字外观",Modifier.padding(top=8.dp),style=MaterialTheme.typography.titleSmall)
            AppearanceSlider("字号",appearance.optDouble("scale",1.0).toFloat(),.5f..2f,{"${(it*100).toInt()}%"}){send("subtitle.setAppearance",JSONObject().put("scale",it))}
            AppearanceSlider("垂直位置",appearance.optDouble("bottomPadding",.08).toFloat(),0f.. .4f,{"距底部 ${(it*100).toInt()}%"}){send("subtitle.setAppearance",JSONObject().put("bottomPadding",it))}
            AppearanceSlider("背景",appearance.optDouble("backgroundOpacity",0.0).toFloat(),0f..1f,{"${(it*100).toInt()}%"}){send("subtitle.setAppearance",JSONObject().put("backgroundOpacity",it))}
            FilewayOutlinedButton(onClick=onFonts,modifier=Modifier.fillMaxWidth()){Text("字体 · ${appearance.optString("fontLabel","选择 TV 字体")}")}
            FilewayTextButton(onClick={send("subtitle.setAppearance",JSONObject().put("reset",true))}){Text("恢复默认外观")}
            Text("ASS / 位图使用片源样式",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AppearanceSlider(title:String,value:Float,range:ClosedFloatingPointRange<Float>,label:(Float)->String,onCommit:(Float)->Unit) {
    var preview by remember{mutableStateOf<Float?>(null)}
    Text("$title · ${label(preview?:value)}",style=MaterialTheme.typography.bodyMedium)
    PlayerSlider(preview?:value,range.endInclusive,title,true,{preview=it},finish={preview?.let(onCommit);preview=null},description=label(preview?:value),mediaSurface=false,minimum=range.start)
}

internal fun tvRemoteTime(ms:Long):String {
    val seconds=ms.coerceAtLeast(0)/1000
    return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)else"%d:%02d".format(seconds/60,seconds%60)
}
