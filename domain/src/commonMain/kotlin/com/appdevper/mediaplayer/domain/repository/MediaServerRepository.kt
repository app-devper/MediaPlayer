package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.ServerStatus
import kotlinx.coroutines.flow.StateFlow

/** The built-in DLNA media server that shares this device's media. */
interface MediaServerRepository {
    val status: StateFlow<ServerStatus>
    val capabilities: ServerCapabilities
    suspend fun start(settings: ServerSettings)
    suspend fun stop()
}
