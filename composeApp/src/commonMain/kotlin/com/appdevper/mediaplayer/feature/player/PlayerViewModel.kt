package com.appdevper.mediaplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.session.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Now playing: local player, photo viewer or renderer remote control. */
class PlayerViewModel(private val session: PlaybackSession, private val airPlaySession: AirPlayReceiverSession) : ViewModel() {
    val uiState: StateFlow<PlaybackState> = session.state

    /** This device's player, which the player screen shows and controls. */
    val localPlayer: LocalPlayer = session.local

    /** An iPhone/iPad/Mac playing audio here takes the player over (our own player is paused meanwhile). */
    val airPlay: StateFlow<AirPlayActivity> = airPlaySession.activity

    /** Our queue, or the AirPlay sender's when it plays here (YouTube, Music…). */
    fun next() {
        session.next()
    }

    /** Back to the start after a few seconds, else the previous item (or the sender's). */
    fun previous() {
        session.previous()
    }

    /** Play/pause on the sender, for its music (our player isn't the one playing it). */
    fun senderPlayPause() {
        viewModelScope.launch { airPlaySession.sendToSender(SenderCommand.PlayPause) }
    }

    fun playAt(index: Int) = session.playAt(index)
    fun togglePause() = session.togglePause()
    fun stop() = session.stop()
    fun seek(positionMs: Long) = session.seek(positionMs)
    fun setVolume(volume: Int) = session.setVolume(volume)
    fun setMuted(muted: Boolean) = session.setMuted(muted)
    fun playHere() = session.setTarget(PlaybackTarget.Local)
}
