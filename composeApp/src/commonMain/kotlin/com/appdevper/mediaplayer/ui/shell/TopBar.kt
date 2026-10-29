package com.appdevper.mediaplayer.ui.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.components.ListRow
import com.appdevper.mediaplayer.designsystem.components.interactive
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.platform.AirPlayButton
import com.appdevper.mediaplayer.platform.deviceLabel
import com.appdevper.mediaplayer.platform.supportsAirPlay
import com.appdevper.mediaplayer.ui.subtitle

@Composable
internal fun TopBar(
    state: ShellUiState,
    showSidebarButton: Boolean,
    onSidebar: () -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onSearchAgain: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showSidebarButton) GhostIconButton(AppIcons.Sidebar, "Open sidebar", onSidebar)
        if (canGoBack) GhostIconButton(AppIcons.Back, "Back", onBack)
        TargetPicker(state, onSelectTarget, onSearchAgain)
        Spacer(Modifier.weight(1f))
        if (supportsAirPlay) AirPlayButton(Modifier.size(40.dp))
    }
}

@Composable
private fun TargetPicker(state: ShellUiState, onSelect: (PlaybackTarget) -> Unit, onSearchAgain: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = AppTheme.colors
    val remote = state.target as? PlaybackTarget.Renderer
    Box {
        // Always says where things play: this device, or the TV (green, as anything playing elsewhere).
        Row(
            Modifier.interactive({ open = true }, shape = Radius.medium)
                .semantics(mergeDescendants = true) { contentDescription = "Play on ${remote?.device?.name ?: "this device"}, change" }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (remote != null) {
                Icon(AppIcons.Cast, null, Modifier.size(18.dp), tint = c.success)
            } else {
                Icon(AppIcons.Phone, null, Modifier.size(18.dp), tint = c.textSecondary)
            }
            Text(
                remote?.device?.name ?: "This device",
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Icon(AppIcons.ChevronDown, null, Modifier.size(16.dp), tint = c.textTertiary)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = Radius.large,
            containerColor = c.surface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.width(320.dp).border(1.dp, c.border, Radius.large),
        ) {
            Text("Play on", style = MaterialTheme.typography.labelMedium, color = c.textTertiary, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
            PickerItem(
                icon = AppIcons.Phone,
                title = "This device",
                subtitle = if (supportsAirPlay) "$deviceLabel · AirPlay via the button on the right" else deviceLabel,
                selected = remote == null,
            ) { onSelect(PlaybackTarget.Local); open = false }
            state.renderers.forEach { d ->
                PickerItem(AppIcons.Tv, d.name, d.subtitle(), selected = remote?.device?.id == d.id) {
                    onSelect(PlaybackTarget.Renderer(d)); open = false
                }
            }
            if (state.renderers.isEmpty()) {
                Text(
                    "No TVs or speakers found yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = c.border)
            PickerItem(AppIcons.Refresh, if (state.searching) "Searching…" else "Search again", null, selected = false) { onSearchAgain() }
        }
    }
}

@Composable
private fun PickerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 6.dp),
        leading = { IconTile(icon, size = 34.dp, shape = Radius.small) },
        trailing = { if (selected) Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = AppTheme.colors.textPrimary) },
    )
}
