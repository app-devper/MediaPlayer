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
) : DeviceRepository {
    override val devices: StateFlow<List<MediaDevice>> = controlPoint.devices
        .map { list -> list.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val isSearching: StateFlow<Boolean> = controlPoint.searching

    override fun start() = controlPoint.start()
    override fun refresh() = controlPoint.refresh()
    override fun reset() = controlPoint.reset()
    override fun device(id: String): MediaDevice? = controlPoint.device(id)?.toDomain()
}
