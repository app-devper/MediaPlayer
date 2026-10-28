package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.mapper.toDomain
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class DeviceRepositoryImpl(
    private val controlPoint: ControlPoint,
    scope: CoroutineScope,
    /** UDNs of devices this app runs itself and shouldn't offer as targets (our own renderer). */
    private val ownUdns: () -> Set<String> = { emptySet() },
    /** Addresses the user added by hand, kept across launches. */
    savedAddresses: List<String> = emptyList(),
    private val saveAddress: (String) -> Unit = {},
    private val removeAddress: (String) -> Unit = {},
) : DeviceRepository {
    private val saved = MutableStateFlow(savedAddresses)
    override val savedAddresses: StateFlow<List<String>> = saved.asStateFlow()

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
            val text = address.trim()
            controlPoint.remember(listOf(text))
            saveAddress(text)
            saved.update { list -> if (list.any { it.equals(text, ignoreCase = true) }) list else list + text }
        }
        return found
    }

    override suspend fun forgetAddress(address: String) {
        controlPoint.forget(address)
        removeAddress(address)
        saved.update { list -> list.filterNot { it.equals(address.trim(), ignoreCase = true) } }
    }
}
