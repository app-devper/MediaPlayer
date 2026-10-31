package app.devper.appplayer.data.repository

import app.devper.appplayer.data.renderer.MediaRenderer
import app.devper.appplayer.data.renderer.RendererConfig
import app.devper.appplayer.domain.model.ReceiverLink
import app.devper.appplayer.domain.model.ReceiverPlayback
import app.devper.appplayer.domain.model.ReceiverStatus
import app.devper.appplayer.domain.repository.CastReceiverRepository
import kotlinx.coroutines.flow.StateFlow

class CastReceiverRepositoryImpl(
    private val renderer: MediaRenderer,
    private val settings: SettingsRepositoryImpl,
) : CastReceiverRepository {
    override val status: StateFlow<ReceiverStatus> = renderer.state
    override fun connect(link: ReceiverLink) = renderer.connect(link)

    override suspend fun start(name: String) = renderer.start(RendererConfig(name, settings.rendererUdn))
    override suspend fun stop() = renderer.stop()
    override fun publish(playback: ReceiverPlayback) = renderer.publish(playback)
}
