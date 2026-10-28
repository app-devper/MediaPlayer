package com.appdevper.mediaplayer.platform

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import com.appdevper.mediaplayer.playback.ExoPlaybackEngine
import com.appdevper.mediaplayer.ui.player.PlayerControlBar
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * Shows the app's one player ([PlaybackSession.local] on [ExoPlaybackEngine]) and its controls. The
 * player follows the session by itself and keeps playing when this leaves the screen.
 */
@OptIn(UnstableApi::class)
@Composable
actual fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    modifier: Modifier,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
) {
    val local = koinInject<PlaybackSession>().local
    val player = koinInject<ExoPlaybackEngine>().player
    val status by local.status.collectAsStateWithLifecycle()
    val error by local.error.collectAsStateWithLifecycle()
    val state = status?.state
    val playing = state == TransportState.PLAYING || state == TransportState.TRANSITIONING
    val buffering = state == TransportState.TRANSITIONING
    fun togglePlay() {
        if (playing) local.pause() else local.play()
    }

    val c = AppTheme.colors
    val content = if (isAudio) c.textPrimary else Color.White
    var seeking by remember { mutableStateOf(false) }
    // Bumped by every tap or key, so the hide timer starts over.
    var touches by remember { mutableIntStateOf(0) }
    val controls: @Composable (Modifier) -> Unit = { barModifier ->
        PlayerControlBar(
            playing = playing,
            positionMs = status?.positionMs ?: 0,
            durationMs = status?.durationMs ?: 0,
            onPlayPause = {
                touches++
                togglePlay()
            },
            onSeek = local::seek,
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

    // Video: the bar floats over the picture and hides by itself while playing; a tap (or the TV remote's
    // OK) on the picture shows or hides it, any key shows it. Paused, loading or failed, it stays.
    var controlsVisible by remember { mutableStateOf(true) }
    val canHide = playing && !buffering && !seeking
    val pictureFocus = remember { FocusRequester() }
    var barHasFocus by remember { mutableStateOf(false) }
    // With a remote or keyboard (TV, or the first D-pad press), the picture takes focus so keys reach the
    // player: OK shows or hides the bar, media keys play and pause. Touch leaves focus alone.
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(inputMode) {
        if (inputMode == InputMode.Keyboard && !barHasFocus) pictureFocus.requestFocus()
    }
    LaunchedEffect(canHide, touches) {
        if (!canHide) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(CONTROLS_HIDE_MS)
        // A remote's focus on a bar button would be lost with the bar: it goes to the picture.
        if (barHasFocus) pictureFocus.requestFocus()
        controlsVisible = false
    }
    Box(
        modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                touches++
                when (e.key) {
                    Key.MediaPlayPause -> togglePlay()
                    Key.MediaPlay -> local.play()
                    Key.MediaPause -> local.pause()
                    // OK: the focused picture's click shows or hides the bar (or the focused button's runs).
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> return@onPreviewKeyEvent false
                    else -> {
                        controlsVisible = true
                        return@onPreviewKeyEvent false
                    }
                }
                controlsVisible = true
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            // The player plays on (its sound, without a picture) when this leaves the screen.
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Over the picture: taps go to Compose, not to the PlayerView.
        Box(
            Modifier.fillMaxSize()
                .focusRequester(pictureFocus)
                .clickable(interactionSource = null, indication = null, onClickLabel = "Show or hide the controls") {
                    touches++
                    controlsVisible = if (canHide) !controlsVisible else true
                },
        )
        when {
            error != null -> Text(error.orEmpty(), color = Color.White, modifier = Modifier.padding(16.dp))
            buffering -> CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp, color = Color.White)
            // Paused: a big play button in the middle, like other video players.
            !playing -> Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button, onClick = ::togglePlay),
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
                    .onFocusChanged { barHasFocus = it.hasFocus }
                    // Taps on the bar's empty parts don't reach the picture (which would hide the bar).
                    .pointerInput(Unit) { detectTapGestures { touches++ } }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 4.dp),
            )
        }
    }
}

private const val CONTROLS_HIDE_MS = 2_500L
