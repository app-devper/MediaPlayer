package app.devper.appplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import app.devper.appplayer.designsystem.theme.AppTheme
import app.devper.appplayer.domain.usecase.AppStartupUseCase
import app.devper.appplayer.ui.shell.AppShell
import io.ktor.client.HttpClient
import org.koin.compose.koinInject

@OptIn(ExperimentalCoilApi::class)
@Composable
fun App() {
    val http = koinInject<HttpClient>()
    val startup = koinInject<AppStartupUseCase>()
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .components { add(KtorNetworkFetcherFactory(http)) }
            .crossfade(true)
            .build()
    }
    LaunchedEffect(Unit) { startup() }
    AppTheme { AppShell() }
}
