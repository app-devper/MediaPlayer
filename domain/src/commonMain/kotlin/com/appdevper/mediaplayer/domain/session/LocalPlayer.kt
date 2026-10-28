package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.SubtitleChoice
import com.appdevper.mediaplayer.domain.model.SubtitleState
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.domain.model.TransportPlayback
import com.appdevper.mediaplayer.domain.model.TransportState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    /** Where subtitle files are fetched from ([SubtitleChoice.File]). */
    subtitleFiles: SubtitleRepository = NoSubtitleFiles,
    /** Where they're read (not the player's thread). */
    subtitleParsing: CoroutineDispatcher = Dispatchers.Default,
) : Transport {

    private val subtitlesOfItem = LocalSubtitles(engine, subtitleFiles, scope, subtitleParsing)

    private val _status = MutableStateFlow<RendererStatus?>(null)

    /** What the player is doing; null until something loaded. Ends with [RendererStatus.ended]. */
    val status: StateFlow<RendererStatus?> = _status.asStateFlow()

    private val _playback = MutableStateFlow(TransportPlayback(volume = 100))

    /** [status] and [error] with its volume and mute, for [PlaybackSession]. */
    override val playback: StateFlow<TransportPlayback> = _playback.asStateFlow()

    private fun publish() {
        _playback.value = TransportPlayback(_status.value, volume, muted, _error.value)
    }

    private val _error = MutableStateFlow<String?>(null)

    /** Why the current item can't play, if it failed. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _textTracks = MutableStateFlow<List<TextTrack>>(emptyList())

    /** Subtitle tracks inside the loaded file. */
    val textTracks: StateFlow<List<TextTrack>> = _textTracks.asStateFlow()

    /**
     * The subtitle for the loaded item ([selectSubtitle]): kept through a reload of the same item, none
     * for another; a track picked in the platform's own controls (AVKit's menu) becomes it.
     */
    val subtitles: StateFlow<SubtitleState> = subtitlesOfItem.state.asStateFlow()

    /** The subtitle line on screen now, for us to draw (none when the engine draws its track itself). */
    val subtitleText: StateFlow<String?> = subtitlesOfItem.text.asStateFlow()

    private val _loaded = MutableStateFlow<MediaEntry?>(null)

    /** The song or video in the player; null for nothing, a photo, or playing on a renderer. */
    val loaded: StateFlow<MediaEntry?> = _loaded.asStateFlow()

    // Loaded paused or stopped by a controller: STOPPED until it plays.
    private var stopped = true
    private var pausedInBackground = false
    private var volume = 100
    private var muted = false
    private var poll: Job? = null

    // The last item asked for (kept while nothing is loaded, e.g. playing on a TV meanwhile): the same one
    // again keeps its subtitles.
    private var lastItem: Pair<String, String?>? = null

    override fun load(entry: MediaEntry, autoPlay: Boolean, startPositionMs: Long) = onPlayer {
        val item = entry.id to entry.streamUrl
        val sameItem = lastItem == item
        lastItem = item
        val url = entry.streamUrl
        if (url == null || (entry.kind != MediaKind.AUDIO && entry.kind != MediaKind.VIDEO)) return@onPlayer unloadNow()
        pausedInBackground = false
        stopped = !autoPlay
        subtitlesOfItem.onLoad(sameItem)
        _loaded.value = entry
        _status.value = null
        publish()
        engine.load(entry, url, autoPlay, startPositionMs.coerceAtLeast(0))
        applyVolume()
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
        _error.value = null
        publish()
        poll?.cancel()
        poll = null
        _loaded.value = null
        _textTracks.value = emptyList()
        subtitlesOfItem.onUnload()
        stopped = true
        pausedInBackground = false
        engine.unload()
    }

    override fun play() = whenLoaded(::playNow)

    private fun playNow() {
        val facts = engine.facts()
        when {
            facts.error != null -> {
                engine.reload(facts.positionMs)
                subtitlesOfItem.onLoad(sameItem = true)
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

    /** Pauses while playing or about to, else plays: what the player is doing when it gets there, not when asked. */
    fun togglePause() = whenLoaded {
        if (_status.value?.state?.playing == true) engine.pause() else playNow()
    }

    /**
     * Back or on by [deltaMs] (← →, a remote): not right onto the end, which would finish it, and a
     * held key would go on in the next item.
     */
    fun seekBy(deltaMs: Long) = whenLoaded {
        val facts = engine.facts()
        val to = (facts.positionMs + deltaMs).coerceAtLeast(0)
        val end = (facts.durationMs - SEEK_END_MARGIN_MS).coerceAtLeast(0)
        stopped = false
        engine.seekTo(if (facts.durationMs > 0) to.coerceAtMost(end) else to)
    }

    /** Shows [choice] for the loaded item: a file is fetched and read first ([subtitles] says so). */
    fun selectSubtitle(choice: SubtitleChoice) = onPlayer {
        if (_loaded.value == null) return@onPlayer
        subtitlesOfItem.pick(choice)
        refresh()
    }

    override fun setVolume(volume: Int) = onPlayer {
        this.volume = volume.coerceIn(0, 100)
        muted = false
        applyVolume()
    }

    override fun setMuted(muted: Boolean) = onPlayer {
        this.muted = muted
        applyVolume()
    }

    private fun applyVolume() {
        engine.setVolume(if (muted) 0f else volume / 100f)
        publish()
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
        facts = subtitlesOfItem.sync(facts)
        _textTracks.value = facts.textTracks
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
        publish()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val SEEK_END_MARGIN_MS = 1_000L
    }
}

private object NoSubtitleFiles : SubtitleRepository {
    override suspend fun fetch(url: String): ByteArray = throw IllegalStateException("No subtitle files here")
}
