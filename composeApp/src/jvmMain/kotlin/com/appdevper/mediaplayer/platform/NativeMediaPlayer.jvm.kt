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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
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
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import com.appdevper.mediaplayer.playback.FfmpegPlaybackEngine
import com.appdevper.mediaplayer.playback.FfmpegPlayer
import com.appdevper.mediaplayer.ui.player.PlayerControlBar
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Shows the app's one player ([PlaybackSession.local] on [FfmpegPlaybackEngine]): plays from DLNA
 * servers, http(s) URLs and HLS, and plays on when this leaves the screen.
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
    val session = koinInject<PlaybackSession>()
    // Empty for a moment while the session's item goes into the player.
    val player = koinInject<FfmpegPlaybackEngine>().player.collectAsStateWithLifecycle().value ?: return
    FfmpegPlayerView(player, session, title, isAudio, modifier, fullscreen, onToggleFullscreen)
}

@Composable
private fun FfmpegPlayerView(
    player: FfmpegPlayer,
    session: PlaybackSession,
    title: String,
    isAudio: Boolean,
    modifier: Modifier,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
) {
    val local = session.local
    val status by player.status.collectAsStateWithLifecycle()
    val frame by player.frames.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf(false) }
    // The session's volume, so a controller casting here sees it and sets it.
    val volume by remember(session) { session.state.map { if (it.localMuted) 0 else it.localVolume }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(100)

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
    // M or the speaker icon: mute, and back to the volume it had (full, if it was dragged down to 0).
    fun toggleMute() {
        val s = session.state.value
        when {
            !s.localMuted && s.localVolume > 0 -> session.setMuted(true)
            else -> {
                session.setMuted(false)
                if (s.localVolume == 0) session.setVolume(100)
            }
        }
    }
    // ← / →: back or on by SEEK_STEP_MS, within the video.
    fun seekBy(deltaMs: Long) {
        val to = (status.positionMs + deltaMs).coerceAtLeast(0)
        // Not right onto the end: that would finish it and a held key would go on in the next item.
        local.seek(if (status.durationMs > 0) to.coerceAtMost((status.durationMs - SEEK_END_MARGIN_MS).coerceAtLeast(0)) else to)
    }

    fun togglePlay() {
        if (status.playing) local.pause() else local.play()
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
            onSeek = local::seek,
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
                val muted = volume <= 0
                Icon(
                    if (muted) AppIcons.VolumeOff else AppIcons.Volume,
                    if (muted) "Unmute (M)" else "Mute (M)",
                    Modifier.size(18.dp),
                    tint = content.copy(alpha = 0.85f),
                )
            }
            ThinSlider(
                value = volume.toFloat(),
                onValueChange = {
                    session.setMuted(false)
                    session.setVolume(it.roundToInt())
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
                    // Cmd/Ctrl/Alt combinations belong to the app and the system (Cmd+F, Ctrl+M…).
                    e.isMetaPressed || e.isCtrlPressed || e.isAltPressed -> return@onKeyEvent false
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

private const val SEEK_END_MARGIN_MS = 1_000L

/** An empty cursor: AWT has no "none", so a 1×1 transparent image. Made when first needed (not headless). */
private val HiddenPointer: PointerIcon by lazy {
    if (GraphicsEnvironment.isHeadless()) {
        PointerIcon.Default
    } else {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
    }
}
