@file:OptIn(ExperimentalForeignApi::class)

package app.devper.appplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import app.devper.appplayer.playback.AvPlaybackEngine
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.compose.koinInject
import platform.AVKit.AVPlayerViewController

/**
 * The app's AVPlayer ([AvPlaybackEngine]) in AVKit, with AVKit's own controls: full screen, Picture in
 * Picture and AirPlay to an Apple TV.
 */
@Composable
actual fun VideoSurface(title: String, modifier: Modifier) {
    val engine = koinInject<AvPlaybackEngine>()
    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = engine.player
                showsPlaybackControls = true
                allowsPictureInPicturePlayback = true
                // Now playing is IosBackgroundPlayer's.
                updatesNowPlayingInfoCenter = false
                // So the background pause leaves Picture in Picture playing.
                delegate = engine.pictureInPictureDelegate
            }
        },
        // The player plays on (its sound) when this leaves the screen.
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

actual val videoSurfaceHasControls: Boolean = true

actual val pointerIsMouse: Boolean = false

actual fun Modifier.hidePointer(hidden: Boolean): Modifier = this
