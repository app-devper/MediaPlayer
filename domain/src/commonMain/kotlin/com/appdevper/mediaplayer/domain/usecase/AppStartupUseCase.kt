package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.playback.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.playback.CastReceiverSession
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository

/** App launch: start network discovery, the DLNA and AirPlay receivers (they follow their settings) and, if enabled, the built-in server. */
class AppStartupUseCase(
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val server: MediaServerRepository,
    private val receiver: CastReceiverSession,
    private val airPlay: AirPlayReceiverSession,
) {
    suspend operator fun invoke() {
        devices.start()
        receiver.start()
        airPlay.start()
        if (settings.serverSettings.value.autoStart) server.start(settings.serverSettings.value)
    }
}
