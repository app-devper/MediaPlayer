package com.appdevper.mediaplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.appdevper.mediaplayer.designsystem.components.CircleButton
import com.appdevper.mediaplayer.designsystem.components.EmptyState
import com.appdevper.mediaplayer.designsystem.components.GhostIconButton
import com.appdevper.mediaplayer.designsystem.components.ListRow
import com.appdevper.mediaplayer.designsystem.components.PillButton
import com.appdevper.mediaplayer.designsystem.components.PillStyle
import com.appdevper.mediaplayer.designsystem.components.SectionLabel
import com.appdevper.mediaplayer.designsystem.components.ThinSlider
import com.appdevper.mediaplayer.designsystem.components.Thumbnail
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.platform.NativeMediaPlayer
import com.appdevper.mediaplayer.ui.LocalFullscreen
import com.appdevper.mediaplayer.ui.formatClock
import com.appdevper.mediaplayer.ui.icon
import org.koin.compose.viewmodel.koinViewModel

/** Now playing (replaces `FullScreenPlayerActivity`, `ImageActivity` and the remote controls). */
@Composable
fun PlayerScreen() {
    val vm = koinViewModel<PlayerViewModel>()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val airPlay by vm.airPlay.collectAsStateWithLifecycle()
    val item = state.current
    when {
        airPlay.audioOnly -> AirPlayAudio(airPlay)
        item == null -> EmptyState(AppIcons.ListMusic, "Nothing playing", "Pick a video, song or photo from a media server.")
        state.target is PlaybackTarget.Renderer -> RemoteControls(state, item, vm)
        item.kind == MediaKind.IMAGE -> PhotoViewer(state, vm)
        else -> LocalPlayer(state, item, vm)
    }
}

@Composable
private fun LocalPlayer(state: PlaybackState, item: MediaEntry, vm: PlayerViewModel) {
    val c = AppTheme.colors
    val isAudio = item.kind == MediaKind.AUDIO
    val fullscreenState = LocalFullscreen.current
    val fullscreen = fullscreenState.isFullscreen && !isAudio
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The player stays at this position in both layouts, so going full screen doesn't reload it.
        Box(
            if (fullscreen) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .then(if (isAudio) Modifier.height(280.dp) else Modifier.aspectRatio(16f / 9f))
                    .clip(Radius.large)
                    .background(if (isAudio) c.surfaceSecondary else Color.Black)
            },
            contentAlignment = Alignment.Center,
        ) {
            if (isAudio) {
                Thumbnail(item.thumbnailUrl, AppIcons.Music, size = 160.dp, shape = Radius.large, modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
            }
            key(item.id, item.streamUrl) {
                NativeMediaPlayer(
                    url = item.streamUrl.orEmpty(),
                    title = item.title,
                    isAudio = isAudio,
                    onEnded = { vm.onLocalEnded() },
                    modifier = if (isAudio) Modifier.fillMaxWidth().height(96.dp).align(Alignment.BottomCenter) else Modifier.fillMaxSize(),
                    autoPlay = state.autoPlay,
                    startPositionMs = state.startPositionMs,
                    volume = if (state.localMuted) 0f else state.localVolume / 100f,
                    commands = vm.localCommands,
                    onStatus = vm::reportLocalStatus,
                    fullscreen = fullscreen,
                    onToggleFullscreen = if (isAudio) null else fullscreenState::toggle,
                )
            }
        }
        if (!fullscreen) {
            LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            item.subtitleLine()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 1) }
                        }
                        GhostIconButton(AppIcons.SkipPrevious, "Previous", { vm.previous() }, enabled = state.hasPrevious, tint = c.textPrimary)
                        GhostIconButton(AppIcons.SkipNext, "Next", { vm.next() }, enabled = state.hasNext, tint = c.textPrimary)
                    }
                }
                if (state.queue.size > 1) {
                    item { SectionLabel("Up next") }
                    itemsIndexed(state.queue, key = { _, e -> e.id }) { i, e -> QueueRow(e, i == state.index) { vm.playAt(i) } }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun QueueRow(e: MediaEntry, current: Boolean, onClick: () -> Unit) {
    ListRow(
        title = e.title,
        subtitle = e.subtitleLine(),
        selected = current,
        onClick = onClick,
        leading = { Thumbnail(e.thumbnailUrl, e.kind.icon(), size = 40.dp, shape = Radius.small) },
        trailing = { if (current) Icon(AppIcons.Volume, "Playing", Modifier.size(16.dp), tint = AppTheme.colors.textSecondary) },
    )
}

@Composable
private fun PhotoViewer(state: PlaybackState, vm: PlayerViewModel) {
    val pager = rememberPagerState(initialPage = state.index.coerceAtLeast(0)) { state.queue.size }
    LaunchedEffect(state.index) { if (pager.currentPage != state.index && state.index >= 0) pager.scrollToPage(state.index) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (it != vm.uiState.value.index) vm.playAt(it) }
    }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { state.queue[it].id }) { page ->
            ZoomableImage(state.queue[page].streamUrl.orEmpty(), state.queue[page].title)
        }
        Text(
            "${pager.currentPage + 1} / ${state.queue.size} · ${state.queue.getOrNull(pager.currentPage)?.title.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

@Composable
private fun ZoomableImage(url: String, title: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    AsyncImage(
        url, title,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
            .transformable(transform, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun RemoteControls(state: PlaybackState, item: MediaEntry, vm: PlayerViewModel) {
    val c = AppTheme.colors
    val renderer = (state.target as PlaybackTarget.Renderer).device
    val status = state.remote.status
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Thumbnail(
            item.thumbnailUrl,
            item.kind.icon(),
            size = 260.dp,
            shape = Radius.xLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(item.title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 2)
        listOfNotNull(item.artist, item.album).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
        }
        Row(
            Modifier.border(1.dp, c.border, Radius.pill).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(AppIcons.Cast, null, Modifier.size(14.dp), tint = c.success)
            Text(
                "Playing on ${renderer.name}" + (status?.state?.label()?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = c.textSecondary,
            )
        }
        state.remote.error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }

        if (item.kind != MediaKind.IMAGE) {
            val duration = (status?.durationMs?.takeIf { it > 0 } ?: item.durationMs ?: 0L)
            val position = status?.positionMs ?: 0L
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                ThinSlider(
                    value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { vm.seek((it * duration).toLong()) }
                        dragging = null
                    },
                    enabled = duration > 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(position), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(formatClock(duration), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GhostIconButton(AppIcons.Stop, "Stop", { vm.stop() }, size = 44.dp, iconSize = 20.dp, tint = c.textSecondary)
            GhostIconButton(AppIcons.SkipPrevious, "Previous", { vm.previous() }, enabled = state.hasPrevious, size = 44.dp, iconSize = 24.dp, tint = c.textPrimary)
            CircleButton(if (status?.state == TransportState.PLAYING) AppIcons.Pause else AppIcons.Play, "Play/Pause", { vm.togglePause() }, size = 64.dp)
            GhostIconButton(AppIcons.SkipNext, "Next", { vm.next() }, enabled = state.hasNext, size = 44.dp, iconSize = 24.dp, tint = c.textPrimary)
            Spacer(Modifier.size(44.dp))
        }

        state.remote.volume?.let { vol ->
            var local by remember(vol) { mutableFloatStateOf(vol.toFloat()) }
            Row(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Volume, "Volume", Modifier.size(18.dp), tint = c.textSecondary)
                Spacer(Modifier.size(10.dp))
                ThinSlider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { vm.setVolume(local.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        PillButton("Play on this device instead", { vm.playHere() }, style = PillStyle.Secondary, icon = AppIcons.Phone)
        if (state.queue.size > 1) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                SectionLabel("Up next")
                state.queue.forEachIndexed { i, e -> QueueRow(e, i == state.index) { vm.playAt(i) } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun TransportState.label(): String? = when (this) {
    TransportState.PLAYING -> "playing"
    TransportState.PAUSED -> "paused"
    TransportState.STOPPED -> "stopped"
    TransportState.TRANSITIONING -> "loading"
    TransportState.NO_MEDIA -> "idle"
    TransportState.UNKNOWN -> null
}

private fun MediaEntry.subtitleLine(): String? =
    listOfNotNull(artist, album, durationMs?.let(::formatClock)).joinToString(" · ").takeIf { it.isNotEmpty() }
