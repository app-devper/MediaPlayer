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
import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.domain.model.SubtitleCue
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.usecase.LoadSubtitlesUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Now playing: local player, photo viewer or renderer remote control. */
class PlayerViewModel(
    private val session: PlaybackSession,
    private val airPlaySession: AirPlayReceiverSession,
    private val loadSubtitles: LoadSubtitlesUseCase,
) : ViewModel() {
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

    private val _subtitles = MutableStateFlow(SubtitleState())

    /** The subtitle picked for the current video (off until one is picked) and its lines. */
    val subtitles: StateFlow<SubtitleState> = _subtitles.asStateFlow()
    private var subtitleJob: Job? = null

    init {
        // Another item: subtitles off again (its files are others).
        viewModelScope.launch { session.state.map { it.current }.distinctUntilChanged().collect { selectSubtitle(null) } }
        // The track that actually shows (kept through reloads; or picked in AVKit's own menu, which
        // replaces a file's subtitles).
        viewModelScope.launch {
            combine(session.local.selectedTextTrack, session.local.textTracks) { id, tracks -> id to tracks.firstOrNull { it.id == id } }
                .distinctUntilChanged()
                .collect { (id, track) ->
                    if (id != null && track == null) return@collect // not listed yet
                    val s = _subtitles.value
                    if (track != s.track) _subtitles.value = if (track != null) SubtitleState(track = track) else s.copy(track = null)
                }
        }
    }

    /** Subtitle tracks inside the playing file. */
    val textTracks: StateFlow<List<TextTrack>> = session.local.textTracks

    /** Shows [subtitle] (fetched and read first), or none (no file's, no track's). */
    fun selectSubtitle(subtitle: Subtitle?) {
        subtitleJob?.cancel()
        session.local.selectTextTrack(null)
        _subtitles.value = SubtitleState(selected = subtitle, loading = subtitle != null)
        if (subtitle == null) return
        subtitleJob = viewModelScope.launch {
            val result = loadSubtitles(subtitle)
            // Only for the pick still on screen (not one replaced, or turned off with another item).
            if (_subtitles.value.selected != subtitle) return@launch
            _subtitles.value = result.fold(
                onSuccess = { SubtitleState(selected = subtitle, cues = it) },
                onFailure = { SubtitleState(error = "Couldn't load the subtitles (${subtitle.label})") },
            )
        }
    }

    /** Shows a subtitle track inside the file (the player reads or draws it). */
    fun selectTrack(track: TextTrack) {
        subtitleJob?.cancel()
        _subtitles.value = SubtitleState(track = track)
        session.local.selectTextTrack(track.id)
    }

    fun playAt(index: Int) = session.playAt(index)
    fun toggleShuffle() = session.setShuffle(!session.state.value.shuffle)
    fun cycleRepeat() = session.cycleRepeat()
    fun togglePause() = session.togglePause()
    fun stop() = session.stop()
    fun seek(positionMs: Long) = session.seek(positionMs)
    fun setVolume(volume: Int) = session.setVolume(volume)
    fun setMuted(muted: Boolean) = session.setMuted(muted)
    fun playHere() = session.setTarget(PlaybackTarget.Local)
}

/**
 * Subtitles on the player: a file picked ([selected]) with its lines once read, or a track inside
 * the video ([track]), or why a file couldn't be read.
 */
data class SubtitleState(
    val selected: Subtitle? = null,
    val track: TextTrack? = null,
    val cues: List<SubtitleCue> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
