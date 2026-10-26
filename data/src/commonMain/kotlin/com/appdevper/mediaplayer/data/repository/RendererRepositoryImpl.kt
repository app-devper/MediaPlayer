package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.mapper.toDidl
import com.appdevper.mediaplayer.data.mapper.toDomain
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.data.upnp.RendererClient
import com.appdevper.mediaplayer.data.upnp.SoapClient
import com.appdevper.mediaplayer.data.upnp.UpnpDevice
import com.appdevper.mediaplayer.data.upnp.UpnpException
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.repository.RendererRepository

class RendererRepositoryImpl(
    private val controlPoint: ControlPoint,
    private val soap: SoapClient,
) : RendererRepository {
    private val clients = HashMap<String, RendererClient>()

    private fun client(device: MediaDevice): RendererClient {
        val upnp: UpnpDevice = controlPoint.device(device.id) ?: throw UpnpException("${device.name} is no longer available.")
        return clients.getOrPut(device.id) { RendererClient(soap, upnp) }
    }

    override suspend fun load(device: MediaDevice, entry: MediaEntry) = client(device).load(entry.toDidl())
    override suspend fun play(device: MediaDevice) = client(device).play()
    override suspend fun pause(device: MediaDevice) = client(device).pause()
    override suspend fun stop(device: MediaDevice) = client(device).stop()
    override suspend fun seek(device: MediaDevice, positionMs: Long) = client(device).seek(positionMs)
    override suspend fun status(device: MediaDevice): RendererStatus = client(device).status().toDomain()
    override suspend fun volume(device: MediaDevice): Int? = client(device).volume()
    override suspend fun setVolume(device: MediaDevice, volume: Int) = client(device).setVolume(volume)
}
