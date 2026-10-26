package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Makes this device a target other devices can play on (DLNA MediaRenderer). */
interface CastReceiverRepository {
    val status: StateFlow<ReceiverStatus>
    val commands: Flow<CastCommand>

    /** Starts advertising as [name], or restarts under a new name. */
    suspend fun start(name: String)
    suspend fun stop()

    /** Latest local playback, answered to status queries and evented to subscribers. */
    fun publish(playback: ReceiverPlayback)
}
