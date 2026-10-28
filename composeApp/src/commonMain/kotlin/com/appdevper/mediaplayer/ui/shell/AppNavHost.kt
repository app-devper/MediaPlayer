package com.appdevper.mediaplayer.ui.shell

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.feature.browse.BrowseScreen
import com.appdevper.mediaplayer.feature.home.HomeScreen
import com.appdevper.mediaplayer.feature.mirror.MirrorScreen
import com.appdevper.mediaplayer.feature.player.PlayerScreen
import com.appdevper.mediaplayer.feature.server.ServerScreen
import com.appdevper.mediaplayer.feature.shell.ShellUiState

@Composable
internal fun AppNavHost(
    nav: NavHostController,
    startDestination: Any,
    state: ShellUiState,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefresh: () -> Unit,
    onAddServer: (String) -> Unit,
    onDismissAddServer: () -> Unit,
    onForgetServer: (String) -> Unit,
) {
    NavHost(nav, startDestination = startDestination) {
        composable<HomeRoute> {
            HomeScreen(
                state = state,
                onOpenServer = { d -> nav.navigate(BrowseRoute(d.id, "0", d.name)) },
                onSelectTarget = onSelectTarget,
                onRefresh = onRefresh,
                onOpenMyServer = { nav.navigate(ServerRoute) },
                onAddServer = onAddServer,
                onDismissAddServer = onDismissAddServer,
                onForgetServer = onForgetServer,
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
        composable<MirrorRoute> { MirrorScreen() }
    }
}
