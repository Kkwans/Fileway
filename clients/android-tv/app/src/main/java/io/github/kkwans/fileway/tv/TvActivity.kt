package io.github.kkwans.fileway.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme

class TvActivity : ComponentActivity() {
    private lateinit var model: TvModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = 5894
        model = ViewModelProvider(this)[TvModel::class.java]
        model.remoteGateway.key={code->
            val time=SystemClock.uptimeMillis()
            window.decorView.rootView.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0))
            window.decorView.rootView.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_UP,code,0))
        }
        model.remoteGateway.back={
            val view=window.decorView
            if(ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true)getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
            else onBackPressedDispatcher.onBackPressed()
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val media by model.media.state.collectAsStateWithLifecycle()
            TvTheme(state.dark) {
                CompositionLocalProvider(LocalTvRemoteGateway provides model.remoteGateway) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (!state.connected) TvLoginScreen(model)
                    else {
                        // Keep the library and its actual lazy-list/focus state
                        // attached below the player. Returning no longer builds
                        // a new library under a different global theme.
                        TvBrowseScreen(model, ::finish, active = media.file == null)
                        if (media.file != null) TvTheme(dark = true, media = true) { TvMediaScreen(model) }
                    }
                    TvPairingScreen(model)
                }
                }
            }
        }
    }
    override fun onStart(){super.onStart();model.foreground()}
    override fun onStop() { model.pause(); super.onStop() }
    override fun onDestroy(){model.remoteGateway.key=null;model.remoteGateway.back=null;super.onDestroy()}
}
