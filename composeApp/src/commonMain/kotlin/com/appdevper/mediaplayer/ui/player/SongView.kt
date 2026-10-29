package com.appdevper.mediaplayer.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
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

/**
 * Said under the title: where it plays when that isn't here (on a TV, from a sender), or that it's
 * loading. [busy] shows a spinner; [live] shows it playing elsewhere (green), not stopped there.
 */
internal class SongSource(val text: String, val busy: Boolean = false, val live: Boolean = true)

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
    /** False: nothing to show a position of (a photo). */
    showPosition: Boolean = true,
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
            modifier = Modifier.widthIn(max = 520.dp).semantics { heading() },
        )
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        }
        // One line, always there: "Loading", where it plays, or what went wrong come and go (on every
        // load) without moving the position and the buttons under them.
        Box(Modifier.padding(top = 12.dp).heightIn(min = STATUS_HEIGHT), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.danger,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                source != null -> SourceLine(source)
            }
        }
        Spacer(Modifier.height(12.dp))
        // There before the duration is known (a sender says it later), so nothing moves when it is.
        if (showPosition) SeekBar(positionMs, durationMs, onSeek)
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
        Modifier.border(1.dp, c.border, Radius.pill).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (source.busy) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.textSecondary)
        } else {
            Icon(AppIcons.Cast, null, Modifier.size(14.dp), tint = if (source.live) c.success else c.textTertiary)
        }
        Text(source.text, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: ((Long) -> Unit)?) {
    val c = AppTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        // Only shown (a sender's music): a plain bar, not a slider that looks greyed out.
        if (onSeek == null) {
            LinearProgressIndicator(
                progress = { if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(4.dp),
                color = c.textPrimary,
                trackColor = c.trackInactive,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else ThinSlider(
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

/** The status line's height: one pill, so it never pushes what's under it. */
private val STATUS_HEIGHT = 30.dp

/** The artwork's size: big enough to recognise, small enough that the controls stay on a phone screen. */
private val ARTWORK = 240.dp
