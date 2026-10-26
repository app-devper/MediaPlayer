package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** This device as an AirPlay receiver (screen mirroring and audio). */
interface AirPlayRepository {
    val status: StateFlow<AirPlayStatus>
    val activity: StateFlow<AirPlayActivity>

    /** AirPlay video (YouTube, Safari…): what the sender asks the local player to do. */
    val commands: Flow<CastCommand>

    /** Starts advertising as [name], or restarts with new settings. */
    suspend fun start(name: String, requirePin: Boolean)
    suspend fun stop()

    /** Latest local playback, for the sender's progress bar. */
    fun publish(playback: ReceiverPlayback)
}
