package com.appdevper.mediaplayer.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.components.CircleButton
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.ThinSlider
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.ui.formatClock

/** Where a song plays when it isn't this device: on a TV, or from a sender. Said under the title. */
internal class SongSource(val text: String, val busy: Boolean = false)

/**
 * A song (or anything without a picture here), wherever it plays: on this device, on a TV, or from
 * an AirPlay sender. One layout, so it's learnt once: the artwork, what it is, where it plays, the
 * position, and one big play button between previous and next. [below] holds what differs (shuffle
 * and repeat, volume, "play here", up next).
 */
@Composable
internal fun SongView(
    artwork: @Composable (size: Dp) -> Unit,
    title: String,
    detail: String?,
    source: SongSource?,
    error: String?,
    positionMs: Long,
    durationMs: Long,
    /** Null: the position is shown but can't be moved (a sender's music). */
    onSeek: ((Long) -> Unit)?,
    /** Null: no play button (nothing to play, or a sender that takes no commands). */
    playing: Boolean?,
    onPlayPause: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    nextEnabled: Boolean = true,
    onStop: (() -> Unit)? = null,
    /** Shown instead of the buttons when there are none. */
    noControlsHint: String? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        artwork(ARTWORK)
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        }
        source?.let { SourceLine(it) }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = c.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(20.dp))
        if (durationMs > 0 || onSeek != null) SeekBar(positionMs, durationMs, onSeek)
        Spacer(Modifier.height(8.dp))
        if (playing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // Stop sits outside the three, so the play button stays in the middle.
                if (onStop != null) GhostIconButton(AppIcons.Stop, "Stop", onStop, size = 44.dp, iconSize = 20.dp, tint = c.textSecondary) else Spacer(Modifier.size(44.dp))
                GhostIconButton(AppIcons.SkipPrevious, "Previous", { onPrevious?.invoke() }, enabled = onPrevious != null, size = 48.dp, iconSize = 26.dp, tint = c.textPrimary)
                CircleButton(if (playing) AppIcons.Pause else AppIcons.Play, if (playing) "Pause" else "Play", onPlayPause, size = 68.dp)
                GhostIconButton(AppIcons.SkipNext, "Next", { onNext?.invoke() }, enabled = onNext != null && nextEnabled, size = 48.dp, iconSize = 26.dp, tint = c.textPrimary)
                Spacer(Modifier.size(44.dp))
            }
        } else if (noControlsHint != null) {
            Text(noControlsHint, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, textAlign = TextAlign.Center)
        }
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, content = below)
        Spacer(Modifier.height(24.dp))
    }
}

/** On a TV, from a sender: green, as everywhere the app shows something playing elsewhere. */
@Composable
private fun SourceLine(source: SongSource) {
    val c = AppTheme.colors
    Row(
        Modifier.padding(top = 12.dp).border(1.dp, c.border, Radius.pill).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (source.busy) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.textSecondary)
        } else {
            Icon(AppIcons.Cast, null, Modifier.size(14.dp), tint = c.success)
        }
        Text(source.text, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: ((Long) -> Unit)?) {
    val c = AppTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        ThinSlider(
            value = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek?.invoke((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = onSeek != null && durationMs > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragging?.let { (it * durationMs).toLong() } ?: positionMs
            Text(formatClock(shown), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            Text(if (durationMs > 0) formatClock(durationMs) else "", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
    }
}

/** The artwork's size: big enough to recognise, small enough that the controls stay on a phone screen. */
private val ARTWORK = 240.dp
