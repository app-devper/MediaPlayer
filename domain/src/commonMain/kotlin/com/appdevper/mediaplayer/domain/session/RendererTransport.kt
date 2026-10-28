package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.TransportPlayback
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A DLNA renderer (TV, speaker…) as a [Transport], through [RendererRepository]: polls it every
 * [pollIntervalMs] and marks the status [RendererStatus.ended] when it finishes a track by itself.
 */
internal class RendererTransport(
    private val renderer: RendererRepository,
    private val device: MediaDevice,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long,
) : Transport {

    private val _playback = MutableStateFlow(TransportPlayback())
    override val playback: StateFlow<TransportPlayback> = _playback.asStateFlow()

    private var entry: MediaEntry? = null
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    override fun load(entry: MediaEntry, autoPlay: Boolean, startPositionMs: Long) {
        this.entry = entry
        loadJob?.cancel()
        stopPolling()
        _playback.update { it.copy(status = null, error = null) }
        loadJob = scope.launch {
            try {
                renderer.load(device, entry)
                if (_playback.value.volume == null) {
                    val v = runCatching { renderer.volume(device) }.getOrNull()
                    _playback.update { it.copy(volume = v) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setError(e.message ?: "The renderer rejected this media")
            }
            startPolling()
        }
    }

    override fun unload() {
        loadJob?.cancel()
        stopPolling()
        val p = _playback.value
        scope.launch {
            runCatching { renderer.stop(device) }
            // Not left silent for whoever plays on it next.
            if (p.muted && p.volume != null) runCatching { renderer.setVolume(device, p.volume) }
        }
    }

    override fun play() {
        command { renderer.play(it) }
        pollAgain()
    }

    override fun pause() = command { renderer.pause(it) }

    override fun stop() {
        stopPolling()
        command { renderer.stop(it) }
        _playback.update { p -> p.copy(status = p.status?.copy(state = TransportState.STOPPED, positionMs = 0, ended = false)) }
    }

    override fun seek(positionMs: Long) {
        command { renderer.seek(it, positionMs) }
        pollAgain()
    }

    /** After a stop or the end, polling stopped: the renderer's state is followed again. */
    private fun pollAgain() {
        if (pollJob?.isActive != true && loadJob?.isActive != true) startPolling()
    }

    override fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        _playback.update { it.copy(volume = v, muted = false) }
        command { renderer.setVolume(it, v) }
    }

    /** Volume 0 on the renderer, then back to its volume (the repository has no mute of its own). */
    override fun setMuted(muted: Boolean) {
        val back = _playback.value.volume ?: return // its volume isn't known yet: nothing to go back to
        _playback.update { it.copy(muted = muted) }
        command { renderer.setVolume(it, if (muted) 0 else back) }
    }

    private fun startPolling() {
        val photo = entry?.kind == MediaKind.IMAGE
        pollJob?.cancel()
        pollJob = scope.launch {
            var wasPlaying = false
            while (isActive) {
                val status = runCatching { renderer.status(device) }.getOrNull()
                if (status != null) {
                    // It finished the track by itself (a photo never does): the session moves on.
                    val ended = wasPlaying && status.state == TransportState.STOPPED && !photo
                    _playback.update { it.copy(status = status.copy(ended = ended)) }
                    if (ended) return@launch
                    wasPlaying = status.state.playing
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun setError(message: String?) = _playback.update { it.copy(error = message) }

    private fun command(block: suspend (MediaDevice) -> Unit) {
        scope.launch {
            try {
                block(device)
                setError(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setError(e.message)
            }
        }
    }
}
