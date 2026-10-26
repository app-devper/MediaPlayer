package com.appdevper.mediaplayer.domain.playback

import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.repository.CastReceiverRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * This device as a cast target: starts/stops the receiver from settings, turns what other devices
 * ask for into [PlaybackSession] calls, and reports local playback back to them.
 */
class CastReceiverSession(
    private val receiver: CastReceiverRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackSession,
    private val scope: CoroutineScope,
) {
    val status: StateFlow<ReceiverStatus> get() = receiver.status

    private val _openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when something was cast here, so the UI can bring up the player. */
    val openPlayer: SharedFlow<Unit> = _openPlayer.asSharedFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.serverSettings
                .map { if (it.receiverEnabled) it.name else null }
                .distinctUntilChanged()
                .collectLatest { name -> if (name == null) receiver.stop() else receiver.start(name) }
        }
        scope.launch { receiver.commands.collect { playback.apply(it) { _openPlayer.tryEmit(Unit) } } }
        scope.launch {
            playback.state.map { it.receiverPlayback() }.distinctUntilChanged().collect(receiver::publish)
        }
    }
}
