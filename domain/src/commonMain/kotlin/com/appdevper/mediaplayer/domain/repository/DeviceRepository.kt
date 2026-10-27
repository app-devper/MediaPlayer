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

    /**
     * Asks [address] directly (a host, host:port or a description URL), for networks or devices where
     * discovery can't find it (an iPhone without the multicast entitlement). Returns what answered,
     * which then shows in [devices] and is asked again on later launches; empty if nothing did.
     */
    suspend fun addByAddress(address: String): List<MediaDevice>
}
