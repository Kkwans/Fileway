package io.github.kkwans.fileway.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
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
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val media by model.media.state.collectAsStateWithLifecycle()
            TvTheme(if(media.file!=null)true else state.dark, media = media.file != null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when { !state.connected -> TvLoginScreen(model)
                        media.file != null -> TvMediaScreen(model)
                        else -> TvBrowseScreen(model, ::finish)
                    }
                }
            }
        }
    }
    override fun onStart(){super.onStart();model.foreground()}
    override fun onStop() { model.pause(); super.onStop() }
}
