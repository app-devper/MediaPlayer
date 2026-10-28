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

/** The control point's devices and added addresses, as domain models. */
class DeviceRepositoryImpl(private val controlPoint: ControlPoint, scope: CoroutineScope) : DeviceRepository {
    override val savedAddresses: StateFlow<List<String>> = controlPoint.addedAddresses

    override val devices: StateFlow<List<MediaDevice>> = controlPoint.devices
        .map { list -> list.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val isSearching: StateFlow<Boolean> = controlPoint.searching

    override fun start() = controlPoint.start()
    override fun refresh() = controlPoint.refresh()
    override fun reset() = controlPoint.reset()
    override fun device(id: String): MediaDevice? = controlPoint.device(id)?.toDomain()

    override suspend fun addByAddress(address: String): List<MediaDevice> = controlPoint.addByAddress(address).map { it.toDomain() }

    override suspend fun forgetAddress(address: String) = controlPoint.forget(address)
}
