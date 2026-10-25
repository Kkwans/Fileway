package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Product shape defaults; Material retains its interaction, semantics and touch behavior. */
@Composable internal fun FilewayButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    var pulse by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalFilewayIconPulse provides pulse) {
        androidx.compose.material3.Button(onClick = { pulse++; onClick() }, modifier = modifier, enabled = enabled,
    shape = shape, colors = colors, elevation = elevation, border = border,
    contentPadding = contentPadding, interactionSource = interactionSource, content = content)
    }
}

@Composable internal fun FilewayTextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), colors: ButtonColors = ButtonDefaults.textButtonColors(),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    var pulse by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalFilewayIconPulse provides pulse) {
        androidx.compose.material3.TextButton(onClick = { pulse++; onClick() }, modifier = modifier, enabled = enabled,
    shape = shape, colors = colors, border = border,
    contentPadding = contentPadding, interactionSource = interactionSource, content = content)
    }
}

@Composable internal fun FilewayOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else .38f)),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    var pulse by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalFilewayIconPulse provides pulse) {
        androidx.compose.material3.OutlinedButton(onClick = { pulse++; onClick() }, modifier = modifier, enabled = enabled,
    shape = shape, colors = colors, elevation = elevation, border = border,
    contentPadding = contentPadding, interactionSource = interactionSource, content = content)
    }
}

@Composable internal fun FilewayTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    var pulse by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalFilewayIconPulse provides pulse) {
        androidx.compose.material3.FilledTonalButton(onClick = { pulse++; onClick() }, modifier = modifier, enabled = enabled,
    shape = shape, colors = colors, elevation = elevation, border = border,
    contentPadding = contentPadding, interactionSource = interactionSource, content = content)
    }
}
