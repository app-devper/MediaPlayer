package com.appdevper.mediaplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.playback.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.appdevper.mediaplayer.domain.playback.drives
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Now playing: local player, photo viewer or renderer remote control. */
class PlayerViewModel(private val session: PlaybackSession, private val airPlaySession: AirPlayReceiverSession) : ViewModel() {
    val uiState: StateFlow<PlaybackState> = session.state

    /** An iPhone/iPad/Mac playing audio here takes the player over (our own player is paused meanwhile). */
    val airPlay: StateFlow<AirPlayActivity> = airPlaySession.activity

    /** For the local player: commands from a device casting to this one. */
    val localCommands: Flow<LocalPlayerCommand> = session.localCommands

    /** What plays came from an AirPlay sender: skipping is its call (its queue: YouTube, Music…). */
    private fun senderDrives() = airPlay.value.drives(session.state.value.current)

    fun next() {
        if (senderDrives()) sendToSender(SenderCommand.Next) else session.next()
    }

    fun previous() {
        if (senderDrives()) sendToSender(SenderCommand.Previous) else session.previous()
    }

    /** Play/pause on the sender, for its music (our player isn't the one playing it). */
    fun senderPlayPause() = sendToSender(SenderCommand.PlayPause)

    private fun sendToSender(command: SenderCommand) {
        viewModelScope.launch { airPlaySession.sendToSender(command) }
    }

    fun playAt(index: Int) = session.playAt(index)
    fun onLocalEnded() = session.next()
    fun reportLocalStatus(status: RendererStatus) = session.reportLocalStatus(status)
    fun togglePause() = session.togglePause()
    fun stop() = session.stop()
    fun seek(positionMs: Long) = session.seek(positionMs)
    fun setVolume(volume: Int) = session.setVolume(volume)
    fun playHere() = session.setTarget(PlaybackTarget.Local)
}
