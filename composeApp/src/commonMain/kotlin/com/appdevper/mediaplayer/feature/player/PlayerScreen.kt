package com.appdevper.mediaplayer.feature.player

import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.domain.model.SubtitleChoice
import com.appdevper.mediaplayer.domain.model.SubtitleState
import com.appdevper.mediaplayer.domain.model.TextTrack
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.DpOffset
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.appdevper.mediaplayer.domain.model.RepeatMode
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.drives
import com.appdevper.mediaplayer.ui.LocalFullscreen
import com.appdevper.mediaplayer.ui.formatClock
import com.appdevper.mediaplayer.ui.icon
import com.appdevper.mediaplayer.ui.joinDetails
import com.appdevper.mediaplayer.ui.player.LocalPlayerView
import org.koin.compose.viewmodel.koinViewModel

/** Now playing (replaces `FullScreenPlayerActivity`, `ImageActivity` and the remote controls). */
@Composable
fun PlayerScreen() {
    val vm = koinViewModel<PlayerViewModel>()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val airPlay by vm.airPlay.collectAsStateWithLifecycle()
    val item = state.current
    when {
        airPlay.audioOnly -> AirPlayAudio(
            airPlay,
            onPrevious = vm::previous,
            onPlayPause = vm::senderPlayPause,
            onNext = vm::next,
        )
        item == null -> EmptyState(AppIcons.ListMusic, "Nothing playing", "Pick a video, song or photo from a media server.")
        state.target is PlaybackTarget.Renderer -> RemoteControls(state, item, vm)
        item.kind == MediaKind.IMAGE -> PhotoViewer(state, vm)
        // Its AirPlay video: the sender's queue decides next/previous.
        else -> LocalPlayer(state, item, vm, senderSkips = airPlay.drives(item))
    }
}

@Composable
private fun LocalPlayer(state: PlaybackState, item: MediaEntry, vm: PlayerViewModel, senderSkips: Boolean) {
    val c = AppTheme.colors
    val subtitles by vm.subtitles.collectAsStateWithLifecycle()
    val textTracks by vm.textTracks.collectAsStateWithLifecycle()
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
                LocalPlayerView(
                    player = vm.localPlayer,
                    title = item.title,
                    isAudio = isAudio,
                    volume = state.localVolume,
                    muted = state.localMuted,
                    onVolumeChange = vm::setVolume,
                    onMutedChange = vm::setMuted,
                    modifier = if (isAudio) Modifier.fillMaxWidth().height(96.dp).align(Alignment.BottomCenter) else Modifier.fillMaxSize(),
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
                            subtitles.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                        }
                        if (item.subtitles.isNotEmpty() || textTracks.isNotEmpty()) SubtitleMenu(item.subtitles, textTracks, subtitles, vm)
                        if (!senderSkips) QueueModes(state, vm)
                        GhostIconButton(AppIcons.SkipPrevious, "Previous", { vm.previous() }, tint = c.textPrimary)
                        GhostIconButton(AppIcons.SkipNext, "Next", { vm.next() }, enabled = senderSkips || state.hasNext, tint = c.textPrimary)
                    }
                }
                if (state.queue.size > 1) {
                    item { SectionLabel("Up next") }
                    // In the order they play (shuffled or not).
                    items(state.order, key = { state.queue[it].id }) { i -> QueueRow(state.queue[i], i == state.index) { vm.playAt(i) } }
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
        joinDetails(item.artist, item.album)?.let {
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
            GhostIconButton(AppIcons.SkipPrevious, "Previous", { vm.previous() }, size = 44.dp, iconSize = 24.dp, tint = c.textPrimary)
            CircleButton(if (status?.state == TransportState.PLAYING) AppIcons.Pause else AppIcons.Play, "Play/Pause", { vm.togglePause() }, size = 64.dp)
            GhostIconButton(AppIcons.SkipNext, "Next", { vm.next() }, enabled = state.hasNext, size = 44.dp, iconSize = 24.dp, tint = c.textPrimary)
            Spacer(Modifier.size(44.dp))
        }
        if (!state.casted && item.kind != MediaKind.IMAGE) Row(verticalAlignment = Alignment.CenterVertically) { QueueModes(state, vm) }

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

private fun MediaEntry.subtitleLine(): String? = joinDetails(artist, album, durationMs?.let(::formatClock))

/** Shuffle (with more than one item) and repeat (off → all → one), lit while on. Not for something cast here. */
@Composable
private fun QueueModes(state: PlaybackState, vm: PlayerViewModel) {
    if (state.casted) return
    val c = AppTheme.colors
    if (state.queue.size > 1) {
        ModeButton(AppIcons.Shuffle, "Shuffle", if (state.shuffle) "On" else "Off", on = state.shuffle, vm::toggleShuffle)
    }
    val repeat = when (state.repeat) {
        RepeatMode.OFF -> "Off"
        RepeatMode.ALL -> "All"
        RepeatMode.ONE -> "One"
    }
    ModeButton(if (state.repeat == RepeatMode.ONE) AppIcons.RepeatOne else AppIcons.Repeat, "Repeat", repeat, on = state.repeat != RepeatMode.OFF, vm::cycleRepeat)
}

/** A mode button: lit, with a dot under it, while on (not by color alone); says its state. */
@Composable
private fun ModeButton(icon: ImageVector, name: String, state: String, on: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(contentAlignment = Alignment.BottomCenter) {
        GhostIconButton(icon, name, onClick, modifier = Modifier.semantics { stateDescription = state }, tint = if (on) c.textPrimary else c.textTertiary)
        if (on) Box(Modifier.padding(bottom = 2.dp).size(4.dp).clip(CircleShape).background(c.textPrimary))
    }
}

/** Subtitles for this video: off, one of its files, or a track inside it (a check on the one showing). */
@Composable
private fun SubtitleMenu(files: List<Subtitle>, tracks: List<TextTrack>, state: SubtitleState, vm: PlayerViewModel) {
    val c = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        val choice = state.choice
        val shown = when {
            state.loading -> "Loading"
            choice is SubtitleChoice.File -> choice.subtitle.label
            choice is SubtitleChoice.Track -> choice.track.label
            else -> "Off"
        }
        ModeButton(AppIcons.Captions, "Subtitles", shown, on = choice != SubtitleChoice.Off) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = Radius.large,
            containerColor = c.surface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.width(260.dp).border(1.dp, c.border, Radius.large),
        ) {
            Text("Subtitles", style = MaterialTheme.typography.labelMedium, color = c.textTertiary, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
            @Composable
            fun option(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) = ListRow(
                title = title,
                subtitle = subtitle,
                onClick = {
                    open = false
                    onClick()
                },
                modifier = Modifier.padding(horizontal = 6.dp),
                trailing = { if (selected) Icon(AppIcons.Check, "Showing", Modifier.size(18.dp), tint = c.textPrimary) },
            )
            @Composable
            fun pick(c: SubtitleChoice) = option(
                when (c) {
                    SubtitleChoice.Off -> "Off"
                    is SubtitleChoice.File -> c.subtitle.label
                    is SubtitleChoice.Track -> c.track.label
                },
                when (c) {
                    SubtitleChoice.Off -> null
                    is SubtitleChoice.File -> "File"
                    is SubtitleChoice.Track -> "In the video"
                },
                choice == c,
            ) { vm.selectSubtitle(c) }
            pick(SubtitleChoice.Off)
            files.forEach { pick(SubtitleChoice.File(it)) }
            tracks.forEach { pick(SubtitleChoice.Track(it)) }
        }
    }
}
