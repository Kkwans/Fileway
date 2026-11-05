package io.github.kkwans.fileway.tv

// Selection-list focus structure adapted from BV, MIT, Copyright (c) 2022 aaa1115910.
// Source: aaa1115910/bv@0e454c06, player/tv/.../component/RadioMenuList.kt.
// Fileway supplies its own row layout, identity keys, touch input and media callbacks.

import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPalette
import io.github.kkwans.nasfilebrowser.ui.designsystem.drawMediaProgressTrack
import kotlinx.coroutines.delay

internal enum class TvPlayerPage(val title:String) {
    SETTINGS("播放设置"), AUDIO("音轨"), SUBTITLE("字幕"), APPEARANCE("字幕外观"), SYNC("字幕同步"),
    FONT("字幕字体"), EXTERNAL("选择外挂字幕"), SPEED("播放速度"), HDR("动态范围"), RATIO("画面比例"),
    VOLUME("应用音量"), QUEUE("播放列表"), INFO("播放信息"),
}
internal sealed interface TvPlayerRow {
    val key:String
    data class Heading(override val key:String,val title:String):TvPlayerRow
    data class Choice(override val key:String,val title:String,val selected:Boolean=false,val enabled:Boolean=true,
        val supporting:String?=null,val value:String?=null,val icon:Int?=null,val destination:Boolean=false,
        val selectable:Boolean=true,val action:()->Unit):TvPlayerRow
}

/** One stable dialog window for all player pages. Its material is static alpha. */
@Composable internal fun TvPlayerPanel(title:String,labels:List<String>,child:Boolean,onBack:()->Unit,onClose:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,dismissOnBackPress=false)) {
        TvTheme(dark=true,media=true) {
            BackHandler(onBack=onBack)
            TvRemoteDialogKeys(onBack)
            val view=LocalView.current
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.let { window->
                    window.setDimAmount(0f)
                    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    WindowCompat.getInsetsController(window,view).hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            val measure=rememberTextMeasurer();val density=LocalDensity.current
            val style=MaterialTheme.typography.bodyLarge
            val sample=remember(labels){labels.take(48)+listOfNotNull(labels.maxByOrNull{it.length})}
            val bodyWidth=remember(sample,style,density) {
                with(density){sample.maxOfOrNull{measure.measure(it,style,maxLines=1).size.width}?.toDp()?:200.dp}+112.dp
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val available=maxWidth-32.dp
                val width=maxOf(bodyWidth,with(density){measure.measure(title,MaterialTheme.typography.titleMedium,maxLines=1).size.width.toDp()}+128.dp)
                    .coerceIn(minOf(304.dp,available),minOf(400.dp,available))
                Column(Modifier.align(Alignment.CenterEnd).padding(16.dp).width(width).fillMaxHeight()
                    .background(FilewayPalette.playerSurface.copy(alpha=.94f),RoundedCornerShape(12.dp))
                    .padding(horizontal=12.dp,vertical=8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(child)TvPlaybackAction("返回上一页",Art.drawable.fileway_ic_arrow_back,onBack,compact=true)
                        Text(title,Modifier.weight(1f).padding(horizontal=8.dp),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        TvPlaybackAction("关闭面板",Art.drawable.fileway_ic_close,onClose,compact=true)
                    }
                    key(title){Column(Modifier.fillMaxWidth().weight(1f),content=content)}
                }
            }
        }
    }
}

@Composable internal fun TvPlayerChoiceList(rows:List<TvPlayerRow>,onBack:()->Unit,modifier:Modifier=Modifier,initialKey:String?=null,onFocused:(String)->Unit={}) {
    val scroll=rememberLazyListState()
    val keys=rows.map{it.key}
    val first=remember(keys){rows.filterIsInstance<TvPlayerRow.Choice>().let{choices->choices.firstOrNull{it.enabled&&it.key==initialKey}?:choices.firstOrNull{it.enabled&&it.selected}?:choices.firstOrNull{it.enabled}}?.key}
    val initialFocus=remember{FocusRequester()}
    LaunchedEffect(keys) {
        val index=rows.indexOfFirst{it.key==first}
        if(index>=0){withFrameNanos{};if(scroll.layoutInfo.visibleItemsInfo.none{it.index==index})scroll.scrollToItem(index);withFrameNanos{};initialFocus.requestFocus()}
    }
    LazyColumn(modifier.fillMaxWidth().focusRestorer(initialFocus).focusGroup().tvRemoteKeys {
        if(it.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){onBack();true}else false
    },state=scroll,contentPadding=PaddingValues(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        items(rows,key={it.key}) { row->when(row) {
            is TvPlayerRow.Heading->Text(row.title,Modifier.padding(start=12.dp,top=12.dp,bottom=4.dp),style=MaterialTheme.typography.labelMedium,color=Color.White.copy(alpha=.65f))
            is TvPlayerRow.Choice->TvPlayerChoiceRow(row,(if(row.key==first)Modifier.focusRequester(initialFocus)else Modifier).onFocusChanged{if(it.isFocused)onFocused(row.key)})
        }}
    }
}

@Composable internal fun TvPlayerChoiceRow(row:TvPlayerRow.Choice,modifier:Modifier) {
    val interaction=remember{MutableInteractionSource()}
    val accent=FilewayPalette.darkAccent
    val outline=Border(BorderStroke(2.dp,accent),shape=TvShape)
    ListItem(selected=row.selected,onClick=row.action,enabled=row.enabled,interactionSource=interaction,
        modifier=modifier.fillMaxWidth().heightIn(min=52.dp).tvTouch(row.action,row.enabled,interaction),
        shape=ListItemDefaults.shape(TvShape),scale=ListItemDefaults.scale(focusedScale=1f),
        border=ListItemDefaults.border(focusedBorder=outline,focusedSelectedBorder=outline),
        colors=ListItemDefaults.colors(containerColor=Color.Transparent,contentColor=Color.White,
            selectedContainerColor=accent.copy(alpha=.12f),selectedContentColor=Color.White,
            focusedContainerColor=accent.copy(alpha=.22f),focusedContentColor=Color.White,
            focusedSelectedContainerColor=accent.copy(alpha=.28f),focusedSelectedContentColor=Color.White),
        headlineContent={Text(row.title,style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)},
        supportingContent=row.supporting?.takeIf{it.isNotBlank()}?.let{text->{Text(text,style=MaterialTheme.typography.labelSmall,color=LocalContentColor.current.copy(alpha=.7f),maxLines=2,overflow=TextOverflow.Ellipsis)}},
        leadingContent={Box(Modifier.size(24.dp),contentAlignment=Alignment.Center){
            when {row.icon!=null->Icon(painterResource(row.icon),null,Modifier.size(24.dp))
                row.selected&&row.selectable->Icon(painterResource(Art.drawable.fileway_ic_check),null,Modifier.size(22.dp))}
        }},trailingContent={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            row.value?.let{Text(it,Modifier.widthIn(max=112.dp),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)}
            if(row.destination)Icon(painterResource(Art.drawable.fileway_ic_arrow_forward),null,Modifier.size(20.dp))
        }})
}

/** The phone track with TV key handling and one commit per touch drag. */
@Composable internal fun TvPlayerRange(label:String,value:Float,range:ClosedFloatingPointRange<Float>,step:Float,format:(Float)->String,modifier:Modifier=Modifier,onCommit:(Float)->Unit) {
    var preview by remember{mutableStateOf<Float?>(null)}
    var focused by remember{mutableStateOf(false)}
    var dragging by remember{mutableStateOf(false)}
    val commit by rememberUpdatedState(onCommit)
    val shown=preview?:value
    LaunchedEffect(value){if(!dragging&&preview?.let{kotlin.math.abs(it-value)<step/2}==true)preview=null}
    LaunchedEffect(preview){if(preview!=null){delay(5000);if(!dragging)preview=null}}
    val set:(Float)->Unit={next->preview=next.coerceIn(range);commit(requireNotNull(preview))}
    val span=range.endInclusive-range.start
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()){Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge);Text(format(shown),style=MaterialTheme.typography.labelMedium,color=FilewayPalette.darkAccent)}
        Canvas(modifier.fillMaxWidth().height(48.dp).onFocusChanged{focused=it.isFocused}
            .pointerInput(range){detectTapGestures{point->set(range.start+span*(point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f))}}
            .pointerInput(range){detectHorizontalDragGestures(onDragStart={dragging=true},onHorizontalDrag={change,_->change.consume();preview=range.start+span*(change.position.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)},
                onDragEnd={dragging=false;preview?.let(commit)},onDragCancel={dragging=false;preview=null})}
            .tvRemoteKeys{event->when(event.nativeKeyEvent.keyCode){KeyEvent.KEYCODE_DPAD_LEFT->{set(shown-step);true};KeyEvent.KEYCODE_DPAD_RIGHT->{set(shown+step);true};else->false}}
            .semantics{contentDescription=label;stateDescription=format(shown);progressBarRangeInfo=ProgressBarRangeInfo(shown,range);setProgress{target->if(target.isFinite()){set(target);true}else false}}
            .focusable()) {
            drawMediaProgressTrack((shown-range.start)/span,Color.White.copy(alpha=.22f),FilewayPalette.darkAccent,
                thickness=if(focused)4.dp else 3.dp,thumbRadius=if(focused||dragging)6.dp else 4.dp)
        }
    }
}
