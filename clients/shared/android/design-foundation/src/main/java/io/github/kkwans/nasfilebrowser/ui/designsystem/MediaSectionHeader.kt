package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

data class FilewayMediaSectionHeaderStyle(
    val titleStyle: TextStyle,
    val titleColor: Color,
    val minHeight: Dp = FilewayMediaSpace.Phone.touchTarget,
)

fun FilewayMediaSectionHeaderStyleDefaults(): FilewayMediaSectionHeaderStyle {
    val colors = FilewayMediaPalette.light
    return FilewayMediaSectionHeaderStyle(
        titleStyle = filewayTextStyle(18, 24, androidx.compose.ui.text.font.FontWeight.Medium),
        titleColor = colors.text,
    )
}

@Composable
fun FilewayMediaSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: FilewayMediaSectionHeaderStyle = FilewayMediaSectionHeaderStyleDefaults(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = style.minHeight), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, Modifier.weight(1f).semantics { heading() }, style = style.titleStyle.copy(color = style.titleColor))
        actions()
    }
}
