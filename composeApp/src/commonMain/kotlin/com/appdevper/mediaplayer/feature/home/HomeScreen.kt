package com.appdevper.mediaplayer.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.components.CenteredColumn
import com.appdevper.mediaplayer.designsystem.components.ComposerField
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.components.OutlineCard
import com.appdevper.mediaplayer.designsystem.components.PillButton
import com.appdevper.mediaplayer.designsystem.components.PillStyle
import com.appdevper.mediaplayer.designsystem.components.StatusDot
import com.appdevper.mediaplayer.designsystem.components.Thumbnail
import com.appdevper.mediaplayer.designsystem.components.interactive
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.feature.shell.AddServerState
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.platform.deviceLabel
import com.appdevper.mediaplayer.ui.icon
import com.appdevper.mediaplayer.ui.subtitle

/**
 * Landing page, what's used most first: the media servers to browse, where to play, and sharing this
 * device. No greeting: the servers are the way in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: ShellUiState,
    onOpenServer: (MediaDevice) -> Unit,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefresh: () -> Unit,
    onOpenMyServer: () -> Unit,
    onAddServer: (String) -> Unit = {},
    onDismissAddServer: () -> Unit = {},
    onForgetServer: (String) -> Unit = {},
) {
    val c = AppTheme.colors
    // imePadding: room for the keyboard, so what's under a focused field can scroll into view.
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        CenteredColumn(maxWidth = 720.dp) {
            Spacer(Modifier.height(8.dp))
            SectionHeader("Media servers") {
                // With none found, the card below has the search; here it would say it twice.
                // Searching: says so where the button was, so nothing jumps.
                if (state.servers.isNotEmpty() && state.searching) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(16.dp), strokeWidth = 2.dp, color = c.textSecondary)
                } else if (state.servers.isNotEmpty()) {
                    GhostIconButton(AppIcons.Refresh, "Search again", onRefresh, iconSize = 18.dp, tint = c.textSecondary)
                }
            }
            if (state.servers.isEmpty()) {
                SearchingCard(state.searching, onRefresh)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                    state.servers.forEach { d ->
                        // Several share a row; one alone is capped so it doesn't stretch across a wide window.
                        val width = if (state.servers.size > 1) Modifier.weight(1f) else Modifier.widthIn(max = 360.dp).fillMaxWidth()
                        OutlineCard(onClick = { onOpenServer(d) }, modifier = width.widthIn(min = 200.dp)) {
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

            AddServerByAddress(state.addServer, onAddServer, onDismissAddServer)
            SavedAddresses(state.savedAddresses, onForgetServer)

            SectionHeader("Play on")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetChip(AppIcons.Phone, "This device ($deviceLabel)", state.target == PlaybackTarget.Local) { onSelectTarget(PlaybackTarget.Local) }
                state.renderers.forEach { d ->
                    val selected = (state.target as? PlaybackTarget.Renderer)?.device?.id == d.id
                    TargetChip(AppIcons.Tv, d.name, selected) { onSelectTarget(PlaybackTarget.Renderer(d)) }
                }
            }

            SectionHeader("Share")
            OutlineCard(onClick = onOpenMyServer, modifier = Modifier.fillMaxWidth()) {
                val sharing = state.serverStatus is ServerStatus.Running
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
                    // Said, not only shown by the dot's color.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(sharing)
                        Text(if (sharing) "On" else "Off", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    }
                    Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** A section's title, with an action at its end when there's one. */
@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.textPrimary,
            modifier = Modifier.weight(1f).padding(start = 2.dp).semantics { heading() },
        )
        action()
    }
}

@Composable
private fun SearchingCard(searching: Boolean, onRefresh: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().border(1.dp, c.border, Radius.large).padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (searching) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.textSecondary)
        } else {
            Icon(AppIcons.Server, null, Modifier.size(20.dp), tint = c.textSecondary)
        }
        // The action goes under the text, so the text keeps the width on a phone.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (searching) "Looking for media servers…" else "No media servers found", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text("NAS boxes, PCs and phones running this app on the same Wi‑Fi show up here.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            if (!searching) {
                PillButton("Search again", onRefresh, style = PillStyle.Secondary, icon = AppIcons.Refresh, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/**
 * For servers discovery can't find (an iPhone without Apple's multicast entitlement, or a network
 * that blocks multicast): ask one by its address. Found servers join the list and are kept.
 */
@Composable
private fun AddServerByAddress(state: AddServerState, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state) {
        if (state is AddServerState.Added) {
            open = false
            address = ""
        }
    }
    val working = state is AddServerState.Working
    // Opening it is asking to type: the field takes focus (and the keyboard comes up).
    val field = remember { FocusRequester() }
    LaunchedEffect(open) { if (open) field.requestFocus() }
    // The answer below the field mustn't stay behind the keyboard.
    val message = remember { BringIntoViewRequester() }
    LaunchedEffect(state) { if (open && state is AddServerState.NotFound) message.bringIntoView() }
    LaunchedEffect(open) { if (open) message.bringIntoView() }
    fun submit() {
        if (address.isNotBlank() && !working) onAdd(address)
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!open) {
            Row(
                Modifier.heightIn(min = 48.dp).interactive({
                    onDismiss() // a fresh start: no earlier "Added" or "not found"
                    open = true
                }, shape = Radius.pill).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(AppIcons.Plus, null, Modifier.size(16.dp), tint = c.textSecondary)
                Text("Add a server by address", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
            }
            if (state is AddServerState.Added) {
                Text(
                    "Added ${state.names.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.success,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            return@Column
        }
        Text("Server address", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, modifier = Modifier.padding(top = 8.dp))
        // Full width for the address (up to 15 characters, or a URL); the actions go below it.
        ComposerField(
            value = address,
            onValueChange = {
                address = it
                if (state is AddServerState.NotFound) onDismiss()
            },
            placeholder = "192.168.1.20",
            leadingIcon = AppIcons.Server,
            label = "Server address",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.focusRequester(field),
        )
        val notFound = state as? AddServerState.NotFound
        Text(
            if (notFound != null) {
                "No media server answered at ${notFound.address}. Check the address, and that both are on the same Wi‑Fi."
            } else {
                "A PC, NAS or phone sharing media on this Wi‑Fi, for when it doesn't show up by itself: its IP address (like 192.168.1.20), or the URL of its description.xml."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (notFound != null) c.danger else c.textTertiary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(
            Modifier.padding(top = 4.dp).bringIntoViewRequester(message),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Same size while it works, so nothing jumps; it just says so.
            PillButton(if (working) "Adding…" else "Add", ::submit, enabled = address.isNotBlank() && !working, modifier = Modifier.widthIn(min = 104.dp))
            PillButton("Cancel", {
                open = false
                onDismiss()
            }, style = PillStyle.Secondary)
        }
    }
}

/** Addresses added by hand: they're asked on every search until removed here. */
@Composable
private fun SavedAddresses(addresses: List<String>, onForget: (String) -> Unit) {
    if (addresses.isEmpty()) return
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Added by address", style = MaterialTheme.typography.labelMedium, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp))
        addresses.forEach { address ->
            key(address) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.Server, null, Modifier.padding(start = 4.dp).size(16.dp), tint = c.textSecondary)
                    Text(address, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    GhostIconButton(AppIcons.Close, "Remove $address", { onForget(address) }, iconSize = 16.dp)
                }
            }
        }
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
