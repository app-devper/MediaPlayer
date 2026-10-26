package com.appdevper.mediaplayer.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.feature.shell.ShellViewModel
import com.appdevper.mediaplayer.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

internal sealed interface Selection {
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
