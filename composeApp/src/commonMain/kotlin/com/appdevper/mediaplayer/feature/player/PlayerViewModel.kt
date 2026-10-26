package com.appdevper.mediaplayer.feature.player

import androidx.lifecycle.ViewModel
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.playback.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Now playing: local player, photo viewer or renderer remote control. */
class PlayerViewModel(private val session: PlaybackSession, airPlay: AirPlayReceiverSession) : ViewModel() {
    val uiState: StateFlow<PlaybackState> = session.state

    /** An iPhone/iPad/Mac playing audio here takes the player over (our own player is paused meanwhile). */
    val airPlay: StateFlow<AirPlayActivity> = airPlay.activity

    /** For the local player: commands from a device casting to this one. */
    val localCommands: Flow<LocalPlayerCommand> = session.localCommands

    fun next() = session.next()
    fun previous() = session.previous()
    fun playAt(index: Int) = session.playAt(index)
    fun onLocalEnded() = session.next()
    fun reportLocalStatus(status: RendererStatus) = session.reportLocalStatus(status)
    fun togglePause() = session.togglePause()
    fun stop() = session.stop()
    fun seek(positionMs: Long) = session.seek(positionMs)
    fun setVolume(volume: Int) = session.setVolume(volume)
    fun playHere() = session.setTarget(PlaybackTarget.Local)
}
