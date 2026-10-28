package com.appdevper.mediaplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.InputMode
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appdevper.mediaplayer.designsystem.components.ThinSlider
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.platform.AirPlayButton
import com.appdevper.mediaplayer.platform.VideoSurface
import com.appdevper.mediaplayer.platform.hidePointer
import com.appdevper.mediaplayer.platform.pointerIsMouse
import com.appdevper.mediaplayer.platform.supportsAirPlay
import com.appdevper.mediaplayer.platform.videoSurfaceHasControls
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlin.math.roundToInt

/**
 * The app's local player on screen, the same on every platform: the controls for a song; for a video
 * the picture ([VideoSurface]) with controls over it that hide while it plays. Only the picture is
 * platform code (and AVKit's own controls on iOS). The player plays on when this leaves the screen.
 *
 * With a mouse ([pointerIsMouse], desktop) a click on the picture plays or pauses, a double-click goes
 * full screen, the pointer hides with the controls, keys are shortcuts (Space, ← →, F, M) and the bar
 * has a volume control. With touch, a tap shows or hides the controls; a remote's OK does too.
 */
@Composable
internal fun LocalPlayerView(
    player: LocalPlayer,
    title: String,
    isAudio: Boolean,
    /** 0..100, and muted: the session's local volume. */
    volume: Int,
    muted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    if (!isAudio && videoSurfaceHasControls) {
        Box(modifier.fillMaxSize()) {
            VideoSurface(title, Modifier.fillMaxSize())
            // Above AVKit's own controls.
            SubtitleLine(player, fullscreen, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 72.dp))
        }
        return
    }
    val status by player.status.collectAsStateWithLifecycle()
    val error by player.error.collectAsStateWithLifecycle()
    val state = status?.state
    val playing = state == TransportState.PLAYING || state == TransportState.TRANSITIONING
    val buffering = state == TransportState.TRANSITIONING
    // Reads the player now, not this composition's `playing`: gesture handlers keep the function they started with.
    fun togglePlay() {
        val now = player.status.value?.state
        if (now == TransportState.PLAYING || now == TransportState.TRANSITIONING) player.pause() else player.play()
    }

    // M or the speaker icon: mute, and back to the volume it had (full, if it was dragged down to 0).
    val shownVolume = if (muted) 0 else volume
    fun toggleMute() {
        if (shownVolume > 0) {
            onMutedChange(true)
        } else {
            onMutedChange(false)
            if (volume == 0) onVolumeChange(100)
        }
    }

    val c = AppTheme.colors
    val content = if (isAudio) c.textPrimary else Color.White
    var seeking by remember { mutableStateOf(false) }
    val trailing: @Composable RowScope.() -> Unit = {
        if (pointerIsMouse) {
            VolumeControls(shownVolume, ::toggleMute, { onMutedChange(false); onVolumeChange(it) }, content)
        }
        // AVKit's controls had the AirPlay button; songs keep one on our bar.
        if (isAudio && supportsAirPlay) AirPlayButton(Modifier.size(40.dp), tint = content)
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
            PlayerControlBar(
                playing = playing,
                positionMs = status?.positionMs ?: 0,
                durationMs = status?.durationMs ?: 0,
                onPlayPause = ::togglePlay,
                onSeek = player::seek,
                content = content,
                onDraggingChange = { seeking = it },
                trailing = trailing,
            )
        }
        return
    }

    // Video: the bar floats over the picture and hides by itself while it plays. Pointer moves, taps and
    // keys go through a flow, not state, so they don't recompose the player; each starts the timer over.
    val activity = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    var controlsVisible by remember { mutableStateOf(true) }
    var overControls by remember { mutableStateOf(false) }
    // Keyboard and remotes: the picture takes focus so keys reach the player; when the bar hides with
    // the focus on one of its buttons, the focus goes back to the picture rather than being lost.
    var controlsHaveFocus by remember { mutableStateOf(false) }
    val pictureFocus = remember { FocusRequester() }
    val canHide = playing && !buffering && !seeking && !overControls
    LaunchedEffect(canHide) {
        controlsVisible = true
        if (!canHide) return@LaunchedEffect
        activity.onStart { emit(Unit) }.collectLatest {
            controlsVisible = true
            delay(CONTROLS_HIDE_MS)
            if (controlsHaveFocus) pictureFocus.requestFocus()
            controlsVisible = false
        }
    }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(inputMode) {
        if (inputMode == InputMode.Keyboard && !controlsHaveFocus) pictureFocus.requestFocus()
    }
    // ← / →: back or on by SEEK_STEP_MS. Not right onto the end: that would finish it, and a held key
    // would go on in the next item.
    fun seekBy(deltaMs: Long) {
        val position = status?.positionMs ?: return
        val duration = status?.durationMs ?: 0
        val to = (position + deltaMs).coerceAtLeast(0)
        player.seek(if (duration > 0) to.coerceAtMost((duration - SEEK_END_MARGIN_MS).coerceAtLeast(0)) else to)
    }

    val picture = if (pointerIsMouse) {
        Modifier
            .pointerInput(onToggleFullscreen) {
                detectTapGestures(
                    onTap = {
                        pictureFocus.requestFocus()
                        togglePlay()
                    },
                    onDoubleTap = onToggleFullscreen?.let { toggle -> { toggle() } },
                )
            }
            .focusRequester(pictureFocus)
            .focusable()
    } else {
        Modifier
            .focusRequester(pictureFocus)
            .clickable(interactionSource = null, indication = null, onClickLabel = "Show or hide the controls") {
                if (controlsVisible && canHide) controlsVisible = false else activity.tryEmit(Unit)
            }
    }
    Box(
        modifier.fillMaxSize().background(Color.Black)
            // With a mouse, any movement brings the controls back.
            .then(
                if (pointerIsMouse) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                                activity.tryEmit(Unit)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            // Any key brings them back; media keys play and pause (keyboards, remotes, headsets).
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // OK on a remote: the focused picture's click shows or hides the bar (or a focused button's runs).
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) return@onPreviewKeyEvent false
                activity.tryEmit(Unit)
                when (e.key) {
                    Key.MediaPlayPause -> togglePlay()
                    Key.MediaPlay -> player.play()
                    Key.MediaPause -> player.pause()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            // Shortcuts on the focused picture (a focused control takes its own keys first): Space
            // play/pause, ← → seek (held: repeats), F full screen, M mute.
            .onKeyEvent { e ->
                when {
                    !pointerIsMouse -> return@onKeyEvent false
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
            .hidePointer(!controlsVisible),
        contentAlignment = Alignment.Center,
    ) {
        VideoSurface(title, Modifier.fillMaxSize())
        // Over the picture: clicks and taps go to Compose, not to the platform view. It carries the title.
        Box(Modifier.fillMaxSize().semantics { contentDescription = title }.then(picture))
        when {
            error != null -> Text(error.orEmpty(), color = Color.White, modifier = Modifier.padding(16.dp))
            buffering -> CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp, color = Color.White)
            // Paused: a big play button in the middle, like other video players. With a mouse a click
            // anywhere on the picture plays, so it's only a picture there (the bar has "Play").
            !playing -> Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .then(if (pointerIsMouse) Modifier.clearAndSetSemantics {} else Modifier.clickable(role = Role.Button, onClick = ::togglePlay)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Play, if (pointerIsMouse) null else "Play", Modifier.size(30.dp), tint = Color.White)
            }
        }
        // Moves up out of the bar's way while it shows.
        SubtitleLine(
            player, fullscreen,
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = if (controlsVisible) 80.dp else 24.dp),
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerControlBar(
                playing = playing,
                positionMs = status?.positionMs ?: 0,
                durationMs = status?.durationMs ?: 0,
                onPlayPause = ::togglePlay,
                onSeek = player::seek,
                content = content,
                modifier = Modifier
                    .onFocusChanged { controlsHaveFocus = it.hasFocus }
                    // Stays up while the pointer is over it.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                when (awaitPointerEvent().type) {
                                    PointerEventType.Enter -> overControls = pointerIsMouse
                                    PointerEventType.Exit -> overControls = false
                                }
                            }
                        }
                    }
                    // Taps on the bar's empty parts don't reach the picture; they keep the bar up.
                    .pointerInput(Unit) { detectTapGestures { activity.tryEmit(Unit) } }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 4.dp),
                onDraggingChange = {
                    seeking = it
                    activity.tryEmit(Unit)
                },
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                trailing = trailing,
            )
        }
    }
}

/**
 * The subtitle line on screen now (the player works it out, from a file or a track in the video),
 * white on a dark band so it reads on any picture. Only this reads it, so the rest isn't recomposed.
 */
@Composable
private fun SubtitleLine(player: LocalPlayer, large: Boolean, modifier: Modifier) {
    val line = player.subtitleText.collectAsStateWithLifecycle().value ?: return
    Text(
        line,
        color = Color.White,
        fontSize = if (large) 26.sp else 17.sp,
        lineHeight = if (large) 32.sp else 22.sp,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge.copy(shadow = Shadow(Color.Black, Offset(0f, 1f), 4f)),
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Mute button and volume slider (desktop's bar; phones have volume keys). */
@Composable
private fun VolumeControls(volume: Int, onToggleMute: () -> Unit, onVolumeChange: (Int) -> Unit, content: Color) {
    IconButton(onClick = onToggleMute) {
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
        onValueChange = { onVolumeChange(it.roundToInt()) },
        valueRange = 0f..100f,
        activeColor = content,
        inactiveColor = content.copy(alpha = 0.3f),
        modifier = Modifier.width(110.dp),
    )
}

private const val CONTROLS_HIDE_MS = 2_500L
private const val SEEK_STEP_MS = 5_000L
private const val SEEK_END_MARGIN_MS = 1_000L
