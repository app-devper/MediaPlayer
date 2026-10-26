package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository

/** App launch: start network discovery and, if enabled, the built-in server. */
class AppStartupUseCase(
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val server: MediaServerRepository,
) {
    suspend operator fun invoke() {
        devices.start()
        if (settings.serverSettings.value.autoStart) server.start(settings.serverSettings.value)
    }
}
