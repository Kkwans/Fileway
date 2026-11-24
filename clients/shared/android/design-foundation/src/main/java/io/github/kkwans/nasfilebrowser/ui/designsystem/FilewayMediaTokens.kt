package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class FilewayMediaColorRoles(
    val canvas: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val secondary: Color,
    val focus: Color,
    val accent: Color,
    val score: Color,
    val selected: Color,
    val onAccent: Color,
    val play: Color,
    val onPlay: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val scrim: Color,
)

object FilewayMediaPalette {
    val dark = FilewayMediaColorRoles(
        canvas = Color(0xFF111214), surface = Color(0xFF1A1C1F), raised = Color(0xFF25282C),
        text = Color(0xFFF3F4F6), secondary = Color(0xFFBBC1CA), focus = Color(0xFFF3F4F6),
        accent = Color(0xFF8FB8FF), score = Color(0xFFF1C875), selected = Color(0xFF23354C),
        onAccent = Color(0xFF101B2A), play = Color(0xFFF1EEE7), onPlay = Color(0xFF17191D),
        success = Color(0xFF92D2AA), warning = Color(0xFFE7C581), error = Color(0xFFFFB4AA),
        scrim = Color.Black,
    )
    val light = FilewayMediaColorRoles(
        canvas = Color(0xFFF5F7FA), surface = Color.White, raised = Color(0xFFEAEEF4),
        text = Color(0xFF172235), secondary = Color(0xFF59677B), focus = Color(0xFF1767E8),
        accent = Color(0xFF1767E8), score = Color(0xFF855B00), selected = Color(0xFFE1ECFF),
        onAccent = Color.White, play = Color(0xFF172235), onPlay = Color.White,
        success = Color(0xFF26774F), warning = Color(0xFF855D1C), error = Color(0xFFB33835),
        scrim = Color(0xFF10141B),
    )
    val player = dark.copy(
        canvas = FilewayPalette.playerCanvas, surface = FilewayPalette.playerSurface,
        raised = Color(0xFF242A34), text = Color(0xFFF1F3F5), secondary = Color(0xFFADB4BE),
        focus = Color(0xFFF1F3F5), accent = FilewayPalette.darkAccent, scrim = Color.Black,
    )
}

data class FilewayMediaSpaceRoles(
    val tiny: Dp,
    val small: Dp,
    val content: Dp,
    val itemGap: Dp,
    val pageInset: Dp,
    val sectionGap: Dp,
    val focusGutter: Dp,
    val focusOverflow: Dp,
    val touchTarget: Dp,
    val remoteTarget: Dp,
)

object FilewayMediaSpace {
    val Phone = FilewayMediaSpaceRoles(
        tiny = 4.dp, small = 8.dp, content = 12.dp, itemGap = 12.dp,
        pageInset = 16.dp, sectionGap = 24.dp, focusGutter = 0.dp, focusOverflow = 0.dp,
        touchTarget = 48.dp, remoteTarget = 48.dp,
    )
    val Tv = FilewayMediaSpaceRoles(
        tiny = 4.dp, small = 8.dp, content = 12.dp, itemGap = 16.dp,
        pageInset = 48.dp, sectionGap = 32.dp, focusGutter = 8.dp, focusOverflow = 8.dp,
        touchTarget = 48.dp, remoteTarget = 48.dp,
    )
}

object FilewayMediaShape {
    val card = 12.dp
    val badge = 4.dp
    val indicator = 2.dp
    val focusRing = 2.dp
    val focusOverflow = 8.dp
}
