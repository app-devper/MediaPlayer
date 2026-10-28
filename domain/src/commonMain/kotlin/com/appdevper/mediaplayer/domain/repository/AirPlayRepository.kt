package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.ReceiverLink
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.SenderControl
import kotlinx.coroutines.flow.StateFlow

/** This device as an AirPlay receiver (screen mirroring and audio). */
interface AirPlayRepository : SenderControl {
    val status: StateFlow<AirPlayStatus>
    override val activity: StateFlow<AirPlayActivity>

    /** Where AirPlay video (YouTube, Safari…) asks the local player to play; set before [start]. */
    fun connect(link: ReceiverLink)

    /** Starts advertising as [name], or restarts with new settings. */
    suspend fun start(name: String, requirePin: Boolean)
    suspend fun stop()

    /** Latest local playback, for the sender's progress bar. */
    fun publish(playback: ReceiverPlayback)

    override suspend fun sendToSender(command: SenderCommand): Boolean
}
