package app.devper.appplayer.domain.usecase

import app.devper.appplayer.domain.repository.DeviceRepository
import app.devper.appplayer.domain.session.AirPlayReceiverSession
import app.devper.appplayer.domain.session.CastReceiverSession
import app.devper.appplayer.domain.session.MediaServerSession

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
