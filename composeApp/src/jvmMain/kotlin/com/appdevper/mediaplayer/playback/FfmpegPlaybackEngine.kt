package com.appdevper.mediaplayer.playback

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.session.EngineFacts
import com.appdevper.mediaplayer.domain.session.PlaybackEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FFmpeg as the app's [PlaybackEngine], one for the whole app: a [FfmpegPlayer] per item, which the
 * video view shows ([player]) and which plays on when the view leaves the screen.
 */
class FfmpegPlaybackEngine : PlaybackEngine {

    private val _player = MutableStateFlow<FfmpegPlayer?>(null)

    /** The item's player, null with nothing loaded. */
    val player: StateFlow<FfmpegPlayer?> = _player.asStateFlow()

    private var url: String? = null
    private var gain = 1f

    override fun load(entry: MediaEntry, url: String, playWhenReady: Boolean, startPositionMs: Long) {
        this.url = url
        open(url, playWhenReady, startPositionMs)
    }

    private fun open(url: String, playWhenReady: Boolean, startPositionMs: Long) {
        _player.value?.release()
        _player.value = FfmpegPlayer(url, playWhenReady).apply {
            setVolume(gain)
            if (startPositionMs > 0) seek(startPositionMs)
        }
    }

    override fun unload() {
        _player.value?.release()
        _player.value = null
        url = null
    }

    override fun play() {
        _player.value?.play()
    }

    override fun pause() {
        _player.value?.pause()
    }

    override fun seekTo(positionMs: Long) {
        _player.value?.seek(positionMs)
    }

    override fun setVolume(volume: Float) {
        gain = volume
        _player.value?.setVolume(volume)
    }

    override fun reload(positionMs: Long) {
        url?.let { open(it, playWhenReady = false, startPositionMs = positionMs) }
    }

    override fun facts(): EngineFacts {
        val status = _player.value?.status?.value ?: return EngineFacts()
        return EngineFacts(
            playing = status.playing,
            loading = status.buffering,
            ended = status.ended,
            positionMs = status.positionMs,
            durationMs = status.durationMs,
            error = status.error,
        )
    }
}
