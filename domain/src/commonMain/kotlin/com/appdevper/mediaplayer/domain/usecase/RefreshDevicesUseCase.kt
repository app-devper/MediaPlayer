package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.repository.DeviceRepository

class RefreshDevicesUseCase(private val repo: DeviceRepository) {
    /** [clear] forgets known devices first (pull-to-refresh / network change). */
    operator fun invoke(clear: Boolean = false) = if (clear) repo.reset() else repo.refresh()
}
