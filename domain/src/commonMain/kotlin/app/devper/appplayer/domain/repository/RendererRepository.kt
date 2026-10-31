package app.devper.appplayer.domain.repository

import app.devper.appplayer.domain.model.MediaDevice
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.RendererStatus

/** Remote control of a renderer (TV, speaker). */
interface RendererRepository {
    suspend fun load(device: MediaDevice, entry: MediaEntry)
    suspend fun play(device: MediaDevice)
    suspend fun pause(device: MediaDevice)
    suspend fun stop(device: MediaDevice)
    suspend fun seek(device: MediaDevice, positionMs: Long)
    suspend fun status(device: MediaDevice): RendererStatus
    suspend fun volume(device: MediaDevice): Int?
    suspend fun setVolume(device: MediaDevice, volume: Int)
}
