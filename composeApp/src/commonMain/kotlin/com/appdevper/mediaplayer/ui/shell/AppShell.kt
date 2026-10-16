package com.appdevper.mediaplayer.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.feature.browse.BrowseScreen
import com.appdevper.mediaplayer.feature.home.HomeScreen
import com.appdevper.mediaplayer.feature.player.PlayerScreen
import com.appdevper.mediaplayer.feature.server.ServerScreen
import com.appdevper.mediaplayer.feature.shell.ShellUiState
import com.appdevper.mediaplayer.feature.shell.ShellViewModel
import com.appdevper.mediaplayer.platform.AirPlayButton
import com.appdevper.mediaplayer.platform.deviceLabel
import com.appdevper.mediaplayer.platform.supportsAirPlay
import com.appdevper.mediaplayer.ui.components.GhostIconButton
import com.appdevper.mediaplayer.ui.components.IconTile
import com.appdevper.mediaplayer.ui.components.ListRow
import com.appdevper.mediaplayer.ui.components.SectionLabel
import com.appdevper.mediaplayer.ui.components.StatusDot
import com.appdevper.mediaplayer.ui.components.Thumbnail
import com.appdevper.mediaplayer.ui.components.icon
import com.appdevper.mediaplayer.ui.components.interactive
import com.appdevper.mediaplayer.ui.components.subtitle
import com.appdevper.mediaplayer.ui.icons.AppIcons
import com.appdevper.mediaplayer.ui.theme.AppTheme
import com.appdevper.mediaplayer.ui.theme.Radius
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
object HomeRoute

@Serializable
data class BrowseRoute(val deviceId: String, val folderId: String, val title: String)

@Serializable
object PlayerRoute

@Serializable
object ServerRoute

private sealed interface Selection {
    data object Home : Selection
    data class Device(val id: String) : Selection
    data object NowPlaying : Selection
    data object MyServer : Selection
}

/** [startDestination] is a route object (defaults to [HomeRoute]); previews/tests can open another screen. */
@Composable
fun AppShell(startDestination: Any = HomeRoute) {
    val vm = koinViewModel<ShellViewModel>()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val selection: Selection = when {
        dest?.hasRoute<BrowseRoute>() == true -> Selection.Device(entry!!.toRoute<BrowseRoute>().deviceId)
        dest?.hasRoute<PlayerRoute>() == true -> Selection.NowPlaying
        dest?.hasRoute<ServerRoute>() == true -> Selection.MyServer
        else -> Selection.Home
    }
    val onPlayer = selection == Selection.NowPlaying
    val canGoBack = selection != Selection.Home && nav.previousBackStackEntry != null

    BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        val wide = maxWidth >= 900.dp
        var sidebarOpen by rememberSaveable { mutableStateOf(true) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        fun closeDrawer() {
            if (!wide) scope.launch { drawer.close() }
        }

        val sidebar: @Composable () -> Unit = {
            Sidebar(
                state = state,
                selection = selection,
                onHome = { nav.navigate(HomeRoute) { popUpTo<HomeRoute> { inclusive = true } }; closeDrawer() },
                onRefresh = { vm.refresh(clear = true) },
                onOpenDevice = { d ->
                    nav.navigate(BrowseRoute(d.id, "0", d.name)) { popUpTo<HomeRoute>() }
                    closeDrawer()
                },
                onSelectRenderer = { d ->
                    val selected = (state.target as? PlaybackTarget.Renderer)?.device?.id == d.id
                    vm.selectTarget(if (selected) PlaybackTarget.Local else PlaybackTarget.Renderer(d))
                },
                onNowPlaying = { nav.navigate(PlayerRoute) { launchSingleTop = true }; closeDrawer() },
                onMyServer = { nav.navigate(ServerRoute) { popUpTo<HomeRoute>(); launchSingleTop = true }; closeDrawer() },
                onCollapse = { if (wide) sidebarOpen = false else closeDrawer() },
            )
        }

        val main: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
                TopBar(
                    state = state,
                    showSidebarButton = !wide || !sidebarOpen,
                    onSidebar = { if (wide) sidebarOpen = true else scope.launch { drawer.open() } },
                    canGoBack = canGoBack,
                    onBack = { nav.popBackStack() },
                    onSelectTarget = vm::selectTarget,
                    onSearchAgain = { vm.refresh() },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AppNavHost(nav, startDestination, state, onSelectTarget = vm::selectTarget, onRefresh = { vm.refresh(clear = true) })
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.nowPlaying != null && !onPlayer,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        MiniPlayer(state, onOpen = { nav.navigate(PlayerRoute) { launchSingleTop = true } }, onTogglePause = vm::togglePause)
                    }
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(sidebarOpen, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                    Box(Modifier.width(272.dp).fillMaxHeight().background(AppTheme.colors.sidebar)) { sidebar() }
                }
                Box(Modifier.weight(1f)) { main() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                scrimColor = AppTheme.colors.scrim,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = AppTheme.colors.sidebar,
                        drawerShape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp),
                        windowInsets = WindowInsets(0),
                        modifier = Modifier.width(300.dp),
                    ) { sidebar() }
                },
            ) { main() }
        }
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    startDestination: Any,
    state: ShellUiState,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefresh: () -> Unit,
) {
    NavHost(nav, startDestination = startDestination) {
        composable<HomeRoute> {
            HomeScreen(
                state = state,
                onOpenServer = { d -> nav.navigate(BrowseRoute(d.id, "0", d.name)) },
                onSelectTarget = onSelectTarget,
                onRefresh = onRefresh,
                onOpenMyServer = { nav.navigate(ServerRoute) },
            )
        }
        composable<BrowseRoute> { e ->
            val r = e.toRoute<BrowseRoute>()
            BrowseScreen(
                deviceId = r.deviceId,
                folderId = r.folderId,
                title = r.title,
                onOpenFolder = { f -> nav.navigate(BrowseRoute(r.deviceId, f.id, f.title)) },
                onOpenPlayer = { nav.navigate(PlayerRoute) { launchSingleTop = true } },
            )
        }
        composable<PlayerRoute> { PlayerScreen() }
        composable<ServerRoute> { ServerScreen() }
    }
}

// region top bar + "Play on" picker

@Composable
private fun TopBar(
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
        Row(
            Modifier.interactive({ open = true }, shape = Radius.medium).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (remote != null) Icon(AppIcons.Cast, null, Modifier.size(18.dp), tint = c.success)
            Text(
                remote?.device?.name ?: "Media Player",
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

// endregion

// region sidebar

@Composable
private fun Sidebar(
    state: ShellUiState,
    selection: Selection,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDevice: (MediaDevice) -> Unit,
    onSelectRenderer: (MediaDevice) -> Unit,
    onNowPlaying: () -> Unit,
    onMyServer: () -> Unit,
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

        state.nowPlaying?.let { item ->
            ListRow(
                "Now playing",
                subtitle = item.title,
                selected = selection == Selection.NowPlaying,
                onClick = onNowPlaying,
                leading = { SidebarIcon(AppIcons.ListMusic) },
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

// endregion

@Composable
private fun MiniPlayer(state: ShellUiState, onOpen: () -> Unit, onTogglePause: () -> Unit) {
    val item = state.nowPlaying ?: return
    val c = AppTheme.colors
    val remote = state.target as? PlaybackTarget.Renderer
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .border(1.dp, c.border, Radius.xLarge)
                .background(c.surface, Radius.xLarge)
                .interactive(onOpen, shape = Radius.xLarge)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Thumbnail(item.thumbnailUrl, item.kind.icon(), size = 40.dp, shape = Radius.medium)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    remote?.let { "Playing on ${it.device.name}" } ?: "Tap to open the player",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remote != null) c.success else c.textTertiary,
                    maxLines = 1,
                )
            }
            if (remote != null) {
                GhostIconButton(if (state.remotePlaying) AppIcons.Pause else AppIcons.Play, "Play/Pause", onTogglePause, tint = c.textPrimary)
            } else {
                GhostIconButton(AppIcons.ChevronRight, "Open player", onOpen)
            }
        }
    }
}
