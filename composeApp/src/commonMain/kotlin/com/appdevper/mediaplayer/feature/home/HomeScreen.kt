package com.appdevper.mediaplayer.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.components.CenteredColumn
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.components.OutlineCard
import com.appdevper.mediaplayer.designsystem.components.PillButton
import com.appdevper.mediaplayer.designsystem.components.PillStyle
import com.appdevper.mediaplayer.designsystem.components.SectionLabel
import com.appdevper.mediaplayer.designsystem.components.StatusDot
import com.appdevper.mediaplayer.designsystem.components.Thumbnail
import com.appdevper.mediaplayer.designsystem.components.interactive
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.platform.deviceLabel
import com.appdevper.mediaplayer.ui.icon
import com.appdevper.mediaplayer.ui.subtitle

/** Landing page: greeting, servers as suggestion cards, "Play on" chips, share card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: ShellUiState,
    onOpenServer: (MediaDevice) -> Unit,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefresh: () -> Unit,
    onOpenMyServer: () -> Unit,
) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        CenteredColumn(maxWidth = 720.dp) {
            Spacer(Modifier.height(48.dp))
            Icon(AppIcons.Logo, null, Modifier.size(40.dp).align(Alignment.CenterHorizontally), tint = c.textPrimary)
            Spacer(Modifier.height(16.dp))
            Text(
                "What would you like to play?",
                style = MaterialTheme.typography.displaySmall,
                color = c.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Browse media servers on your Wi‑Fi, then play here or on a TV.",
                style = MaterialTheme.typography.bodyLarge,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))

            SectionLabel("Media servers")
            if (state.servers.isEmpty()) {
                SearchingCard(state.searching, onRefresh)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                    state.servers.forEach { d ->
                        OutlineCard(onClick = { onOpenServer(d) }, modifier = Modifier.weight(1f).widthIn(min = 200.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Thumbnail(d.iconUrl, d.icon(), size = 36.dp, shape = Radius.medium)
                                Column {
                                    Text(d.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(d.subtitle(), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            SectionLabel("Play on")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetChip(AppIcons.Phone, "This device ($deviceLabel)", state.target == PlaybackTarget.Local) { onSelectTarget(PlaybackTarget.Local) }
                state.renderers.forEach { d ->
                    val selected = (state.target as? PlaybackTarget.Renderer)?.device?.id == d.id
                    TargetChip(AppIcons.Tv, d.name, selected) { onSelectTarget(PlaybackTarget.Renderer(d)) }
                }
            }

            SectionLabel("Share")
            OutlineCard(onClick = onOpenMyServer, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(AppIcons.Radio, size = 40.dp, shape = Radius.pill)
                    Column(Modifier.weight(1f)) {
                        Text("Share this device", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(
                            "Let TVs and DLNA apps play your videos, music and photos",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary,
                        )
                    }
                    StatusDot(state.serverStatus is ServerStatus.Running)
                    Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
                }
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun SearchingCard(searching: Boolean, onRefresh: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().border(1.dp, c.border, Radius.large).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (searching) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.textSecondary)
        } else {
            Icon(AppIcons.Server, null, Modifier.size(20.dp), tint = c.textSecondary)
        }
        Column(Modifier.weight(1f)) {
            Text(if (searching) "Looking for media servers…" else "No media servers found", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text("NAS boxes, PCs and phones running this app on the same Wi‑Fi show up here.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        if (!searching) PillButton("Search again", onRefresh, style = PillStyle.Secondary, icon = AppIcons.Refresh)
    }
}

@Composable
private fun TargetChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .then(if (selected) Modifier.background(c.primary, Radius.pill) else Modifier.border(1.dp, c.border, Radius.pill))
            .interactive(onClick, shape = Radius.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (selected) c.onPrimary else c.textPrimary
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}
