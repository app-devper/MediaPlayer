package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.TextTrack

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

    /**
     * Shows the subtitle track [id] of [EngineFacts.textTracks], or none. Every load starts with none.
     * The engine either draws it itself or reports its text in [EngineFacts.subtitleText].
     */
    fun selectTextTrack(id: String?)

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
    /** Subtitle tracks in the file (text ones; once the player has read them). */
    val textTracks: List<TextTrack> = emptyList(),
    /** The selected track's text on screen now, for us to draw (null when none, or the engine draws it). */
    val subtitleText: String? = null,
)
