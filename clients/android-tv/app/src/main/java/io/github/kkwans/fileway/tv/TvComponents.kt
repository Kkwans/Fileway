package io.github.kkwans.fileway.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPalette

@Composable fun TvTheme(dark: Boolean, media: Boolean = false, content: @Composable () -> Unit) {
    val base = if (dark) darkColorScheme(primary = FilewayPalette.darkAccent, onPrimary = FilewayPalette.darkCanvas,
        secondary=FilewayPalette.darkAccent, secondaryContainer=Color(0xFF44474F),onSecondaryContainer=Color(0xFFE9EAEC),
        background = Color(0xFF292A2F), onBackground = Color(0xFFE9EAEC), surface = Color(0xFF33343A), onSurface = Color(0xFFE9EAEC))
    else lightColorScheme(primary = FilewayPalette.blue, onPrimary = Color.White, background = FilewayPalette.lightCanvas,
        onBackground = FilewayPalette.lightText,secondary=FilewayPalette.blue,secondaryContainer=Color(0xFFE5EAF1),onSecondaryContainer=FilewayPalette.lightText, surface = FilewayPalette.lightSurface, onSurface = FilewayPalette.lightText)
    val colors = if (media) base.copy(background = FilewayPalette.playerCanvas, surface = FilewayPalette.playerSurface) else base
    MaterialTheme(colorScheme = colors, typography = Typography(
        titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp), titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
        bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp), bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    )) { CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content) }
}

@Composable fun TvButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
    icon: Int? = null, content: (@Composable RowScope.() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    Button(onClick = onClick, enabled = enabled, modifier = modifier.semantics { this.selected = selected }.onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) colors.primary else Color.Transparent, shape),
        shape = ButtonDefaults.shape(shape), scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = if (selected) colors.primary.copy(alpha = .18f) else colors.surface,
            contentColor = colors.onSurface, focusedContainerColor = colors.primary.copy(alpha = .22f), focusedContentColor = colors.onSurface),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        if (content != null) content() else {
            if (icon != null) { Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable fun TvField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    password: Boolean = false, options: KeyboardOptions = KeyboardOptions.Default) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        BasicTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused }.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .2f), RoundedCornerShape(12.dp))
            .padding(12.dp), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = options)
    }
}

@Composable fun TvPanel(title: String, onDismiss: () -> Unit, onBack: () -> Unit = onDismiss, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
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

@Composable fun delayedBusy(busy: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(200); visible = true } else visible = false }
    return visible
}


internal fun tvDate(value:String):String=runCatching {
    java.time.OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value)


@Composable internal fun TvToolbarAction(label:String,icon:Int,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    var focused by remember{mutableStateOf(false)}
    val colors=MaterialTheme.colorScheme
    Column(modifier.width(44.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Button(onClick=onClick,enabled=enabled,modifier=Modifier.size(44.dp).semantics{contentDescription=label}.onFocusChanged{focused=it.isFocused},
            shape=ButtonDefaults.shape(RoundedCornerShape(12.dp)),scale=ButtonDefaults.scale(focusedScale=1f),contentPadding=PaddingValues(10.dp),
            colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=colors.onSurface.copy(alpha=.7f),focusedContainerColor=colors.primary.copy(alpha=.18f),focusedContentColor=colors.onSurface)) {
            Icon(androidx.compose.ui.res.painterResource(icon),null,Modifier.size(24.dp))
        }
        Text(if(focused)label else "",style=MaterialTheme.typography.bodySmall.copy(fontSize=12.sp,lineHeight=16.sp),maxLines=1)
    }
}
@Composable internal fun TvQuietAction(label:String,onClick:()->Unit) {
    Button(onClick=onClick,shape=ButtonDefaults.shape(RoundedCornerShape(12.dp)),scale=ButtonDefaults.scale(focusedScale=1f),
        contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp),colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.65f),focusedContainerColor=MaterialTheme.colorScheme.primary.copy(alpha=.18f),focusedContentColor=MaterialTheme.colorScheme.onSurface)) {
        Text(label,style=MaterialTheme.typography.bodySmall)
    }
}
