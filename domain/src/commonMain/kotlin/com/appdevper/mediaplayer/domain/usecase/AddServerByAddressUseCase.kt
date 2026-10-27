package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.repository.DeviceRepository

/** Adds a media server the network doesn't announce, by its address. */
class AddServerByAddressUseCase(private val repo: DeviceRepository) {
    suspend operator fun invoke(address: String): List<MediaDevice> = repo.addByAddress(address)
}
