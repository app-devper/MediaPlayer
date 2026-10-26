package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.MediaDevice
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
