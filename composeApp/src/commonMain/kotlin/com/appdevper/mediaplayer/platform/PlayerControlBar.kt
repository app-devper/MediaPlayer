package com.appdevper.mediaplayer.platform

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.components.ThinSlider
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.ui.formatClock

/**
 * The control bar of our own players (desktop, Android): the song screens' seek bar (thin track,
 * round thumb), play/pause and the time; then [trailing] (e.g. volume) and full screen.
 * [content] colors everything on it: white over video, the text color on the song page.
 */
@Composable
internal fun PlayerControlBar(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (positionMs: Long) -> Unit,
    content: Color,
    modifier: Modifier = Modifier,
    /** The seek bar is being dragged (e.g. so the bar doesn't hide meanwhile). */
    onDraggingChange: (Boolean) -> Unit = {},
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    val dragging by rememberUpdatedState(onDraggingChange)
    Column(modifier.fillMaxWidth()) {
        ThinSlider(
            value = seeking ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                if (seeking == null) dragging(true)
                seeking = it
            },
            onValueChangeFinished = {
                seeking?.let { onSeek((it * durationMs).toLong()) }
                seeking = null
                dragging(false)
            },
            enabled = durationMs > 0,
            activeColor = content,
            inactiveColor = content.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth().height(20.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPause) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, if (playing) "Pause" else "Play", tint = content)
            }
            val position = seeking?.let { (it * durationMs).toLong() } ?: positionMs
            Text(
                // Unknown duration (live stream): just the position.
                if (durationMs > 0) "${formatClock(position)} / ${formatClock(durationMs)}" else formatClock(position),
                color = content.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            trailing()
            if (onToggleFullscreen != null) {
                IconButton(onClick = onToggleFullscreen) {
                    Icon(if (fullscreen) AppIcons.Minimize else AppIcons.Maximize, if (fullscreen) "Exit full screen" else "Full screen", tint = content)
                }
            }
        }
    }
}
