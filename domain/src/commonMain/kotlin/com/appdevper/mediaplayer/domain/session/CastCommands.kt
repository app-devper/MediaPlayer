package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState

/**
 * Plays what another device cast here (DLNA or AirPlay video) on the local player.
 * [openPlayer] brings the player on screen (on a load, and on play since it may have been closed).
 * Until the next load, the other commands are for our own player only, not a renderer we picked since.
 */
internal fun PlaybackSession.apply(command: CastCommand, openPlayer: () -> Unit) {
    if (command !is CastCommand.Load && state.value.target != PlaybackTarget.Local) return
    when (command) {
        is CastCommand.Load -> {
            setTarget(PlaybackTarget.Local)
            play(command.entry, listOf(command.entry), autoPlay = command.autoPlay, startPositionMs = command.startPositionMs, casted = true)
            openPlayer()
        }
        CastCommand.Play -> {
            play()
            openPlayer()
        }
        CastCommand.Pause -> pause()
        CastCommand.Stop -> stop()
        is CastCommand.Seek -> seek(command.positionMs)
        is CastCommand.SetVolume -> setVolume(command.volume)
        is CastCommand.SetMute -> setMuted(command.muted)
    }
}

/** Local playback as reported back to the sending device. */
internal fun PlaybackState.receiverPlayback(): ReceiverPlayback {
    val entry = current.takeIf { target == PlaybackTarget.Local }
    val status = when {
        entry == null -> null
        // Photos are shown by the photo viewer, which has nothing to report.
        entry.kind == MediaKind.IMAGE -> RendererStatus(TransportState.PLAYING, 0, 0)
        else -> local
    }
    return ReceiverPlayback(entry, status, localVolume, localMuted)
}
