package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.repository.MediaServerRepository

class StopMediaServerUseCase(private val server: MediaServerRepository) {
    suspend operator fun invoke() = server.stop()
}
