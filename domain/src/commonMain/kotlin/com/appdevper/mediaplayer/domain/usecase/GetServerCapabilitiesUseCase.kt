package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository

class GetServerCapabilitiesUseCase(private val repo: MediaServerRepository) {
    operator fun invoke(): ServerCapabilities = repo.capabilities
}
