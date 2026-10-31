package app.devper.appplayer.data.di

import app.devper.appplayer.airplay.AirPlayPlayer
import app.devper.appplayer.airplay.createAirPlayReceiver
import app.devper.appplayer.data.renderer.MediaRenderer
import app.devper.appplayer.data.repository.AirPlayRepositoryImpl
import app.devper.appplayer.data.repository.CastReceiverRepositoryImpl
import app.devper.appplayer.data.repository.DeviceRepositoryImpl
import app.devper.appplayer.data.repository.LibraryRepositoryImpl
import app.devper.appplayer.data.repository.MediaServerRepositoryImpl
import app.devper.appplayer.data.repository.RendererRepositoryImpl
import app.devper.appplayer.data.repository.SettingsRepositoryImpl
import app.devper.appplayer.data.server.MediaServer
import app.devper.appplayer.data.upnp.ContentDirectoryClient
import app.devper.appplayer.data.upnp.ControlPoint
import app.devper.appplayer.data.upnp.SoapClient
import app.devper.appplayer.data.upnp.SsdpEngine
import app.devper.appplayer.domain.model.ReceiverStatus
import app.devper.appplayer.domain.repository.AirPlayRepository
import app.devper.appplayer.domain.repository.CastReceiverRepository
import app.devper.appplayer.domain.repository.DeviceRepository
import app.devper.appplayer.domain.repository.LibraryRepository
import app.devper.appplayer.domain.repository.MediaServerRepository
import app.devper.appplayer.domain.repository.RendererRepository
import app.devper.appplayer.domain.repository.SettingsRepository
import app.devper.appplayer.domain.repository.SubtitleRepository
import app.devper.appplayer.data.repository.SubtitleRepositoryImpl
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
