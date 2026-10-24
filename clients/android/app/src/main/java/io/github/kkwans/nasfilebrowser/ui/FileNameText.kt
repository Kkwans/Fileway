package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

@Composable internal fun FileNameText(name: String, style: TextStyle, color: Color,
    maxLines: Int, centered: Boolean, modifier: Modifier = Modifier, minLines: Int = 1) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val nameStyle = style.copy(lineBreak = LineBreak.Paragraph)
    BoxWithConstraints(modifier) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val measured = measurer.measure(name, style = nameStyle, constraints = Constraints(maxWidth = width),
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        val split = if (maxLines == 2 && measured.lineCount == 2 && !measured.isLineEllipsized(1)) {
            extensionOrphanBreak(name, measured.getLineStart(1)) { part ->
                measurer.measure(part, style = nameStyle, softWrap = false).size.width <= width
            }
        } else null
        val alignment = if (centered) TextAlign.Center else TextAlign.Start
        if (split == null) Text(name, Modifier.fillMaxWidth(), style = nameStyle, color = color,
            maxLines = maxLines, minLines = minLines, overflow = TextOverflow.Ellipsis, textAlign = alignment)
        else Column(Modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString(name) }) {
            Text(name.substring(0, split), Modifier.fillMaxWidth(), style = nameStyle, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = alignment)
            Text(name.substring(split), Modifier.fillMaxWidth(), style = nameStyle, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = alignment)
        }
    }
}
