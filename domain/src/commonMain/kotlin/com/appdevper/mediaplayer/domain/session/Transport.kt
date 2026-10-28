package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.RendererStatus

/**
 * Where [PlaybackSession]'s current item plays: this device ([LocalPlayer]) or a DLNA renderer
 * ([RendererTransport]). The session sends every command to the one its target names.
 */
interface Transport {
    /** The last status it knows; null until it knows one. */
    val lastStatus: RendererStatus?

    fun load(entry: MediaEntry, autoPlay: Boolean, startPositionMs: Long)

    /** Lets go of the item (the target changes). */
    fun unload()

    fun play()
    fun pause()

    /** Pauses and rewinds; reported as stopped until it plays again. */
    fun stop()

    fun seek(positionMs: Long)
}
