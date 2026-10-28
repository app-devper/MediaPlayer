package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.session.EngineFacts
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.domain.session.PlaybackEngine
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import kotlinx.coroutines.CoroutineScope

/** A platform player that does what it's told at once; [facts] can be set to anything. */
class FakeEngine : PlaybackEngine {
    val calls = mutableListOf<String>()
    var facts = EngineFacts()
    var gain = 1f

    override fun load(entry: MediaEntry, url: String, playWhenReady: Boolean, startPositionMs: Long) {
        calls += "load:${entry.id}@$startPositionMs"
        facts = EngineFacts(playing = playWhenReady, positionMs = startPositionMs, durationMs = 60_000)
    }

    override fun unload() {
        calls += "unload"
        facts = EngineFacts()
    }

    override fun play() {
        calls += "play"
        facts = facts.copy(playing = true)
    }

    override fun pause() {
        calls += "pause"
        facts = facts.copy(playing = false)
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek:$positionMs"
        facts = facts.copy(positionMs = positionMs, ended = false)
    }

    override fun setVolume(volume: Float) {
        gain = volume
    }

    override fun selectTextTrack(id: String?) {
        calls += "track:$id"
        facts = facts.copy(selectedTextTrack = id)
    }

    override fun reload(positionMs: Long) {
        calls += "reload:$positionMs"
        facts = facts.copy(error = null, positionMs = positionMs)
    }

    override fun facts() = facts

    /** Plays to the end by itself. */
    fun finish() {
        facts = facts.copy(ended = true, positionMs = facts.durationMs)
    }
}

fun playbackSession(renderer: RendererRepository, scope: CoroutineScope, engine: FakeEngine = FakeEngine(), pollIntervalMs: Long = 1_000) =
    PlaybackSession(renderer, LocalPlayer(engine, scope), scope, pollIntervalMs)
