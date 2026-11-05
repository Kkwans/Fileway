package io.github.kkwans.fileway.tv

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.kkwans.nasfilebrowser.ui.designsystem.filewayTextStyle

/** Fixed-weight UI outlines avoid vendor/theme substitutions and variable-axis defaults. */
private val TvUiFont = FontFamily(
    Font(R.font.fileway_ui_regular,weight=FontWeight.Normal),
    Font(R.font.fileway_ui_medium,weight=FontWeight.Medium),
)
internal fun tvTextStyle(size:Int,height:Int,weight:FontWeight=FontWeight.Normal)=
    filewayTextStyle(size,height,weight).copy(fontFamily=TvUiFont)
