package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.mapper.toDidl
import com.appdevper.mediaplayer.data.mapper.toDomain
import com.appdevper.mediaplayer.data.platform.MediaLibraryFactory
import com.appdevper.mediaplayer.data.server.MediaServer
import com.appdevper.mediaplayer.data.server.ServerConfig
import com.appdevper.mediaplayer.data.server.ServerState
import com.appdevper.mediaplayer.data.upnp.ContentDirectoryClient
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.data.upnp.RendererClient
import com.appdevper.mediaplayer.data.upnp.SoapClient
import com.appdevper.mediaplayer.data.upnp.UpnpDevice
import com.appdevper.mediaplayer.data.upnp.UpnpException
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.LibraryRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DeviceRepositoryImpl(
    private val controlPoint: ControlPoint,
    scope: CoroutineScope,
) : DeviceRepository {
    override val devices: StateFlow<List<MediaDevice>> = controlPoint.devices
        .map { list -> list.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val isSearching: StateFlow<Boolean> = controlPoint.searching

    override fun start() = controlPoint.start()
    override fun refresh() = controlPoint.refresh()
    override fun reset() = controlPoint.reset()
    override fun device(id: String): MediaDevice? = controlPoint.device(id)?.toDomain()
}

class LibraryRepositoryImpl(
    private val controlPoint: ControlPoint,
    private val contentDirectory: ContentDirectoryClient,
) : LibraryRepository {
    override suspend fun browse(deviceId: String, folderId: String): List<MediaEntry> {
        val device = controlPoint.device(deviceId) ?: throw UpnpException("This server is no longer available.")
        return contentDirectory.browseChildren(device, folderId).objects.map { it.toDomain() }
    }
}

class RendererRepositoryImpl(
    private val controlPoint: ControlPoint,
    private val soap: SoapClient,
) : RendererRepository {
    private val clients = HashMap<String, RendererClient>()

    private fun client(device: MediaDevice): RendererClient {
        val upnp: UpnpDevice = controlPoint.device(device.id) ?: throw UpnpException("${device.name} is no longer available.")
        return clients.getOrPut(device.id) { RendererClient(soap, upnp) }
    }

    override suspend fun load(device: MediaDevice, entry: MediaEntry) = client(device).load(entry.toDidl())
    override suspend fun play(device: MediaDevice) = client(device).play()
    override suspend fun pause(device: MediaDevice) = client(device).pause()
    override suspend fun stop(device: MediaDevice) = client(device).stop()
    override suspend fun seek(device: MediaDevice, positionMs: Long) = client(device).seek(positionMs)
    override suspend fun status(device: MediaDevice): RendererStatus = client(device).status().toDomain()
    override suspend fun volume(device: MediaDevice): Int? = client(device).volume()
    override suspend fun setVolume(device: MediaDevice, volume: Int) = client(device).setVolume(volume)
}

class MediaServerRepositoryImpl(
    private val server: MediaServer,
    private val libraries: MediaLibraryFactory,
    private val settings: SettingsRepositoryImpl,
    private val controlPoint: ControlPoint,
    scope: CoroutineScope,
) : MediaServerRepository {

    override val status: StateFlow<ServerStatus> = server.state
        .map { s ->
            when (s) {
                ServerState.Stopped -> ServerStatus.Stopped
                ServerState.Starting -> ServerStatus.Starting
                is ServerState.Running -> ServerStatus.Running(s.name, s.url, s.counts)
                is ServerState.Failed -> ServerStatus.Failed(s.message)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, ServerStatus.Stopped)

    override val capabilities: ServerCapabilities get() = libraries.capabilities

    override suspend fun start(settings: ServerSettings) {
        server.start(
            ServerConfig(settings.name, this.settings.serverUdn, settings.sharedKinds),
            libraries.create(settings.folders),
        )
        // Make our own server show up in the device list right away.
        controlPoint.refresh()
    }

    override suspend fun stop() = server.stop()
}
