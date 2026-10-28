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
    /** Played to its end by itself (not stopped by a controller): the session moves on. */
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
    /** What this device's player is doing, null until it loaded something. */
    val local: RendererStatus? = null,
    val localVolume: Int = 100,
    val localMuted: Boolean = false,
) {
    val current: MediaEntry? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index < queue.lastIndex
    val hasPrevious: Boolean get() = index > 0
}
