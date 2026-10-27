package com.appdevper.mediaplayer.platform

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
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
    var error by remember(url) { mutableStateOf<String?>(null) }
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

            override fun onPlayerError(e: PlaybackException) {
                error = e.message ?: e.errorCodeName
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

    // What the controls show, polled more often than the status is reported so the seek bar moves smoothly.
    var shown by remember(player) { mutableStateOf(status()) }
    LaunchedEffect(player) {
        while (true) {
            shown = status()
            delay(CONTROLS_POLL_MS)
        }
    }
    val playing = player.playWhenReady && !stopped && shown.state != TransportState.STOPPED
    val buffering = shown.state == TransportState.TRANSITIONING
    fun togglePlay() {
        if (playing) {
            player.pause()
        } else {
            stopped = false
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        shown = status()
    }

    val c = AppTheme.colors
    val content = if (isAudio) c.textPrimary else Color.White
    var seeking by remember { mutableStateOf(false) }
    // Bumped by every tap, so the hide timer starts over.
    var touches by remember { mutableIntStateOf(0) }
    val controls: @Composable (Modifier) -> Unit = { barModifier ->
        PlayerControlBar(
            playing = playing,
            positionMs = shown.positionMs,
            durationMs = shown.durationMs,
            onPlayPause = {
                touches++
                togglePlay()
            },
            onSeek = {
                stopped = false
                player.seekTo(it)
                shown = status()
            },
            content = content,
            modifier = barModifier,
            onDraggingChange = {
                seeking = it
                touches++
            },
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
        )
    }

    if (isAudio) {
        // Songs: no picture, the bar is always shown, in theme colors.
        Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    error != null -> Text(error.orEmpty(), color = c.danger, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    buffering -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.textSecondary)
                }
            }
            controls(Modifier)
        }
        return
    }

    // Video: the bar floats over the picture; a tap shows or hides it, and it hides by itself while playing.
    var controlsVisible by remember { mutableStateOf(true) }
    val canHide = playing && !buffering && !seeking
    LaunchedEffect(canHide, touches) {
        if (!canHide) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(CONTROLS_HIDE_MS)
        controlsVisible = false
    }
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Over the picture: taps go to Compose, not to the PlayerView.
        Box(
            Modifier.fillMaxSize().pointerInput(onToggleFullscreen) {
                detectTapGestures(
                    onTap = {
                        touches++
                        controlsVisible = !controlsVisible
                    },
                    onDoubleTap = onToggleFullscreen?.let { toggle -> { toggle() } },
                )
            },
        )
        when {
            error != null -> Text(error.orEmpty(), color = Color.White, modifier = Modifier.padding(16.dp))
            buffering -> CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp, color = Color.White)
            // Paused: a big play button in the middle, like other video players.
            !playing -> Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { togglePlay() } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Play, "Play", Modifier.size(30.dp), tint = Color.White)
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            controls(
                Modifier
                    // Taps on the bar's empty parts don't reach the picture (which would hide the bar).
                    .pointerInput(Unit) { detectTapGestures { touches++ } }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 4.dp),
            )
        }
    }
}

private const val CONTROLS_HIDE_MS = 2_500L
private const val CONTROLS_POLL_MS = 200L
