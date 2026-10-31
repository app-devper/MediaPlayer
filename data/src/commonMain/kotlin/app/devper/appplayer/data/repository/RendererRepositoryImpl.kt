package app.devper.appplayer.data.repository

import app.devper.appplayer.data.mapper.toDidl
import app.devper.appplayer.data.mapper.toDomain
import app.devper.appplayer.data.upnp.ControlPoint
import app.devper.appplayer.data.upnp.RendererClient
import app.devper.appplayer.data.upnp.SoapClient
import app.devper.appplayer.data.upnp.UpnpDevice
import app.devper.appplayer.data.upnp.UpnpException
import app.devper.appplayer.domain.model.MediaDevice
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.RendererStatus
import app.devper.appplayer.domain.repository.RendererRepository

class RendererRepositoryImpl(
    private val controlPoint: ControlPoint,
    private val soap: SoapClient,
) : RendererRepository {
    // Made for each command from what the control point knows now: a renderer that moved (another
    // port, a new address) is reached where it is.
    private fun client(device: MediaDevice): RendererClient {
        val upnp: UpnpDevice = controlPoint.device(device.id) ?: throw UpnpException("${device.name} is no longer available.")
        return RendererClient(soap, upnp)
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
