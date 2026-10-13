package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** The application and its ordinary overlays share one Fileway design system. */
@Composable fun ClientTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    FilewayTheme(darkTheme = darkTheme, content = content)
}
