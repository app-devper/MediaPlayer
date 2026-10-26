package com.appdevper.mediaplayer.platform

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** ExoPlayer (Media3) player — replaces the old `LocalPlayback` (android.media.MediaPlayer). */
@OptIn(UnstableApi::class)
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
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val report by rememberUpdatedState(onStatus)
    // "Stopped" = loaded but not started, or stopped by a controller; reported as STOPPED rather than PAUSED.
    var stopped by remember(url) { mutableStateOf(!autoPlay) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build(),
            )
            if (startPositionMs > 0) seekTo(startPositionMs)
            prepare()
            playWhenReady = autoPlay
        }
    }
    fun status() = RendererStatus(
        state = when {
            stopped || player.playbackState == Player.STATE_ENDED -> TransportState.STOPPED
            player.isPlaying -> TransportState.PLAYING
            player.playWhenReady && player.playbackState == Player.STATE_BUFFERING -> TransportState.TRANSITIONING
            else -> TransportState.PAUSED
        },
        positionMs = player.currentPosition.coerceAtLeast(0),
        durationMs = player.duration.takeIf { it > 0 } ?: 0,
        ended = player.playbackState == Player.STATE_ENDED,
    )
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) stopped = false // started from its own controls too
            }
        }
        player.addListener(listener)
        onDispose {
            report(status().copy(state = TransportState.STOPPED))
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        commands.collect { cmd ->
            when (cmd) {
                LocalPlayerCommand.Play -> {
                    stopped = false
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
                LocalPlayerCommand.Pause -> player.pause()
                LocalPlayerCommand.Stop -> {
                    stopped = true
                    player.pause()
                    player.seekTo(0)
                }
                is LocalPlayerCommand.Seek -> player.seekTo(cmd.positionMs)
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            report(status())
            delay(STATUS_INTERVAL_MS)
        }
    }
    LaunchedEffect(player, volume) { player.volume = volume.coerceIn(0f, 1f) }
    val toggle by rememberUpdatedState(onToggleFullscreen)
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                if (onToggleFullscreen != null) setFullscreenButtonClickListener { toggle?.invoke() }
                if (isAudio) {
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                    showController()
                }
            }
        },
        update = { view -> if (onToggleFullscreen != null) view.setFullscreenButtonState(fullscreen) },
        modifier = modifier,
    )
}
