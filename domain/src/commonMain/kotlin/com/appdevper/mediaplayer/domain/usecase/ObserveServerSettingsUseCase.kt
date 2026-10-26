package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

class ObserveServerSettingsUseCase(private val repo: SettingsRepository) {
    operator fun invoke(): StateFlow<ServerSettings> = repo.serverSettings
}
