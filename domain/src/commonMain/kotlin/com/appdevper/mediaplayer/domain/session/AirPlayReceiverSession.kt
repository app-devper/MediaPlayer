package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.SenderCommand
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
 * mirrored screen when a sender starts mirroring, plays AirPlay video on the local player, and
 * pauses our own player while a sender mirrors or streams audio.
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

    private val _openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when a sender plays a video or music here, so the UI can bring up the player. */
    val openPlayer: SharedFlow<Unit> = _openPlayer.asSharedFlow()

    private var started = false

    /** Asks the sender to skip or play/pause in its own app; false when it didn't take it (or has none). */
    suspend fun sendToSender(command: SenderCommand): Boolean = airPlay.sendToSender(command)

    fun start() {
        if (started || airPlay.status.value == AirPlayStatus.Unsupported) return
        started = true
        scope.launch {
            settings.serverSettings
                .map { s -> if (s.airPlayEnabled) s.name to s.airPlayRequirePin else null }
                .distinctUntilChanged()
                .collectLatest { config -> if (config == null) airPlay.stop() else airPlay.start(config.first, config.second) }
        }
        // AirPlay video goes through the same local player as DLNA casts.
        scope.launch { airPlay.commands.collect { playback.apply(it) { _openPlayer.tryEmit(Unit) } } }
        scope.launch {
            playback.state.map { it.receiverPlayback() }.distinctUntilChanged().collect(airPlay::publish)
        }
        scope.launch {
            var wasMirroring = false
            var wasPlaying = false
            airPlay.activity.collect { a ->
                if (a.mirroring && !wasMirroring) _openMirror.tryEmit(Unit)
                val playing = a.mirroring || a.audioOnly
                if (playing && !wasPlaying) playback.sendLocal(LocalPlayerCommand.Pause) // don't play over the sender
                // Music from a sender shows on the player screen, like everything else that plays.
                if (a.audioOnly && !a.mirroring && !wasPlaying) _openPlayer.tryEmit(Unit)
                wasMirroring = a.mirroring
                wasPlaying = playing
            }
        }
    }
}

/**
 * The sender, not our queue, decides what's next: its music is playing, or [current] is its AirPlay
 * video. Then next/previous go to the sender ([AirPlayReceiverSession.sendToSender]).
 */
fun AirPlayActivity.drives(current: MediaEntry?): Boolean =
    canControlSender && !mirroring && (audioOnly || (videoEntryId != null && current?.id == videoEntryId))
