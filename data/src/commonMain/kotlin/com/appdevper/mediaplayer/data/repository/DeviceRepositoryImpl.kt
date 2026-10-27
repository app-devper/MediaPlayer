package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.mapper.toDomain
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DeviceRepositoryImpl(
    private val controlPoint: ControlPoint,
    scope: CoroutineScope,
    /** UDNs of devices this app runs itself and shouldn't offer as targets (our own renderer). */
    private val ownUdns: () -> Set<String> = { emptySet() },
    /** Addresses the user added by hand, kept across launches. */
    savedAddresses: List<String> = emptyList(),
    private val saveAddress: (String) -> Unit = {},
) : DeviceRepository {
    init {
        controlPoint.remember(savedAddresses)
    }

    override val devices: StateFlow<List<MediaDevice>> = controlPoint.devices
        .map { list -> list.filter { it.udn !in ownUdns() }.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val isSearching: StateFlow<Boolean> = controlPoint.searching

    override fun start() = controlPoint.start()
    override fun refresh() = controlPoint.refresh()
    override fun reset() = controlPoint.reset()
    override fun device(id: String): MediaDevice? = controlPoint.device(id)?.toDomain()

    override suspend fun addByAddress(address: String): List<MediaDevice> {
        val found = controlPoint.addByAddress(address).filter { it.udn !in ownUdns() }.map { it.toDomain() }
        // Only an address that has something of ours to show is kept (and asked again later).
        if (found.isNotEmpty()) {
            controlPoint.remember(listOf(address.trim()))
            saveAddress(address.trim())
        }
        return found
    }
}
