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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import com.appdevper.mediaplayer.playback.AvPlaybackEngine
import com.appdevper.mediaplayer.ui.player.PlayerControlBar
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.compose.koinInject
import platform.AVKit.AVPlayerViewController

/**
 * Shows the app's one player ([PlaybackSession.local] on [AvPlaybackEngine]): AVKit for videos (full
 * screen, Picture in Picture, AirPlay), our control bar for songs. The player follows the session by
 * itself and keeps playing when this leaves the screen.
 */
@Composable
actual fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    modifier: Modifier,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
) {
    val engine = koinInject<AvPlaybackEngine>()
    if (!isAudio) {
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
        return
    }

    // Songs: our control bar, as on the other platforms (AVKit's needs more height than the song page gives).
    val local = koinInject<PlaybackSession>().local
    val status by local.status.collectAsStateWithLifecycle()
    val error by local.error.collectAsStateWithLifecycle()
    val state = status?.state
    val playing = state == TransportState.PLAYING || state == TransportState.TRANSITIONING
    val c = AppTheme.colors
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error.orEmpty(), color = c.danger, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state == TransportState.TRANSITIONING ->
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.textSecondary)
            }
        }
        PlayerControlBar(
            playing = playing,
            positionMs = status?.positionMs ?: 0,
            durationMs = status?.durationMs ?: 0,
            onPlayPause = { if (playing) local.pause() else local.play() },
            onSeek = local::seek,
            content = c.textPrimary,
        ) {
            // AVKit's controls had the AirPlay button; songs keep one here.
            AirPlayButton(Modifier.size(40.dp), tint = c.textPrimary)
        }
    }
}
