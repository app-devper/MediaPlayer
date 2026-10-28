package com.appdevper.mediaplayer.platform

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.appdevper.mediaplayer.playback.ExoPlaybackEngine
import org.koin.compose.koinInject

/** The app's ExoPlayer ([ExoPlaybackEngine]) in a PlayerView without its own controls. */
@OptIn(UnstableApi::class)
@Composable
actual fun VideoSurface(title: String, modifier: Modifier) {
    val player = koinInject<ExoPlaybackEngine>().player
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                // Subtitles are ours (LocalPlayerView), not PlayerView's.
                subtitleView?.visibility = View.GONE
            }
        },
        // The player plays on (its sound, without a picture) when this leaves the screen.
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

actual val videoSurfaceHasControls: Boolean = false

actual val pointerIsMouse: Boolean = false

actual fun Modifier.hidePointer(hidden: Boolean): Modifier = this
