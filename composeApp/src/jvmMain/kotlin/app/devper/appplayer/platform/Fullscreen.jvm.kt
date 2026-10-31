package app.devper.appplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.flow.first
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/** The app window's state, provided by Main.kt so the player can take the window full screen. */
val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }

@Composable
actual fun SystemFullscreenEffect(fullscreen: Boolean, onExit: () -> Unit) {
    val window = LocalWindowState.current ?: return
    val exit by rememberUpdatedState(onExit)
    var placementBefore by remember { mutableStateOf<WindowPlacement?>(null) }

    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            if (placementBefore == null) placementBefore = window.placement
            window.placement = WindowPlacement.Fullscreen
            // Left full screen the OS way (e.g. the green button): follow.
            snapshotFlow { window.placement }.first { it != WindowPlacement.Fullscreen }
            exit()
        } else {
            placementBefore?.let { window.placement = it }
            placementBefore = null
        }
    }
    DisposableEffect(fullscreen) {
        if (!fullscreen) return@DisposableEffect onDispose {}
        val escape = KeyEventDispatcher { e ->
            (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE).also { if (it) exit() }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape)
        onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape) }
    }
}
