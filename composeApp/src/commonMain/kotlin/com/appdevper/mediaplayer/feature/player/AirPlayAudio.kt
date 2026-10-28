package com.appdevper.mediaplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.appdevper.mediaplayer.designsystem.components.CircleButton
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.AirPlayNowPlaying
import com.appdevper.mediaplayer.domain.model.NowPlaying
import com.appdevper.mediaplayer.ui.formatClock
import com.appdevper.mediaplayer.ui.joinDetails
import kotlinx.coroutines.delay

/** What an iPhone/iPad/Mac plays through AirPlay audio (Music, YouTube Music, Spotify…): shown like our own songs. */
@Composable
internal fun AirPlayAudio(
    audio: NowPlaying.SenderAudio,
    onPrevious: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val c = AppTheme.colors
    val song = audio.song ?: AirPlayNowPlaying()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AirPlayArtwork(song.artwork, size = 260.dp, shape = Radius.xLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            song.title ?: "AirPlay audio",
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        joinDetails(song.artist, song.album)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
        }
        Row(
            Modifier.border(1.dp, c.border, Radius.pill).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(AppIcons.Cast, null, Modifier.size(14.dp), tint = c.success)
            Text("Playing from ${audio.sender ?: "AirPlay"}", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        }
        if (song.durationMs > 0) {
            // The sender reports progress now and then; in between it advances with the clock.
            var position by remember { mutableLongStateOf(song.positionNow()) }
            LaunchedEffect(song) {
                while (true) {
                    position = song.positionNow()
                    delay(500)
                }
            }
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 8.dp)) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / song.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = c.textPrimary,
                    trackColor = c.surfaceSecondary,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(position), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(formatClock(song.durationMs), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }
        }
        if (audio.controllable) {
            // Sent to the sender's app (it plays the music; we only hear it). The sender doesn't tell us
            // whether it's paused, so the icon follows the taps (here, the mini player, the lock screen).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GhostIconButton(AppIcons.SkipPrevious, "Previous", onPrevious, size = 44.dp, iconSize = 24.dp, tint = c.textPrimary)
                CircleButton(
                    if (audio.paused) AppIcons.Play else AppIcons.Pause,
                    if (audio.paused) "Play" else "Pause",
                    onPlayPause,
                    size = 64.dp,
                )
                GhostIconButton(AppIcons.SkipNext, "Next", onNext, size = 44.dp, iconSize = 24.dp, tint = c.textPrimary)
            }
        } else {
            Text(
                "Use the controls on ${audio.sender ?: "the sending device"} to pause or skip.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Cover art sent by the AirPlay sender, or a music icon when there's none. */
@Composable
internal fun AirPlayArtwork(artwork: ByteArray?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    if (artwork == null) {
        IconTile(AppIcons.Music, modifier, size, shape, tint = AppTheme.colors.textTertiary)
        return
    }
    SubcomposeAsyncImage(
        model = artwork,
        contentDescription = "Album art",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(shape).background(AppTheme.colors.surfaceSecondary),
        error = { IconTile(AppIcons.Music, size = size, shape = shape, tint = AppTheme.colors.textTertiary) },
    )
}
