package com.appdevper.mediaplayer.domain.model

// Receiving: other devices playing on this one (DLNA renderer, AirPlay video).

/** What another device asks this one to do while it acts as a receiver. */
sealed interface CastCommand {
    /** [startPositionMs] is part of the load: a separate seek could reach the previous item's player. */
    data class Load(val entry: MediaEntry, val autoPlay: Boolean, val startPositionMs: Long = 0) : CastCommand
    data object Play : CastCommand
    data object Pause : CastCommand
    data object Stop : CastCommand
    data class Seek(val positionMs: Long) : CastCommand
    data class SetVolume(val volume: Int) : CastCommand
    data class SetMute(val muted: Boolean) : CastCommand
}

/** Local playback as reported back to the sending device. [status] is null when nothing is loaded. */
data class ReceiverPlayback(
    val entry: MediaEntry? = null,
    val status: RendererStatus? = null,
    val volume: Int = 100,
    val muted: Boolean = false,
)

sealed interface ReceiverStatus {
    data object Stopped : ReceiverStatus
    data class Running(val name: String) : ReceiverStatus
    data class Failed(val message: String) : ReceiverStatus
}
