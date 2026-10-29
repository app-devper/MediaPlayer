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
import com.appdevper.mediaplayer.domain.model.NowPlaying
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.feature.player.AirPlayArtwork
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.ui.icon

/** What [this] is called in the mini player and the sidebar. */
internal val NowPlaying.title: String?
    get() = when (this) {
        NowPlaying.Nothing -> null
        is NowPlaying.Item -> entry.title
        is NowPlaying.SenderAudio -> song?.title ?: "AirPlay audio"
    }

@Composable
internal fun MiniPlayer(state: ShellUiState, onOpen: () -> Unit, onTogglePause: () -> Unit) {
    val c = AppTheme.colors
    val now = state.nowPlaying
    if (now == NowPlaying.Nothing) return
    val remote = ((now as? NowPlaying.Item)?.target as? PlaybackTarget.Renderer)
    val playing = now.playing
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
            when (now) {
                is NowPlaying.SenderAudio -> AirPlayArtwork(now.song?.artwork, size = 40.dp, shape = Radius.medium)
                is NowPlaying.Item -> Thumbnail(now.entry.thumbnailUrl, now.entry.kind.icon(), size = 40.dp, shape = Radius.medium)
                NowPlaying.Nothing -> Unit
            }
            Column(Modifier.weight(1f)) {
                Text(
                    now.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        now is NowPlaying.SenderAudio -> listOfNotNull(now.song?.artist, "from ${now.sender ?: "AirPlay"}").joinToString(" · ")
                        remote != null -> "Playing on ${remote.device.name}"
                        playing != null -> "On this device"
                        else -> "Tap to open the player"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remote != null || now is NowPlaying.SenderAudio) c.success else c.textTertiary,
                    maxLines = 1,
                )
            }
            if (playing != null) {
                GhostIconButton(if (playing) AppIcons.Pause else AppIcons.Play, "Play/Pause", onTogglePause, tint = c.textPrimary)
            } else {
                GhostIconButton(AppIcons.ChevronRight, "Open player", onOpen)
            }
        }
    }
}
