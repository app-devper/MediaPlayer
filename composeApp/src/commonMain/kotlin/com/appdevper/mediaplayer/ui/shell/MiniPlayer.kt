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
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.ui.components.GhostIconButton
import com.appdevper.mediaplayer.ui.components.Thumbnail
import com.appdevper.mediaplayer.ui.components.icon
import com.appdevper.mediaplayer.ui.components.interactive
import com.appdevper.mediaplayer.ui.icons.AppIcons
import com.appdevper.mediaplayer.ui.theme.AppTheme
import com.appdevper.mediaplayer.ui.theme.Radius

@Composable
internal fun MiniPlayer(state: ShellUiState, onOpen: () -> Unit, onTogglePause: () -> Unit) {
    val item = state.nowPlaying ?: return
    val c = AppTheme.colors
    val remote = state.target as? PlaybackTarget.Renderer
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
            Thumbnail(item.thumbnailUrl, item.kind.icon(), size = 40.dp, shape = Radius.medium)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    remote?.let { "Playing on ${it.device.name}" } ?: "Tap to open the player",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remote != null) c.success else c.textTertiary,
                    maxLines = 1,
                )
            }
            if (remote != null) {
                GhostIconButton(if (state.remotePlaying) AppIcons.Pause else AppIcons.Play, "Play/Pause", onTogglePause, tint = c.textPrimary)
            } else {
                GhostIconButton(AppIcons.ChevronRight, "Open player", onOpen)
            }
        }
    }
}
