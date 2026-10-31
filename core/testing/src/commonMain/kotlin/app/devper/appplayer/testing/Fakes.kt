package app.devper.appplayer.testing

import app.devper.appplayer.domain.model.AirPlayActivity
import app.devper.appplayer.domain.model.AirPlayStatus
import app.devper.appplayer.domain.model.CastCommand
import app.devper.appplayer.domain.model.MediaDevice
import app.devper.appplayer.domain.repository.MediaServerRepository
import app.devper.appplayer.domain.model.ServerStatus
import app.devper.appplayer.domain.model.ServerSettings
import app.devper.appplayer.domain.model.ServerCapabilities
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.ReceiverLink
import app.devper.appplayer.domain.model.ReceiverPlayback
import app.devper.appplayer.domain.model.ReceiverStatus
import app.devper.appplayer.domain.model.RendererStatus
import app.devper.appplayer.domain.model.SenderCommand
import app.devper.appplayer.domain.model.SenderControl
import app.devper.appplayer.domain.model.TransportState
import app.devper.appplayer.domain.repository.AirPlayRepository
import app.devper.appplayer.domain.repository.CastReceiverRepository
import app.devper.appplayer.domain.repository.RendererRepository
import app.devper.appplayer.domain.repository.SettingsRepository
import app.devper.appplayer.domain.repository.SubtitleRepository
import app.devper.appplayer.domain.session.EngineFacts
import app.devper.appplayer.domain.session.LocalPlayer
import app.devper.appplayer.domain.session.PlaybackEngine
import app.devper.appplayer.domain.session.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A platform player that does what it's told at once; [facts] can be set to anything. */
class FakeEngine(private val durationMs: Long = 60_000) : PlaybackEngine {
    val calls = mutableListOf<String>()
    var facts = EngineFacts()
    var gain = 1f

    override fun load(entry: MediaEntry, url: String, playWhenReady: Boolean, startPositionMs: Long) {
        calls += "load:${entry.id}@$startPositionMs"
        facts = EngineFacts(playing = playWhenReady, positionMs = startPositionMs, durationMs = durationMs)
    }

    override fun unload() {
        calls += "unload"
        facts = EngineFacts()
    }

    override fun play() {
        calls += "play"
        facts = facts.copy(playing = true)
    }

    override fun pause() {
        calls += "pause"
        facts = facts.copy(playing = false)
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek:$positionMs"
        facts = facts.copy(positionMs = positionMs, ended = false)
    }

    override fun setVolume(volume: Float) {
        gain = volume
    }

    override fun reload(positionMs: Long) {
        calls += "reload:$positionMs"
        facts = facts.copy(error = null, positionMs = positionMs)
    }

    override fun selectTextTrack(id: String?) {
        calls += "track:$id"
        facts = facts.copy(selectedTextTrack = id)
    }

    override fun facts() = facts

    /** Plays to the end by itself. */
    fun finish() {
        facts = facts.copy(ended = true, positionMs = facts.durationMs)
    }
}

/** A DLNA renderer that takes every command (listed in [calls]) and reports what it's set to. */
class FakeRenderer(
    var state: TransportState = TransportState.PLAYING,
    var position: Long = 1_000,
    var duration: Long = 60_000,
    private val volume: Int = 30,
) : RendererRepository {
    val calls = mutableListOf<String>()
    override suspend fun load(device: MediaDevice, entry: MediaEntry) {
        calls += "load:${entry.id}"
        state = TransportState.PLAYING
    }
    override suspend fun play(device: MediaDevice) { calls += "play" }
    override suspend fun pause(device: MediaDevice) { calls += "pause" }
    override suspend fun stop(device: MediaDevice) { calls += "stop" }
    override suspend fun seek(device: MediaDevice, positionMs: Long) { calls += "seek:$positionMs" }
    override suspend fun status(device: MediaDevice) = RendererStatus(state, position, duration)
    override suspend fun volume(device: MediaDevice) = volume
    override suspend fun setVolume(device: MediaDevice, volume: Int) { calls += "volume:$volume" }
}

/** The DLNA receiver: [send] as a controller would; what it's told is kept. */
class FakeReceiver : CastReceiverRepository {
    val calls = mutableListOf<String>()
    val published = mutableListOf<ReceiverPlayback>()
    override val status = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Stopped)

    /** The playback session's line, once connected. */
    var link: ReceiverLink? = null
    override fun connect(link: ReceiverLink) { this.link = link }

    /** What a controller asks for; false when it's refused. */
    fun send(command: CastCommand): Boolean = link!!.send(command)
    override suspend fun start(name: String) {
        calls += "start:$name"
        status.value = ReceiverStatus.Running(name)
    }
    override suspend fun stop() {
        calls += "stop"
        status.value = ReceiverStatus.Stopped
    }
    override fun publish(playback: ReceiverPlayback) { published += playback }
}

/** The AirPlay receiver ([AirPlayStatus.Unsupported] where the platform has none). */
class FakeAirPlay(status: AirPlayStatus = AirPlayStatus.Stopped) : AirPlayRepository {
    val calls = mutableListOf<String>()
    val published = mutableListOf<ReceiverPlayback>()
    val sentToSender = mutableListOf<SenderCommand>()
    override val status = MutableStateFlow(status)
    override val activity = MutableStateFlow(AirPlayActivity())

    /** The playback session's line, once connected. */
    var link: ReceiverLink? = null
    override fun connect(link: ReceiverLink) { this.link = link }

    /** What the sender's AirPlay video asks for; false when it's refused. */
    fun send(command: CastCommand): Boolean = link!!.send(command)
    override fun publish(playback: ReceiverPlayback) { published += playback }
    override suspend fun sendToSender(command: SenderCommand): Boolean {
        sentToSender += command
        return true
    }
    override suspend fun start(name: String, requirePin: Boolean) {
        calls += "start:$name:pin=$requirePin"
        status.value = AirPlayStatus.Running(name)
    }
    override suspend fun stop() {
        calls += "stop"
        if (status.value != AirPlayStatus.Unsupported) status.value = AirPlayStatus.Stopped
    }
}

/** Settings kept in memory, from [initial]. */
class FakeSettings(
    initial: ServerSettings = ServerSettings("Worawit's MacBook", true, true, true, false, listOf("/Users/me/Movies", "/Users/me/Music")),
) : SettingsRepository {
    override val serverSettings = MutableStateFlow(initial)
    override fun update(transform: (ServerSettings) -> ServerSettings) = serverSettings.update(transform)
}

/** The built-in media server: starts at once with the settings it's given (kept in [starts]). */
class FakeServer : MediaServerRepository {
    val starts = mutableListOf<ServerSettings>()
    override val status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    override val capabilities = ServerCapabilities(canChooseFolders = true, sourceDescription = "the folders listed below")
    override suspend fun start(settings: ServerSettings) {
        starts += settings
        status.value = ServerStatus.Running(settings.name, "http://192.168.1.160:8191", mapOf(MediaKind.VIDEO to 12, MediaKind.AUDIO to 340))
    }
    override suspend fun stop() { status.value = ServerStatus.Stopped }
}

/** Subtitle files: one Thai line from 1 s to 2 s; a URL with "broken" fails, one with "empty" has no lines. */
class FakeSubtitles : SubtitleRepository {
    override suspend fun fetch(url: String): ByteArray = when {
        "broken" in url -> throw IllegalStateException("404")
        "empty" in url -> "nothing here".encodeToByteArray()
        else -> "1\n00:00:01,000 --> 00:00:02,000\nสวัสดี\n".encodeToByteArray()
    }
}

/** A playback session on [engine], subtitle files from [FakeSubtitles] (read at once, not on another thread). */
fun playbackSession(
    renderer: RendererRepository,
    scope: CoroutineScope,
    engine: FakeEngine = FakeEngine(),
    pollIntervalMs: Long = 1_000,
    sender: SenderControl = SenderControl.None,
) = PlaybackSession(
    renderer, LocalPlayer(engine, scope, subtitleFiles = FakeSubtitles(), subtitleParsing = Dispatchers.Unconfined), scope,
    sender = sender, pollIntervalMs = pollIntervalMs,
)
