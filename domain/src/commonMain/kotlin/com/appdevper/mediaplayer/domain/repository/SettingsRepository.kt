package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.ServerSettings
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val serverSettings: StateFlow<ServerSettings>
    fun update(transform: (ServerSettings) -> ServerSettings)
}
