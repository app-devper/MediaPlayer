package app.devper.appplayer.di

import app.devper.appplayer.data.di.AppScope
import app.devper.appplayer.data.di.dataModule
import app.devper.appplayer.data.di.platformDataModule
import app.devper.appplayer.domain.session.AirPlayReceiverSession
import app.devper.appplayer.domain.session.CastReceiverSession
import app.devper.appplayer.domain.session.LocalPlayer
import app.devper.appplayer.domain.session.MediaServerSession
import app.devper.appplayer.domain.session.PlaybackSession
import app.devper.appplayer.domain.usecase.AppStartupUseCase
import app.devper.appplayer.domain.usecase.BrowseFolderUseCase
import app.devper.appplayer.domain.usecase.FilterEntriesUseCase
import app.devper.appplayer.feature.browse.BrowseViewModel
import app.devper.appplayer.feature.mirror.MirrorViewModel
import app.devper.appplayer.feature.player.PlayerViewModel
import app.devper.appplayer.feature.server.ServerViewModel
import app.devper.appplayer.feature.shell.ShellViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import app.devper.appplayer.domain.repository.AirPlayRepository
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** The app-wide sessions + use cases (domain layer wiring). */
val domainModule = module {
    // The platform's PlaybackEngine (platformAppModule) runs on the main thread.
    single { LocalPlayer(get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), subtitleFiles = get()) }
    single { PlaybackSession(get(), get(), get(AppScope), sender = get<AirPlayRepository>()) }
    single { CastReceiverSession(get(), get(), get(), get(AppScope)) }
    single { AirPlayReceiverSession(get(), get(), get(), get(AppScope)) }
    single { MediaServerSession(get(), get(), get(AppScope)) }
    factoryOf(::BrowseFolderUseCase)
    factoryOf(::FilterEntriesUseCase)
    factoryOf(::AppStartupUseCase)
}

/** ViewModels (presentation layer). */
val presentationModule = module {
    viewModelOf(::ShellViewModel)
    viewModelOf(::PlayerViewModel)
    viewModelOf(::ServerViewModel)
    viewModelOf(::MirrorViewModel)
    viewModel { (deviceId: String, folderId: String, title: String) ->
        BrowseViewModel(deviceId, folderId, title, get(), get(), get(), get())
    }
}

/** Platform services the app layer provides to data (e.g. Android foreground service). */
expect val platformAppModule: Module

/** Starts Koin once per process. */
fun initKoin(config: KoinAppDeclaration = {}) {
    if (KoinPlatform.getKoinOrNull() != null) return
    startKoin {
        config()
        modules(dataModule, platformDataModule, domainModule, presentationModule, platformAppModule)
    }
}
