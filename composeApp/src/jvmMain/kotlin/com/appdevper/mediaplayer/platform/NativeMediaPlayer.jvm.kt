package com.appdevper.mediaplayer.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.ui.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

/** Desktop player on FFmpeg ([FfmpegPlayer]): plays from DLNA servers, http(s) URLs and HLS. */
@Composable
actual fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier,
    autoPlay: Boolean,
    volume: Float,
    commands: Flow<LocalPlayerCommand>,
    onStatus: (RendererStatus) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
) {
    val player = remember(url) { FfmpegPlayer(url, autoPlay) }
    val status by player.status.collectAsStateWithLifecycle()
    val frame by player.frames.collectAsStateWithLifecycle()
    val ended by rememberUpdatedState(onEnded)
    val report by rememberUpdatedState(onStatus)
    // "Stopped" = loaded but not started, finished, or stopped by a controller; reported as STOPPED rather than PAUSED.
    var stopped by remember(url) { mutableStateOf(!autoPlay) }
    var seeking by remember { mutableStateOf<Float?>(null) }
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

    val onVideoSlider = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
    )
    Column(modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (onToggleFullscreen != null) Modifier.pointerInput(onToggleFullscreen) { detectTapGestures(onDoubleTap = { onToggleFullscreen() }) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (!isAudio && f != null) {
                val bitmap = remember(f) {
                    SkiaImage.makeRaster(ImageInfo(f.width, f.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), f.bgra, f.width * 4)
                        .toComposeImageBitmap()
                }
                Image(bitmap, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            when {
                status.error != null -> Text(status.error.orEmpty(), color = Color.White, modifier = Modifier.padding(16.dp))
                status.buffering && status.playing -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = Color.White)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (status.playing) {
                    player.pause()
                } else {
                    stopped = false
                    player.play()
                }
            }) {
                Icon(if (status.playing) AppIcons.Pause else AppIcons.Play, "Play/Pause", tint = Color.White)
            }
            Text(formatClock(status.positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = seeking ?: if (status.durationMs > 0) status.positionMs.toFloat() / status.durationMs else 0f,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { player.seek((it * status.durationMs).toLong()) }
                    seeking = null
                },
                enabled = status.durationMs > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = onVideoSlider,
            )
            Text(formatClock(status.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Icon(AppIcons.Volume, null, tint = Color.White, modifier = Modifier.padding(start = 8.dp))
            Slider(
                value = sliderVolume,
                onValueChange = {
                    sliderVolume = it
                    player.setVolume(it / 100f)
                },
                valueRange = 0f..100f,
                modifier = Modifier.padding(start = 4.dp).width(120.dp),
                colors = onVideoSlider,
            )
            if (onToggleFullscreen != null) {
                IconButton(onClick = onToggleFullscreen) {
                    Icon(if (fullscreen) AppIcons.Minimize else AppIcons.Maximize, if (fullscreen) "Exit full screen" else "Full screen", tint = Color.White)
                }
            }
        }
    }
}
