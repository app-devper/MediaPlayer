package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.repository.DeviceRepository

/** Removes a server address the user added (it isn't asked anymore). */
class ForgetServerAddressUseCase(private val repo: DeviceRepository) {
    suspend operator fun invoke(address: String) = repo.forgetAddress(address)
}
