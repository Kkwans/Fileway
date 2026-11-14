package io.github.kkwans.fileway.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import io.github.kkwans.fileway.tv.medialibrary.TvMediaRoute
import io.github.kkwans.nasfilebrowser.data.MediaLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class TvActivity : ComponentActivity() {
    private lateinit var model: TvModel
    private lateinit var subtitlePicker: TvLocalSubtitlePicker
    private val confirmKeyGuard = TvConfirmKeyGuard()
    private var previousWindowCallback: Window.Callback? = null
    private var guardedWindowCallback: Window.Callback? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.callback?.let { previous ->
            previousWindowCallback = previous
            val guarded = object : Window.Callback by previous {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    confirmKeyGuard.intercept(event) || previous.dispatchKeyEvent(event)
            }
            guardedWindowCallback = guarded
            window.callback = guarded
        }
        window.decorView.systemUiVisibility = 5894
        model = ViewModelProvider(this)[TvModel::class.java]
        subtitlePicker = TvLocalSubtitlePicker(this, model.media)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                subtitlePicker.afterResume()
                model.updates.reconcileInstall()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.updates.installerPrompt.collect { if (it != null) model.updates.openInstaller(this@TvActivity) }
            }
        }
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
            val session = model.context
            var catalogOpen by remember(session) { mutableStateOf(false) }
            val catalog by produceState<Result<MediaLibraryRepository>?>(null, session, catalogOpen) {
                value = null
                if (session != null && catalogOpen) {
                    value = try { Result.success(model.catalogRepository(session)) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        Result.failure(failure)
                    }
                }
            }
            TvTheme(state.dark) {
                CompositionLocalProvider(LocalTvRemoteGateway provides model.remoteGateway, LocalTvSubtitlePicker provides subtitlePicker,
                    LocalTvConfirmKeyGuard provides confirmKeyGuard) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (!state.connected) TvLoginScreen(model)
                    else {
                        // Keep the library and its actual lazy-list/focus state
                        // attached below the player. Returning no longer builds
                        // a new library under a different global theme.
                        TvBrowseScreen(model, ::finish, active = media.file == null && !catalogOpen,
                            onCatalog = { catalogOpen = true })
                        if (catalogOpen && session != null) {
                            val repository = catalog?.getOrNull()
                            CompositionLocalProvider(LocalTvInteractive provides (media.file == null)) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    if (repository != null) TvMediaRoute(session, repository, model.imageLoader,
                                        onExit = { catalogOpen = false },
                                        onPlayback = { variant, fromStart -> model.media.openCatalog(repository, variant, fromStart) })
                                    else {
                                        BackHandler(media.file == null) { catalogOpen = false }
                                        TvButton(if (catalog == null) "正在读取影视目录 · 返回" else "影视目录暂时无法打开 · 返回",
                                            { catalogOpen = false })
                                    }
                                }
                            }
                        }
                        if (media.file != null) TvTheme(dark = true, media = true) { TvMediaScreen(model) }
                    }
                    TvPairingScreen(model)
                }
                }
            }
        }
    }
    override fun onStart(){super.onStart();model.foreground()}
    override fun onStop() { subtitlePicker.beforeStop(); model.pause(); super.onStop() }
    override fun onDestroy(){
        guardedWindowCallback?.let { if (window.callback === it) window.callback = previousWindowCallback }
        guardedWindowCallback = null
        previousWindowCallback = null
        confirmKeyGuard.clear();model.remoteGateway.key=null;model.remoteGateway.back=null;super.onDestroy()
    }
}
