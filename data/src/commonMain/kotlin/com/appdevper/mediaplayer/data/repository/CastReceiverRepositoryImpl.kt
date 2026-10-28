package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.renderer.MediaRenderer
import com.appdevper.mediaplayer.data.renderer.RendererConfig
import com.appdevper.mediaplayer.domain.model.ReceiverLink
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.repository.CastReceiverRepository
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
