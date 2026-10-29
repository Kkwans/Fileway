package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/** The same native font and baseline rules on phone and TV. Each application
 * owns its size roles and Material adapter; no phone widget dependency. */
fun filewayTextStyle(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, mono: Boolean = false) = TextStyle(
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
    fontWeight = weight, fontSize = size.sp, lineHeight = height.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
