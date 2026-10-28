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

/** This device's receivers: DLNA ("Play to" from other apps) and AirPlay. */
enum class Receiver { DLNA, AIRPLAY }

/**
 * A receiver's line to the playback session, given to it before it starts. Commands apply at once,
 * so the receiver knows right away whether this device plays what it loaded.
 */
interface ReceiverLink {
    /**
     * Applies [command]. A load always plays here. Volume and mute apply while this device plays; the
     * rest only while [owns]. False when it's refused.
     */
    fun send(command: CastCommand): Boolean

    /** This device plays what the receiver loaded last, not something picked or cast since. */
    val owns: Boolean
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
