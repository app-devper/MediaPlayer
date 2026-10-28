package com.appdevper.mediaplayer.platform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appdevper.mediaplayer.designsystem.components.ThinSlider
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

/** Desktop player on FFmpeg ([FfmpegPlayer]): plays from DLNA servers, http(s) URLs and HLS. */
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
    val player = remember(url) { FfmpegPlayer(url, autoPlay).apply { if (startPositionMs > 0) seek(startPositionMs) } }
    val status by player.status.collectAsStateWithLifecycle()
    val frame by player.frames.collectAsStateWithLifecycle()
    val ended by rememberUpdatedState(onEnded)
    val report by rememberUpdatedState(onStatus)
    // "Stopped" = loaded but not started, finished, or stopped by a controller; reported as STOPPED rather than PAUSED.
    var stopped by remember(url) { mutableStateOf(!autoPlay) }
    var seeking by remember { mutableStateOf(false) }
    var sliderVolume by remember { mutableFloatStateOf(100f) }

    fun rendererStatus() = RendererStatus(
        state = when {
            stopped || status.ended -> TransportState.STOPPED
            status.buffering && status.playing -> TransportState.TRANSITIONING
            status.playing -> TransportState.PLAYING
            else -> TransportState.PAUSED
        },
        positionMs = status.positionMs,
        durationMs = status.durationMs,
        ended = status.ended,
    )

    DisposableEffect(player) {
        onDispose {
            report(rendererStatus().copy(state = TransportState.STOPPED))
            player.release()
        }
    }
    LaunchedEffect(player) {
        commands.collect { cmd ->
            when (cmd) {
                LocalPlayerCommand.Play -> {
                    stopped = false
                    player.play()
                }
                LocalPlayerCommand.Pause -> player.pause()
                LocalPlayerCommand.Stop -> {
                    stopped = true
                    player.pause()
                    player.seek(0)
                }
                is LocalPlayerCommand.Seek -> player.seek(cmd.positionMs)
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            report(rendererStatus())
            delay(STATUS_INTERVAL_MS)
        }
    }
    LaunchedEffect(status.ended) {
        if (status.ended) {
            stopped = true
            ended()
        }
    }
    LaunchedEffect(volume) {
        sliderVolume = volume.coerceIn(0f, 1f) * 100f
        player.setVolume(sliderVolume / 100f)
    }

    // Video: controls float over the picture and hide while it plays; songs: always shown, in theme colors.
    // Mouse moves and keys go through a flow, not state, so moving the mouse doesn't recompose the player.
    val activity = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    var controlsVisible by remember { mutableStateOf(true) }
    var overControls by remember { mutableStateOf(false) }
    // Keyboard: the video area takes focus; keys there (or on a control) show the bar again, and focus
    // moves to the video area when the bar hides, so it isn't lost with the hidden controls.
    var controlsHaveFocus by remember { mutableStateOf(false) }
    val videoFocus = remember { FocusRequester() }
    val canHide = !isAudio && status.playing && !status.buffering && !seeking && !overControls
    LaunchedEffect(canHide) {
        controlsVisible = true
        if (!canHide) return@LaunchedEffect
        activity.onStart { emit(Unit) }.collectLatest {
            controlsVisible = true
            delay(CONTROLS_HIDE_MS)
            if (controlsHaveFocus) videoFocus.requestFocus()
            controlsVisible = false
        }
    }
    // M or the speaker icon: mute, and back to the volume it had.
    var volumeBeforeMute by remember { mutableFloatStateOf(100f) }
    fun toggleMute() {
        if (sliderVolume > 0f) {
            volumeBeforeMute = sliderVolume
            sliderVolume = 0f
        } else {
            sliderVolume = volumeBeforeMute.takeIf { it > 0f } ?: 100f
        }
        player.setVolume(sliderVolume / 100f)
    }
    // ← / →: back or on by SEEK_STEP_MS, within the video.
    fun seekBy(deltaMs: Long) {
        val to = (status.positionMs + deltaMs).coerceAtLeast(0)
        player.seek(if (status.durationMs > 0) to.coerceAtMost(status.durationMs) else to)
    }

    fun togglePlay() {
        if (status.playing) {
            player.pause()
        } else {
            stopped = false
            player.play()
        }
    }

    val c = AppTheme.colors
    val content = if (isAudio) c.textPrimary else Color.White
    val controls: @Composable () -> Unit = {
        // Stays up while the pointer is on it or one of its controls has keyboard focus.
        PlayerControlBar(
            playing = status.playing,
            positionMs = status.positionMs,
            durationMs = status.durationMs,
            onPlayPause = ::togglePlay,
            onSeek = player::seek,
            content = content,
            modifier = Modifier
                .onFocusChanged { controlsHaveFocus = it.hasFocus }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            when (awaitPointerEvent().type) {
                                PointerEventType.Enter -> overControls = true
                                PointerEventType.Exit -> overControls = false
                            }
                        }
                    }
                },
            onDraggingChange = { seeking = it },
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
        ) {
            IconButton(onClick = ::toggleMute) {
                val muted = sliderVolume <= 0f
                Icon(
                    if (muted) AppIcons.VolumeOff else AppIcons.Volume,
                    if (muted) "Unmute (M)" else "Mute (M)",
                    Modifier.size(18.dp),
                    tint = content.copy(alpha = 0.85f),
                )
            }
            ThinSlider(
                value = sliderVolume,
                onValueChange = {
                    sliderVolume = it
                    player.setVolume(it / 100f)
                },
                valueRange = 0f..100f,
                activeColor = content,
                inactiveColor = content.copy(alpha = 0.3f),
                modifier = Modifier.width(110.dp),
            )
        }
    }

    if (isAudio) {
        Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    status.error != null -> Text(
                        status.error.orEmpty(), color = c.danger, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    status.buffering && status.playing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.textSecondary)
                }
            }
            controls()
        }
        return
    }
    Box(
        modifier.fillMaxSize()
            // Any mouse movement or key brings the controls back.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        activity.tryEmit(Unit)
                    }
                }
            }
            .onPreviewKeyEvent { activity.tryEmit(Unit); false }
            .focusRequester(videoFocus)
            .focusable()
            // Keys on the video area: Space play/pause (like clicking it), ← → seek (held: repeats),
            // F full screen, M mute.
            .onKeyEvent { e ->
                when {
                    e.type == KeyEventType.KeyUp && e.key == Key.Spacebar -> togglePlay()
                    e.type != KeyEventType.KeyDown -> return@onKeyEvent false
                    e.key == Key.DirectionLeft -> seekBy(-SEEK_STEP_MS)
                    e.key == Key.DirectionRight -> seekBy(SEEK_STEP_MS)
                    e.key == Key.F && onToggleFullscreen != null -> onToggleFullscreen()
                    e.key == Key.M -> toggleMute()
                    else -> return@onKeyEvent false
                }
                true
            }
            // The pointer hides with the bar while the video plays (moving it brings both back).
            .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else HiddenPointer)
            .pointerInput(onToggleFullscreen) {
                detectTapGestures(
                    onTap = {
                        videoFocus.requestFocus()
                        togglePlay()
                    },
                    onDoubleTap = onToggleFullscreen?.let { toggle -> { toggle() } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f != null) {
            val bitmap = remember(f) {
                SkiaImage.makeRaster(ImageInfo(f.width, f.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), f.bgra, f.width * 4)
                    .toComposeImageBitmap()
            }
            Image(bitmap, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        when {
            status.error != null -> Text(status.error.orEmpty(), color = Color.White, modifier = Modifier.padding(16.dp))
            status.buffering && status.playing -> CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp, color = Color.White)
            // Paused: a big play button in the middle, like other video players.
            !status.playing -> Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Play, null, Modifier.size(30.dp), tint = Color.White)
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    // Clicks on the bar's empty parts (time, gaps, gradient) don't reach the video's play/pause.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 4.dp),
            ) { controls() }
        }
    }
}

private const val CONTROLS_HIDE_MS = 2_500L
private const val SEEK_STEP_MS = 5_000L

/** An empty cursor: AWT has no "none", so a 1×1 transparent image. */
private val HiddenPointer = PointerIcon(
    Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"),
)
