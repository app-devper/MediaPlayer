package com.appdevper.mediaplayer.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.ui.components.ComposerField
import com.appdevper.mediaplayer.ui.components.EmptyState
import com.appdevper.mediaplayer.ui.components.ListRow
import com.appdevper.mediaplayer.ui.components.LoadingState
import com.appdevper.mediaplayer.ui.components.PillButton
import com.appdevper.mediaplayer.ui.components.PillStyle
import com.appdevper.mediaplayer.ui.components.Thumbnail
import com.appdevper.mediaplayer.ui.components.formatClock
import com.appdevper.mediaplayer.ui.components.icon
import com.appdevper.mediaplayer.ui.components.interactive
import com.appdevper.mediaplayer.ui.icons.AppIcons
import com.appdevper.mediaplayer.ui.theme.AppTheme
import com.appdevper.mediaplayer.ui.theme.Radius
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Content directory browser (replaces the old `ContentActivity`). */
@Composable
fun BrowseScreen(
    deviceId: String,
    folderId: String,
    title: String,
    onOpenFolder: (MediaEntry) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val vm = koinViewModel<BrowseViewModel>(key = "$deviceId/$folderId") { parametersOf(deviceId, folderId, title) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val onClick: (MediaEntry) -> Unit = { e ->
        if (e.isFolder) onOpenFolder(e) else if (vm.play(e)) onOpenPlayer()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingState()
            state.error != null -> EmptyState(AppIcons.Server, "Couldn't open this folder", state.error!!) {
                PillButton("Try again", vm::load, style = PillStyle.Secondary, icon = AppIcons.Refresh)
            }
            state.isEmpty -> EmptyState(AppIcons.Folder, "This folder is empty", "There's nothing to play in here.")
            state.isPhotoGrid -> PhotoGrid(state, onClick)
            else -> EntryList(state, onClick)
        }
        if (!state.loading && state.all.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, AppTheme.colors.background, AppTheme.colors.background)))
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                ComposerField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    placeholder = "Search in ${state.title}",
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(state: BrowseUiState) {
    val c = AppTheme.colors
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp)) {
        Text(state.title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val count = if (state.query.isBlank()) "${state.all.size} items" else "${state.visible.size} of ${state.all.size} items"
        Text(listOf(state.deviceName.takeIf { it != state.title }.orEmpty(), count).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
    }
}

@Composable
private fun EntryList(state: BrowseUiState, onClick: (MediaEntry) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 768.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp),
        ) {
            item { Header(state) }
            items(state.visible, key = { it.id }) { e ->
                ListRow(
                    title = e.title,
                    subtitle = e.subtitleLine(),
                    onClick = { onClick(e) },
                    leading = { Thumbnail(e.thumbnailUrl, e.kind.icon(), size = 44.dp, shape = Radius.medium) },
                    trailing = { if (e.isFolder) Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = AppTheme.colors.textTertiary) },
                )
            }
            if (state.visible.isEmpty()) item { NoMatches(state.query) }
        }
    }
}

@Composable
private fun PhotoGrid(state: BrowseUiState, onClick: (MediaEntry) -> Unit) {
    val c = AppTheme.colors
    LazyVerticalGrid(
        GridCells.Adaptive(124.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header(state) }
        items(state.visible, key = { it.id }) { e ->
            Box(
                Modifier.aspectRatio(1f).background(c.surfaceSecondary, Radius.medium).interactive({ onClick(e) }, shape = Radius.medium),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = e.thumbnailUrl
                if (thumb != null && !e.isFolder) {
                    AsyncImage(thumb, e.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(e.kind.icon(), null, Modifier.size(28.dp), tint = c.textSecondary)
                        Text(e.title, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
                    }
                }
            }
        }
        if (state.visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { NoMatches(state.query) }
    }
}

@Composable
private fun NoMatches(query: String) {
    Text(
        "Nothing matches \"$query\"",
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

private fun MediaEntry.subtitleLine(): String = buildList {
    if (isFolder) childCount?.let { add("$it items") }
    if (kind != MediaKind.FOLDER) artist?.let(::add)
    album?.let(::add)
    durationMs?.let { add(formatClock(it)) }
}.joinToString(" · ")
