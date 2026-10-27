package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.airplay.AirPlayAccess
import com.appdevper.mediaplayer.airplay.AirPlayConfig
import com.appdevper.mediaplayer.airplay.AirPlayEvent
import com.appdevper.mediaplayer.airplay.AirPlayPlayer
import com.appdevper.mediaplayer.airplay.AirPlayReceiver
import com.appdevper.mediaplayer.airplay.AirPlayRemoteCommand
import com.appdevper.mediaplayer.airplay.AirPlayVideoControl
import com.appdevper.mediaplayer.airplay.AirPlayVideoInfo
import com.appdevper.mediaplayer.airplay.airPlayGain
import com.appdevper.mediaplayer.data.platform.AppStorage
import com.appdevper.mediaplayer.data.platform.MulticastLock
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayNowPlaying
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.repository.AirPlayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** [AirPlayRepository] over the :airplay receiver; [receiver] is null where AirPlay isn't available. */
class AirPlayRepositoryImpl(
    private val receiver: AirPlayReceiver?,
    private val player: AirPlayPlayer,
    private val settings: SettingsRepositoryImpl,
    private val storage: AppStorage,
    private val multicast: MulticastLock,
    private val scope: CoroutineScope,
    /**
     * A video that ended and that its sender never stopped or replaced is closed after this long. Long:
     * YouTube waits ~20 s on its end screen before the next video, and the user may seek back meanwhile.
     */
    private val abandonedEndMs: Long = 5 * 60_000,
) : AirPlayRepository {

    private val _status = MutableStateFlow(if (receiver == null) AirPlayStatus.Unsupported else AirPlayStatus.Stopped)
    override val status: StateFlow<AirPlayStatus> = _status.asStateFlow()

    private val _activity = MutableStateFlow(AirPlayActivity())
    override val activity: StateFlow<AirPlayActivity> = _activity.asStateFlow()

    private val mutex = Mutex()

    private val _commands = MutableSharedFlow<CastCommand>(extraBufferCapacity = 64)
    override val commands: Flow<CastCommand> = _commands.asSharedFlow()

    // Read from native threads (AirPlayVideoControl.info), written from the app scope.
    @Volatile private var playback = ReceiverPlayback()
    /** Entry id of the current AirPlay video; null when none. */
    @Volatile private var videoId: String? = null
    @Volatile private var videoLoaded = false
    private var videoSequence = 0

    // The sender's volume (its volume buttons) as 0..100; also sent while it plays AirPlay video.
    @Volatile private var senderVolume: Int? = null
    // Our player's volume before an AirPlay video changed it, restored when the video stops.
    @Volatile private var volumeBeforeVideo: Int? = null

    private fun applyVideoVolume(volume: Int) {
        if (volumeBeforeVideo == null) volumeBeforeVideo = playback.volume
        _commands.tryEmit(CastCommand.SetVolume(volume))
    }

    override suspend fun sendToSender(command: SenderCommand): Boolean {
        val r = receiver ?: return false
        return r.sendToSender(
            when (command) {
                SenderCommand.Next -> AirPlayRemoteCommand.Next
                SenderCommand.Previous -> AirPlayRemoteCommand.Previous
                SenderCommand.PlayPause -> AirPlayRemoteCommand.PlayPause
            },
        )
    }

    override fun publish(playback: ReceiverPlayback) {
        this.playback = playback
        val id = videoId
        val current = id != null && playback.entry?.id == id
        if (current) videoLoaded = true
        // Played to the end: the sender normally stops or plays the next video. If it never does
        // (phone locked, app killed), end the session here so a later /rate can't restart it.
        // Anything else (still playing, seeked back, something else plays now) cancels that.
        if (current && playback.status.isFinished()) {
            if (endJob == null) endJob = scope.launch {
                delay(abandonedEndMs)
                if (videoId == id && this@AirPlayRepositoryImpl.playback.entry?.id == id) video.stop()
            }
        } else {
            endJob?.cancel()
            endJob = null
        }
    }

    @Volatile private var endJob: Job? = null

    /**
     * The sender's video is still what our player has. If the user played something else meanwhile, a
     * late /rate or /scrub from the sender must not drive it (before the player reports, the load counts).
     */
    private fun videoIsPlayingHere(): Boolean {
        val id = videoId ?: return false
        val entry = playback.entry
        return !videoLoaded || entry == null || entry.id == id
    }

    /** The player reached the end; leaving the player or a controller's Stop isn't an end. */
    private fun RendererStatus?.isFinished() = this != null && ended && durationMs > 0

    /** AirPlay video (YouTube, Safari…) → the same local player as DLNA casts. */
    private val video = object : AirPlayVideoControl {
        override fun play(url: String, startSeconds: Float) {
            // YouTube's next video comes at the same local URL: a new id makes the player load it.
            val id = "airplay-${++videoSequence}"
            endJob?.cancel()
            endJob = null
            videoId = id
            // The sender moved from AirPlay audio to video (YouTube Music does when switching song → video).
            _activity.update { it.copy(audioOnly = false, nowPlaying = null, videoEntryId = id) }
            videoLoaded = false
            val entry = MediaEntry(
                id = id,
                parentId = "-1",
                title = _activity.value.client?.let { "AirPlay from $it" } ?: "AirPlay video",
                kind = MediaKind.VIDEO,
                streamUrl = url,
                mimeType = if (".m3u8" in url) "application/x-mpegURL" else null,
            )
            _commands.tryEmit(CastCommand.Load(entry, autoPlay = true, startPositionMs = (startSeconds * 1000).toLong().coerceAtLeast(0)))
            senderVolume?.let(::applyVideoVolume)
        }

        // Only while a video is current: after /stop, YouTube sends /rate 1 before the next /play,
        // which would otherwise resume the stopped one.
        override fun seek(seconds: Float) {
            if (!videoIsPlayingHere()) return
            _commands.tryEmit(CastCommand.Seek((seconds * 1000).toLong()))
        }

        override fun setRate(rate: Float) {
            if (!videoIsPlayingHere()) return
            _commands.tryEmit(if (rate > 0f) CastCommand.Play else CastCommand.Pause)
        }

        override fun stop() {
            if (videoId == null) return
            videoId = null
            _activity.update { it.copy(videoEntryId = null) }
            endJob?.cancel()
            endJob = null
            _commands.tryEmit(CastCommand.Stop)
            volumeBeforeVideo?.let { _commands.tryEmit(CastCommand.SetVolume(it)) }
            volumeBeforeVideo = null
        }

        override fun info(): AirPlayVideoInfo? {
            val id = videoId ?: return null
            val p = playback
            if (p.entry?.id != id) {
                // Something else plays here now: tell the sender its video is over. Deliberately -1:
                // UxPlay then drops the sender's connection (YouTube shows an error), since the user
                // replaced its video on this device.
                return if (videoLoaded) AirPlayVideoInfo(-1.0, 0.0, playing = false) else null
            }
            val status = p.status ?: return null
            // At the end: report the end, paused, as an Apple TV does; the sender then plays the next
            // video or stops. (Duration -1, UxPlay's "finished", drops the sender's connection.)
            // The last reported position can be seconds short of the container duration.
            val finished = status.isFinished()
            return AirPlayVideoInfo(
                durationSeconds = status.durationMs / 1000.0,
                positionSeconds = (if (finished) status.durationMs else status.positionMs) / 1000.0,
                playing = status.state == TransportState.PLAYING || status.state == TransportState.TRANSITIONING,
            )
        }
    }

    init {
        receiver?.let { r ->
            r.sink = player
            r.video = video
            r.access = object : AirPlayAccess {
                override fun isPaired(publicKey: String) = publicKey in settings.airPlayPairedKeys
            }
            scope.launch { r.events.collect(::onEvent) }
        }
    }

    private fun updateNowPlaying(change: (AirPlayNowPlaying) -> AirPlayNowPlaying) =
        _activity.update { it.copy(nowPlaying = change(it.nowPlaying ?: AirPlayNowPlaying())) }

    override suspend fun start(name: String, requirePin: Boolean) {
        val r = receiver ?: return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // UxPlay's mDNS responder needs multicast (Android filters it without this lock).
                multicast.acquire()
                try {
                    r.start(AirPlayConfig(name, settings.airPlayDeviceId, "${storage.dir}/airplay_key.pem", requirePin = requirePin))
                    _status.value = AirPlayStatus.Running(name)
                } catch (e: Throwable) {
                    runCatching { r.stop() }
                    multicast.release()
                    // UnsatisfiedLinkError included: a desktop build without the native library for this OS.
                    _status.value = AirPlayStatus.Failed(e.message ?: e::class.simpleName ?: "AirPlay failed to start")
                }
            }
            _activity.value = AirPlayActivity()
        }
    }

    override suspend fun stop() {
        val r = receiver ?: return
        mutex.withLock {
            withContext(Dispatchers.IO) { runCatching { r.stop() } }
            multicast.release()
            _status.value = AirPlayStatus.Stopped
            _activity.value = AirPlayActivity()
        }
    }

    private fun onEvent(event: AirPlayEvent) {
        when (event) {
            is AirPlayEvent.Connections -> if (event.open == 0) {
                _activity.value = AirPlayActivity()
                senderVolume = null
            }
            is AirPlayEvent.ClientConnecting -> _activity.update { it.copy(client = event.name) }
            is AirPlayEvent.ShowPin -> _activity.update { it.copy(pin = event.pin) }
            is AirPlayEvent.ClientPaired -> {
                settings.addAirPlayPairedKey(event.publicKey)
                _activity.update { it.copy(pin = null) }
            }
            is AirPlayEvent.MirroringStarted -> _activity.update { it.copy(mirroring = true, audioOnly = false, pin = null) }
            AirPlayEvent.MirroringStopped -> _activity.update { it.copy(mirroring = false) }
            is AirPlayEvent.AudioStarted -> _activity.update { it.copy(audioOnly = !event.withScreen && !it.mirroring, pin = null) }
            AirPlayEvent.AudioStopped -> _activity.update { it.copy(audioOnly = false, nowPlaying = null) }
            AirPlayEvent.RemoteControlAvailable -> _activity.update { it.copy(canControlSender = true) }
            AirPlayEvent.RemoteControlLost -> _activity.update { it.copy(canControlSender = false) }
            is AirPlayEvent.Volume -> {
                // Comes over the audio (RAOP) connection, also while the sender plays AirPlay video.
                val gain = airPlayGain(event.db)
                player.setVolume(gain)
                senderVolume = (gain * 100).roundToInt()
                if (videoId != null) applyVideoVolume(senderVolume!!)
            }
            is AirPlayEvent.Track -> updateNowPlaying { it.copy(title = event.info.title, artist = event.info.artist, album = event.info.album) }
            is AirPlayEvent.Artwork -> updateNowPlaying { it.copy(artwork = event.image) }
            is AirPlayEvent.Progress -> updateNowPlaying {
                it.copy(positionMs = event.positionMs, durationMs = event.durationMs, reportedAt = TimeSource.Monotonic.markNow())
            }
            is AirPlayEvent.ConnectionLost -> {
                _activity.value = AirPlayActivity()
                senderVolume = null
            }
        }
    }
}
