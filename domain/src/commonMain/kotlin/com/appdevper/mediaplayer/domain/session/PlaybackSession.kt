package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RemotePlayback
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-wide playback session: the queue, the current item and where it plays. Every command (play,
 * pause, stop, seek, volume, next, previous) goes to the [Transport] the target names: [local], this
 * device's player, or a DLNA renderer through [RendererRepository]. Either way, an item that ends by
 * itself moves the queue on.
 */
class PlaybackSession(
    private val renderer: RendererRepository,
    /** This device's player; platform code shows it and passes the app's lifecycle to it. */
    val local: LocalPlayer,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 1_000,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var transport: Transport = local
    private var remoteJob: Job? = null

    /**
     * Next/previous for someone else's queue: an AirPlay sender playing here decides what comes next
     * ([AirPlayReceiverSession]). Returns true when it took the skip; `true` is next, `false` previous.
     */
    internal var senderSkip: ((next: Boolean) -> Boolean)? = null

    init {
        scope.launch {
            var wasEnded = false
            local.status.collect { status ->
                _state.update { it.copy(local = status) }
                val ended = status?.ended == true
                if (ended && !wasEnded && transport === local) advance()
                wasEnded = ended
            }
        }
    }

    fun setTarget(target: PlaybackTarget) {
        val s = _state.value
        if (target == s.target) return
        transport.unload()
        remoteJob?.cancel()
        transport = when (target) {
            PlaybackTarget.Local -> local
            is PlaybackTarget.Renderer -> RendererTransport(renderer, target.device, scope, pollIntervalMs).also(::follow)
        }
        _state.update { it.copy(target = target, remote = RemotePlayback()) }
        loadCurrent()
    }

    private fun follow(remote: RendererTransport) {
        remoteJob = scope.launch {
            remote.playback.collect { playback ->
                _state.update { it.copy(remote = playback) }
                if (playback.status?.ended == true) advance()
            }
        }
    }

    /**
     * Starts playing [entry]; the queue becomes the playable siblings of the same family
     * (photos with photos, audio/video with audio/video) — same as the old QueueHelper.
     * With [autoPlay] false the local player loads paused and waits for [play].
     */
    fun play(entry: MediaEntry, siblings: List<MediaEntry>, autoPlay: Boolean = true, startPositionMs: Long = 0) {
        val photos = entry.kind == MediaKind.IMAGE
        val queue = siblings.filter { it == entry || (it.isPlayable && (it.kind == MediaKind.IMAGE) == photos) }
            .ifEmpty { listOf(entry) }
        _state.update { it.copy(queue = queue, index = queue.indexOf(entry).coerceAtLeast(0), autoPlay = autoPlay, startPositionMs = startPositionMs) }
        loadCurrent()
    }

    fun playAt(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index, autoPlay = true, startPositionMs = 0) }
        loadCurrent()
    }

    private fun loadCurrent() {
        val s = _state.value
        val item = s.current ?: return
        transport.load(item, s.autoPlay, s.startPositionMs)
    }

    /** The next item, or the sender's next when an AirPlay sender decides ([senderSkip]). False when there's none. */
    fun next(): Boolean = senderSkip?.invoke(true) == true || advance()

    private fun advance(): Boolean {
        val s = _state.value
        if (!s.hasNext) return false
        playAt(s.index + 1)
        return true
    }

    /**
     * Like other players: back to the start once it has played [RESTART_MS], else the previous item
     * (or the sender's previous, see [senderSkip]). At the first item it goes back to the start and
     * returns false.
     */
    fun previous(): Boolean {
        if (senderSkip?.invoke(false) == true) return true
        val s = _state.value
        val position = transport.lastStatus?.positionMs ?: 0
        if (position > RESTART_MS) {
            seek(0)
            return true
        }
        if (!s.hasPrevious) {
            seek(0)
            return false
        }
        playAt(s.index - 1)
        return true
    }

    fun play() = transport.play()

    fun pause() = transport.pause()

    fun togglePause() {
        val state = transport.lastStatus?.state
        if (state == TransportState.PLAYING || state == TransportState.TRANSITIONING) pause() else play()
    }

    /**
     * Stops and stays stopped: if the local player loads this item again (e.g. moved back from a
     * renderer) it loads paused instead of playing the stopped item again.
     */
    fun stop() {
        transport.stop()
        if (transport === local) _state.update { it.copy(autoPlay = false) }
    }

    fun seek(positionMs: Long) = transport.seek(positionMs)

    /** 0..100, on the current target. */
    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        when (val t = transport) {
            is RendererTransport -> t.setVolume(v)
            else -> {
                _state.update { it.copy(localVolume = v) }
                applyLocalVolume()
            }
        }
    }

    /** This device's player only (renderers have their own mute). */
    fun setMuted(muted: Boolean) {
        _state.update { it.copy(localMuted = muted) }
        applyLocalVolume()
    }

    private fun applyLocalVolume() {
        val s = _state.value
        local.setVolume(if (s.localMuted) 0f else s.localVolume / 100f)
    }

    private companion object {
        const val RESTART_MS = 3_000L
    }
}
