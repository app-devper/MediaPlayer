package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.SenderCommand
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

    /** Asks the sender to skip or play/pause ([AirPlayActivity.canControlSender]); false if it didn't. */
    suspend fun sendToSender(command: SenderCommand): Boolean
}
