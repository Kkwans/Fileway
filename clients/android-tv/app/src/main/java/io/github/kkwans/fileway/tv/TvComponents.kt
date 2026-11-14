package io.github.kkwans.fileway.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.draw.rotate
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
import io.github.kkwans.fileway.design.R as Art
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import androidx.tv.material3.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPalette

internal val LocalTvInteractive = staticCompositionLocalOf { true }
internal val LocalTvCompact = staticCompositionLocalOf { false }
private val LocalTvMediaTheme = staticCompositionLocalOf { false }
internal val LocalTvConfirmKeyGuard = staticCompositionLocalOf<TvConfirmKeyGuard?> { null }

internal fun TvConfirmKeyGuard.intercept(event: KeyEvent): Boolean = consume(
    TvConfirmPress(event.keyCode, event.downTime, event.deviceId),
    release = event.action == KeyEvent.ACTION_UP,
    repeat = event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0,
)

val LocalTvRemoteGateway=staticCompositionLocalOf<TvRemoteGateway?>{null}

@Immutable internal data class TvSurfaceRoles(
    val chrome: Color, val raised: Color, val secondaryText: Color, val outline: Color,
)
internal val LocalTvSurfaces = staticCompositionLocalOf { TvSurfaceRoles(
    Color(0xFF28282A), Color(0xFF48484B), Color(0xFFD0D0D2), Color(0xFF66666A),
) }
internal val TvShape = RoundedCornerShape(12.dp)
internal val TvOptionIconSize = 24.dp

private val TvTypography = Typography(
    displayLarge = tvTextStyle(36, 44, FontWeight.SemiBold),
    displayMedium = tvTextStyle(32, 40, FontWeight.SemiBold),
    displaySmall = tvTextStyle(28, 36, FontWeight.SemiBold),
    headlineLarge = tvTextStyle(28, 36, FontWeight.SemiBold),
    headlineMedium = tvTextStyle(26, 34, FontWeight.SemiBold),
    headlineSmall = tvTextStyle(24, 32, FontWeight.Medium),
    titleLarge = tvTextStyle(26, 34, FontWeight.SemiBold),
    titleMedium = tvTextStyle(20, 28, FontWeight.Medium),
    titleSmall = tvTextStyle(18, 26, FontWeight.Medium),
    bodyLarge = tvTextStyle(18, 26, FontWeight.Medium), bodyMedium = tvTextStyle(17, 24),
    bodySmall = tvTextStyle(16, 22),
    labelLarge = tvTextStyle(18, 24, FontWeight.Medium),
    labelMedium = tvTextStyle(16, 22, FontWeight.Medium),
    labelSmall = tvTextStyle(14, 20, FontWeight.Medium),
)

// Only the player migrates in this slice; ordinary pages retain their existing metrics.
private val TvMediaTypography = Typography(
    displayLarge = tvTextStyle(36, 44, FontWeight.Medium),
    displayMedium = tvTextStyle(32, 40, FontWeight.Medium),
    displaySmall = tvTextStyle(28, 36, FontWeight.Medium),
    headlineLarge = tvTextStyle(28, 36, FontWeight.Medium),
    headlineMedium = tvTextStyle(24, 32, FontWeight.Medium),
    headlineSmall = tvTextStyle(24, 32, FontWeight.Medium),
    titleLarge = tvTextStyle(24, 32, FontWeight.Medium),
    titleMedium = tvTextStyle(20, 28, FontWeight.Medium),
    titleSmall = tvTextStyle(18, 28, FontWeight.Medium),
    bodyLarge = tvTextStyle(18, 28), bodyMedium = tvTextStyle(18, 28), bodySmall = tvTextStyle(16, 24),
    labelLarge = tvTextStyle(18, 24, FontWeight.Medium), labelMedium = tvTextStyle(16, 24, FontWeight.Medium),
    labelSmall = tvTextStyle(16, 22, FontWeight.Medium),
)

@Composable fun TvTheme(dark: Boolean, media: Boolean = false, content: @Composable () -> Unit) {
    // Raised, quiet neutral layers for projection; blue belongs to actions and
    // focus. Video and image canvases retain their own black surface.
    val colors = if (dark) darkColorScheme(
        primary = FilewayPalette.darkAccent, onPrimary = FilewayPalette.darkCanvas,
        secondary = FilewayPalette.darkAccent, secondaryContainer = Color(0xFF424245),
        onSecondaryContainer = Color(0xFFF0F4FA),
        background = if (media) Color(0xFF111214) else Color(0xFF303032),
        surface = if (media) TvPlayerDesign.surface else Color(0xFF38383B),
        onBackground = if (media) TvPlayerDesign.text else Color(0xFFF0F4FA),
        onSurface = if (media) TvPlayerDesign.text else Color(0xFFF0F4FA),
    ) else lightColorScheme(
        primary = FilewayPalette.blue, onPrimary = Color.White,
        secondary = FilewayPalette.blue, secondaryContainer = Color(0xFFE6EDF8),
        onSecondaryContainer = FilewayPalette.lightText,
        background = FilewayPalette.lightCanvas, surface = FilewayPalette.lightSurface,
        onBackground = FilewayPalette.lightText, onSurface = FilewayPalette.lightText,
    )
    val surfaces = if (media) TvSurfaceRoles(TvPlayerDesign.surface, TvPlayerDesign.raised, TvPlayerDesign.secondary, TvPlayerDesign.outline)
        else if (dark) TvSurfaceRoles(Color(0xFF28282A), Color(0xFF48484B), Color(0xFFD0D0D2), Color(0xFF66666A))
        else TvSurfaceRoles(Color(0xFFEDF1F6), Color(0xFFE6EDF6), FilewayPalette.lightSecondary, Color(0xFFB2BFCE))
    MaterialTheme(colorScheme = colors, typography = if (media) TvMediaTypography else TvTypography) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, LocalTvSurfaces provides surfaces,
            LocalTvMediaTheme provides media, content = content)
    }
}

@Composable fun TvButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
    icon: Int? = null, content: (@Composable RowScope.() -> Unit)? = null) {
    val interactive = LocalTvInteractive.current
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape
    val colors = MaterialTheme.colorScheme
    val media = LocalTvMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    Button(onClick = onClick, enabled = enabled && interactive, interactionSource = interaction, modifier = modifier.heightIn(min = 48.dp).tvTouch(onClick, enabled && interactive, interaction).focusProperties { canFocus=interactive }.semantics { this.selected = selected }.onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) { if (media) TvPlayerDesign.focus else colors.primary } else Color.Transparent, shape),
        shape = ButtonDefaults.shape(shape), scale = ButtonDefaults.scale(focusedScale = 1f),
        border = if (media) ButtonDefaults.border(focusedBorder = Border.None) else ButtonDefaults.border(),
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
    password: Boolean = false, options: KeyboardOptions = KeyboardOptions.Default,
    focusedBorderColor: Color = MaterialTheme.colorScheme.primary,
    keyboardActions: KeyboardActions = KeyboardActions.Default, showLabel: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val gateway=LocalTvRemoteGateway.current
    val interactive=LocalTvInteractive.current
    val latest by rememberUpdatedState(onValueChange)
    val receiver=remember { {text:String->latest(text)} }
    DisposableEffect(gateway){onDispose{if(gateway?.input===receiver)gateway.input=null}}
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) Text(label, style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText)
        BasicTextField(value, onValueChange, enabled=interactive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused;if(it.isFocused)gateway?.input=receiver else if(gateway?.input===receiver)gateway.input=null }.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(2.dp, if (focused) focusedBorderColor else LocalTvSurfaces.current.outline, TvShape)
            .padding(12.dp), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = options, keyboardActions = keyboardActions)
    }
}

@Composable fun TvPanel(title: String, onDismiss: () -> Unit, onBack: () -> Unit = onDismiss, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        TvRemoteDialogKeys(onBack)
        val body=remember { FocusRequester() }
        LaunchedEffect(title) { androidx.compose.runtime.withFrameNanos {};body.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f))) {
            Column(Modifier.align(Alignment.CenterEnd).width((maxWidth * .46f).coerceIn(320.dp, 460.dp)).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface.copy(alpha=.90f), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TvToolbarAction("关闭",Art.drawable.fileway_ic_close,onDismiss)
                }
                Column(Modifier.weight(1f).fillMaxWidth().focusRequester(body).focusGroup(),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
            }
        }
    }
}

/** Dialogs own a separate Android Window; dispatch to that actual focus tree. */
@Composable fun TvRemoteDialogKeys(onBack:()->Unit){
    val gateway=LocalTvRemoteGateway.current;val view=LocalView.current;val confirmGuard=LocalTvConfirmKeyGuard.current
    val latestBack by rememberUpdatedState(onBack)
    DisposableEffect(gateway,view,confirmGuard){
        val previousKey=gateway?.key;val previousBack=gateway?.back
        val window=(view.parent as? DialogWindowProvider)?.window
        val previousCallback=window?.callback
        val guardedCallback = if (confirmGuard != null && previousCallback != null) object : Window.Callback by previousCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                confirmGuard.intercept(event) || previousCallback.dispatchKeyEvent(event)
        } else null
        if (window != null && guardedCallback != null) window.callback = guardedCallback
        val receiver:(Int)->Unit={code->val time=SystemClock.uptimeMillis();view.rootView.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0));view.rootView.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_UP,code,0));Unit}
        val goBack:()->Unit={
            if(androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
            else latestBack()
        };gateway?.key=receiver;gateway?.back=goBack
        onDispose{
            if (window != null && guardedCallback != null && window.callback === guardedCallback) window.callback = previousCallback
            if(gateway?.key===receiver)gateway.key=previousKey;if(gateway?.back===goBack)gateway.back=previousBack
        }
    }
}

fun tvTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds/3600, seconds/60%60, seconds%60) else "%d:%02d".format(seconds/60, seconds%60)
}
fun tvSize(bytes: Long): String = when { bytes >= 1073741824 -> "%.1f GB".format(bytes / 1073741824.0); bytes >= 1048576 -> "%.1f MB".format(bytes/1048576.0); bytes >= 1024 -> "%.0f KB".format(bytes/1024.0); else -> "$bytes B" }

/** Keep repeats of one physical press together even when focus changes. */
internal class TvRemoteKeyLatch {
    private val consumed = mutableMapOf<Int, Long>()

    fun down(key: Int, downTime: Long, repeat: Int, handle: () -> Boolean): Boolean {
        // A dialog can receive the old key-up after focus leaves this tree.
        // A new physical press must never inherit that abandoned latch.
        if (repeat == 0 || consumed[key] != downTime) consumed.remove(key)
        val handled = handle()
        if (handled) consumed[key] = downTime
        return handled || consumed[key] == downTime
    }

    fun up(key: Int, downTime: Long): Boolean {
        if (consumed[key] != downTime) return false
        consumed.remove(key)
        return true
    }
}

/** Consume a handled remote press through release, including repeats after focus moves. */
fun Modifier.tvRemoteKeys(onDown: (androidx.compose.ui.input.key.KeyEvent) -> Boolean): Modifier = this.composed {
    val consumed = remember { TvRemoteKeyLatch() }
    this.onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent.keyCode
        val downTime = event.nativeKeyEvent.downTime
        when (event.type) {
            androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                var handled = false
                val result = consumed.down(key, downTime, event.nativeKeyEvent.repeatCount) {
                    onDown(event).also { handled = it }
                }
                if (BuildConfig.DEBUG && key == KeyEvent.KEYCODE_DPAD_DOWN && result) {
                    android.util.Log.d("FilewayTvInput", "remote-down handled=$handled consumed=$result repeat=${event.nativeKeyEvent.repeatCount}")
                }
                result
            }
            androidx.compose.ui.input.key.KeyEventType.KeyUp -> consumed.up(key, downTime).also {
                if (BuildConfig.DEBUG && key == KeyEvent.KEYCODE_DPAD_DOWN && it) android.util.Log.d("FilewayTvInput", "remote-down released")
            }
            else -> false
        }
    }
}

/** TV Material 1.1.0 provides D-pad/semantics actions without pointer clicks.
 * Add pointer handling only, keeping its existing focus and key semantics.
 * A scroll cancels the press; lifting after a scroll never opens a card. */
fun Modifier.tvTouch(onClick: () -> Unit, enabled: Boolean = true, interaction: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val focus = remember { FocusRequester() }
    this.focusRequester(focus).pointerInput(enabled, interaction, onLongClick != null) {
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
            onLongPress = if (onLongClick != null) ({ longClick?.invoke() }) else null,
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
    showSelection:Boolean=true, leading:(@Composable ()->Unit)?=null, dense:Boolean=false,
) {
    val interactive=LocalTvInteractive.current
    val colors=MaterialTheme.colorScheme
    val interaction=remember { MutableInteractionSource() }
    val active = enabled && interactive
    val rowModifier = modifier.fillMaxWidth().heightIn(min = if (dense) 48.dp else 52.dp)
        .tvTouch(onClick, active, interaction).focusProperties { canFocus = interactive }
    val shape = ListItemDefaults.shape(TvShape)
    val scale = ListItemDefaults.scale(focusedScale = 1f)
    val rowColors = ListItemDefaults.colors(containerColor=Color.Transparent,contentColor=colors.onSurface,
        selectedContainerColor=colors.primary.copy(alpha=.12f),selectedContentColor=colors.onSurface,
        focusedContainerColor=colors.onSurface,focusedContentColor=colors.surface,
        focusedSelectedContainerColor=colors.onSurface,focusedSelectedContentColor=colors.surface)
    val headlineContent: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val supportingContent: (@Composable () -> Unit)? = supporting?.takeIf { it.isNotBlank() }?.let { subtitle ->
        { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = .75f),
            maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    val leadingContent: (@Composable BoxScope.() -> Unit)? = when {
        leading != null -> { { leading() } }
        icon != null -> { { Icon(painterResource(icon), null, Modifier.size(TvOptionIconSize)) } }
        else -> null
    }
    val trailingContent: @Composable () -> Unit = {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
            value?.let{Text(it,style=MaterialTheme.typography.labelMedium)}
            if(destination)Icon(painterResource(Art.drawable.fileway_ic_expand_more),null,Modifier.size(20.dp).rotate(-90f))
            else if(showSelection)Box(Modifier.size(24.dp),contentAlignment=Alignment.Center){if(selected)Icon(painterResource(Art.drawable.fileway_ic_check),null,Modifier.size(22.dp))}
        }
    }
    if (dense) {
        DenseListItem(selected, onClick, headlineContent, rowModifier, enabled = active, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent, shape = shape, colors = rowColors,
            scale = scale, interactionSource = interaction)
    } else {
        ListItem(selected, onClick, headlineContent, rowModifier, enabled = active, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent, shape = shape, colors = rowColors,
            scale = scale, interactionSource = interaction)
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
