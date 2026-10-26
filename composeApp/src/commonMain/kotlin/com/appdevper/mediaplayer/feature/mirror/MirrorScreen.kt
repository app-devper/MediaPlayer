package com.appdevper.mediaplayer.feature.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.platform.AirPlayMirrorView
import com.appdevper.mediaplayer.ui.LocalFullscreen
import org.koin.compose.viewmodel.koinViewModel

/** The mirrored screen of an iPhone/iPad/Mac, or what's playing from it. */
@Composable
fun MirrorScreen() {
    val vm = koinViewModel<MirrorViewModel>()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val activity = state.activity
    val fullscreen = LocalFullscreen.current
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(fullscreen) { detectTapGestures(onDoubleTap = { if (activity.mirroring) fullscreen.toggle() }) },
        contentAlignment = Alignment.Center,
    ) {
        val size = state.videoSize
        if (activity.mirroring) {
            val ratio = size?.let { it.width.toFloat() / it.height }?.takeIf { it > 0f } ?: (16f / 9f)
            AirPlayMirrorView(vm.player, Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f))
            if (!fullscreen.isFullscreen) activity.client?.let { SenderChip("Mirroring $it", Modifier.align(Alignment.TopStart).padding(12.dp)) }
            GhostIconButton(
                if (fullscreen.isFullscreen) AppIcons.Minimize else AppIcons.Maximize,
                if (fullscreen.isFullscreen) "Exit full screen" else "Full screen",
                fullscreen::toggle,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                tint = Color.White,
            )
        } else {
            Message(
                icon = if (activity.audioOnly) AppIcons.Music else AppIcons.Cast,
                title = when {
                    activity.audioOnly -> "Playing audio from ${activity.client ?: "a device"}"
                    activity.client != null -> "Connected to ${activity.client}"
                    else -> "Waiting for a device"
                },
                message = when (val s = state.status) {
                    is AirPlayStatus.Running -> "Pick “${s.name}” in Control Center › Screen Mirroring on an iPhone, iPad or Mac, or from the AirPlay button in apps like YouTube and Music."
                    is AirPlayStatus.Failed -> "AirPlay couldn't start: ${s.message}"
                    AirPlayStatus.Unsupported -> "This device can't receive AirPlay."
                    AirPlayStatus.Stopped -> "Turn on AirPlay in My Server › Receive."
                },
            )
        }
    }
}

@Composable
private fun SenderChip(text: String, modifier: Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.55f), Radius.pill).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.Cast, null, Modifier.size(16.dp), tint = AppTheme.colors.success)
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    Column(
        Modifier.padding(32.dp).widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(icon, size = 56.dp, shape = Radius.large, tint = Color.White, modifier = Modifier.background(Color.White.copy(alpha = 0.08f), Radius.large))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}
