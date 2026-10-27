package com.appdevper.mediaplayer.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.Thumbnail
import com.appdevper.mediaplayer.designsystem.components.interactive
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.feature.player.AirPlayArtwork
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.ui.icon

@Composable
internal fun MiniPlayer(state: ShellUiState, onOpen: () -> Unit, onTogglePause: () -> Unit, onToggleLocal: () -> Unit) {
    val c = AppTheme.colors
    val airPlay = state.airPlay.takeIf { it.audioOnly }
    val item = state.nowPlaying
    if (airPlay == null && item == null) return
    val remote = (state.target as? PlaybackTarget.Renderer).takeIf { airPlay == null }
    val localPlaying = state.localPlaying.takeIf { airPlay == null && remote == null }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .border(1.dp, c.border, Radius.xLarge)
                .background(c.surface, Radius.xLarge)
                .interactive(onOpen, shape = Radius.xLarge)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (airPlay != null) {
                AirPlayArtwork(airPlay.nowPlaying?.artwork, size = 40.dp, shape = Radius.medium)
            } else if (item != null) {
                Thumbnail(item.thumbnailUrl, item.kind.icon(), size = 40.dp, shape = Radius.medium)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    airPlay?.let { it.nowPlaying?.title ?: "AirPlay audio" } ?: item?.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        airPlay != null -> listOfNotNull(airPlay.nowPlaying?.artist, "from ${airPlay.client ?: "AirPlay"}").joinToString(" · ")
                        remote != null -> "Playing on ${remote.device.name}"
                        localPlaying != null -> "On this device"
                        else -> "Tap to open the player"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remote != null || airPlay != null) c.success else c.textTertiary,
                    maxLines = 1,
                )
            }
            if (remote != null) {
                GhostIconButton(if (state.remotePlaying) AppIcons.Pause else AppIcons.Play, "Play/Pause", onTogglePause, tint = c.textPrimary)
            } else if (localPlaying != null) {
                GhostIconButton(if (localPlaying) AppIcons.Pause else AppIcons.Play, "Play/Pause", onToggleLocal, tint = c.textPrimary)
            } else {
                GhostIconButton(AppIcons.ChevronRight, "Open player", onOpen)
            }
        }
    }
}
