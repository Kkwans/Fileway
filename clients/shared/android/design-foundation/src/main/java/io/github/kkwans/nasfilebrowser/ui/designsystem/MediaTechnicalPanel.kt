package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class FilewayMediaTechnicalPanelStyle(
    val titleStyle: TextStyle,
    val badgeStyle: TextStyle,
    val lineStyle: TextStyle,
    val titleColor: Color,
    val badgeColor: Color,
    val badgeBackground: Color,
    val lineColor: Color,
    val badgeShape: RoundedCornerShape = RoundedCornerShape(FilewayMediaShape.badge),
    val sectionGap: Dp = FilewayMediaSpace.Phone.content,
    val badgeGap: Dp = FilewayMediaSpace.Phone.small,
    val badgeHorizontalPadding: Dp = 12.dp,
    val badgeVerticalPadding: Dp = 8.dp,
)

fun FilewayMediaTechnicalPanelStyleDefaults(): FilewayMediaTechnicalPanelStyle {
    val colors = FilewayMediaPalette.light
    return FilewayMediaTechnicalPanelStyle(
        titleStyle = filewayTextStyle(18, 24, androidx.compose.ui.text.font.FontWeight.Medium),
        badgeStyle = filewayTextStyle(13, 18, androidx.compose.ui.text.font.FontWeight.Medium),
        lineStyle = filewayTextStyle(15, 22),
        titleColor = colors.text,
        badgeColor = colors.secondary,
        badgeBackground = colors.raised,
        lineColor = colors.secondary,
    )
}

@Composable
fun FilewayMediaTechnicalPanel(
    title: String,
    badges: List<String>,
    lines: List<String>,
    modifier: Modifier = Modifier,
    style: FilewayMediaTechnicalPanelStyle = FilewayMediaTechnicalPanelStyleDefaults(),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(style.sectionGap)) {
        BasicText(title, Modifier.semantics { heading() }, style = style.titleStyle.copy(color = style.titleColor))
        if (badges.isNotEmpty()) FlowRow(
            horizontalArrangement = Arrangement.spacedBy(style.badgeGap),
            verticalArrangement = Arrangement.spacedBy(style.badgeGap),
        ) {
            badges.forEach { label ->
                BasicText(
                    label,
                    Modifier.clip(style.badgeShape).background(style.badgeBackground)
                        .padding(horizontal = style.badgeHorizontalPadding, vertical = style.badgeVerticalPadding),
                    style = style.badgeStyle.copy(color = style.badgeColor),
                )
            }
        }
        lines.forEach { line ->
            BasicText(line, style = style.lineStyle.copy(color = style.lineColor))
        }
    }
}
