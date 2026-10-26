package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.repository.DeviceRepository

class GetDeviceUseCase(private val repo: DeviceRepository) {
    operator fun invoke(id: String): MediaDevice? = repo.device(id)
}
