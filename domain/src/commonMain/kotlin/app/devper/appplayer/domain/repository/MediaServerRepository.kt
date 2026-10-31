package app.devper.appplayer.domain.repository

import app.devper.appplayer.domain.model.ServerCapabilities
import app.devper.appplayer.domain.model.ServerSettings
import app.devper.appplayer.domain.model.ServerStatus
import kotlinx.coroutines.flow.StateFlow

/** The built-in DLNA media server that shares this device's media. */
interface MediaServerRepository {
    val status: StateFlow<ServerStatus>
    val capabilities: ServerCapabilities
    suspend fun start(settings: ServerSettings)
    suspend fun stop()
}
