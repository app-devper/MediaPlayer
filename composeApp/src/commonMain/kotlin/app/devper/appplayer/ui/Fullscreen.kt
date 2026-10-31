package app.devper.appplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Video filling the whole screen: the app shell hides its chrome, the platform hides its own. */
class FullscreenState {
    var isFullscreen by mutableStateOf(false)
        private set

    fun toggle() {
        isFullscreen = !isFullscreen
    }

    fun exit() {
        isFullscreen = false
    }
}

val LocalFullscreen = staticCompositionLocalOf { FullscreenState() }
