package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.repository.SettingsRepository

/**
 * Saves settings. Returns true when the change affects what a running server publishes,
 * so the caller can restart it.
 */
class UpdateServerSettingsUseCase(private val repo: SettingsRepository) {
    operator fun invoke(transform: (ServerSettings) -> ServerSettings): Boolean {
        val before = repo.serverSettings.value
        repo.update(transform)
        val after = repo.serverSettings.value
        return before.name != after.name || before.sharedKinds != after.sharedKinds || before.folders != after.folders
    }
}
