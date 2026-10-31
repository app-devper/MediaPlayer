package app.devper.appplayer.domain.repository

import app.devper.appplayer.domain.model.ServerSettings
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val serverSettings: StateFlow<ServerSettings>
    fun update(transform: (ServerSettings) -> ServerSettings)
}
