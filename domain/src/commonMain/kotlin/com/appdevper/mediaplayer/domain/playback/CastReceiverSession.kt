package com.appdevper.mediaplayer.domain.playback

import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
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
        scope.launch { receiver.commands.collect(::handle) }
        scope.launch {
            playback.state.map(::snapshot).distinctUntilChanged().collect(receiver::publish)
        }
    }

    private fun handle(command: CastCommand) {
        when (command) {
            is CastCommand.Load -> {
                playback.setTarget(PlaybackTarget.Local)
                playback.play(command.entry, listOf(command.entry), autoPlay = command.autoPlay)
                _openPlayer.tryEmit(Unit)
            }
            CastCommand.Play -> playback.sendLocal(LocalPlayerCommand.Play)
            CastCommand.Pause -> playback.sendLocal(LocalPlayerCommand.Pause)
            CastCommand.Stop -> playback.sendLocal(LocalPlayerCommand.Stop)
            is CastCommand.Seek -> playback.sendLocal(LocalPlayerCommand.Seek(command.positionMs))
            is CastCommand.SetVolume -> playback.setLocalVolume(command.volume)
            is CastCommand.SetMute -> playback.setLocalMuted(command.muted)
        }
    }

    private fun snapshot(s: PlaybackState): ReceiverPlayback {
        val entry = s.current.takeIf { s.target == PlaybackTarget.Local }
        val status = when {
            entry == null -> null
            // Photos are shown by the photo viewer, which has nothing to report.
            entry.kind == MediaKind.IMAGE -> RendererStatus(TransportState.PLAYING, 0, 0)
            else -> s.local
        }
        return ReceiverPlayback(entry, status, s.localVolume, s.localMuted)
    }
}
