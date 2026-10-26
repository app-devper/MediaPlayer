package com.appdevper.mediaplayer.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.components.ListRow
import com.appdevper.mediaplayer.designsystem.components.SectionLabel
import com.appdevper.mediaplayer.designsystem.components.StatusDot
import com.appdevper.mediaplayer.designsystem.components.Thumbnail
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.ui.icon

@Composable
internal fun Sidebar(
    state: ShellUiState,
    selection: Selection,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDevice: (MediaDevice) -> Unit,
    onSelectRenderer: (MediaDevice) -> Unit,
    onNowPlaying: () -> Unit,
    onMyServer: () -> Unit,
    onAirPlay: () -> Unit,
    onCollapse: () -> Unit,
) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Logo, null, Modifier.size(26.dp), tint = c.textPrimary)
            Spacer(Modifier.width(10.dp))
            Text("Media Player", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            GhostIconButton(AppIcons.Sidebar, "Close sidebar", onCollapse)
        }
        ListRow("Home", selected = selection == Selection.Home, onClick = onHome, leading = { SidebarIcon(AppIcons.Home) })
        ListRow(
            "Search network",
            onClick = onRefresh,
            leading = { SidebarIcon(AppIcons.Refresh) },
            trailing = {
                if (state.searching) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.textTertiary)
            },
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { SectionLabel("Media servers") }
            items(state.servers, key = { "s" + it.id }) { d ->
                ListRow(
                    d.name,
                    selected = selection == Selection.Device(d.id),
                    onClick = { onOpenDevice(d) },
                    leading = { Thumbnail(d.iconUrl, d.icon(), size = 24.dp, shape = Radius.small) },
                )
            }
            if (state.servers.isEmpty()) item { SidebarHint(if (state.searching) "Looking for servers…" else "No servers found") }

            item { SectionLabel("TVs & speakers") }
            items(state.renderers, key = { "r" + it.id }) { d ->
                val active = (state.target as? PlaybackTarget.Renderer)?.device?.id == d.id
                ListRow(
                    d.name,
                    onClick = { onSelectRenderer(d) },
                    leading = { Thumbnail(d.iconUrl, AppIcons.Tv, size = 24.dp, shape = Radius.small) },
                    trailing = { if (active) Icon(AppIcons.Cast, "Playing here", Modifier.size(16.dp), tint = c.success) },
                )
            }
            if (state.renderers.isEmpty()) item { SidebarHint("None found yet") }
        }

        if (state.hasNowPlaying) {
            ListRow(
                "Now playing",
                subtitle = if (state.airPlay.audioOnly) state.airPlay.nowPlaying?.title ?: "AirPlay from ${state.airPlay.client}" else state.nowPlaying?.title,
                selected = selection == Selection.NowPlaying,
                onClick = onNowPlaying,
                leading = { SidebarIcon(AppIcons.ListMusic) },
            )
        }
        state.airPlay.client?.takeIf { state.airPlay.mirroring }?.let { client ->
            ListRow(
                "AirPlay",
                subtitle = "Mirroring $client",
                selected = selection == Selection.Mirror,
                onClick = onAirPlay,
                leading = { SidebarIcon(AppIcons.Cast) },
                trailing = { StatusDot(true) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.border)
        val status = state.serverStatus
        ListRow(
            "My Server",
            subtitle = when (status) {
                is ServerStatus.Running -> "Sharing on the network"
                ServerStatus.Starting -> "Starting…"
                is ServerStatus.Failed -> "Couldn't start"
                ServerStatus.Stopped -> "Not sharing"
            },
            selected = selection == Selection.MyServer,
            onClick = onMyServer,
            leading = { IconTile(AppIcons.Radio, size = 32.dp, shape = Radius.pill) },
            trailing = { StatusDot(status is ServerStatus.Running) },
        )
    }
}

@Composable
private fun SidebarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) =
    Icon(icon, null, Modifier.size(20.dp), tint = AppTheme.colors.textPrimary)

@Composable
private fun SidebarHint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textTertiary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
