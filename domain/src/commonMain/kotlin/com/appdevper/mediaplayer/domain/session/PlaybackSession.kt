package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.NowPlaying
import com.appdevper.mediaplayer.domain.model.PlaybackState
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.Receiver
import com.appdevper.mediaplayer.domain.model.RepeatMode
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.SenderControl
import com.appdevper.mediaplayer.domain.model.TransportPlayback
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * App-wide playback session: the queue, the current item and where it plays. Every command (play,
 * pause, stop, seek, volume, next, previous) goes to the [Transport] the target names: [local], this
 * device's player, or a DLNA renderer through [RendererRepository]. Either way, an item that ends by
 * itself moves the queue on. While an AirPlay [sender] decides what plays (its music, or its video
 * here), play/pause, next and previous go to it instead ([nowPlaying]).
 */
class PlaybackSession(
    private val renderer: RendererRepository,
    /** This device's player; platform code shows it and passes the app's lifecycle to it. */
    val local: LocalPlayer,
    private val scope: CoroutineScope,
    private val sender: SenderControl = SenderControl.None,
    private val pollIntervalMs: Long = 1_000,
    private val random: Random = Random,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // The transport the target names; its playback becomes the state's.
    private val current = MutableStateFlow<Transport>(local)
    private val transport: Transport get() = current.value

    // What the new transport reported when the target switched (see init).
    @kotlin.concurrent.Volatile private var staleAtSwitch: TransportPlayback? = null

    // Play/pause sent to the sender's music, which doesn't say when it's paused; new music plays.
    private val senderPaused = MutableStateFlow(false)

    /** What plays now and where its controls go. */
    val nowPlaying: StateFlow<NowPlaying> = combine(_state, sender.activity, senderPaused, ::nowPlaying)
        .stateIn(scope, SharingStarted.Eagerly, NowPlaying.Nothing)

    private fun nowPlaying(s: PlaybackState, a: AirPlayActivity, paused: Boolean): NowPlaying {
        if (a.audioOnly && !a.mirroring) return NowPlaying.SenderAudio(a.client, a.nowPlaying, a.canControlSender, paused)
        val item = s.current ?: return NowPlaying.Nothing
        val playing = when {
            item.kind == MediaKind.IMAGE -> null
            // A renderer can always be told to play.
            s.target is PlaybackTarget.Renderer -> s.playing ?: false
            else -> s.playback.status?.state?.takeIf { it != TransportState.STOPPED }?.playing
        }
        // Its AirPlay video, while it's the one here: the sender's queue decides what's next.
        val senderVideo = a.videoEntryId != null && s.castBy == Receiver.AIRPLAY && s.target == PlaybackTarget.Local && item.id == a.videoEntryId
        return NowPlaying.Item(item, s.target, playing, senderSkips = senderVideo && a.canControlSender && !a.mirroring)
    }

    init {
        // New music from the sender plays.
        scope.launch { sender.activity.collect { if (!it.audioOnly) senderPaused.value = false } }
        // One "ended" for every transport: an item that ends by itself moves the queue on.
        scope.launch {
            current.collectLatest { t ->
                // A StateFlow gives its current value first. The one it had when the target switched is old
                // (the previous item's status, which a controller casting here mustn't see): skip that very
                // value; anything it reports since is new. Its "ended" doesn't count either.
                val old = staleAtSwitch
                var wasEnded = t.playback.value.status?.ended == true
                t.playback.collect { playback ->
                    if (playback === old) return@collect
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
        val next = when (target) {
            PlaybackTarget.Local -> local
            is PlaybackTarget.Renderer -> RendererTransport(renderer, target.device, scope, pollIntervalMs)
        }
        staleAtSwitch = next.playback.value
        current.value = next
        // Its volume and mute; no status until it has one for the item it loads.
        _state.update { it.copy(target = target, playback = next.playback.value.copy(status = null)) }
        loadCurrent()
    }

    /**
     * Starts playing [entry]; the queue becomes the playable siblings of the same family
     * (photos with photos, audio/video with audio/video) — same as the old QueueHelper.
     * With [autoPlay] false the local player loads paused and waits for [play]. [castBy]: another
     * device sent it through that receiver, so it ends as the sender expects (no repeat).
     */
    fun play(entry: MediaEntry, siblings: List<MediaEntry>, autoPlay: Boolean = true, startPositionMs: Long = 0, castBy: Receiver? = null) {
        val photos = entry.kind == MediaKind.IMAGE
        val queue = siblings.filter { it == entry || (it.isPlayable && (it.kind == MediaKind.IMAGE) == photos) }
            .ifEmpty { listOf(entry) }
        val index = queue.indexOf(entry).coerceAtLeast(0)
        // No status yet: a controller casting here mustn't see the previous item's meanwhile.
        _state.update {
            it.copy(
                queue = queue, index = index, order = order(queue.size, index, it.shuffle), castBy = castBy,
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
     * sender's next while it decides ([NowPlaying.senderSkips]). False when there's none.
     */
    fun next(): Boolean = toSender(SenderCommand.Next) || skipTo(_state.value.nextIndex)

    /** Sends [command] to the sender while it decides what plays; false when it doesn't. */
    private fun toSender(command: SenderCommand): Boolean {
        if (!nowPlayingNow().senderSkips) return false
        scope.launch { sender.sendToSender(command) }
        return true
    }

    // Now, not as last collected: a command right after a change goes where it says.
    private fun nowPlayingNow() = nowPlaying(_state.value, sender.activity.value, senderPaused.value)

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
     * in the order (or the sender's previous, see [NowPlaying.senderSkips]); before the first, the
     * last with repeat all. With nothing before it, back to the start, and false.
     */
    fun previous(): Boolean {
        if (toSender(SenderCommand.Previous)) return true
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

    /** Where it plays now: our item here or on a renderer, or the sender's music. */
    fun togglePause() {
        val now = nowPlayingNow()
        if (now is NowPlaying.SenderAudio) {
            if (toSender(SenderCommand.PlayPause)) senderPaused.update { !it }
            return
        }
        if (transport.playback.value.status?.state?.playing == true) pause() else play()
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
