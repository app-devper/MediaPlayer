@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appdevper.mediaplayer.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.playback.IosBackgroundPlayer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject
import platform.AVKit.AVPlayerViewController

/**
 * Shows the app's one player ([IosBackgroundPlayer]): AVKit for videos (full screen, Picture in
 * Picture, AirPlay), our control bar for songs. That player follows the playback session by itself
 * (loads the item, takes [commands], reports the status, moves on at the end, sets the volume) and
 * keeps playing when this leaves the screen, so those parameters are unused here.
 */
@Composable
actual fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier,
    autoPlay: Boolean,
    startPositionMs: Long,
    volume: Float,
    commands: Flow<LocalPlayerCommand>,
    onStatus: (RendererStatus) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
) {
    val background = koinInject<IosBackgroundPlayer>()
    val player = background.player
    if (!isAudio) {
        UIKitViewController(
            factory = {
                AVPlayerViewController().apply {
                    this.player = player
                    showsPlaybackControls = true
                    allowsPictureInPicturePlayback = true
                    // Now playing is the background player's.
                    updatesNowPlayingInfoCenter = false
                }
            },
            // The player plays on (its sound) when this leaves the screen.
            onRelease = { it.player = null },
            modifier = modifier,
        )
        return
    }

    // Songs: our control bar, as on the other platforms (AVKit's needs more height than the song page gives).
    val stopped by background.stopped.collectAsStateWithLifecycle()
    var shown by remember(url) { mutableStateOf(background.status()) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    // Only while the app is in front; the background player reports to the session on its own.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(url, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                shown = background.status()
                error = background.error
                delay(CONTROLS_POLL_MS)
            }
        }
    }
    val playing = !stopped && shown.state != TransportState.STOPPED && background.isPlaying
    fun togglePlay() {
        if (playing) background.pause() else background.resume()
        shown = background.status()
    }
    val c = AppTheme.colors
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error.orEmpty(), color = c.danger, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                shown.state == TransportState.TRANSITIONING ->
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.textSecondary)
            }
        }
        PlayerControlBar(
            playing = playing,
            positionMs = shown.positionMs,
            durationMs = shown.durationMs,
            onPlayPause = ::togglePlay,
            onSeek = {
                background.seek(it)
                shown = background.status()
            },
            content = c.textPrimary,
        ) {
            // AVKit's controls had the AirPlay button; songs keep one here.
            AirPlayButton(Modifier.size(40.dp), tint = c.textPrimary)
        }
    }
}

private const val CONTROLS_POLL_MS = 200L
