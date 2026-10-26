package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.platform.MediaLibraryFactory
import com.appdevper.mediaplayer.data.server.MediaServer
import com.appdevper.mediaplayer.data.server.ServerConfig
import com.appdevper.mediaplayer.data.server.ServerState
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
