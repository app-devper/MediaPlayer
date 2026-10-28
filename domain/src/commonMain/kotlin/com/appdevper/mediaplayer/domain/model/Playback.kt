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

/**
 * What a [com.appdevper.mediaplayer.domain.session.Transport] reports: its status (null until it has
 * one), volume 0..100 (null until a renderer says), whether it's muted, and why a command failed.
 */
data class TransportPlayback(
    val status: RendererStatus? = null,
    val volume: Int? = null,
    val muted: Boolean = false,
    val error: String? = null,
)

data class PlaybackState(
    val target: PlaybackTarget = PlaybackTarget.Local,
    val queue: List<MediaEntry> = emptyList(),
    val index: Int = -1,
    /** What the current target is doing: this device's player, or the renderer. */
    val playback: TransportPlayback = TransportPlayback(),
    /** Whether the local player starts as soon as the current item loads. */
    val autoPlay: Boolean = true,
    /** Where the local player starts the current item. */
    val startPositionMs: Long = 0,
    /** Plays the queue in a random order ([order]). */
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** The queue's indices in the order they play: in turn, or shuffled with the item that started first. */
    val order: List<Int> = emptyList(),
    /** Another device cast this (a one-item queue): it ends as the sender expects, without repeat. */
    val casted: Boolean = false,
) {
    val current: MediaEntry? get() = queue.getOrNull(index)

    /** Repeat as it applies to this queue. */
    val effectiveRepeat: RepeatMode get() = if (casted) RepeatMode.OFF else repeat

    /** The item after this one in [order]; after the last, the first again with [RepeatMode.ALL]. */
    val nextIndex: Int? get() = step(+1)

    /** The item before this one in [order]; before the first, the last with [RepeatMode.ALL]. */
    val previousIndex: Int? get() = step(-1)

    val hasNext: Boolean get() = nextIndex != null
    val hasPrevious: Boolean get() = previousIndex != null

    private fun step(by: Int): Int? {
        val at = order.indexOf(index)
        if (at < 0) return null
        return order.getOrNull(at + by) ?: order.takeIf { effectiveRepeat == RepeatMode.ALL }?.let { it[(at + by).mod(it.size)] }
    }
}

/** What happens at the end of the queue, or of each item. */
enum class RepeatMode {
    OFF,
    /** The whole queue, over and over. */
    ALL,
    /** The current item, over and over (next and previous still move on). */
    ONE,
}
