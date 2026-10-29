package com.appdevper.mediaplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.NowPlaying
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.RepeatMode
import com.appdevper.mediaplayer.domain.model.SubtitleChoice
import com.appdevper.mediaplayer.domain.model.SubtitleState
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

/** The player screen: which view it shows, and how its controls behave. The screen only lays it out. */
sealed interface PlayerUi {
    data object Empty : PlayerUi

    /** Music an AirPlay sender plays here. */
    data class SenderAudio(val audio: NowPlaying.SenderAudio) : PlayerUi

    /** Photos, one at a time; the pager follows [index]. */
    data class Photos(val photos: List<MediaEntry>, val index: Int) : PlayerUi

    /** A song or video on this device's player. */
    data class Local(val entry: MediaEntry, val volume: Int, val muted: Boolean, val controls: Controls) : PlayerUi

    /** A song or video (or photo) on a DLNA renderer, with its remote control. */
    data class Remote(
        val entry: MediaEntry,
        val renderer: MediaDevice,
        val status: RendererStatus?,
        val error: String?,
        /** Null until the renderer says. */
        val volume: Int?,
        val controls: Controls,
    ) : PlayerUi
}

/** The transport controls and the queue under them, the same wherever the item plays. */
data class Controls(
    /** Play/Pause shows Pause: playing, or about to. */
    val playing: Boolean,
    val nextEnabled: Boolean,
    /** Shuffle and repeat (not for something cast here, or a sender's queue, or a photo). */
    val modes: Modes?,
    /** The queue in the order it plays; empty with a single item. */
    val upNext: List<QueueRow>,
)

/** Shuffle (null with one item) and repeat. */
data class Modes(val shuffle: Boolean?, val repeat: RepeatMode)

/** [index] in the queue, to play it. */
data class QueueRow(val index: Int, val entry: MediaEntry, val current: Boolean)

/** Now playing: local player, photo viewer or renderer remote control. */
class PlayerViewModel(private val session: PlaybackSession) : ViewModel() {

    // Worked out where the session changes (Unconfined), so it's never a frame behind a tap.
    val uiState: StateFlow<PlayerUi> = combine(session.state, session.nowPlaying, ::playerUi)
        .stateIn(viewModelScope + Dispatchers.Unconfined, SharingStarted.Eagerly, playerUi(session.state.value, session.nowPlaying.value))

    /** This device's player, which the player screen shows and controls. */
    val localPlayer: LocalPlayer = session.local

    /** Our queue, or the AirPlay sender's when it decides (YouTube, Music…). */
    fun next() {
        session.next()
    }

    /** Back to the start after a few seconds, else the previous item (or the sender's). */
    fun previous() {
        session.previous()
    }

    /** The subtitle on this device's player (off, a file, a track in the video) and whether it loads. */
    val subtitles: StateFlow<SubtitleState> = session.local.subtitles

    /** Subtitle tracks inside the playing file. */
    val textTracks: StateFlow<List<TextTrack>> = session.local.textTracks

    fun selectSubtitle(choice: SubtitleChoice) = session.local.selectSubtitle(choice)

    fun playAt(index: Int) = session.playAt(index)
    fun toggleShuffle() = session.setShuffle(!session.state.value.shuffle)
    fun cycleRepeat() = session.cycleRepeat()

    /** Where it plays now, the sender's music included. */
    fun togglePause() = session.togglePause()
    fun stop() = session.stop()
    fun seek(positionMs: Long) = session.seek(positionMs)
    fun setVolume(volume: Int) = session.setVolume(volume)
    fun setMuted(muted: Boolean) = session.setMuted(muted)
    fun playHere() = session.setTarget(PlaybackTarget.Local)

    companion object {
        fun playerUi(s: PlaybackState, now: NowPlaying): PlayerUi = when (now) {
            NowPlaying.Nothing -> PlayerUi.Empty
            is NowPlaying.SenderAudio -> PlayerUi.SenderAudio(now)
            is NowPlaying.Item -> when (val target = now.target) {
                is PlaybackTarget.Renderer -> PlayerUi.Remote(
                    now.entry, target.device, s.playback.status, s.playback.error, s.playback.volume, controls(s, now),
                )
                PlaybackTarget.Local ->
                    if (now.isPhoto) PlayerUi.Photos(s.queue, s.index)
                    else PlayerUi.Local(now.entry, s.playback.volume ?: 100, s.playback.muted, controls(s, now))
            }
        }

        private fun controls(s: PlaybackState, now: NowPlaying.Item) = Controls(
            playing = now.playing == true,
            nextEnabled = now.senderSkips || s.hasNext,
            modes = Modes(shuffle = s.shuffle.takeIf { s.queue.size > 1 }, repeat = s.repeat)
                .takeIf { !s.casted && !now.senderSkips && !now.isPhoto },
            upNext = if (s.queue.size > 1) s.order.map { QueueRow(it, s.queue[it], it == s.index) } else emptyList(),
        )
    }
}
