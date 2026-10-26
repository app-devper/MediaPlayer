package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.StateFlow

class ObserveDevicesUseCase(private val repo: DeviceRepository) {
    operator fun invoke(): StateFlow<List<MediaDevice>> = repo.devices
    val isSearching: StateFlow<Boolean> get() = repo.isSearching
}
