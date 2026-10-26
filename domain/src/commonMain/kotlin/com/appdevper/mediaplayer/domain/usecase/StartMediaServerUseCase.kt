package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository

class StartMediaServerUseCase(
    private val server: MediaServerRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke() = server.start(settings.serverSettings.value)
}
