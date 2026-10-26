package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import kotlinx.coroutines.flow.StateFlow

/** This device as an AirPlay receiver (screen mirroring and audio). */
interface AirPlayRepository {
    val status: StateFlow<AirPlayStatus>
    val activity: StateFlow<AirPlayActivity>

    /** Starts advertising as [name], or restarts with new settings. */
    suspend fun start(name: String, requirePin: Boolean)
    suspend fun stop()
}
