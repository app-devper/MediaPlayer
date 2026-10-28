package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry

/**
 * A platform media player (ExoPlayer, AVPlayer, FFmpeg) as [LocalPlayer] drives it: one song or
 * video at a time, no rules of its own. Called on [LocalPlayer]'s thread only (the main thread).
 */
interface PlaybackEngine {
    /** Puts [url] in the player at [startPositionMs], playing or paused; clears any end or error. */
    fun load(entry: MediaEntry, url: String, playWhenReady: Boolean, startPositionMs: Long)

    /** Empties the player. */
    fun unload()

    fun play()
    fun pause()

    /** Clears the end: a finished item seeked back is no longer finished. */
    fun seekTo(positionMs: Long)

    /** 0..1. */
    fun setVolume(volume: Float)

    /** Loads the item again at [positionMs] (it failed); [LocalPlayer] plays it next. */
    fun reload(positionMs: Long)

    /** What the player is doing now; [LocalPlayer] polls it. */
    fun facts(): EngineFacts
}

data class EngineFacts(
    /** Meant to play: playing, or waiting to (not paused). */
    val playing: Boolean = false,
    /** Waiting for data. */
    val loading: Boolean = false,
    /** Played to its end. */
    val ended: Boolean = false,
    val positionMs: Long = 0,
    /** 0 when unknown (live streams). */
    val durationMs: Long = 0,
    /** Why it can't play, if it failed. */
    val error: String? = null,
    /** On another screen (Picture in Picture, a TV over AirPlay): it plays on in the background. */
    val shownElsewhere: Boolean = false,
)
