package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RepeatMode
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    private val random: Random = Random,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // The transport the target names; its playback becomes the state's.
    private val current = MutableStateFlow<Transport>(local)
    private val transport: Transport get() = current.value

    /**
     * Next/previous for someone else's queue: an AirPlay sender playing here decides what comes next
     * ([AirPlayReceiverSession]). Returns true when it took the skip; `true` is next, `false` previous.
     */
    internal var senderSkip: ((next: Boolean) -> Boolean)? = null

    init {
        // One "ended" for every transport: an item that ends by itself moves the queue on.
        scope.launch {
            current.collectLatest { t ->
                var wasEnded = false
                t.playback.collect { playback ->
                    _state.update { it.copy(playback = playback) }
                    val ended = playback.status?.ended == true
                    if (ended && !wasEnded) onEnded()
                    wasEnded = ended
                }
            }
        }
    }

    fun setTarget(target: PlaybackTarget) {
        val s = _state.value
        if (target == s.target) return
        transport.unload()
        current.value = when (target) {
            PlaybackTarget.Local -> local
            is PlaybackTarget.Renderer -> RendererTransport(renderer, target.device, scope, pollIntervalMs)
        }
        _state.update { it.copy(target = target, playback = transport.playback.value) }
        loadCurrent()
    }

    /**
     * Starts playing [entry]; the queue becomes the playable siblings of the same family
     * (photos with photos, audio/video with audio/video) — same as the old QueueHelper.
     * With [autoPlay] false the local player loads paused and waits for [play]. [casted]: another
     * device sent it, so it ends as the sender expects (no repeat).
     */
    fun play(entry: MediaEntry, siblings: List<MediaEntry>, autoPlay: Boolean = true, startPositionMs: Long = 0, casted: Boolean = false) {
        val photos = entry.kind == MediaKind.IMAGE
        val queue = siblings.filter { it == entry || (it.isPlayable && (it.kind == MediaKind.IMAGE) == photos) }
            .ifEmpty { listOf(entry) }
        val index = queue.indexOf(entry).coerceAtLeast(0)
        // No status yet: a controller casting here mustn't see the previous item's meanwhile.
        _state.update {
            it.copy(
                queue = queue, index = index, order = order(queue.size, index, it.shuffle), casted = casted,
                autoPlay = autoPlay, startPositionMs = startPositionMs, playback = it.playback.copy(status = null),
            )
        }
        loadCurrent()
    }

    /** Shuffled: the current item first, then the rest in a random order. Else in turn. */
    private fun order(size: Int, first: Int, shuffle: Boolean): List<Int> = when {
        size == 0 -> emptyList()
        shuffle -> listOf(first) + (0 until size).filter { it != first }.shuffled(random)
        else -> (0 until size).toList()
    }

    fun setShuffle(shuffle: Boolean) =
        _state.update { it.copy(shuffle = shuffle, order = order(it.queue.size, it.index.coerceAtLeast(0), shuffle)) }

    fun setRepeat(repeat: RepeatMode) = _state.update { it.copy(repeat = repeat) }

    /** Off → all → one → off. */
    fun cycleRepeat() = _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }

    /** The user picked [index] in the queue. Shuffled, the order starts again from it, so the rest still plays. */
    fun playAt(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { if (it.shuffle) it.copy(order = order(it.queue.size, index, shuffle = true)) else it }
        moveTo(index)
    }

    /** To [index] within the current order (next, previous, the end). */
    private fun moveTo(index: Int) {
        _state.update { it.copy(index = index, autoPlay = true, startPositionMs = 0, playback = it.playback.copy(status = null)) }
        loadCurrent()
    }

    private fun loadCurrent() {
        val s = _state.value
        val item = s.current ?: return
        transport.load(item, s.autoPlay, s.startPositionMs)
    }

    /**
     * The next item in [PlaybackState.order] (the first again after the last, with repeat all), or the
     * sender's next when an AirPlay sender decides ([senderSkip]). False when there's none.
     */
    fun next(): Boolean = senderSkip?.invoke(true) == true || skipTo(_state.value.nextIndex)

    private fun skipTo(index: Int?): Boolean {
        if (index == null) return false
        moveTo(index)
        return true
    }

    /** The current item ended by itself: the same again with repeat one, else the next one. */
    private fun onEnded() {
        val s = _state.value
        if (s.effectiveRepeat == RepeatMode.ONE) moveTo(s.index) else skipTo(s.nextIndex)
    }

    /**
     * Like other players: back to the start once it has played [RESTART_MS], else the previous item
     * in the order (or the sender's previous, see [senderSkip]); before the first, the last with
     * repeat all. With nothing before it, back to the start, and false.
     */
    fun previous(): Boolean {
        if (senderSkip?.invoke(false) == true) return true
        val s = _state.value
        // Stopped or finished counts as the start: back to the previous item.
        val status = transport.playback.value.status
        val position = if (status == null || status.ended || status.state == TransportState.STOPPED) 0 else status.positionMs
        if (position > RESTART_MS) {
            seek(0)
            return true
        }
        if (!skipTo(s.previousIndex)) {
            seek(0)
            return false
        }
        return true
    }

    fun play() = transport.play()

    fun pause() = transport.pause()

    fun togglePause() {
        val state = transport.playback.value.status?.state
        if (state == TransportState.PLAYING || state == TransportState.TRANSITIONING) pause() else play()
    }

    /**
     * Stops and stays stopped: if the local player loads this item again (e.g. moved back from a
     * renderer) it loads paused instead of playing the stopped item again.
     */
    fun stop() {
        transport.stop()
        _state.update { it.copy(autoPlay = false) }
    }

    fun seek(positionMs: Long) = transport.seek(positionMs)

    /** 0..100, on the current target. */
    fun setVolume(volume: Int) = transport.setVolume(volume.coerceIn(0, 100))

    fun setMuted(muted: Boolean) = transport.setMuted(muted)

    private companion object {
        const val RESTART_MS = 3_000L
    }
}
