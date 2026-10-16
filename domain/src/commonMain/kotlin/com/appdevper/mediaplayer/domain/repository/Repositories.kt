package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.ServerStatus
import kotlinx.coroutines.flow.StateFlow

/** Network discovery of media servers and renderers. */
interface DeviceRepository {
    val devices: StateFlow<List<MediaDevice>>
    val isSearching: StateFlow<Boolean>
    fun start()
    fun refresh()
    fun reset()
    fun device(id: String): MediaDevice?
}

/** Browsing the content of a media server. */
interface LibraryRepository {
    suspend fun browse(deviceId: String, folderId: String): List<MediaEntry>
}

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

/** The built-in DLNA media server that shares this device's media. */
interface MediaServerRepository {
    val status: StateFlow<ServerStatus>
    val capabilities: ServerCapabilities
    suspend fun start(settings: ServerSettings)
    suspend fun stop()
}

interface SettingsRepository {
    val serverSettings: StateFlow<ServerSettings>
    fun update(transform: (ServerSettings) -> ServerSettings)
}
