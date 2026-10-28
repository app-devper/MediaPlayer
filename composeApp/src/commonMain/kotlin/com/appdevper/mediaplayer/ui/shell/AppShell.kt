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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.feature.shell.ShellViewModel
import com.appdevper.mediaplayer.platform.SystemFullscreenEffect
import com.appdevper.mediaplayer.ui.FullscreenState
import com.appdevper.mediaplayer.ui.LocalFullscreen
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

internal sealed interface Selection {
    data object Home : Selection
    data class Device(val id: String) : Selection
    data object NowPlaying : Selection
    data object MyServer : Selection
    data object Mirror : Selection
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
        dest?.hasRoute<MirrorRoute>() == true -> Selection.Mirror
        else -> Selection.Home
    }
    LaunchedEffect(vm, nav) {
        vm.openPlayer.collect {
            // Even singleTop replaces the entry (and the running player) when it's already on screen.
            if (nav.currentDestination?.hasRoute<PlayerRoute>() != true) nav.navigate(PlayerRoute) { launchSingleTop = true }
        }
    }
    LaunchedEffect(vm, nav) {
        vm.openMirror.collect {
            if (nav.currentDestination?.hasRoute<MirrorRoute>() != true) nav.navigate(MirrorRoute) { launchSingleTop = true }
        }
    }
    val onPlayer = selection == Selection.NowPlaying || selection == Selection.Mirror
    val canGoBack = selection != Selection.Home && nav.previousBackStackEntry != null
    val fullscreenState = remember { FullscreenState() }
    // Full screen only makes sense on the player / mirrored screen.
    val fullscreen = fullscreenState.isFullscreen && onPlayer
    LaunchedEffect(onPlayer) { if (!onPlayer) fullscreenState.exit() }
    SystemFullscreenEffect(fullscreen, onExit = fullscreenState::exit)

    CompositionLocalProvider(LocalFullscreen provides fullscreenState) {
        BoxWithConstraints(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else AppTheme.colors.background)) {
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
                    onAirPlay = { nav.navigate(MirrorRoute) { launchSingleTop = true }; closeDrawer() },
                    onCollapse = { if (wide) sidebarOpen = false else closeDrawer() },
                )
            }

            // One instance, moved (not rebuilt) between the wide and narrow layouts below: turning a phone
            // to landscape for full screen can make it wide, and rebuilding restarted the video in the player.
            // What changes is passed in, so the moved content never sees an old value.
            val movableMain = remember(startDestination, vm, nav) {
                movableContentOf { fullscreen: Boolean, wide: Boolean, canGoBack: Boolean, onPlayer: Boolean ->
                    Column(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
                        if (!fullscreen) TopBar(
                            state = state,
                            showSidebarButton = !wide || !sidebarOpen,
                            onSidebar = { if (wide) sidebarOpen = true else scope.launch { drawer.open() } },
                            canGoBack = canGoBack,
                            onBack = { nav.popBackStack() },
                            onSelectTarget = vm::selectTarget,
                            onSearchAgain = { vm.refresh() },
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AppNavHost(
                                nav,
                                startDestination,
                                state,
                                onSelectTarget = vm::selectTarget,
                                onRefresh = { vm.refresh(clear = true) },
                                onAddServer = vm::addServer,
                                onDismissAddServer = vm::dismissAddServer,
                                onForgetServer = vm::forgetServer,
                            )
                            androidx.compose.animation.AnimatedVisibility(
                                visible = state.hasNowPlaying && !onPlayer,
                                enter = slideInVertically { it } + fadeIn(),
                                exit = slideOutVertically { it } + fadeOut(),
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                MiniPlayer(state, onOpen = { nav.navigate(PlayerRoute) { launchSingleTop = true } }, onTogglePause = vm::togglePause, onToggleLocal = vm::toggleLocal)
                            }
                        }
                    }
                }
            }
            val main: @Composable () -> Unit = { movableMain(fullscreen, wide, canGoBack, onPlayer) }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(sidebarOpen && !fullscreen, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                        Box(Modifier.width(272.dp).fillMaxHeight().background(AppTheme.colors.sidebar)) { sidebar() }
                    }
                    Box(Modifier.weight(1f)) { main() }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    gesturesEnabled = !fullscreen,
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
            state.airPlay.pin?.let { pin -> PinOverlay(pin, state.airPlay.client) }
        }
    }
}
