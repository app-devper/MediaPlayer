package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.Receiver
import com.appdevper.mediaplayer.domain.model.ReceiverLink
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState

/**
 * [receiver]'s line to this session: what another device cast through it plays on the local player.
 * [openPlayer] brings the player on screen (on a load, and on play since it may have been closed).
 * Until its next load, its other commands are only for what it loaded here, not for something picked,
 * cast or moved to a renderer since.
 */
internal fun PlaybackSession.link(receiver: Receiver, openPlayer: () -> Unit): ReceiverLink = object : ReceiverLink {
    override val owns: Boolean
        get() = state.value.let { it.castBy == receiver && it.target == PlaybackTarget.Local }

    override fun send(command: CastCommand): Boolean {
        val here = state.value.target == PlaybackTarget.Local
        when (command) {
            is CastCommand.Load -> {
                setTarget(PlaybackTarget.Local)
                play(command.entry, listOf(command.entry), autoPlay = command.autoPlay, startPositionMs = command.startPositionMs, castBy = receiver)
                openPlayer()
            }
            is CastCommand.SetVolume -> if (here) setVolume(command.volume) else return false
            is CastCommand.SetMute -> if (here) setMuted(command.muted) else return false
            else -> {
                if (!owns) return false
                when (command) {
                    CastCommand.Play -> {
                        play()
                        openPlayer()
                    }
                    CastCommand.Pause -> pause()
                    CastCommand.Stop -> stop()
                    is CastCommand.Seek -> seek(command.positionMs)
                    else -> Unit
                }
            }
        }
        return true
    }
}

/** Local playback as reported back to the sending device. */
internal fun PlaybackState.receiverPlayback(): ReceiverPlayback {
    val entry = current.takeIf { target == PlaybackTarget.Local }
    val status = when {
        entry == null -> null
        // Photos are shown by the photo viewer, which has nothing to report.
        entry.kind == MediaKind.IMAGE -> RendererStatus(TransportState.PLAYING, 0, 0)
        else -> playback.status
    }
    return ReceiverPlayback(entry, status, playback.volume ?: 100, playback.muted)
}
