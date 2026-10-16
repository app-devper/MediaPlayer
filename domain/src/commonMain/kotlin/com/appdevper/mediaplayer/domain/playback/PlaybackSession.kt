package com.appdevper.mediaplayer.domain.playback

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RemotePlayback
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
 * App-wide playback session: the queue, the current item and where it plays.
 * Local playback is rendered by the UI's native player; remote playback is driven from here
 * through [RendererRepository], polling the renderer once a second and auto-advancing.
 */
class PlaybackSession(
    private val renderer: RendererRepository,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 1_000,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var loadJob: Job? = null

    private val remoteDevice: MediaDevice? get() = (_state.value.target as? PlaybackTarget.Renderer)?.device

    fun setTarget(target: PlaybackTarget) {
        val previous = remoteDevice
        if (target == _state.value.target) return
        _state.update { it.copy(target = target, remote = RemotePlayback()) }
        if (previous != null) scope.launch { runCatching { renderer.stop(previous) } }
        if (remoteDevice != null && _state.value.current != null) loadCurrent() else stopPolling()
    }

    /**
     * Starts playing [entry]; the queue becomes the playable siblings of the same family
     * (photos with photos, audio/video with audio/video) — same as the old QueueHelper.
     */
    fun play(entry: MediaEntry, siblings: List<MediaEntry>) {
        val photos = entry.kind == MediaKind.IMAGE
        val queue = siblings.filter { it == entry || (it.isPlayable && (it.kind == MediaKind.IMAGE) == photos) }
            .ifEmpty { listOf(entry) }
        _state.update { it.copy(queue = queue, index = queue.indexOf(entry).coerceAtLeast(0)) }
        if (remoteDevice != null) loadCurrent()
    }

    fun playAt(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index) }
        if (remoteDevice != null) loadCurrent()
    }

    fun next(): Boolean {
        val s = _state.value
        if (!s.hasNext) return false
        playAt(s.index + 1)
        return true
    }

    fun previous(): Boolean {
        val s = _state.value
        if (!s.hasPrevious) return false
        playAt(s.index - 1)
        return true
    }

    fun clear() {
        stopPolling()
        _state.update { it.copy(queue = emptyList(), index = -1, remote = RemotePlayback()) }
    }

    // region remote control

    private fun loadCurrent() {
        val device = remoteDevice ?: return
        val item = _state.value.current ?: return
        loadJob?.cancel()
        stopPolling()
        loadJob = scope.launch {
            _state.update { it.copy(remote = it.remote.copy(status = null, error = null)) }
            try {
                renderer.load(device, item)
                if (_state.value.remote.volume == null) {
                    val v = runCatching { renderer.volume(device) }.getOrNull()
                    _state.update { it.copy(remote = it.remote.copy(volume = v)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setError(e.message ?: "The renderer rejected this media")
            }
            startPolling(device)
        }
    }

    private fun startPolling(device: MediaDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var wasPlaying = false
            while (isActive && remoteDevice == device) {
                val status = runCatching { renderer.status(device) }.getOrNull()
                if (status != null) {
                    _state.update { it.copy(remote = it.remote.copy(status = status)) }
                    // The renderer finished the track on its own → advance.
                    if (wasPlaying && status.state == TransportState.STOPPED && _state.value.current?.kind != MediaKind.IMAGE) {
                        next()
                        return@launch
                    }
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

    private fun setError(message: String?) = _state.update { it.copy(remote = it.remote.copy(error = message)) }

    private fun remote(block: suspend (MediaDevice) -> Unit) {
        val device = remoteDevice ?: return
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

    fun togglePause() {
        val playing = _state.value.remote.status?.state == TransportState.PLAYING
        remote { if (playing) renderer.pause(it) else renderer.play(it) }
    }

    fun stop() {
        stopPolling()
        remote { renderer.stop(it) }
        _state.update { s ->
            s.copy(remote = s.remote.copy(status = s.remote.status?.copy(state = TransportState.STOPPED, positionMs = 0)))
        }
    }

    fun seek(positionMs: Long) = remote { renderer.seek(it, positionMs) }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        _state.update { it.copy(remote = it.remote.copy(volume = v)) }
        remote { renderer.setVolume(it, v) }
    }

    // endregion
}
