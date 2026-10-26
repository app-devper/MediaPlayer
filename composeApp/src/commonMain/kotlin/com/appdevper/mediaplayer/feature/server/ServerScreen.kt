package com.appdevper.mediaplayer.feature.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appdevper.mediaplayer.designsystem.components.AppSwitch
import com.appdevper.mediaplayer.designsystem.components.CenteredColumn
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.components.PillButton
import com.appdevper.mediaplayer.designsystem.components.PillStyle
import com.appdevper.mediaplayer.designsystem.components.StatusDot
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.usecase.countOf
import com.appdevper.mediaplayer.platform.rememberMediaPermissionRequest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ServerScreen() {
    val vm = koinViewModel<ServerViewModel>()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val permission = rememberMediaPermissionRequest()
    val c = AppTheme.colors
    val settings = state.settings

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        CenteredColumn(maxWidth = 680.dp) {
            Spacer(Modifier.height(8.dp))
            Text("My Server", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text(
                "Share this device's media with TVs, consoles and DLNA apps on your Wi‑Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(20.dp))
            StatusCard(state, onStart = { permission.request(vm::start) }, onStop = vm::stop)

            Group("General") {
                SettingRow("Server name") { NameField(settings.name, vm::rename) }
                Divider()
                SwitchRow("Start sharing when the app opens", settings.autoStart, vm::setAutoStart)
            }
            Group("Receive") {
                SwitchRow("Let other devices play here", settings.receiverEnabled, vm::setReceiverEnabled, AppIcons.Cast)
                ReceiverHint(state.receiver, enabled = settings.receiverEnabled, name = settings.name)
                if (state.airPlay != AirPlayStatus.Unsupported) {
                    Divider()
                    SwitchRow("AirPlay from iPhone, iPad and Mac", settings.airPlayEnabled, vm::setAirPlayEnabled, AppIcons.Tv)
                    AirPlayHint(state.airPlay, enabled = settings.airPlayEnabled, name = settings.name)
                    if (settings.airPlayEnabled) {
                        // An AirPlay option: indented under it, after the AirPlay hint it would otherwise split.
                        SwitchRow("Ask for a code on first connection", settings.airPlayRequirePin, vm::setAirPlayRequirePin, indent = true)
                    }
                }
            }
            Group("Share") {
                SwitchRow("Videos", settings.shareVideo, vm::setShareVideo, AppIcons.Film)
                Divider()
                SwitchRow("Music", settings.shareAudio, vm::setShareAudio, AppIcons.Music)
                Divider()
                SwitchRow("Photos", settings.shareImage, vm::setShareImage, AppIcons.Image)
            }
            if (state.capabilities.canChooseFolders) {
                Group("Folders") {
                    settings.folders.forEachIndexed { i, f ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Folder, null, Modifier.size(18.dp), tint = c.textSecondary)
                            Text(f, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                            GhostIconButton(AppIcons.Trash, "Remove", { vm.removeFolder(f) }, size = 32.dp, iconSize = 18.dp)
                        }
                    }
                    if (settings.folders.isNotEmpty()) Divider()
                    AddFolderRow(vm::addFolder)
                }
            }
            Group("About") {
                SettingRow("Shares") { Text(state.capabilities.sourceDescription, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.widthIn(max = 260.dp)) }
                Divider()
                SettingRow("Version") { Text("2.0.0", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary) }
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun StatusCard(state: ServerUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val c = AppTheme.colors
    val status = state.status
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.border, Radius.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(AppIcons.Radio, size = 44.dp, shape = Radius.pill, tint = if (status is ServerStatus.Running) c.success else c.textPrimary)
            Column(Modifier.weight(1f)) {
                Text(state.settings.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(status is ServerStatus.Running)
                    Text(
                        when (status) {
                            ServerStatus.Stopped -> "Not sharing"
                            ServerStatus.Starting -> "Starting…"
                            is ServerStatus.Running -> "Visible on your network"
                            is ServerStatus.Failed -> "Couldn't start"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
            }
            when (status) {
                ServerStatus.Starting -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.textSecondary)
                is ServerStatus.Running -> PillButton("Stop", onStop, style = PillStyle.Secondary)
                else -> PillButton("Start sharing", onStart, icon = AppIcons.Radio)
            }
        }
        if (status is ServerStatus.Running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountChip(AppIcons.Film, status.counts.countOf(MediaKind.VIDEO), "videos")
                CountChip(AppIcons.Music, status.counts.countOf(MediaKind.AUDIO), "songs")
                CountChip(AppIcons.Image, status.counts.countOf(MediaKind.IMAGE), "photos")
            }
            Text(status.url, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        if (status is ServerStatus.Failed) Text(status.message, style = MaterialTheme.typography.bodySmall, color = c.danger)
        if (state.permissionDenied) {
            Text("Media access was denied, so there's nothing to share. Allow it in system settings.", style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

@Composable
private fun ReceiverHint(status: ReceiverStatus, enabled: Boolean, name: String) {
    val c = AppTheme.colors
    val (text, color) = when {
        status is ReceiverStatus.Failed -> "Couldn't start: ${status.message}" to c.danger
        status is ReceiverStatus.Running -> "Shows up as “$name” in the Play to / Cast menu of DLNA apps on your Wi‑Fi." to c.textTertiary
        enabled -> "Starting…" to c.textTertiary
        else -> "Phones and computers on your Wi‑Fi can send videos, music and photos to this device." to c.textTertiary
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 30.dp, bottom = 10.dp))
}

@Composable
private fun AirPlayHint(status: AirPlayStatus, enabled: Boolean, name: String) {
    val c = AppTheme.colors
    val (text, color) = when {
        status is AirPlayStatus.Failed -> "Couldn't start: ${status.message}" to c.danger
        status is AirPlayStatus.Running -> "Shows up as “$name” in Screen Mirroring and AirPlay on Apple devices on your Wi‑Fi." to c.textTertiary
        enabled -> "Starting…" to c.textTertiary
        else -> "Mirror an iPhone, iPad or Mac screen here, or play its music and videos." to c.textTertiary
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 30.dp, bottom = 10.dp))
}

@Composable
private fun CountChip(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, label: String) {
    val c = AppTheme.colors
    Row(
        Modifier.background(c.surfaceSecondary, Radius.pill).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = c.textSecondary)
        Text("$count $label", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = c.border)
    Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun Divider() = HorizontalDivider(color = AppTheme.colors.border)

@Composable
private fun SettingRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier,
    /** Indented under the row above, as one of its options. */
    indent: Boolean = false,
    control: @Composable () -> Unit,
) {
    val c = AppTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (indent) 30.dp else 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = c.textSecondary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
        control()
    }
}

/**
 * A setting with a switch: the whole row toggles it, screen readers read the label with its state, and
 * it's highlighted on hover and keyboard focus (the switch itself no longer handles input).
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    indent: Boolean = false,
) {
    val c = AppTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    SettingRow(
        label,
        icon,
        indent = indent,
        modifier = Modifier.clip(Radius.small)
            .background(if (hovered || focused) c.hover else Color.Transparent)
            .hoverable(source)
            .toggleable(checked, source, indication = null, role = Role.Switch, onValueChange = onChange),
    ) { AppSwitch(checked, onCheckedChange = null) }
}

/** Inline text field that saves on Enter or via the check button. */
@Composable
private fun NameField(saved: String, onSave: (String) -> Unit) {
    val c = AppTheme.colors
    var draft by remember(saved) { mutableStateOf(saved) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.widthIn(min = 160.dp, max = 240.dp).background(c.surfaceSecondary, Radius.small).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(draft) }),
            )
        }
        if (draft != saved) GhostIconButton(AppIcons.Check, "Save", { onSave(draft) }, size = 32.dp, iconSize = 18.dp, tint = c.textPrimary)
    }
}

@Composable
private fun AddFolderRow(onAdd: (String) -> Unit) {
    val c = AppTheme.colors
    var path by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).background(c.surfaceSecondary, Radius.small).padding(horizontal = 10.dp, vertical = 9.dp)) {
            if (path.isEmpty()) Text("Add a folder path, e.g. /Users/me/Movies", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = path,
                onValueChange = { path = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd(path); path = "" }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        GhostIconButton(AppIcons.Plus, "Add folder", { onAdd(path); path = "" }, enabled = path.isNotBlank(), tint = c.textPrimary)
    }
}
