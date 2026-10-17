package io.github.kkwans.nasfilebrowser

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.data.AppTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.ui.ClientApp
import io.github.kkwans.nasfilebrowser.ui.ClientTheme

class MainActivity : ComponentActivity() {
    private val model: ClientModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openIntent(intent)
        setContent {
            val appearance by model.appearance.state.collectAsStateWithLifecycle()
            val client by model.state.collectAsStateWithLifecycle()
            val dark = when (appearance.theme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            // Read media state during composition, not only inside an effect;
            // otherwise entering the viewer does not invalidate this scope.
            val lightBars = !dark && client.selected == null && client.image == null
            DisposableEffect(lightBars) {
                val style = if (lightBars) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    else SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                // Compose paints the surface behind the gesture area. Do not
                // let a separate system contrast scrim turn it into a gray strip.
                window.isNavigationBarContrastEnforced = false
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = lightBars
                bars.isAppearanceLightNavigationBars = lightBars
                onDispose { }
            }
            androidx.compose.runtime.SideEffect {
                if (client.selected == null && client.image == null) {
                    val bars = WindowCompat.getInsetsController(window, window.decorView)
                    bars.isAppearanceLightStatusBars = !dark
                    bars.isAppearanceLightNavigationBars = !dark
                }
            }
            ClientTheme(darkTheme = dark) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { ClientApp(model) }
            }
        }
        // Theme.Material's native content parent consumes Insets via fitsSystemWindows.
        // Compose owns those Insets; prevent duplicate native padding after recreation.
        (findViewById<android.view.View>(android.R.id.content).parent as? android.view.View)
            ?.fitsSystemWindows = false
    }
    override fun onStart() { super.onStart(); model.foreground(true) }
    private fun openIntent(intent: android.content.Intent) {
        if (intent.getBooleanExtra("open_downloads", false)) { intent.removeExtra("open_downloads"); model.openDownloads() }
        if (intent.getBooleanExtra("open_uploads", false)) { intent.removeExtra("open_uploads"); model.openUploads() }
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); openIntent(intent) }
    override fun onStop() { model.foreground(false); model.pausePlayback(); super.onStop() }
}
