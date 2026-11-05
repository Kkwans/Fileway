package io.github.kkwans.fileway.tv

import android.view.KeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.launch

/** Phone player panel hierarchy, adapted to TV focus and a single static-alpha window. */
@Composable internal fun ColumnScope.TvPlayerMenuContent(
    model:TvModel,page:TvPlayerPage,state:TvMediaState,player:PlayerState,cache:TvCacheState,resize:Int,
    onResize:(Int)->Unit,onNavigate:(TvPlayerPage)->Unit,onBack:()->Unit,onClose:()->Unit,onBrowserBack:((()->Unit)?)->Unit,initialKey:String?,onFocused:(String)->Unit,
) {
    val appearance by model.media.playbackPreferences.textSubtitleAppearance.collectAsStateWithLifecycle()
    val fonts by SubtitleFontCatalog.state.collectAsStateWithLifecycle()
    val audio=state.file?.mediaKind()==MediaKind.AUDIO
    val closeLatest by rememberUpdatedState(onClose)
    fun choice(key:String,title:String,selected:Boolean=false,supporting:String?=null,icon:Int?=null,value:String?=null,destination:Boolean=false,enabled:Boolean=true,selectable:Boolean=true,action:()->Unit)=
        TvPlayerRow.Choice(key,title,selected,enabled,supporting,value,icon,destination,selectable,action)
    fun destination(target:TvPlayerPage,value:String?=null,icon:Int?=null)=choice(target.name,target.title,value=value,icon=icon,destination=true,selectable=false){onNavigate(target)}
    val rows:List<TvPlayerRow> = when(page) {
        TvPlayerPage.SETTINGS->buildList {
            add(destination(TvPlayerPage.SPEED,"${player.rate}×",Art.drawable.fileway_ic_speed))
            if(!audio){add(destination(TvPlayerPage.RATIO,listOf("适合屏幕","拉伸","裁切")[resize],Art.drawable.fileway_ic_fit_screen));add(destination(TvPlayerPage.HDR,state.mode.title))}
            add(destination(TvPlayerPage.VOLUME,"${player.volume}%",Art.drawable.fileway_ic_volume_up))
            add(destination(TvPlayerPage.INFO,icon=Art.drawable.fileway_ic_info))
        }
        TvPlayerPage.AUDIO->buildList {
            if(state.compatible) {
                add(choice("audio/off","关闭音轨",state.sourceAudioOff){model.media.selectSourceAudio(-1);onClose()})
                state.sourceAudio.forEach{track->add(choice("source/${track.index}",track.title,!state.sourceAudioOff&&state.selectedSourceAudio==track.index,
                    listOf(track.language,track.codec).filter{it.isNotBlank()}.joinToString(" · ")){model.media.selectSourceAudio(track.index);onClose()})}
            }else player.audio.forEach{track->add(choice("native/${track.id}",track.title,player.selectedAudio==track.id,
                listOf(track.language,track.codec).filter{it.isNotBlank()}.joinToString(" · "),enabled=!state.busy){model.media.selectAudio(track.id);onClose()})}
        }
        TvPlayerPage.SUBTITLE->buildList {
            add(destination(TvPlayerPage.APPEARANCE,icon=Art.drawable.fileway_ic_settings))
            add(destination(TvPlayerPage.SYNC,"${player.subtitleDelayMs} ms"))
            add(TvPlayerRow.Heading("tracks","字幕轨道"))
            add(choice("off","关闭字幕",player.selectedSubtitle==-1&&state.selectedSourceSubtitle==-1){model.media.subtitlesOff();onClose()})
            val source=state.sourceText.takeIf{state.sourceSubtitles}.orEmpty()
            source.forEach{track->add(choice("source/${track.index}",track.title,state.selectedSourceSubtitle==track.index,
                listOf(track.language,track.codec).filter{it.isNotBlank()}.joinToString(" · ")){model.media.selectSourceSubtitle(track.index);onClose()})}
            player.subtitles.filter{it.id>=0&&(source.isEmpty()||model.media.player.importedSubtitleIdentity(it.id)!=null)}.forEach{track->
                add(choice("native/${track.id}",track.title,player.selectedSubtitle==track.id&&state.selectedSourceSubtitle==-1,
                    listOf(track.language,track.codec).filter{it.isNotBlank()}.joinToString(" · ")){model.media.selectPlayerSubtitle(track.id);onClose()})
            }
            if(state.sidecars.isNotEmpty()) {
                add(TvPlayerRow.Heading("sidecars","同目录外挂"))
                state.sidecars.forEach{file->add(choice("external/${file.mediaKey}",file.name,selectable=false){model.media.selectExternalSubtitle(file);onClose()})}
            }
            add(destination(TvPlayerPage.EXTERNAL,icon=Art.drawable.fileway_ic_folder))
        }
        TvPlayerPage.APPEARANCE->listOf(destination(TvPlayerPage.FONT,fonts.resolve(appearance.fontKey).label),
            choice("reset","恢复默认外观",selectable=false){model.media.updateSubtitleAppearance(TextSubtitleAppearance())})
        TvPlayerPage.FONT->fonts.options.map{font->choice(font.key,font.label,appearance.fontKey==font.key){model.media.updateSubtitleAppearance(appearance.copy(fontKey=font.key));onBack()}}
        TvPlayerPage.SPEED->listOf(.5f,.75f,1f,1.25f,1.5f,2f).map{rate->choice("rate/$rate","${rate}×",kotlin.math.abs(player.rate-rate)<.001f){model.media.player.rate(rate);onClose()}}
        TvPlayerPage.HDR->TvHdrMode.entries.map{mode->choice(mode.name,mode.title,state.mode==mode,enabled=!state.busy&&(mode!=TvHdrMode.SDR||state.sdrAvailable)){model.media.switchMode(mode)}}
        TvPlayerPage.RATIO->listOf("适合屏幕","拉伸","裁切").mapIndexed{index,title->choice(index.toString(),title,resize==index){onResize(index);onClose()}}
        TvPlayerPage.QUEUE->remember(state.queue){state.queue?.let{queue->queue.items.mapIndexed{index,file->choice("queue/$index/${file.mediaKey}",file.name,index==queue.index,value=(index+1).toString()){model.media.navigateQueue(index-queue.index);closeLatest()}}}.orEmpty()}
        else->emptyList()
    }
    when(page) {
        TvPlayerPage.APPEARANCE->{
            val target=remember{FocusRequester()};val initial=remember{initialKey?:"scale"}
            fun anchor(key:String)= (if(initial==key)Modifier.focusRequester(target)else Modifier).onFocusChanged{if(it.isFocused)onFocused(key)}
            TvPlayerScroll(target) {
            TvPlayerRange("字号",appearance.scale,.5f..2f,.1f,{"${(it*100).toInt()}%"},anchor("scale")){model.media.updateSubtitleAppearance(appearance.copy(scale=it))}
            TvPlayerRange("垂直位置",appearance.bottomPadding,0f.. .4f,.02f,{"距底部 ${(it*100).toInt()}%"},anchor("position")){model.media.updateSubtitleAppearance(appearance.copy(bottomPadding=it))}
            TvPlayerRange("文字背景",appearance.backgroundOpacity,0f..1f,.05f,{"${(it*100).toInt()}%"},anchor("background")){model.media.updateSubtitleAppearance(appearance.copy(backgroundOpacity=it))}
            TvPlayerChoiceButton("位置参考",if(appearance.area==TextSubtitleArea.PICTURE)"画面内"else"整个画布",anchor("area")) {
                model.media.updateSubtitleAppearance(appearance.copy(area=if(appearance.area==TextSubtitleArea.PICTURE)TextSubtitleArea.STAGE else TextSubtitleArea.PICTURE))
            }
            TvPlayerChoiceButton("字体",fonts.resolve(appearance.fontKey).label,anchor("font")){onNavigate(TvPlayerPage.FONT)}
            TvPlayerChoiceButton("恢复默认外观",null,anchor("reset")){model.media.updateSubtitleAppearance(TextSubtitleAppearance())}
            Text("ASS / 位图保留片源样式",style=MaterialTheme.typography.labelSmall,color=Color.White.copy(alpha=.65f))
            }
        }
        TvPlayerPage.SYNC->TvPlayerScroll {
            Text("${player.subtitleDelayMs} ms",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TvButton("提前 100 ms",{model.media.player.subtitleDelay((player.subtitleDelayMs-100).coerceAtLeast(-600000))},Modifier.weight(1f))
                TvButton("延后 100 ms",{model.media.player.subtitleDelay((player.subtitleDelayMs+100).coerceAtMost(600000))},Modifier.weight(1f))
            }
            var value by remember{mutableStateOf((player.subtitleDelayMs/1000.0).toString())}
            TvField("精确延迟（秒）",value,{value=it.take(16)},options=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            val parsed=parseSubtitleDelayMillis(value)?.takeIf{it in -600000..600000}
            TvButton("应用",{parsed?.let(model.media.player::subtitleDelay)},Modifier.fillMaxWidth(),enabled=parsed!=null)
            TvPlayerChoiceButton("恢复同步",null){model.media.player.subtitleDelay(0)}
        }
        TvPlayerPage.VOLUME->TvPlayerScroll {
            TvPlayerRange("应用音量",player.volume.toFloat(),0f..100f,1f,{"${it.toInt()}%"}){model.media.player.volume(it.toInt())}
            Text("当前媒体的输出音量",style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.65f))
        }
        TvPlayerPage.INFO->TvPlayerInfo(model,state,player,cache)
        TvPlayerPage.EXTERNAL->TvSubtitleFiles(model,onBack,onClose,onBrowserBack)
        else-> {
            if(page==TvPlayerPage.SUBTITLE) {
                if(state.subtitleLoading||player.subtitleLoading)Text("正在加载字幕…",Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
                state.subtitleError?.let{Text(it,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            }
            if(page==TvPlayerPage.FONT)LaunchedEffect(Unit){SubtitleFontCatalog.request()}
            if(page==TvPlayerPage.SPEED) {
                // Presets remain a lazy focus list; precise input is its own page content below.
                var value by remember{mutableStateOf(player.rate.toString())}
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.fillMaxWidth().weight(1f)){TvPlayerChoiceList(rows,onBack,initialKey=initialKey,onFocused=onFocused)}
                    TvField("自定义速度（0.1–5）",value,{value=it.take(12)},options=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                    TvButton("应用",{parsePlaybackRate(value)?.let{model.media.player.rate(it);onClose()}},Modifier.fillMaxWidth(),enabled=parsePlaybackRate(value)!=null)
                }
            }else {
                val populated=rows.ifEmpty{listOf(choice("empty","暂无可用选项",supporting="返回播放",selectable=false,action=onBack))}
                TvPlayerChoiceList(populated,onBack,Modifier.weight(1f),initialKey,onFocused)
            }
        }
    }
}

@Composable private fun TvPlayerChoiceButton(title:String,value:String?,modifier:Modifier=Modifier,onClick:()->Unit) {
    TvPlayerChoiceRow(TvPlayerRow.Choice(title,title,value=value,destination=true,selectable=false,action=onClick),modifier)
}

@Composable private fun TvPlayerScroll(initial:FocusRequester?=null,content:@Composable ColumnScope.()->Unit) {
    val scroll=rememberScrollState();val focus=remember{FocusRequester()}
    LaunchedEffect(Unit){withFrameNanos{};(initial?:focus).requestFocus()}
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).focusRequester(focus).focusGroup().padding(horizontal=8.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}

@Composable private fun TvPlayerInfo(model:TvModel,state:TvMediaState,player:PlayerState,cache:TvCacheState) {
    val scroll=rememberScrollState();val scope=rememberCoroutineScope();val focus=remember{FocusRequester()}
    LaunchedEffect(Unit){withFrameNanos{};focus.requestFocus()}
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).focusRequester(focus).tvRemoteKeys{event->
        val delta=when(event.nativeKeyEvent.keyCode){KeyEvent.KEYCODE_DPAD_DOWN->80;KeyEvent.KEYCODE_DPAD_UP->-80;else->0}
        if(delta!=0&&(if(delta>0)scroll.canScrollForward else scroll.canScrollBackward)){scope.launch{scroll.scrollTo((scroll.value+delta).coerceIn(0,scroll.maxValue))};true}else false
    }.focusable().padding(12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TvPlayerInfoField("名称",state.file?.name.orEmpty());TvPlayerInfoField("路径",state.file?.path.orEmpty())
        TvPlayerInfoField("媒体缓存","${tvSize(cache.usedBytes)} / ${tvSize(cache.limitBytes)}${cache.error?.let{"\n$it"}.orEmpty()}")
        if(state.file?.mediaKind()==MediaKind.VIDEO) {
            TvPlayerInfoField("片源动态范围",state.info?.optString("dynamicRange","未知").orEmpty())
            TvPlayerInfoField("请求模式 / 实际路径","${state.mode.title} / ${if(state.compatible)"SDR 兼容"else"原文件"}")
            TvPlayerInfoField("解码输出",tvOutputSignal(player));TvPlayerInfoField("视频解码器","${player.videoDecoder}\n${player.videoDecoderKind}")
            TvPlayerInfoField("画面尺寸","${player.width} × ${player.height}")
        }
        TvPlayerInfoField("音频解码器",player.audioDecoder)
        TvPlayerInfoField("音频输出缓冲","${player.audioRenderedBuffers} / 跳过 ${player.audioSkippedBuffers}")
    }
}

@Composable private fun TvPlayerInfoField(title:String,value:String) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.labelMedium,color=Color.White.copy(alpha=.6f))
        Text(value,style=MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun ColumnScope.TvSubtitleFiles(model:TvModel,onBack:()->Unit,onClose:()->Unit,onBrowserBack:((()->Unit)?)->Unit) {
    val owner=model.context;val state by model.media.state.collectAsStateWithLifecycle();val source=state.file
    var path by remember{mutableStateOf(source?.path?.substringBeforeLast('/')?.ifBlank{"/"}?:"/")}
    var wire by remember{mutableStateOf(source?.wirePath?.substringBeforeLast('/')?.ifBlank{"/"}?:"/")}
    var files by remember{mutableStateOf<List<ResourceRef>>(emptyList())}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};var refresh by remember{mutableIntStateOf(0)}
    LaunchedEffect(owner?.owner,wire,refresh) {
        if(owner==null)return@LaunchedEffect
        busy=true;error=null
        try{files=TvModel.directoryFiles(owner.api.request("GET","/api/resources$wire")).filter{it.directory||isExternalSubtitle(it.name)};busy=false}
        catch(failure:Exception){if(failure is kotlinx.coroutines.CancellationException)throw failure;error=failure.message?:"目录读取失败";busy=false}
    }
    DisposableEffect(wire) {
        onBrowserBack(if(wire!="/")({path=path.substringBeforeLast('/').ifBlank{"/"};wire=wire.substringBeforeLast('/').ifBlank{"/"}})else null)
        onDispose{onBrowserBack(null)}
    }
    Text(path,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
    if(busy)Text("正在读取…",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
    error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
    val rows=buildList<TvPlayerRow> {
        if(wire!="/")add(TvPlayerRow.Choice("parent","上一级",icon=Art.drawable.fileway_ic_arrow_back,selectable=false,action={path=path.substringBeforeLast('/').ifBlank{"/"};wire=wire.substringBeforeLast('/').ifBlank{"/"}}))
        if(error!=null)add(TvPlayerRow.Choice("retry","重试",selectable=false,action={refresh++}))
        files.forEach{file->add(TvPlayerRow.Choice(file.mediaKey,file.name,enabled=!busy,icon=if(file.directory)Art.drawable.fileway_ic_folder else Art.drawable.fileway_ic_subtitles,selectable=false,action={
            if(file.directory){path=file.path;wire=file.wirePath}else{model.media.selectExternalSubtitle(file);onClose()}
        }))}
        if(isEmpty())add(TvPlayerRow.Choice("empty","此目录没有字幕或子文件夹",supporting="返回字幕",selectable=false,action=onBack))
    }
    TvPlayerChoiceList(rows,{if(wire!="/"){path=path.substringBeforeLast('/').ifBlank{"/"};wire=wire.substringBeforeLast('/').ifBlank{"/"}}else onBack()},Modifier.weight(1f))
}
