package com.appdevper.mediaplayer.platform

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** ExoPlayer (Media3) player — replaces the old `LocalPlayback` (android.media.MediaPlayer). */
@OptIn(UnstableApi::class)
@Composable
actual fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build(),
            )
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                if (isAudio) {
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                    showController()
                }
            }
        },
        modifier = modifier,
    )
}
