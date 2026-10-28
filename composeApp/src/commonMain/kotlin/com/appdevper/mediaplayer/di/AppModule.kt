package com.appdevper.mediaplayer.di

import com.appdevper.mediaplayer.data.di.AppScope
import com.appdevper.mediaplayer.data.di.dataModule
import com.appdevper.mediaplayer.data.di.platformDataModule
import com.appdevper.mediaplayer.domain.session.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.session.CastReceiverSession
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import com.appdevper.mediaplayer.domain.usecase.AddServerByAddressUseCase
import com.appdevper.mediaplayer.domain.usecase.AppStartupUseCase
import com.appdevper.mediaplayer.domain.usecase.BrowseFolderUseCase
import com.appdevper.mediaplayer.domain.usecase.FilterEntriesUseCase
import com.appdevper.mediaplayer.domain.usecase.ForgetServerAddressUseCase
import com.appdevper.mediaplayer.domain.usecase.GetDeviceUseCase
import com.appdevper.mediaplayer.domain.usecase.GetServerCapabilitiesUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveDevicesUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerSettingsUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerStatusUseCase
import com.appdevper.mediaplayer.domain.usecase.RefreshDevicesUseCase
import com.appdevper.mediaplayer.domain.usecase.StartMediaServerUseCase
import com.appdevper.mediaplayer.domain.usecase.StopMediaServerUseCase
import com.appdevper.mediaplayer.domain.usecase.UpdateServerSettingsUseCase
import com.appdevper.mediaplayer.feature.browse.BrowseViewModel
import com.appdevper.mediaplayer.feature.mirror.MirrorViewModel
import com.appdevper.mediaplayer.feature.player.PlayerViewModel
import com.appdevper.mediaplayer.feature.server.ServerViewModel
import com.appdevper.mediaplayer.feature.shell.ShellViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** Use cases + the app-wide playback session (domain layer wiring). */
val domainModule = module {
    // The platform's PlaybackEngine (platformAppModule) runs on the main thread.
    single { LocalPlayer(get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), subtitleFiles = get()) }
    single { PlaybackSession(get(), get(), get(AppScope)) }
    single { CastReceiverSession(get(), get(), get(), get(AppScope)) }
    single { AirPlayReceiverSession(get(), get(), get(), get(AppScope)) }
    factoryOf(::ObserveDevicesUseCase)
    factoryOf(::RefreshDevicesUseCase)
    factoryOf(::AddServerByAddressUseCase)
    factoryOf(::ForgetServerAddressUseCase)
    factoryOf(::GetDeviceUseCase)
    factoryOf(::BrowseFolderUseCase)
    factoryOf(::FilterEntriesUseCase)
    factoryOf(::ObserveServerStatusUseCase)
    factoryOf(::ObserveServerSettingsUseCase)
    factoryOf(::UpdateServerSettingsUseCase)
    factoryOf(::StartMediaServerUseCase)
    factoryOf(::StopMediaServerUseCase)
    factoryOf(::GetServerCapabilitiesUseCase)
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
