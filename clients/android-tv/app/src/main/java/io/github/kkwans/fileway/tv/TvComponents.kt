package io.github.kkwans.fileway.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import io.github.kkwans.nasfilebrowser.ui.designsystem.filewayTextStyle
import io.github.kkwans.fileway.design.R as Art
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalView
import android.os.SystemClock
import android.view.KeyEvent
import androidx.tv.material3.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPalette

internal val LocalTvInteractive = staticCompositionLocalOf { true }
internal val LocalTvCompact = staticCompositionLocalOf { false }

val LocalTvRemoteGateway=staticCompositionLocalOf<TvRemoteGateway?>{null}

@Immutable internal data class TvSurfaceRoles(
    val chrome: Color, val raised: Color, val secondaryText: Color, val outline: Color,
)
internal val LocalTvSurfaces = staticCompositionLocalOf { TvSurfaceRoles(
    Color(0xFF20262F), Color(0xFF35404D), Color(0xFFBCC7D7), Color(0xFF4E5B6D),
) }
internal val TvShape = RoundedCornerShape(12.dp)

private val TvTypography = Typography(
    displayLarge = filewayTextStyle(36, 44, FontWeight.SemiBold),
    displayMedium = filewayTextStyle(32, 40, FontWeight.SemiBold),
    displaySmall = filewayTextStyle(28, 36, FontWeight.SemiBold),
    headlineLarge = filewayTextStyle(28, 36, FontWeight.SemiBold),
    headlineMedium = filewayTextStyle(26, 34, FontWeight.SemiBold),
    headlineSmall = filewayTextStyle(24, 32, FontWeight.Medium),
    titleLarge = filewayTextStyle(26, 34, FontWeight.SemiBold),
    titleMedium = filewayTextStyle(20, 28, FontWeight.Medium),
    titleSmall = filewayTextStyle(18, 26, FontWeight.Medium),
    bodyLarge = filewayTextStyle(18, 26), bodyMedium = filewayTextStyle(17, 24),
    bodySmall = filewayTextStyle(16, 22),
    labelLarge = filewayTextStyle(18, 24, FontWeight.Medium),
    labelMedium = filewayTextStyle(16, 22, FontWeight.Medium),
    labelSmall = filewayTextStyle(14, 20, FontWeight.Medium),
)

@Composable fun TvTheme(dark: Boolean, media: Boolean = false, content: @Composable () -> Unit) {
    // Raised, quiet neutral layers for projection; blue belongs to actions and
    // focus. Video and image canvases retain their own black surface.
    val colors = if (dark) darkColorScheme(
        primary = FilewayPalette.darkAccent, onPrimary = FilewayPalette.darkCanvas,
        secondary = FilewayPalette.darkAccent, secondaryContainer = Color(0xFF35404D),
        onSecondaryContainer = Color(0xFFF0F4FA),
        background = if (media) FilewayPalette.playerCanvas else Color(0xFF282F39),
        surface = if (media) FilewayPalette.playerSurface else Color(0xFF303946),
        onBackground = Color(0xFFF0F4FA), onSurface = Color(0xFFF0F4FA),
    ) else lightColorScheme(
        primary = FilewayPalette.blue, onPrimary = Color.White,
        secondary = FilewayPalette.blue, secondaryContainer = Color(0xFFE6EDF8),
        onSecondaryContainer = FilewayPalette.lightText,
        background = FilewayPalette.lightCanvas, surface = FilewayPalette.lightSurface,
        onBackground = FilewayPalette.lightText, onSurface = FilewayPalette.lightText,
    )
    val surfaces = if (dark) TvSurfaceRoles(Color(0xFF222933), Color(0xFF3A4655), Color(0xFFBCC7D7), Color(0xFF536175))
        else TvSurfaceRoles(Color(0xFFEDF1F6), Color(0xFFE6EDF6), FilewayPalette.lightSecondary, Color(0xFFB2BFCE))
    MaterialTheme(colorScheme = colors, typography = TvTypography) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, LocalTvSurfaces provides surfaces, content = content)
    }
}

@Composable fun TvButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
    icon: Int? = null, content: (@Composable RowScope.() -> Unit)? = null) {
    val interactive = LocalTvInteractive.current
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Button(onClick = onClick, enabled = enabled && interactive, interactionSource = interaction, modifier = modifier.heightIn(min = 48.dp).tvTouch(onClick, enabled && interactive, interaction).focusProperties { canFocus=interactive }.semantics { this.selected = selected }.onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) colors.primary else Color.Transparent, shape),
        shape = ButtonDefaults.shape(shape), scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = if (selected) colors.primary.copy(alpha = .18f) else colors.surface,
            contentColor = if (selected) colors.primary else colors.onSurface, focusedContainerColor = LocalTvSurfaces.current.raised, focusedContentColor = colors.onSurface),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        if (content != null) content() else {
            if (icon != null) { Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable fun TvField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    password: Boolean = false, options: KeyboardOptions = KeyboardOptions.Default) {
    var focused by remember { mutableStateOf(false) }
    val gateway=LocalTvRemoteGateway.current
    val interactive=LocalTvInteractive.current
    val latest by rememberUpdatedState(onValueChange)
    val receiver=remember { {text:String->latest(text)} }
    DisposableEffect(gateway){onDispose{if(gateway?.input===receiver)gateway.input=null}}
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText)
        BasicTextField(value, onValueChange, enabled=interactive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused;if(it.isFocused)gateway?.input=receiver else if(gateway?.input===receiver)gateway.input=null }.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else LocalTvSurfaces.current.outline, TvShape)
            .padding(12.dp), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = options)
    }
}

@Composable fun TvPanel(title: String, onDismiss: () -> Unit, onBack: () -> Unit = onDismiss, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        TvRemoteDialogKeys(onBack)
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f))) {
            Column(Modifier.align(Alignment.CenterEnd).width((maxWidth * .46f).coerceIn(320.dp, 460.dp)).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface.copy(alpha=.94f), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TvButton("关闭", onDismiss)
                }
                content()
            }
        }
    }
}

/** Dialogs own a separate Android Window; dispatch to that actual focus tree. */
@Composable fun TvRemoteDialogKeys(onBack:()->Unit){
    val gateway=LocalTvRemoteGateway.current;val view=LocalView.current
    val latestBack by rememberUpdatedState(onBack)
    DisposableEffect(gateway,view){
        val previousKey=gateway?.key;val previousBack=gateway?.back
        val receiver:(Int)->Unit={code->val time=SystemClock.uptimeMillis();view.rootView.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0));view.rootView.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_UP,code,0));Unit}
        val goBack:()->Unit={
            if(androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
            else latestBack()
        };gateway?.key=receiver;gateway?.back=goBack
        onDispose{if(gateway?.key===receiver)gateway.key=previousKey;if(gateway?.back===goBack)gateway.back=previousBack}
    }
}

fun tvTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds/3600, seconds/60%60, seconds%60) else "%d:%02d".format(seconds/60, seconds%60)
}
fun tvSize(bytes: Long): String = when { bytes >= 1073741824 -> "%.1f GB".format(bytes / 1073741824.0); bytes >= 1048576 -> "%.1f MB".format(bytes/1048576.0); bytes >= 1024 -> "%.0f KB".format(bytes/1024.0); else -> "$bytes B" }

/** Consume a handled remote press through release, including repeats after focus moves. */
fun Modifier.tvRemoteKeys(onDown: (androidx.compose.ui.input.key.KeyEvent) -> Boolean): Modifier = this.composed {
    val consumed = remember { mutableSetOf<Int>() }
    this.onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent.keyCode
        when (event.type) {
            androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                val handled = onDown(event)
                if (handled) consumed.add(key)
                handled || key in consumed
            }
            androidx.compose.ui.input.key.KeyEventType.KeyUp -> consumed.remove(key)
            else -> false
        }
    }
}

/** TV Material 1.1.0 provides D-pad/semantics actions without pointer clicks.
 * Add pointer handling only, keeping its existing focus and key semantics.
 * A scroll cancels the press; lifting after a scroll never opens a card. */
fun Modifier.tvTouch(onClick: () -> Unit, enabled: Boolean = true, interaction: MutableInteractionSource? = null): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val focus = remember { FocusRequester() }
    this.focusRequester(focus).pointerInput(enabled, interaction) {
        if (enabled) detectTapGestures(
            onPress = { point ->
                focus.requestFocus()
                val press = PressInteraction.Press(point)
                interaction?.emit(press)
                val released=tryAwaitRelease()
                if (released) interaction?.emit(PressInteraction.Release(press))
                else interaction?.emit(PressInteraction.Cancel(press))
            },
            onTap = { click() },
        )
    }
}

@Composable fun delayedBusy(busy: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(200); visible = true } else visible = false }
    return visible
}


internal fun tvDate(value:String):String=runCatching {
    java.time.OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value)


@Composable internal fun TvToolbarAction(label:String,icon:Int,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    val interactive=LocalTvInteractive.current
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Button(onClick=onClick, enabled=enabled && interactive, interactionSource=interaction,
        modifier=modifier.size(48.dp).tvTouch(onClick,enabled && interactive,interaction).focusProperties { canFocus=interactive }.semantics { contentDescription=label }
            .onFocusChanged { focused=it.isFocused }.border(2.dp,if(focused)colors.primary else Color.Transparent,TvShape),
        shape=ButtonDefaults.shape(TvShape), scale=ButtonDefaults.scale(focusedScale=1f), contentPadding=PaddingValues(12.dp),
        colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=LocalTvSurfaces.current.secondaryText,
            focusedContainerColor=LocalTvSurfaces.current.raised,focusedContentColor=colors.onSurface)) {
        Icon(painterResource(icon),null,Modifier.size(24.dp))
    }
}

/** A current value and keyboard focus are different states. The fixed check
 * slot keeps labels aligned when a different row receives focus. */
@Composable internal fun TvOptionRow(
    title:String, onClick:()->Unit, modifier:Modifier=Modifier, supporting:String?=null,
    selected:Boolean=false, enabled:Boolean=true, icon:Int?=null, value:String?=null, destination:Boolean=false,
    showSelection:Boolean=true, leading:(@Composable ()->Unit)?=null,
) {
    val interactive=LocalTvInteractive.current
    val colors=MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val interaction=remember { MutableInteractionSource() }
    Button(onClick=onClick,enabled=enabled && interactive,interactionSource=interaction,
        modifier=modifier.fillMaxWidth().heightIn(min=52.dp).tvTouch(onClick,enabled && interactive,interaction).focusProperties { canFocus=interactive }
            .semantics { this.selected=selected }.onFocusChanged { focused=it.isFocused }
            .border(2.dp,if(focused)colors.primary else Color.Transparent,TvShape),
        shape=ButtonDefaults.shape(TvShape),scale=ButtonDefaults.scale(focusedScale=1f),
        contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp),
        colors=ButtonDefaults.colors(containerColor=if(selected)colors.primary.copy(alpha=.12f)else Color.Transparent,
            contentColor=colors.onSurface,focusedContainerColor=LocalTvSurfaces.current.raised,focusedContentColor=colors.onSurface)) {
        if(leading!=null) { leading();Spacer(Modifier.width(14.dp)) }
        else if(icon!=null) { Icon(painterResource(icon),null,Modifier.size(24.dp),tint=if(selected)colors.primary else LocalTvSurfaces.current.secondaryText);Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            supporting?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText,maxLines=2,overflow=TextOverflow.Ellipsis) }
        }
        value?.let { Spacer(Modifier.width(12.dp));Text(it,style=MaterialTheme.typography.labelMedium,color=LocalTvSurfaces.current.secondaryText) }
        if(destination) { Spacer(Modifier.width(12.dp));Icon(painterResource(Art.drawable.fileway_ic_arrow_forward),null,Modifier.size(22.dp),tint=LocalTvSurfaces.current.secondaryText) }
        else if(showSelection) { Spacer(Modifier.width(12.dp));Box(Modifier.size(24.dp),contentAlignment=Alignment.Center) {
            if(selected)Icon(painterResource(Art.drawable.fileway_ic_check),null,Modifier.size(22.dp),tint=colors.primary)
        } }
    }
}

@Composable internal fun TvQuietAction(label:String,onClick:()->Unit) {
    val interactive=LocalTvInteractive.current
    var focused by remember { mutableStateOf(false) }
    val interaction=remember { MutableInteractionSource() }
    val colors=MaterialTheme.colorScheme
    Button(onClick=onClick,enabled=interactive,interactionSource=interaction,
        modifier=Modifier.heightIn(min=48.dp).tvTouch(onClick,enabled=interactive,interaction=interaction).focusProperties { canFocus=interactive }
            .onFocusChanged { focused=it.isFocused }.border(2.dp,if(focused)colors.primary else Color.Transparent,TvShape),
        shape=ButtonDefaults.shape(TvShape),scale=ButtonDefaults.scale(focusedScale=1f),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
        colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=LocalTvSurfaces.current.secondaryText,
            focusedContainerColor=LocalTvSurfaces.current.raised,focusedContentColor=colors.onSurface)) {
        Text(label,style=MaterialTheme.typography.labelMedium)
    }
}
