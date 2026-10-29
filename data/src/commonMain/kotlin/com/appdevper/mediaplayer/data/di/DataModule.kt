package com.appdevper.mediaplayer.data.di

import com.appdevper.mediaplayer.airplay.AirPlayPlayer
import com.appdevper.mediaplayer.airplay.createAirPlayReceiver
import com.appdevper.mediaplayer.data.renderer.MediaRenderer
import com.appdevper.mediaplayer.data.repository.AirPlayRepositoryImpl
import com.appdevper.mediaplayer.data.repository.CastReceiverRepositoryImpl
import com.appdevper.mediaplayer.data.repository.DeviceRepositoryImpl
import com.appdevper.mediaplayer.data.repository.LibraryRepositoryImpl
import com.appdevper.mediaplayer.data.repository.MediaServerRepositoryImpl
import com.appdevper.mediaplayer.data.repository.RendererRepositoryImpl
import com.appdevper.mediaplayer.data.repository.SettingsRepositoryImpl
import com.appdevper.mediaplayer.data.server.MediaServer
import com.appdevper.mediaplayer.data.upnp.ContentDirectoryClient
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.data.upnp.SoapClient
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.repository.AirPlayRepository
import com.appdevper.mediaplayer.domain.repository.CastReceiverRepository
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.LibraryRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.data.repository.SubtitleRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Qualifier for the app-lifetime coroutine scope. */
val AppScope = named("appScope")

/** Qualifier for the AirPlay receiver's own multicast lock (separate from SSDP's). */
val AirPlayMulticast = named("airPlayMulticast")

/** Platform bindings: MulticastLocks, MediaLibraryFactory, AppStorage. */
expect val platformDataModule: Module

val dataModule = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    // UPnP / DLNA infrastructure
    single { SsdpEngine(get(AppScope), get()) }
    single { SoapClient(get()) }
    single {
        val renderer = get<MediaRenderer>()
        // Our own renderer is hidden only while it runs, so a stale UDN never hides a real device.
        val settings = get<SettingsRepositoryImpl>()
        ControlPoint(
            get(), get(), get(AppScope),
            store = settings.addressStore,
            ownUdns = { if (renderer.state.value is ReceiverStatus.Running) setOf(settings.rendererUdn) else emptySet() },
        )
    }
    single { ContentDirectoryClient(get()) }
    single { MediaServer(get(), get(), get(AppScope), get()) }
    single { MediaRenderer(get(), get(), get(AppScope)) }

    // Repositories
    single { SettingsRepositoryImpl() } bind SettingsRepository::class
    single<DeviceRepository> { DeviceRepositoryImpl(get(), get(AppScope)) }
    single<LibraryRepository> { LibraryRepositoryImpl(get(), get()) }
    single<RendererRepository> { RendererRepositoryImpl(get(), get()) }
    single<MediaServerRepository> { MediaServerRepositoryImpl(get(), get(), get(), get(), get(AppScope)) }
    single<CastReceiverRepository> { CastReceiverRepositoryImpl(get(), get()) }
    single<SubtitleRepository> { SubtitleRepositoryImpl(get()) }

    // AirPlay receiver (null on iOS); the player is also what the UI draws mirroring with.
    single { AirPlayPlayer() }
    single<AirPlayRepository> {
        val player = get<AirPlayPlayer>()
        AirPlayRepositoryImpl(createAirPlayReceiver(), player, player::setVolume, get(), get(), get(AirPlayMulticast), get(AppScope))
    }
}
