package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.model.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * This device's player, one for the app: plays [PlaybackSession]'s songs and videos on a
 * [PlaybackEngine] (ExoPlayer, AVPlayer, FFmpeg) whether a player screen shows it or not. The rules
 * every platform shares live here:
 * - loaded paused, finished, or stopped by a controller, it reports [TransportState.STOPPED], not PAUSED;
 * - Play after the end starts from the beginning; Play after an error loads the item again where it was;
 * - a video pauses while the app is in the background ([onAppBackground]), unless it's on another
 *   screen, and plays on when the app is back, unless anything else paused, played or stopped it meanwhile.
 *
 * It polls the engine every [pollIntervalMs] into [status]. Every engine call runs in [scope], which
 * must run on the engine's thread (the main thread in the app).
 */
class LocalPlayer(
    private val engine: PlaybackEngine,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : Transport {

    private val _status = MutableStateFlow<RendererStatus?>(null)

    /** What the player is doing; null until something loaded. Ends with [RendererStatus.ended]. */
    val status: StateFlow<RendererStatus?> = _status.asStateFlow()

    override val lastStatus: RendererStatus? get() = _status.value

    private val _error = MutableStateFlow<String?>(null)

    /** Why the current item can't play, if it failed. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _textTracks = MutableStateFlow<List<TextTrack>>(emptyList())

    /** Subtitle tracks inside the loaded file. */
    val textTracks: StateFlow<List<TextTrack>> = _textTracks.asStateFlow()

    private val _selectedTextTrack = MutableStateFlow<String?>(null)

    /**
     * The track inside the file that shows, or none: what was picked ([selectTextTrack]), or what the
     * platform's own controls picked (AVKit's menu). It stays through a reload of the same item.
     */
    val selectedTextTrack: StateFlow<String?> = _selectedTextTrack.asStateFlow()

    // The engine loses the choice on every load: until the tracks are known, it isn't applied yet.
    private var trackApplied = false

    private val _subtitleText = MutableStateFlow<String?>(null)

    /** The selected track's text on screen now, when we draw it ([selectTextTrack]). */
    val subtitleText: StateFlow<String?> = _subtitleText.asStateFlow()

    private val _loaded = MutableStateFlow<MediaEntry?>(null)

    /** The song or video in the player; null for nothing, a photo, or playing on a renderer. */
    val loaded: StateFlow<MediaEntry?> = _loaded.asStateFlow()

    // Loaded paused or stopped by a controller: STOPPED until it plays.
    private var stopped = true
    private var pausedInBackground = false
    private var volume = 1f
    private var poll: Job? = null

    override fun load(entry: MediaEntry, autoPlay: Boolean, startPositionMs: Long) = onPlayer {
        val url = entry.streamUrl
        if (url == null || (entry.kind != MediaKind.AUDIO && entry.kind != MediaKind.VIDEO)) return@onPlayer unloadNow()
        pausedInBackground = false
        stopped = !autoPlay
        // Another item: its tracks are others, so none. The same one again keeps its track.
        if (_loaded.value?.let { it.id != entry.id || it.streamUrl != entry.streamUrl } != false) _selectedTextTrack.value = null
        trackApplied = false
        _loaded.value = entry
        _status.value = null
        engine.load(entry, url, autoPlay, startPositionMs.coerceAtLeast(0))
        engine.setVolume(volume)
        refresh()
        if (poll == null) {
            poll = scope.launch {
                while (true) {
                    delay(pollIntervalMs)
                    refresh()
                }
            }
        }
    }

    override fun unload() = onPlayer(::unloadNow)

    private fun unloadNow() {
        if (_loaded.value == null) return
        // The last report says stopped, not playing.
        _status.value = _status.value?.copy(state = TransportState.STOPPED, ended = false)
        poll?.cancel()
        poll = null
        _loaded.value = null
        _error.value = null
        _textTracks.value = emptyList()
        _subtitleText.value = null
        _selectedTextTrack.value = null
        stopped = true
        pausedInBackground = false
        engine.unload()
    }

    override fun play() = whenLoaded {
        val facts = engine.facts()
        when {
            facts.error != null -> {
                engine.reload(facts.positionMs)
                trackApplied = false
            }
            facts.ended -> engine.seekTo(0)
        }
        stopped = false
        engine.play()
    }

    override fun pause() = whenLoaded { engine.pause() }

    override fun stop() = whenLoaded {
        stopped = true
        engine.pause()
        engine.seekTo(0)
    }

    override fun seek(positionMs: Long) = whenLoaded {
        stopped = false
        engine.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Shows a subtitle track inside the file ([textTracks]), or none; kept through reloads of the item. */
    fun selectTextTrack(id: String?) = onPlayer {
        if (_loaded.value == null) return@onPlayer
        _selectedTextTrack.value = id
        trackApplied = false
        refresh()
    }

    /**
     * Once the engine knows the tracks: applies our choice (none, unless picked; a file's default
     * doesn't show by itself). After that, a different track showing was picked by the platform's own
     * controls, and becomes the choice.
     */
    private fun syncTextTrack(facts: EngineFacts): EngineFacts {
        val wanted = _selectedTextTrack.value
        if (!trackApplied) {
            if (facts.textTracks.isEmpty()) return facts
            engine.selectTextTrack(wanted?.takeIf { id -> facts.textTracks.any { it.id == id } })
            trackApplied = true
            return engine.facts()
        }
        if (facts.selectedTextTrack != wanted) _selectedTextTrack.value = facts.selectedTextTrack
        return facts
    }

    /** 0..1. */
    fun setVolume(volume: Float) = onPlayer {
        this.volume = volume.coerceIn(0f, 1f)
        engine.setVolume(this.volume)
    }

    /** The app went to the background: a playing video pauses, unless it's on another screen. */
    fun onAppBackground() = onPlayer {
        val facts = engine.facts()
        if (_loaded.value?.kind == MediaKind.VIDEO && facts.playing && !facts.shownElsewhere) {
            engine.pause()
            pausedInBackground = true
            refresh()
        }
    }

    /** The app is back: a video paused by [onAppBackground] plays on. */
    fun onAppForeground() = onPlayer {
        if (!pausedInBackground) return@onPlayer
        pausedInBackground = false
        if (_loaded.value?.kind == MediaKind.VIDEO) engine.play()
        refresh()
    }

    /** A command from anyone (our controls, a controller, the lock screen) on the loaded item. */
    private fun whenLoaded(block: () -> Unit) = onPlayer {
        if (_loaded.value == null) return@onPlayer
        pausedInBackground = false
        block()
        refresh()
    }

    private fun onPlayer(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun refresh() {
        if (_loaded.value == null) return
        var facts = engine.facts()
        // Finished: paused (some engines still mean to play), so seeking back doesn't play on by itself.
        if (facts.ended && facts.playing) {
            engine.pause()
            facts = engine.facts()
        }
        // Started by itself (e.g. after a phone call) or by the system's controls: no longer stopped.
        if (facts.playing && !facts.ended) stopped = false
        _error.value = facts.error
        facts = syncTextTrack(facts)
        _textTracks.value = facts.textTracks
        _subtitleText.value = facts.subtitleText
        _status.value = RendererStatus(
            state = when {
                facts.ended || (stopped && !facts.playing) -> TransportState.STOPPED
                facts.error != null || !facts.playing -> TransportState.PAUSED
                facts.loading -> TransportState.TRANSITIONING
                else -> TransportState.PLAYING
            },
            positionMs = facts.positionMs.coerceAtLeast(0),
            durationMs = facts.durationMs.coerceAtLeast(0),
            ended = facts.ended,
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
