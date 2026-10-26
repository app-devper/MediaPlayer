package com.appdevper.mediaplayer.domain.playback

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.repository.AirPlayRepository
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
 * This device as an AirPlay receiver: starts/stops it from settings, asks the UI to show the
 * mirrored screen when a sender starts mirroring, and pauses our own player while a sender plays.
 */
class AirPlayReceiverSession(
    private val airPlay: AirPlayRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackSession,
    private val scope: CoroutineScope,
) {
    val status: StateFlow<AirPlayStatus> get() = airPlay.status
    val activity: StateFlow<AirPlayActivity> get() = airPlay.activity

    private val _openMirror = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when a sender starts mirroring, so the UI can bring up the mirrored screen. */
    val openMirror: SharedFlow<Unit> = _openMirror.asSharedFlow()

    private var started = false

    fun start() {
        if (started || airPlay.status.value == AirPlayStatus.Unsupported) return
        started = true
        scope.launch {
            settings.serverSettings
                .map { s -> if (s.airPlayEnabled) s.name to s.airPlayRequirePin else null }
                .distinctUntilChanged()
                .collectLatest { config -> if (config == null) airPlay.stop() else airPlay.start(config.first, config.second) }
        }
        scope.launch {
            var wasMirroring = false
            var wasPlaying = false
            airPlay.activity.collect { a ->
                if (a.mirroring && !wasMirroring) _openMirror.tryEmit(Unit)
                val playing = a.mirroring || a.audioOnly
                if (playing && !wasPlaying) playback.sendLocal(LocalPlayerCommand.Pause) // don't play over the sender
                wasMirroring = a.mirroring
                wasPlaying = playing
            }
        }
    }
}
