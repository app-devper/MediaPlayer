package app.devper.appplayer.data.repository

import app.devper.appplayer.airplay.AirPlayAccess
import app.devper.appplayer.airplay.AirPlayConfig
import app.devper.appplayer.airplay.AirPlayEvent
import app.devper.appplayer.airplay.AirPlayMediaSink
import app.devper.appplayer.airplay.AirPlayReceiver
import app.devper.appplayer.airplay.AirPlayRemoteCommand
import app.devper.appplayer.airplay.airPlayGain
import app.devper.appplayer.data.platform.AppStorage
import app.devper.appplayer.data.platform.MulticastLock
import app.devper.appplayer.domain.model.AirPlayActivity
import app.devper.appplayer.domain.model.AirPlayNowPlaying
import app.devper.appplayer.domain.model.AirPlayStatus
import app.devper.appplayer.domain.model.ReceiverLink
import app.devper.appplayer.domain.model.ReceiverPlayback
import app.devper.appplayer.domain.model.SenderCommand
import app.devper.appplayer.domain.repository.AirPlayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * [AirPlayRepository] over the :airplay receiver; [receiver] is null where AirPlay isn't available.
 * Mirroring and audio go to [sink]; AirPlay video to the local player ([AirPlayVideoSession]).
 */
class AirPlayRepositoryImpl(
    private val receiver: AirPlayReceiver?,
    /** Plays mirroring and audio (the app's [app.devper.appplayer.airplay.AirPlayPlayer]). */
    private val sink: AirPlayMediaSink,
    /** The sink's volume, 0..1. */
    private val setSinkVolume: (Float) -> Unit,
    private val settings: SettingsRepositoryImpl,
    private val storage: AppStorage,
    private val multicast: MulticastLock,
    private val scope: CoroutineScope,
    /** See [AirPlayVideoSession]. */
    abandonedEndMs: Long = 5 * 60_000,
) : AirPlayRepository {

    private val _status = MutableStateFlow(if (receiver == null) AirPlayStatus.Unsupported else AirPlayStatus.Stopped)
    override val status: StateFlow<AirPlayStatus> = _status.asStateFlow()

    private val _activity = MutableStateFlow(AirPlayActivity())
    override val activity: StateFlow<AirPlayActivity> = _activity.asStateFlow()

    private val mutex = Mutex()

    private val video = AirPlayVideoSession(
        scope, abandonedEndMs,
        sender = { _activity.value.client },
        onVideo = { id ->
            // The sender moved from AirPlay audio to video (YouTube Music does when switching song → video).
            _activity.update { if (id == null) it.copy(videoEntryId = null) else it.copy(audioOnly = false, nowPlaying = null, videoEntryId = id) }
        },
    )

    override fun connect(link: ReceiverLink) {
        video.link = link
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

    override fun publish(playback: ReceiverPlayback) = video.publish(playback)

    init {
        receiver?.let { r ->
            r.sink = sink
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
                video.setSenderVolume(null)
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
                setSinkVolume(gain)
                video.setSenderVolume((gain * 100).roundToInt())
            }
            is AirPlayEvent.Track -> updateNowPlaying { it.copy(title = event.info.title, artist = event.info.artist, album = event.info.album) }
            is AirPlayEvent.Artwork -> updateNowPlaying { it.copy(artwork = event.image) }
            is AirPlayEvent.Progress -> updateNowPlaying {
                it.copy(positionMs = event.positionMs, durationMs = event.durationMs, reportedAt = TimeSource.Monotonic.markNow())
            }
            is AirPlayEvent.ConnectionLost -> {
                _activity.value = AirPlayActivity()
                video.setSenderVolume(null)
            }
        }
    }
}
