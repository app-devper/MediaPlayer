package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.RemotePlayback
import com.appdevper.mediaplayer.domain.model.RendererStatus
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

    private val _playback = MutableStateFlow(RemotePlayback())
    val playback: StateFlow<RemotePlayback> = _playback.asStateFlow()

    override val lastStatus: RendererStatus? get() = _playback.value.status

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
        scope.launch { runCatching { renderer.stop(device) } }
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

    fun setVolume(volume: Int) {
        _playback.update { it.copy(volume = volume) }
        command { renderer.setVolume(it, volume) }
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
                    wasPlaying = status.state == TransportState.PLAYING || status.state == TransportState.TRANSITIONING
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
