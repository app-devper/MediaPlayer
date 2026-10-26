package com.appdevper.mediaplayer.data.di

import com.appdevper.mediaplayer.data.renderer.MediaRenderer
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
import com.appdevper.mediaplayer.domain.repository.CastReceiverRepository
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.LibraryRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
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

/** Platform bindings: MulticastLock + MediaLibraryFactory. */
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
    single { ControlPoint(get(), get(), get(AppScope)) }
    single { ContentDirectoryClient(get()) }
    single { MediaServer(get(), get(AppScope), get()) }
    single { MediaRenderer(get(), get(), get(AppScope)) }

    // Repositories
    single { SettingsRepositoryImpl() } bind SettingsRepository::class
    single<DeviceRepository> {
        val settings = get<SettingsRepositoryImpl>()
        val renderer = get<MediaRenderer>()
        // Hide our own renderer only while it runs, so a stale UDN never hides a real device.
        DeviceRepositoryImpl(get(), get(AppScope)) { if (renderer.state.value is ReceiverStatus.Running) setOf(settings.rendererUdn) else emptySet() }
    }
    single<LibraryRepository> { LibraryRepositoryImpl(get(), get()) }
    single<RendererRepository> { RendererRepositoryImpl(get(), get()) }
    single<MediaServerRepository> { MediaServerRepositoryImpl(get(), get(), get(), get(), get(AppScope)) }
    single<CastReceiverRepository> { CastReceiverRepositoryImpl(get(), get()) }
}
