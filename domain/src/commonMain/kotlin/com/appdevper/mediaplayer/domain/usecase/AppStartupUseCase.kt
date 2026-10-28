package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.session.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.session.CastReceiverSession
import com.appdevper.mediaplayer.domain.session.MediaServerSession

/** App launch: network discovery, then the DLNA and AirPlay receivers and the built-in server (each follows its settings). */
class AppStartupUseCase(
    private val devices: DeviceRepository,
    private val server: MediaServerSession,
    private val receiver: CastReceiverSession,
    private val airPlay: AirPlayReceiverSession,
) {
    operator fun invoke() {
        devices.start()
        receiver.start()
        airPlay.start()
        server.start()
    }
}
