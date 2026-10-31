package app.devper.appplayer.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.SubcomposeAsyncImage
import app.devper.appplayer.designsystem.components.IconTile
import app.devper.appplayer.designsystem.icons.AppIcons
import app.devper.appplayer.designsystem.theme.AppTheme
import app.devper.appplayer.designsystem.theme.Radius
import app.devper.appplayer.domain.model.AirPlayNowPlaying
import app.devper.appplayer.domain.model.NowPlaying
import app.devper.appplayer.ui.player.SongSource
import app.devper.appplayer.ui.player.SongView
import app.devper.appplayer.ui.joinDetails
import kotlinx.coroutines.delay

/** What an iPhone/iPad/Mac plays through AirPlay audio (Music, YouTube Music, Spotify…): shown like our own songs. */
@Composable
internal fun AirPlayAudio(
    audio: NowPlaying.SenderAudio,
    onPrevious: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val song = audio.song ?: AirPlayNowPlaying()
    // The sender reports progress now and then; in between it advances with the clock.
    var position by remember { mutableLongStateOf(song.positionNow()) }
    LaunchedEffect(song) {
        if (song.durationMs <= 0) return@LaunchedEffect // no position to show
        while (true) {
            position = song.positionNow()
            delay(500)
        }
    }
    val sender = audio.sender ?: "AirPlay"
    // Sent to the sender's app (it plays the music; we only hear it). It doesn't tell us whether it's
    // paused, so the icon follows the taps (here, the mini player, the lock screen).
    SongView(
        artwork = { AirPlayArtwork(song.artwork, size = it, shape = Radius.xLarge) },
        title = song.title ?: "AirPlay audio",
        detail = joinDetails(song.artist, song.album),
        source = SongSource("From $sender"),
        error = null,
        positionMs = position,
        durationMs = song.durationMs,
        onSeek = null,
        playing = audio.playing,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        noControlsHint = "Use the controls on ${audio.sender ?: "the sending device"} to pause or skip.",
    )
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
