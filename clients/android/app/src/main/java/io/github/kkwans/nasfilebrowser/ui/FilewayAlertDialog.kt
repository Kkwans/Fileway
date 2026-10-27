package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Shared alert dialog surface with a window-scoped live backdrop blur. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun FilewayAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = Color.Unspecified,
    titleContentColor: Color = Color.Unspecified,
    textContentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) {
    var blurred by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.filewayGlassFrame(),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = RoundedCornerShape(12.dp),
        containerColor = filewayGlassColor(blurred),
        iconContentColor = if (iconContentColor == Color.Unspecified) AlertDialogDefaults.iconContentColor else iconContentColor,
        titleContentColor = if (titleContentColor == Color.Unspecified) AlertDialogDefaults.titleContentColor else titleContentColor,
        textContentColor = if (textContentColor == Color.Unspecified) AlertDialogDefaults.textContentColor else textContentColor,
        tonalElevation = 0.dp,
        properties = properties,
        confirmButton = {
            FilewayDialogGlass { blurred = it }
            FilewayDialogKeyboardFit()
            confirmButton()
        },
    )
}

/** Shared date picker dialog surface with the same window-scoped blur behavior. */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilewayDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: DatePickerColors = DatePickerDefaults.colors(),
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    var blurred by remember { mutableStateOf(false) }
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.filewayGlassFrame(),
        dismissButton = dismissButton,
        shape = RoundedCornerShape(12.dp),
        colors = colors.copy(containerColor = filewayGlassColor(blurred)),
        tonalElevation = 0.dp,
        properties = properties,
        confirmButton = {
            FilewayDialogGlass { blurred = it }
            FilewayDialogKeyboardFit()
            confirmButton()
        },
        content = content,
    )
}
