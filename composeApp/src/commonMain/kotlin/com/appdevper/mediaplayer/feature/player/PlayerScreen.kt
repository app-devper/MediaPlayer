package com.appdevper.mediaplayer.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.appdevper.mediaplayer.domain.model.RepeatMode
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.ui.LocalFullscreen
import com.appdevper.mediaplayer.ui.formatClock
import com.appdevper.mediaplayer.ui.icon
import com.appdevper.mediaplayer.ui.joinDetails
import com.appdevper.mediaplayer.ui.player.LocalPlayerView
import com.appdevper.mediaplayer.ui.player.SongSource
import com.appdevper.mediaplayer.ui.player.SongView
import com.appdevper.mediaplayer.platform.AirPlayButton
import com.appdevper.mediaplayer.platform.pointerIsMouse
import com.appdevper.mediaplayer.platform.supportsAirPlay
import org.koin.compose.viewmodel.koinViewModel

/** Now playing (replaces `FullScreenPlayerActivity`, `ImageActivity` and the remote controls). */
@Composable
fun PlayerScreen(vm: PlayerViewModel = koinViewModel()) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    when (val u = ui) {
        PlayerUi.Empty -> EmptyState(AppIcons.ListMusic, "Nothing playing", "Pick a video, song or photo from a media server.")
        is PlayerUi.SenderAudio -> AirPlayAudio(u.audio, onPrevious = vm::previous, onPlayPause = vm::togglePause, onNext = vm::next)
        is PlayerUi.Remote -> RemoteControls(u, vm)
        is PlayerUi.Photos -> PhotoViewer(u, vm)
        is PlayerUi.Local -> if (u.entry.kind == MediaKind.AUDIO) LocalSong(u, vm) else LocalPlayer(u, vm)
    }
}

/** A song on this device: the song layout every song uses, with this device's own volume on desktop. */
@Composable
private fun LocalSong(ui: PlayerUi.Local, vm: PlayerViewModel) {
    val item = ui.entry
    val status by vm.localPlayer.status.collectAsStateWithLifecycle()
    val error by vm.localPlayer.error.collectAsStateWithLifecycle()
    SongView(
        artwork = { Thumbnail(item.thumbnailUrl, AppIcons.Music, size = it, shape = Radius.xLarge) },
        title = item.title,
        detail = joinDetails(item.artist, item.album),
        source = SongSource("Loading", busy = true).takeIf { status?.state == TransportState.TRANSITIONING },
        error = error,
        positionMs = status?.positionMs ?: 0,
        durationMs = status?.durationMs?.takeIf { it > 0 } ?: item.durationMs ?: 0,
        onSeek = vm::seek,
        playing = ui.controls.playing,
        onPlayPause = vm::togglePause,
        onPrevious = vm::previous,
        onNext = vm::next,
        nextEnabled = ui.controls.nextEnabled,
    ) {
        ModesRow(ui.controls.modes, vm)
        // Phones have volume keys; desktop has none, so the volume is here.
        if (pointerIsMouse) VolumeRow(if (ui.muted) 0 else ui.volume, onVolume = vm::setVolume, onToggleMute = {
            if (ui.muted || ui.volume == 0) {
                vm.setMuted(false)
                if (ui.volume == 0) vm.setVolume(100)
            } else {
                vm.setMuted(true)
            }
        })
        // AVKit's controls have the AirPlay button for videos; songs have it here.
        if (supportsAirPlay) AirPlayButton(Modifier.padding(top = 8.dp).size(40.dp), tint = AppTheme.colors.textPrimary)
        UpNext(ui.controls.upNext, vm)
    }
}

@Composable
private fun ModesRow(modes: Modes?, vm: PlayerViewModel) {
    if (modes == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { QueueModes(modes, vm) }
}

/** Mute and a slider; [onToggleMute] null: the device has no mute here (a TV's own volume). */
@Composable
private fun VolumeRow(volume: Int, onVolume: (Int) -> Unit, onToggleMute: (() -> Unit)? = null) {
    val c = AppTheme.colors
    var local by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    Row(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val silent = local <= 0f
        if (onToggleMute != null) {
            GhostIconButton(if (silent) AppIcons.VolumeOff else AppIcons.Volume, if (silent) "Unmute (M)" else "Mute (M)", onToggleMute, iconSize = 18.dp, tint = c.textSecondary)
        } else {
            Icon(AppIcons.Volume, "Volume", Modifier.padding(horizontal = 11.dp).size(18.dp), tint = c.textSecondary)
        }
        ThinSlider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onVolume(local.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The queue in the order it plays, the current one marked; nothing for a single item. */
@Composable
private fun UpNext(rows: List<QueueRow>, vm: PlayerViewModel) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        SectionLabel("Up next")
        rows.forEach { row -> key(row.entry.id) { QueueRow(row) { vm.playAt(row.index) } } }
    }
}

@Composable
/** A video on this device: the picture with its controls, then what it is and what's next. */
private fun LocalPlayer(ui: PlayerUi.Local, vm: PlayerViewModel) {
    val c = AppTheme.colors
    val item = ui.entry
    val subtitles by vm.subtitles.collectAsStateWithLifecycle()
    val textTracks by vm.textTracks.collectAsStateWithLifecycle()
    val fullscreenState = LocalFullscreen.current
    val fullscreen = fullscreenState.isFullscreen
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The player stays at this position in both layouts, so going full screen doesn't reload it.
        Box(
            if (fullscreen) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .aspectRatio(16f / 9f)
                    .clip(Radius.large)
                    .background(Color.Black)
            },
            contentAlignment = Alignment.Center,
        ) {
            key(item.id, item.streamUrl) {
                LocalPlayerView(
                    player = vm.localPlayer,
                    title = item.title,
                    volume = ui.volume,
                    muted = ui.muted,
                    onVolumeChange = vm::setVolume,
                    onMutedChange = vm::setMuted,
                    modifier = Modifier.fillMaxSize(),
                    fullscreen = fullscreen,
                    onToggleFullscreen = fullscreenState::toggle,
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
                        QueueModes(ui.controls.modes, vm)
                        GhostIconButton(AppIcons.SkipPrevious, "Previous", { vm.previous() }, tint = c.textPrimary)
                        GhostIconButton(AppIcons.SkipNext, "Next", { vm.next() }, enabled = ui.controls.nextEnabled, tint = c.textPrimary)
                    }
                }
                if (ui.controls.upNext.isNotEmpty()) {
                    item { SectionLabel("Up next") }
                    items(ui.controls.upNext, key = { it.entry.id }) { row -> QueueRow(row) { vm.playAt(row.index) } }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun QueueRow(row: QueueRow, onClick: () -> Unit) {
    val e = row.entry
    val current = row.current
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
private fun PhotoViewer(ui: PlayerUi.Photos, vm: PlayerViewModel) {
    val photos = ui.photos
    val pager = rememberPagerState(initialPage = ui.index.coerceAtLeast(0)) { photos.size }
    LaunchedEffect(ui.index) { if (pager.currentPage != ui.index && ui.index >= 0) pager.scrollToPage(ui.index) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (it != (vm.uiState.value as? PlayerUi.Photos)?.index) vm.playAt(it) }
    }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { photos[it].id }) { page ->
            ZoomableImage(photos[page].streamUrl.orEmpty(), photos[page].title)
        }
        Text(
            "${pager.currentPage + 1} / ${photos.size} · ${photos.getOrNull(pager.currentPage)?.title.orEmpty()}",
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

/** On a TV or speaker: the song layout, with the TV's volume and a way back to this device. */
@Composable
private fun RemoteControls(ui: PlayerUi.Remote, vm: PlayerViewModel) {
    val item = ui.entry
    val status = ui.status
    val photo = item.kind == MediaKind.IMAGE
    SongView(
        artwork = { Thumbnail(item.thumbnailUrl, item.kind.icon(), size = it, shape = Radius.xLarge) },
        title = item.title,
        detail = joinDetails(item.artist, item.album),
        source = when (status?.state) {
            TransportState.TRANSITIONING -> SongSource("Loading on ${ui.renderer.name}", busy = true)
            TransportState.STOPPED, TransportState.NO_MEDIA -> SongSource("Stopped on ${ui.renderer.name}")
            else -> SongSource("On ${ui.renderer.name}")
        },
        error = ui.error,
        positionMs = status?.positionMs ?: 0,
        durationMs = if (photo) 0 else status?.durationMs?.takeIf { it > 0 } ?: item.durationMs ?: 0,
        onSeek = vm::seek.takeUnless { photo },
        playing = ui.controls.playing,
        onPlayPause = vm::togglePause,
        onPrevious = vm::previous,
        onNext = vm::next,
        nextEnabled = ui.controls.nextEnabled,
        onStop = vm::stop,
    ) {
        ModesRow(ui.controls.modes, vm)
        ui.volume?.let { VolumeRow(it, onVolume = vm::setVolume) }
        PillButton("Play on this device instead", { vm.playHere() }, style = PillStyle.Secondary, icon = AppIcons.Phone, modifier = Modifier.padding(top = 16.dp))
        UpNext(ui.controls.upNext, vm)
    }
}


private fun MediaEntry.subtitleLine(): String? = joinDetails(artist, album, durationMs?.let(::formatClock))

/** Shuffle (with more than one item) and repeat (off → all → one), lit while on; none where they don't apply. */
@Composable
private fun QueueModes(modes: Modes?, vm: PlayerViewModel) {
    if (modes == null) return
    modes.shuffle?.let { on -> ModeButton(AppIcons.Shuffle, "Shuffle", if (on) "On" else "Off", on = on, vm::toggleShuffle) }
    val repeat = when (modes.repeat) {
        RepeatMode.OFF -> "Off"
        RepeatMode.ALL -> "All"
        RepeatMode.ONE -> "One"
    }
    ModeButton(if (modes.repeat == RepeatMode.ONE) AppIcons.RepeatOne else AppIcons.Repeat, "Repeat", repeat, on = modes.repeat != RepeatMode.OFF, vm::cycleRepeat)
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
