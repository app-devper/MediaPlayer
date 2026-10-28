package com.appdevper.mediaplayer.domain.model

// What plays where: the queue, the local player and DLNA renderers.

/** Where playback happens: on this device, or on a DLNA renderer (TV, speaker…). */
sealed interface PlaybackTarget {
    data object Local : PlaybackTarget
    data class Renderer(val device: MediaDevice) : PlaybackTarget
}

enum class TransportState { PLAYING, PAUSED, STOPPED, TRANSITIONING, NO_MEDIA, UNKNOWN }

data class RendererStatus(
    val state: TransportState,
    val positionMs: Long,
    val durationMs: Long,
    /** Played to its end (not stopped by a controller, nor by leaving the player). Local players only. */
    val ended: Boolean = false,
)

data class RemotePlayback(
    val status: RendererStatus? = null,
    val volume: Int? = null,
    val error: String? = null,
)

data class PlaybackState(
    val target: PlaybackTarget = PlaybackTarget.Local,
    val queue: List<MediaEntry> = emptyList(),
    val index: Int = -1,
    val remote: RemotePlayback = RemotePlayback(),
    /** Whether the local player starts as soon as the current item loads. */
    val autoPlay: Boolean = true,
    /** Where the local player starts the current item. */
    val startPositionMs: Long = 0,
    /** Last state reported by the local player, null until it reports. */
    val local: RendererStatus? = null,
    val localVolume: Int = 100,
    val localMuted: Boolean = false,
    /** Counts play()/playAt(): a player that outlives its screen loads the same item again when it changes. */
    val loads: Int = 0,
) {
    val current: MediaEntry? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index < queue.lastIndex
    val hasPrevious: Boolean get() = index > 0
}

/** Commands for the on-screen player that don't come from its own controls (e.g. a DLNA controller). */
sealed interface LocalPlayerCommand {
    data object Play : LocalPlayerCommand
    data object Pause : LocalPlayerCommand
    /** Pause and rewind; the player reports [TransportState.STOPPED] until it plays again. */
    data object Stop : LocalPlayerCommand
    data class Seek(val positionMs: Long) : LocalPlayerCommand
}
