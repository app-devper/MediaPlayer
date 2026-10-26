package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import kotlinx.coroutines.flow.StateFlow

class ObserveServerStatusUseCase(private val repo: MediaServerRepository) {
    operator fun invoke(): StateFlow<ServerStatus> = repo.status
}

/** Counts per kind for the server summary line. */
fun Map<MediaKind, Int>.countOf(kind: MediaKind): Int = this[kind] ?: 0
