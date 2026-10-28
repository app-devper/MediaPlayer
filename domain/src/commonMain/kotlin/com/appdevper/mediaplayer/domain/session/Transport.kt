package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.TransportPlayback
import kotlinx.coroutines.flow.StateFlow

/**
 * Where [PlaybackSession]'s current item plays: this device ([LocalPlayer]) or a DLNA renderer
 * ([RendererTransport]). The session sends every command to the one its target names.
 */
interface Transport {
    /** What it reports: status (ending with [com.appdevper.mediaplayer.domain.model.RendererStatus.ended]), volume, mute, error. */
    val playback: StateFlow<TransportPlayback>

    fun load(entry: MediaEntry, autoPlay: Boolean, startPositionMs: Long)

    /** Lets go of the item (the target changes). */
    fun unload()

    fun play()
    fun pause()

    /** Pauses and rewinds; reported as stopped until it plays again. */
    fun stop()

    fun seek(positionMs: Long)

    /** 0..100. Unmutes. */
    fun setVolume(volume: Int)

    /** Silent, and back to its volume. */
    fun setMuted(muted: Boolean)
}
