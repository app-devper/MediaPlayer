package app.devper.appplayer.domain.session

import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.ServerCapabilities
import app.devper.appplayer.domain.model.ServerSettings
import app.devper.appplayer.domain.model.ServerStatus
import app.devper.appplayer.domain.repository.MediaServerRepository
import app.devper.appplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The built-in DLNA server as the app runs it. Like the receivers, it follows its settings: a change
 * to what it publishes (its name, what it shares, its folders) restarts it once while it runs,
 * wherever the change comes from; and at launch it starts if set to.
 */
class MediaServerSession(
    private val server: MediaServerRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val status: StateFlow<ServerStatus> get() = server.status
    val serverSettings: StateFlow<ServerSettings> get() = settings.serverSettings
    val capabilities: ServerCapabilities get() = server.capabilities

    private var started = false

    /** At launch: follows the settings from now on, and starts the server if set to. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.serverSettings
                .map { Published(it.name, it.sharedKinds, it.folders) }
                .distinctUntilChanged()
                .drop(1) // what it starts with
                .collect { if (server.status.value is ServerStatus.Running) server.start(settings.serverSettings.value) }
        }
        if (settings.serverSettings.value.autoStart) scope.launch { server.start(settings.serverSettings.value) }
    }

    suspend fun turnOn() = server.start(settings.serverSettings.value)

    suspend fun turnOff() = server.stop()

    fun update(transform: (ServerSettings) -> ServerSettings) = settings.update(transform)

    private data class Published(val name: String, val kinds: Set<MediaKind>, val folders: List<String>)
}
